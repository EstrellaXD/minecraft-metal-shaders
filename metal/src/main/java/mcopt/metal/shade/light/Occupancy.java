/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade.light;

import java.nio.IntBuffer;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Which blocks around the camera stop light (BlockState.canOcclude: full opaque cubes, slabs, stairs...; not glass, leaves,
 * water), one bit per block in a shared GPU buffer that the visibility kernel reads (dynlight_vis.metal has the layout): a
 * toroidal window of 8 x 8 x 8 chunk sections (128^3 blocks) that follows the camera section by section. Sections are read
 * from the client's chunks on the render thread within a CPU budget per frame, nearest first; sections the window newly
 * covers read as air until filled; block updates set their bit at once (ClientLevel.sendBlockUpdated, the mixin), section
 * updates (chunk data, light) queue a refill, and one slot per frame is refreshed round-robin anyway (self-healing).
 * Render thread only.
 */
final class Occupancy {
	static final int SPAN = 8, SLOTS = SPAN * SPAN * SPAN, WORDS = 128;
	static final int BYTES = SLOTS * WORDS * 4;
	private static final long NONE = Long.MIN_VALUE;
	private static final Predicate<BlockState> OCCLUDES = BlockState::canOcclude, CLEAR = s -> !s.canOcclude();

	private final IntBuffer bits;
	private final long[] held = new long[SLOTS];
	private final boolean[] dirty = new boolean[SLOTS];
	private final int[] retry = new int[SLOTS];
	private final int[] scratch = new int[WORDS];
	private int ox = Integer.MIN_VALUE, oy, oz; // the window's min section
	private int refresh;
	private ClientLevel level;
	/** Bumped whenever any bit changes (visibility volumes of static lights may be reused while it stays). */
	int generation;
	int filledLastFrame, pending;
	long nanosLastFrame;

	Occupancy(IntBuffer bits) {
		this.bits = bits;
		java.util.Arrays.fill(this.held, NONE);
	}

	private static long key(int sx, int sy, int sz) {
		return ((long) sx & 0x3FFFFFL) << 42 | ((long) sz & 0x3FFFFFL) << 20 | ((long) sy & 0xFFFFFL);
	}

	private static int slot(int sx, int sy, int sz) {
		return (sy & 7) << 6 | (sz & 7) << 3 | (sx & 7);
	}

	/** The window's min corner in absolute blocks (x, y, z) and whether a block's section is inside it. */
	boolean covers(int bx, int by, int bz) {
		int sx = bx >> 4, sy = by >> 4, sz = bz >> 4;
		return sx >= this.ox && sx < this.ox + SPAN && sy >= this.oy && sy < this.oy + SPAN && sz >= this.oz && sz < this.oz + SPAN;
	}

	/** Re-centres the window on the camera and fills sections within the budget. */
	void update(ClientLevel lvl, double camX, double camY, double camZ, long budgetNanos) {
		long t0 = System.nanoTime();
		if (lvl != this.level) {
			this.level = lvl;
			java.util.Arrays.fill(this.held, NONE);
			this.ox = Integer.MIN_VALUE;
		}
		int nx = Math.floorDiv((int) Math.floor(camX) + 8, 16) - 4, ny = Math.floorDiv((int) Math.floor(camY) + 8, 16) - 4,
			nz = Math.floorDiv((int) Math.floor(camZ) + 8, 16) - 4;
		if (nx != this.ox || ny != this.oy || nz != this.oz) {
			this.ox = nx;
			this.oy = ny;
			this.oz = nz;
			for (int dy = 0; dy < SPAN; dy++) {
				for (int dz = 0; dz < SPAN; dz++) {
					for (int dx = 0; dx < SPAN; dx++) {
						int sx = nx + dx, sy = ny + dy, sz = nz + dz, s = slot(sx, sy, sz);
						long k = key(sx, sy, sz);
						if (this.held[s] != k) {
							this.held[s] = k;
							this.bits.put(s * WORDS, new int[WORDS], 0, WORDS); // air until filled
							this.dirty[s] = true;
							this.retry[s] = 0;
							this.generation++;
						}
					}
				}
			}
		}
		if (lvl == null) return;
		int csx = Math.floorDiv((int) Math.floor(camX), 16), csy = Math.floorDiv((int) Math.floor(camY), 16), csz = Math.floorDiv((int) Math.floor(camZ), 16);
		int filled = 0;
		long deadline = t0 + budgetNanos;
		// nearest dirty sections first (512 slots: a linear pick per fill is cheap next to a fill)
		while (System.nanoTime() < deadline) {
			int best = -1, bestD = Integer.MAX_VALUE, waiting = 0;
			for (int dy = 0; dy < SPAN; dy++) {
				for (int dz = 0; dz < SPAN; dz++) {
					for (int dx = 0; dx < SPAN; dx++) {
						int sx = this.ox + dx, sy = this.oy + dy, sz = this.oz + dz, s = slot(sx, sy, sz);
						if (!this.dirty[s]) continue;
						if (this.retry[s] > 0) {
							this.retry[s]--;
							continue;
						}
						waiting++;
						int d = Math.abs(sx - csx) + Math.abs(sy - csy) * 2 + Math.abs(sz - csz);
						if (d < bestD) {
							bestD = d;
							best = s;
						}
					}
				}
			}
			this.pending = waiting;
			if (best < 0) break;
			long k = this.held[best];
			int sx = (int) (k >> 42), sz = (int) (k << 22 >> 42), sy = (int) (k << 44 >> 44);
			if (fill(lvl, best, sx, sy, sz)) {
				this.dirty[best] = false;
			} else {
				this.retry[best] = 30; // chunk not loaded yet: look again in a while
			}
			filled++;
		}
		// one slot per frame is read again regardless (missed updates heal within ~4 s at 120 fps)
		if (System.nanoTime() < deadline + budgetNanos / 2) {
			this.refresh = (this.refresh + 1) % SLOTS;
			long k = this.held[this.refresh];
			if (k != NONE && !this.dirty[this.refresh]) {
				fill(lvl, this.refresh, (int) (k >> 42), (int) (k << 44 >> 44), (int) (k << 22 >> 42));
			}
		}
		this.filledLastFrame = filled;
		this.nanosLastFrame = System.nanoTime() - t0;
	}

