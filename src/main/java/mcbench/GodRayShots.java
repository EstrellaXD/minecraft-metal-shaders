/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import mcbench.Spot.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * Opt-in god-ray screenshots ({@code -Dmcbench.godRays=true}; off by default, so the standard plan is unchanged), owned by
 * the volumetric light work (mcopt-shade M2, branch m2-vl). After the standard shots, the poses are found in the loaded
 * world around the bench spot (server thread, read only; nothing is built), then shot like extraShots:
 * <ul>
 * <li>{@code vl_forest_morning}: under a leaf canopy with gaps, low morning sun, looking toward it (east) and a little up.</li>
 * <li>{@code vl_forest_side}: same spot, morning sun from the side (looking south): parallel shafts.</li>
 * <li>{@code vl_forest_evening}: outside the forest edge, evening sun behind the trees (looking west).</li>
 * <li>{@code vl_shelter}: inside a village house looking out of its door into low sun (the cave mouth / overhang case);
 * the time follows the door's direction. Skipped if no door faces east or west.</li>
 * <li>{@code vl_underwater}: 7 blocks under the surface of deep water, looking up toward the morning sun.</li>
 * </ul>
 * The chosen poses go to results.json "godRays".
 */
final class GodRayShots {
	static final boolean ON = Boolean.getBoolean("mcbench.godRays");

	private GodRayShots() {
	}

	/** Called from BenchDriver.buildPlan after the standard shots. */
	static void plan(java.util.Deque<BenchDriver.Step> steps) {
		if (!ON) return;
		steps.add(new BenchDriver.Step("godrays-find", 60) {
			CompletableFuture<Map<String, Object[]>> f;

			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				f = CompletableFuture.supplyAsync(() -> find(s.overworld()), s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (!f.isDone()) return false;
				Map<String, Object[]> found = f.get();
				Map<String, Object> json = new LinkedHashMap<>();
				List<BenchDriver.Step> extra = new ArrayList<>();
				for (var e : found.entrySet()) {
					Pose p = (Pose) e.getValue()[0];
					int time = (Integer) e.getValue()[1];
					Map<String, Object> m = p.json();
					m.put("time", time);
					json.put(e.getKey(), m);
					BenchDriver.log("god-ray scene " + e.getKey() + " @" + time + " " + p);
					extra.add(new BenchDriver.Scene(e.getKey(), time, false, () -> p));
					extra.add(new BenchDriver.Settle(e.getKey(), false, 3, 120));
					extra.add(new BenchDriver.Hold(e.getKey(), Math.max(BenchDriver.SHOT_WAIT, 3)));
					extra.add(new BenchDriver.Shot(e.getKey()));
				}
				BenchDriver.results.put("godRays", json);
				for (int i = extra.size() - 1; i >= 0; i--) steps.addFirst(extra.get(i));
				return true;
			}
		});
	}

	private static boolean leaves(ServerLevel l, BlockPos p) {
		return l.getBlockState(p).is(BlockTags.LEAVES);
	}

	/** Ground (first free block above the floor) of a dry column, or MIN_VALUE. */
	private static int ground(ServerLevel l, int x, int z) {
		if (!l.hasChunk(x >> 4, z >> 4)) return Integer.MIN_VALUE;
		int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos p = new BlockPos(x, y, z);
		if (!l.getFluidState(p.below()).isEmpty() || !l.getFluidState(p).isEmpty()) return Integer.MIN_VALUE;
		return y;
	}

	/** Leaves overhead (3..14 blocks above the ground) in this column. */
	private static boolean covered(ServerLevel l, int x, int z, int g) {
		int top = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		if (top - g < 3) return false;
		return leaves(l, new BlockPos(x, top - 1, z));
	}

