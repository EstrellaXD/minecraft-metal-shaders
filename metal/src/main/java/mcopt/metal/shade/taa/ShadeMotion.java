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
package mcopt.metal.shade.taa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * M3 per-object motion for TAAU (bible E18 step 4), the cheap way: no motion target and no change to any world pass. Each
 * frame (GameRenderer.renderLevel, from ShadeTaa.camera) the entities near the camera are put at their render position
 * (lerp(partial tick, xOld, x), exactly what EntityRenderer draws them at); those whose render position changed since the
 * previous frame become "movers": a camera-relative world box (their bounding box at the render position, padded for parts
 * that stick out: heads, tails, held items) and the change. taa.metal reprojects a pixel whose reconstructed surface falls
 * in a mover's box from (world - change): the entity's own pixels keep their history instead of smearing it along the
 * motion. Rotation and animation (limb swing) are not covered; the rectification still handles those. At most
 * {@link #MAX} movers, nearest first. mcopt.shade.taaMotion=false turns it off.
 */
final class ShadeMotion {
	static final boolean ON = Boolean.parseBoolean(System.getProperty("mcopt.shade.taaMotion", "true"));
	static final int MAX = 32;
	/** Movers further than this (blocks) are ignored: a few pixels, the rectification copes. */
	private static final double RANGE = 64;
	/** A render-position change above this (blocks per frame) is a teleport: the history is wrong there anyway. */
	private static final double JUMP = 6;
	private static final double PAD = 0.3;

	/** Entity id -> {x, y, z, frame} of its last render position. */
	private final Map<Integer, double[]> last = new HashMap<>();
	private long frame;
	private final List<double[]> movers = new ArrayList<>();
	int count;

	/** Collects this frame's movers (render thread, world frame: level and partial tick of this frame). */
	void update(Vec3 camera) {
		this.frame++;
		this.movers.clear();
		this.count = 0;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || camera == null) {
			this.last.clear();
			return;
		}
		float pt = mc.gameRenderer.gameRenderState().levelRenderState.worldPartialTicks;
		Entity cameraEntity = mc.getCameraEntity();
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		for (Entity e : level.entitiesForRendering()) {
			if (firstPerson && e == cameraEntity) continue;
			double x = Mth.lerp(pt, e.xOld, e.getX()), y = Mth.lerp(pt, e.yOld, e.getY()), z = Mth.lerp(pt, e.zOld, e.getZ());
			double cx = x - camera.x, cy = y - camera.y, cz = z - camera.z;
			double d2 = cx * cx + cy * cy + cz * cz;
			if (d2 > RANGE * RANGE) continue;
			double[] l = this.last.get(e.getId());
			if (l == null) {
				this.last.put(e.getId(), new double[] {x, y, z, this.frame});
				continue;
			}
			if (l[3] == this.frame - 1) {
				double dx = x - l[0], dy = y - l[1], dz = z - l[2];
				double m2 = dx * dx + dy * dy + dz * dz;
				if (m2 > 1e-8 && m2 < JUMP * JUMP) {
					AABB b = e.getBoundingBox();
					double ox = x - e.getX() - camera.x, oy = y - e.getY() - camera.y, oz = z - e.getZ() - camera.z;
					this.movers.add(new double[] {d2, b.minX + ox - PAD, b.minY + oy - PAD * 0.5, b.minZ + oz - PAD, b.maxX + ox + PAD, b.maxY + oy + PAD,
						b.maxZ + oz + PAD, dx, dy, dz});
				}
			}
			l[0] = x;
			l[1] = y;
			l[2] = z;
			l[3] = this.frame;
		}
		if ((this.frame & 63) == 0) { // forget entities not seen for a while
			for (Iterator<double[]> it = this.last.values().iterator(); it.hasNext();) {
				if (it.next()[3] < this.frame - 2) it.remove();
			}
		}
		if (this.movers.size() > MAX) this.movers.sort((a, b) -> Double.compare(a[0], b[0]));
		this.count = Math.min(MAX, this.movers.size());
	}

	/** Hands this frame's movers to the native resolve (taa.metal TaaMovers). */
	void upload(long taa) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int bytes = 16 + Math.max(1, this.count) * 48;
			long p = stack.ncalloc(16, bytes, 1);
			MemoryUtil.memPutInt(p, this.count);
			for (int i = 0; i < this.count; i++) {
				double[] m = this.movers.get(i);
				long at = p + 16 + i * 48L;
				for (int k = 0; k < 3; k++) {
					MemoryUtil.memPutFloat(at + k * 4L, (float) m[1 + k]);
					MemoryUtil.memPutFloat(at + 16 + k * 4L, (float) m[4 + k]);
					MemoryUtil.memPutFloat(at + 32 + k * 4L, (float) m[7 + k]);
				}
			}
			TaaNative.movers(taa, p, bytes);
		}
	}

	void clear(long taa) {
		this.count = 0;
		this.last.clear();
		TaaNative.movers(taa, 0, 0);
	}
}
