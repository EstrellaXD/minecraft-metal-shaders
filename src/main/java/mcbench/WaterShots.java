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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * Opt-in water screenshots ({@code -Dmcbench.water=true}; off by default, so the standard plan is unchanged), owned by the
 * water and reflections work (mcopt-shade M2, branch m2-water). After the standard shots (or instead of them with
 * {@code -Dmcbench.standardShots=false}) the poses are found in the loaded world around the bench spot (server thread, read
 * only; nothing is built), then shot like extraShots:
 * <ul>
 * <li>{@code water_sunset}: low over open water facing the setting sun (west), the far shore on the horizon: glitter road,
 * sky and shore reflections.</li>
 * <li>{@code water_shore_noon}: noon, 3 blocks above water looking slightly down at a shore with trees 10-24 blocks away:
 * screen-space reflections of the shore and trees, refraction and absorption of the shallows.</li>
 * <li>{@code water_shore_evening}: the same pose in warm evening light (time 11000).</li>
 * <li>{@code water_shallows}: noon, looking steeply down into 2-4 blocks of water near a shore: bottom refraction,
 * caustics, foam at the edge.</li>
 * <li>{@code water_underwater_up}: 4 blocks under the surface of the deepest water nearby, noon, looking up toward the sun
 * (south, the shade sun path tilts that way): Snell's window and total internal reflection.</li>
 * </ul>
 * The chosen poses go to results.json "waterShots".
 */
final class WaterShots {
	static final boolean ON = Boolean.getBoolean("mcbench.water");

	private WaterShots() {
	}