	/** Server thread. name -> {Pose, time}. */
	static Map<String, Object[]> find(ServerLevel l) {
		Map<String, Object[]> out = new LinkedHashMap<>();
		Spot spot = BenchDriver.spot;
		int cx = spot.cx, cz = spot.cz, r = 112;
		// 1. canopy: the column whose 13x13 neighbourhood is 65-85% covered (dense with gaps), free space to stand
		double bestScore = -1;
		int bx = 0, bz = 0, by = 0;
		for (int x = cx - r; x <= cx + r; x += 3) {
			for (int z = cz - r; z <= cz + r; z += 3) {
				int g = ground(l, x, z);
				if (g == Integer.MIN_VALUE || !covered(l, x, z, g)) continue;
				if (!l.getBlockState(new BlockPos(x, g, z)).isAir() || !l.getBlockState(new BlockPos(x, g + 1, z)).isAir()
					|| !l.getBlockState(new BlockPos(x, g + 2, z)).isAir()) continue;
				int n = 0, c = 0;
				for (int i = -6; i <= 6; i += 2) {
					for (int j = -6; j <= 6; j += 2) {
						int gg = ground(l, x + i, z + j);
						if (gg == Integer.MIN_VALUE) continue;
						n++;
						if (covered(l, x + i, z + j, gg)) c++;
					}
				}
				if (n < 30) continue;
				double frac = c / (double) n;
				double score = 1.0 - Math.abs(frac - 0.75) - (Math.abs(x - cx) + Math.abs(z - cz)) / 2000.0;
				if (score > bestScore) {
					bestScore = score;
					bx = x;
					bz = z;
					by = g;
				}
			}
		}
		if (bestScore > 0) {
			double ex = bx + 0.5, ez = bz + 0.5, ey = by + 0.2; // feet
			out.put("vl_forest_morning", new Object[] {new Pose(ex, ey, ez, -90f, -10f), 900});
			out.put("vl_forest_side", new Object[] {new Pose(ex, ey, ez, 0f, -6f), 1300});
			// 2. forest edge, evening: walk east from the canopy spot to open ground, then a few blocks more; look back west
			int x = bx;
			int open = 0;
			for (; x < bx + 60 && open < 5; x++) {
				int g = ground(l, x, bz);
				if (g == Integer.MIN_VALUE) {
					open = 0;
					continue;
				}
				open = covered(l, x, bz, g) ? 0 : open + 1;
			}
			int g = ground(l, x + 4, bz);
			if (open >= 5 && g != Integer.MIN_VALUE) out.put("vl_forest_evening", new Object[] {new Pose(x + 4.5, g + 0.2, bz + 0.5, 90f, -6f), 11450});
		}
		// 3. shelter: a village door facing east or west, the camera inside looking out
		Pose shelter = null;
		int shelterTime = 0;
		double bestD = 1e9;
		for (int x = cx - r; x <= cx + r && shelter == null; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				if (!l.hasChunk(x >> 4, z >> 4)) continue;
				int top = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				for (int y = top - 4; y <= top; y++) {
					BlockState s = l.getBlockState(new BlockPos(x, y, z));
					if (!(s.getBlock() instanceof DoorBlock) || s.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
					Direction facing = s.getValue(DoorBlock.FACING);
					if (facing.getAxis() != Direction.Axis.X) continue;
					// inside = the side with a roof over the camera spot 2 blocks from the door
					for (int sgn = -1; sgn <= 1; sgn += 2) {
						int ix = x + sgn * 2;
						BlockPos in = new BlockPos(ix, y, z);
						boolean roofed = l.getHeight(Heightmap.Types.MOTION_BLOCKING, ix, z) > y + 2;
						if (!roofed || !l.getBlockState(in).isAir() || !l.getBlockState(in.above()).isAir()) continue;
						boolean outsideEast = sgn < 0; // inside is west of the door: outside (and the sun) is east
						double d = Math.abs(x - cx) + Math.abs(z - cz);
						if (d < bestD) {
							bestD = d;
							shelter = new Pose(ix + 0.5 + sgn * 0.3, y + 0.05, z + 0.5, outsideEast ? -90f : 90f, -2f);
							shelterTime = outsideEast ? 700 : 11550;
						}
					}
				}
			}
		}
		if (shelter != null) out.put("vl_shelter", new Object[] {shelter, shelterTime});
		// 4. under water: the deepest column near the centre (surface at sea level), 7 blocks down, looking up east
		int wx = 0, wz = 0, wDepth = 0;
		for (int x = cx - r; x <= cx + r; x += 4) {
			for (int z = cz - r; z <= cz + r; z += 4) {
				if (!l.hasChunk(x >> 4, z >> 4)) continue;
				int surf = l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				if (l.getFluidState(new BlockPos(x, surf, z)).getType() != Fluids.WATER) continue;
				int floor = l.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
				int depth = surf - floor;
				if (depth > wDepth) {
					wDepth = depth;
					wx = x;
					wz = z;
				}
			}
		}
		if (wDepth >= 9) {
			int surf = l.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
			out.put("vl_underwater", new Object[] {new Pose(wx + 0.5, surf - 7 - Spot.EYE, wz + 0.5, -90f, -32f), 2600});
		}
		return out;
	}
}
