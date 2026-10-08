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
import java.util.function.Predicate;
import java.util.function.Supplier;
import mcbench.Spot.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Wet-ground A/B shots (opt-in, off by default so the standard plan is unchanged), owned by the mcopt-shade M3.3 wet-ground
 * work (branch m3.3-wet, mcopt.shade.wetAll). {@code -Dmcbench.wet=<scene>,<scene>...} (or {@code all}); at each scene the
 * ticks are frozen after the settle and every variant of {@code -Dmcbench.wetVariants=<variant>;<variant>...} (MiscShots'
 * phase syntax {@code <label>} or {@code <label>:<key>=<value>,...}; default {@code m32:mcopt.shade.wetAll=false;new:mcopt.shade.wetAll=true})
 * is set (system properties mcopt-shade reads every frame), held {@code -Dmcbench.wetHold} (2.5) s and shot as
 * {@code wet_<scene>_<label>}, then restored. Scenes:
 * <ul>
 * <li>{@code ground} (noon, rain): WeatherShots' ground pose (2.6 blocks above the grass beside the torch grid).</li>
 * <li>{@code sunset} (12000, clear, after the rain): the same pose with the wetness held at {@code -Dmcbench.wetSunsetWetness}
 * (0.85) through mcopt.shade.wetness (read every frame by mcopt-shade M3.3), so every variant sees the same wet ground.</li>
 * <li>{@code night} (18000, rain): the same pose: torch light on the wet ground and in the puddles.</li>
 * <li>{@code storm} (noon, thunder): WeatherShots' storm pose (the sunset spot 6 blocks up looking west over the water).</li>
 * <li>{@code path}, {@code sand}, {@code stone} (noon, rain): the 7x7 patch with the most open-sky village path / sand /
 * stone-like (stone, cobblestone, stone bricks, gravel, andesite, diorite, granite) top blocks within 128 blocks of the torch
 * grid, seen from 7 blocks away, the eye 2.6 above it (skipped when none is found).</li>
 * <li>{@code ground_low} (noon, rain): the ground pose 1 block lower and level (pitch 6): grazing view over the wet meadow.</li>
 * </ul>
 * Poses go to results.json "wetShots".
 */
final class WetShots {
	static final String SPEC = System.getProperty("mcbench.wet", "").trim();
	static final String VARIANTS = System.getProperty("mcbench.wetVariants", "m32:mcopt.shade.wetAll=false;new:mcopt.shade.wetAll=true").trim();
	static final double HOLD = Double.parseDouble(System.getProperty("mcbench.wetHold", "2.5"));
	static final String SUNSET_WET = System.getProperty("mcbench.wetSunsetWetness", "0.85");
	static final Map<String, Object> JSON = new LinkedHashMap<>();
	static final String[] ALL = {"ground", "ground_low", "sunset", "night", "storm", "path", "sand", "stone"};

	record Found(Pose path, Pose sand, Pose stone) {
	}

	static Found found;

	private WetShots() {
	}

	static boolean on(String scene) {
		if (SPEC.isEmpty() || SPEC.equals("false")) return false;
		if (SPEC.equals("true") || SPEC.equals("all")) return true;
		for (String s : SPEC.split(",")) if (s.trim().equals(scene)) return true;
		return false;
	}