	/** Reads one section into its slot; false when its chunk isn't there yet (left as air). */
	private boolean fill(ClientLevel lvl, int s, int sx, int sy, int sz) {
		int[] w = this.scratch;
		java.util.Arrays.fill(w, 0);
		boolean ok = true;
		int index = sy - lvl.getMinSectionY();
		if (index >= 0 && index < lvl.getSectionsCount()) {
			LevelChunk chunk = lvl.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
			if (chunk == null) {
				ok = false;
			} else {
				LevelChunkSection section = chunk.getSection(index);
				if (!section.hasOnlyAir()) {
					PalettedContainer<BlockState> states = section.getStates();
					if (states.maybeHas(OCCLUDES)) {
						if (!states.maybeHas(CLEAR)) {
							java.util.Arrays.fill(w, -1);
						} else {
							int i = 0;
							for (int y = 0; y < 16; y++) {
								for (int z = 0; z < 16; z++) {
									int word = 0;
									for (int x = 0; x < 16; x++) {
										if (states.get(x, y, z).canOcclude()) word |= 1 << ((i + x) & 31);
									}
									w[i >> 5] |= word;
									i += 16;
								}
							}
						}
					}
				}
			}
		}
		boolean changed = false;
		for (int i = 0; i < WORDS; i++) {
			if (this.bits.get(s * WORDS + i) != w[i]) {
				changed = true;
				break;
			}
		}
		if (changed) {
			this.bits.put(s * WORDS, w, 0, WORDS);
			this.generation++;
		}
		return ok;
	}

	/** A block changed (ClientLevel.sendBlockUpdated): its bit, at once. */
	void block(int x, int y, int z, BlockState state) {
		int sx = x >> 4, sy = y >> 4, sz = z >> 4;
		if (this.ox == Integer.MIN_VALUE || !covers(x, y, z)) return;
		int s = slot(sx, sy, sz);
		if (this.held[s] != key(sx, sy, sz)) return;
		int idx = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
		int at = s * WORDS + (idx >> 5);
		int old = this.bits.get(at), now = state.canOcclude() ? old | 1 << (idx & 31) : old & ~(1 << (idx & 31));
		if (now != old) {
			this.bits.put(at, now);
			this.generation++;
		}
	}

	/** A section changed (chunk data arrived, a bulk update): refill it soon. */
	void section(int sx, int sy, int sz) {
		if (this.ox == Integer.MIN_VALUE) return;
		if (sx < this.ox || sx >= this.ox + SPAN || sy < this.oy || sy >= this.oy + SPAN || sz < this.oz || sz >= this.oz + SPAN) return;
		int s = slot(sx, sy, sz);
		if (this.held[s] != key(sx, sy, sz)) return;
		this.dirty[s] = true;
		this.retry[s] = 0;
	}

	/** Whether the block at absolute (x, y, z) stops light (false outside the window or not filled yet). */
	boolean solid(int x, int y, int z) {
		if (this.ox == Integer.MIN_VALUE || !covers(x, y, z)) return false;
		int s = slot(x >> 4, y >> 4, z >> 4);
		int idx = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
		return (this.bits.get(s * WORDS + (idx >> 5)) >>> (idx & 31) & 1) != 0;
	}

	/**
	 * Whether the segment from a to b crosses an opaque block (the cells of a and b excepted): the visibility kernel's walk,
	 * on the CPU, for the light of entities computed here (the local player's hand).
	 */
	boolean blocked(double ax, double ay, double az, double bx, double by, double bz) {
		int cx = (int) Math.floor(ax), cy = (int) Math.floor(ay), cz = (int) Math.floor(az);
		int tx = (int) Math.floor(bx), ty = (int) Math.floor(by), tz = (int) Math.floor(bz);
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-6) return false;
		dx /= len;
		dy /= len;
		dz /= len;
		int stx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sty = dy > 0 ? 1 : dy < 0 ? -1 : 0, stz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
		double tMaxX = stx == 0 ? 1e30 : ((stx > 0 ? cx + 1 - ax : ax - cx) / Math.abs(dx));
		double tMaxY = sty == 0 ? 1e30 : ((sty > 0 ? cy + 1 - ay : ay - cy) / Math.abs(dy));
		double tMaxZ = stz == 0 ? 1e30 : ((stz > 0 ? cz + 1 - az : az - cz) / Math.abs(dz));
		double tdx = stx == 0 ? 1e30 : 1 / Math.abs(dx), tdy = sty == 0 ? 1e30 : 1 / Math.abs(dy), tdz = stz == 0 ? 1e30 : 1 / Math.abs(dz);
		for (int i = 0; i < 96; i++) {
			if (cx == tx && cy == ty && cz == tz) return false;
			if (tMaxX < tMaxY && tMaxX < tMaxZ) {
				cx += stx;
				tMaxX += tdx;
			} else if (tMaxY < tMaxZ) {
				cy += sty;
				tMaxY += tdy;
			} else {
				cz += stz;
				tMaxZ += tdz;
			}
			if (cx == tx && cy == ty && cz == tz) return false;
			if (solid(cx, cy, cz)) return true;
		}
		return false;
	}
}