	/** Called from BenchDriver.buildPlan after the standard shots. */
	static void plan(java.util.Deque<BenchDriver.Step> steps) {
		if (!ON) return;
		steps.add(new BenchDriver.Step("water-find", 60) {
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
					BenchDriver.log("water scene " + e.getKey() + " @" + time + " " + p);
					extra.add(new BenchDriver.Scene(e.getKey(), time, false, () -> p));
					extra.add(new BenchDriver.Settle(e.getKey(), false, 3, 120));
					extra.add(new BenchDriver.Hold(e.getKey(), Math.max(BenchDriver.SHOT_WAIT, 3)));
					extra.add(new BenchDriver.Shot(e.getKey()));
				}
				BenchDriver.results.put("waterShots", json);
				for (int i = extra.size() - 1; i >= 0; i--) steps.addFirst(extra.get(i));
				return true;
			}
		});
	}

	/** Sea-level water column: the surface block's y (water on top), or MIN_VALUE. */
	private static int surface(ServerLevel l, int x, int z) {
		if (!l.hasChunk(x >> 4, z >> 4)) return Integer.MIN_VALUE;
		int top = l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		return l.getFluidState(new BlockPos(x, top, z)).getType() == Fluids.WATER ? top : Integer.MIN_VALUE;
	}

	private static int depth(ServerLevel l, int x, int z, int surf) {
		return surf - l.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) + 1;
	}

	/** Leaves 3..12 blocks above sea level within 3 blocks of (x, z). */
	private static int trees(ServerLevel l, int x, int z, int sea) {
		int n = 0;
		for (int i = -3; i <= 3; i++) {
			for (int j = -3; j <= 3; j++) {
				if (!l.hasChunk((x + i) >> 4, (z + j) >> 4)) continue;
				int top = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x + i, z + j) - 1;
				if (top - sea >= 3 && top - sea <= 14 && l.getBlockState(new BlockPos(x + i, top, z + j)).is(BlockTags.LEAVES)) n++;
			}
		}
		return n;
	}

	/** Server thread. name -> {Pose, time}. */
	static Map<String, Object[]> find(ServerLevel l) {
		Map<String, Object[]> out = new LinkedHashMap<>();
		Spot spot = BenchDriver.spot;
		int cx = spot.cx, cz = spot.cz, r = 112;
		int sea = l.getSeaLevel() - 1; // the top water block of the sea
		// 1. sunset: the standard sunset_water spot, low over the water (eye 1.6 above the surface), looking west
		Pose s = spot.sunset;
		out.put("water_sunset", new Object[] {new Pose(s.x(), sea + 1 + 1.6 - Spot.EYE, s.z(), 90f, 2f), 12250});
		// 2. shore with trees: a water spot (depth >= 3) from which one of 8 directions reaches land after 10-24 blocks of
		// water, with leaves near that landfall; score = trees there, nearer the centre wins ties
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
		double best = 0;
		Pose shore = null;
		for (int x = cx - r; x <= cx + r; x += 4) {
			for (int z = cz - r; z <= cz + r; z += 4) {
				int surf = surface(l, x, z);
				if (surf != sea || depth(l, x, z, surf) < 3) continue;
				for (int[] d : dirs) {
					int k = 1;
					for (; k <= 26; k++) {
						if (surface(l, x + d[0] * k, z + d[1] * k) != sea) break;
					}
					if (k < 10 || k > 24) continue;
					int lx = x + d[0] * (k + 2), lz = z + d[1] * (k + 2);
					int t = trees(l, lx, lz, sea);
					if (t < 4) continue;
					double score = t - (Math.abs(x - cx) + Math.abs(z - cz)) / 400.0;
					if (score > best) {
						best = score;
						double ex = x + 0.5, ez = z + 0.5, ey = sea + 1 + 3.0;
						shore = Pose.lookAt(ex, ey, ez, lx + 0.5, sea + 1 + 1.0, lz + 0.5);
					}
				}
			}
		}
		if (shore != null) {
			out.put("water_shore_noon", new Object[] {shore, 6000});
			out.put("water_shore_evening", new Object[] {shore, 11000});
		}
		// 3. shallows: 2-4 blocks deep, next to land, camera 3 blocks above the surface looking 50 degrees down toward the land
		Pose shallows = null;
		double bestD = 1e9;
		for (int x = cx - r; x <= cx + r; x += 3) {
			for (int z = cz - r; z <= cz + r; z += 3) {
				int surf = surface(l, x, z);
				if (surf != sea) continue;
				int dp = depth(l, x, z, surf);
				if (dp < 2 || dp > 4) continue;
				for (int[] d : dirs) {
					int lx = x + d[0] * 4, lz = z + d[1] * 4;
					if (!l.hasChunk(lx >> 4, lz >> 4) || surface(l, lx, lz) != Integer.MIN_VALUE) continue;
					int land = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
					if (land - (sea + 1) > 2) continue; // a beach, not a cliff
					double dd = Math.abs(x - cx) + Math.abs(z - cz);
					if (dd < bestD) {
						bestD = dd;
						double ex = x - d[0] * 2 + 0.5, ez = z - d[1] * 2 + 0.5, ey = sea + 1 + 3.2;
						shallows = Pose.lookAt(ex, ey, ez, x + d[0] * 1.5 + 0.5, sea + 1 - dp, z + d[1] * 1.5 + 0.5);
					}
				}
			}
		}
		if (shallows != null) out.put("water_shallows", new Object[] {shallows, 6000});
		// 4. under water: the deepest column near the centre, 4 blocks under the surface, looking up south toward the sun
		int wx = 0, wz = 0, wDepth = 0;
		for (int x = cx - r; x <= cx + r; x += 4) {
			for (int z = cz - r; z <= cz + r; z += 4) {
				int surf = surface(l, x, z);
				if (surf != sea) continue;
				int dp = depth(l, x, z, surf);
				if (dp > wDepth) {
					wDepth = dp;
					wx = x;
					wz = z;
				}
			}
		}
		if (wDepth >= 7) out.put("water_underwater_up", new Object[] {new Pose(wx + 0.5, sea + 1 - 4 - Spot.EYE, wz + 0.5, 0f, -50f), 6000});
		return out;
	}
}