	/** Called from BenchDriver.buildPlan after the other shots. */
	static void plan(java.util.Deque<BenchDriver.Step> steps) {
		boolean any = false;
		for (String s : ALL) any |= on(s);
		if (!any) return;
		BenchDriver.results.put("wetShots", JSON);
		List<MiscShots.Phase> vs = new ArrayList<>();
		for (String p : VARIANTS.split(";")) if (!p.isBlank()) vs.add(MiscShots.Phase.parse(p));
		JSON.put("variants", VARIANTS);
		// the torch grid's area loaded on the server: WeatherShots' poses and the surface search read it
		steps.add(new BenchDriver.Scene("wet-site", 6000, false, () -> BenchDriver.spot.night));
		steps.add(new BenchDriver.Settle("wet-site", false, 3, 120));
		steps.add(new BenchDriver.Step("wet-find", 60) {
			CompletableFuture<Found> f;

			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				f = CompletableFuture.supplyAsync(() -> {
					if (WeatherShots.poses == null) WeatherShots.poses = WeatherShots.find(s, s.overworld());
					return find(s.overworld());
				}, s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (!f.isDone()) return false;
				found = f.get();
				BenchDriver.log("wet poses " + WeatherShots.poses + " " + found);
				WeatherShots.Poses p = WeatherShots.poses;
				List<BenchDriver.Step> extra = new ArrayList<>();
				if (on("ground") && p.ground() != null) scene(extra, "ground", 6000, "rain", p.ground(), null, vs);
				if (on("ground_low") && p.ground() != null) {
					Pose g = p.ground();
					scene(extra, "ground_low", 6000, "rain", new Pose(g.x(), g.y() - 1, g.z(), g.yaw(), 6f), null, vs);
				}
				if (on("sunset") && p.ground() != null) scene(extra, "sunset", 12000, "clear", p.ground(), SUNSET_WET, vs);
				if (on("night") && p.ground() != null) scene(extra, "night", 18000, "rain", p.ground(), null, vs);
				if (on("storm") && p.storm() != null) scene(extra, "storm", 6000, "thunder", p.storm(), null, vs);
				if (on("path") && found.path != null) scene(extra, "path", 6000, "rain", found.path, null, vs);
				if (on("sand") && found.sand != null) scene(extra, "sand", 6000, "rain", found.sand, null, vs);
				if (on("stone") && found.stone != null) scene(extra, "stone", 6000, "rain", found.stone, null, vs);
				for (int i = extra.size() - 1; i >= 0; i--) steps.addFirst(extra.get(i));
				return true;
			}
		});
	}

	/** One pose: settle, freeze the ticks, one shot per variant (properties set, held, restored), unfreeze. */
	private static void scene(List<BenchDriver.Step> extra, String name, int time, String weather, Pose pose, String wetness, List<MiscShots.Phase> vs) {
		boolean rain = !weather.equals("clear");
		String sc = "wet-" + name;
		String[] wetBefore = new String[1];
		if (wetness != null) extra.add(prop(sc + "-wet", () -> {
			wetBefore[0] = System.getProperty("mcopt.shade.wetness");
			System.setProperty("mcopt.shade.wetness", wetness);
		}));
		extra.add(new CloudShots.WeatherScene(sc, time, weather, () -> pose));
		extra.add(new BenchDriver.Settle(sc, rain, 3, 120));
		extra.add(new BenchDriver.Hold(sc, rain ? Math.max(BenchDriver.SHOT_WAIT, 5) : Math.max(BenchDriver.SHOT_WAIT, 3)));
		extra.add(command(sc + "-freeze", "tick freeze"));
		Map<String, Object> json = pose.json();
		json.put("time", time);
		json.put("weather", weather);
		if (wetness != null) json.put("wetness", wetness);
		for (MiscShots.Phase v : vs) {
			String shot = "wet_" + name + "_" + v.label();
			Map<String, String> before = new LinkedHashMap<>();
			extra.add(prop("set-" + shot, () -> {
				before.clear();
				for (var e : v.props().entrySet()) {
					before.put(e.getKey(), System.getProperty(e.getKey()));
					set(e.getKey(), e.getValue());
				}
				BenchDriver.log("wet variant " + shot + " (" + v.describe() + ")");
			}));
			extra.add(new BenchDriver.Hold(shot, HOLD));
			extra.add(new BenchDriver.Shot(shot));
			extra.add(prop("restore-" + shot, () -> {
				for (var e : before.entrySet()) set(e.getKey(), e.getValue());
			}));
		}
		extra.add(command(sc + "-unfreeze", "tick unfreeze"));
		if (wetness != null) extra.add(prop(sc + "-unwet", () -> set("mcopt.shade.wetness", wetBefore[0])));
		JSON.put(name, json);
	}

	private static void set(String key, String value) {
		if (value == null || value.equals("-")) System.clearProperty(key);
		else System.setProperty(key, value);
	}

	private static BenchDriver.Step prop(String name, Runnable r) {
		return new BenchDriver.Step(name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				r.run();
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return true;
			}
		};
	}

	private static BenchDriver.Step command(String name, String c) {
		return new BenchDriver.Step(name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.cmd(BenchDriver.server(mc), c);
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= 0.3;
			}
		};
	}

	// ---------------------------------------------------------------------------------------------- surface search

	/** Server thread: the best open-sky path / sand / stone patches near the torch grid. */
	static Found find(ServerLevel l) {
		Spot spot = BenchDriver.spot;
		int cx = spot.torchX, cz = spot.torchZ, R = 128, N = 2 * R + 1;
		int[] top = new int[N * N];
		BlockState[] st = new BlockState[N * N];
		for (int i = 0; i < N; i++) {
			for (int j = 0; j < N; j++) {
				int x = cx - R + i, z = cz - R + j, k = i * N + j;
				top[k] = Integer.MIN_VALUE;
				if (!l.hasChunk(x >> 4, z >> 4)) continue;
				int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				if (l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1 != y) continue; // something above (leaves): no open sky
				BlockState s = l.getBlockState(new BlockPos(x, y, z));
				if (!s.getFluidState().isEmpty()) continue;
				top[k] = y;
				st[k] = s;
			}
		}
		Pose path = best(l, top, st, cx, cz, R, s -> s.is(Blocks.DIRT_PATH));
		Pose sand = best(l, top, st, cx, cz, R, s -> s.is(Blocks.SAND) || s.is(Blocks.RED_SAND));
		Pose stone = best(l, top, st, cx, cz, R, s -> s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.STONE_BRICKS)
			|| s.is(Blocks.GRAVEL) || s.is(Blocks.ANDESITE) || s.is(Blocks.DIORITE) || s.is(Blocks.GRANITE) || s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.COBBLESTONE_SLAB));
		return new Found(path, sand, stone);
	}

	/** The 7x7 window with the most matching top blocks (>= 10, within 2 blocks of height), seen from 7 blocks away. */
	private static Pose best(ServerLevel l, int[] top, BlockState[] st, int cx, int cz, int R, Predicate<BlockState> match) {
		int N = 2 * R + 1;
		int[] sum = new int[(N + 1) * (N + 1)];
		for (int i = 0; i < N; i++) {
			for (int j = 0; j < N; j++) {
				int k = i * N + j;
				int m = top[k] != Integer.MIN_VALUE && match.test(st[k]) ? 1 : 0;
				sum[(i + 1) * (N + 1) + j + 1] = m + sum[i * (N + 1) + j + 1] + sum[(i + 1) * (N + 1) + j] - sum[i * (N + 1) + j];
			}
		}
		int bestN = 9, bi = -1, bj = -1;
		for (int i = 3; i < N - 3; i++) {
			for (int j = 3; j < N - 3; j++) {
				int a = i - 3, b = j - 3, c = i + 4, d = j + 4;
				int n = sum[c * (N + 1) + d] - sum[a * (N + 1) + d] - sum[c * (N + 1) + b] + sum[a * (N + 1) + b];
				if (n <= bestN || top[i * N + j] == Integer.MIN_VALUE || !match.test(st[i * N + j])) continue;
				// prefer windows near the grid a little (they are loaded and built for sure)
				bestN = n;
				bi = i;
				bj = j;
			}
		}
		if (bi < 0) return null;
		int x = cx - R + bi, z = cz - R + bj, y = top[bi * N + bj];
		int[][] dirs = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {1, 0}, {0, 1}, {-1, 0}, {0, -1}};
		for (int[] d : dirs) {
			double len = Math.sqrt(d[0] * d[0] + d[1] * d[1]);
			double ex = x + 0.5 + d[0] / len * 7, ez = z + 0.5 + d[1] / len * 7, ey = y + 1 + 2.6;
			boolean ok = true;
			for (int s = 0; s <= 6 && ok; s++) { // the line of sight from the eye down to the patch is open
				double t = s / 6.0;
				BlockPos bp = BlockPos.containing(ex + (x + 0.5 - ex) * t, ey + (y + 1.2 - ey) * t, ez + (z + 0.5 - ez) * t);
				ok = l.getBlockState(bp).isAir();
			}
			if (ok && !l.getBlockState(BlockPos.containing(ex, ey + 0.5, ez)).isAir()) ok = false;
			if (ok) return Pose.lookAt(ex, ey, ez, x + 0.5, y + 1, z + 0.5);
		}
		return null;
	}
}
