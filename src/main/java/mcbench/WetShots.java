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
 * <p>{@code -Dmcbench.wetPerf=<scene>@<seconds>@<phase>;<phase>...} (groups separated by {@code /}): recorded perf phases at
 * one of these scenes (its time, weather and pose, panning +-25 degrees over the phase), each phase with its own system
 * properties (MiscShots' phase syntax), after an unrecorded warm-up phase with the first phase's properties. The phases are
 * MiscShots' {@code abN} slots (numbered after MiscShots' own; results.json "ab" says scene and properties, perf.abN / gpu.abN
 * hold the numbers), so they need no new recorder phases.
 */
final class WetShots {
	static final String SPEC = System.getProperty("mcbench.wet", "").trim();
	static final String VARIANTS = System.getProperty("mcbench.wetVariants", "m32:mcopt.shade.wetAll=false;new:mcopt.shade.wetAll=true").trim();
	static final double HOLD = Double.parseDouble(System.getProperty("mcbench.wetHold", "2.5"));
	static final String SUNSET_WET = System.getProperty("mcbench.wetSunsetWetness", "0.85");
	static final Map<String, Object> JSON = new LinkedHashMap<>();
	static final String[] ALL = {"ground", "ground_low", "sunset", "night", "storm", "path", "sand", "stone"};
	static final String PERF = System.getProperty("mcbench.wetPerf", "").trim();

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
		boolean any = !PERF.isEmpty();
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
				for (String group : PERF.split("/")) if (!group.isBlank()) perf(extra, group.trim(), p);
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

	/** The scene's time, weather, pose and held wetness (null: from the weather). */
	private record Where(int time, String weather, Pose pose, String wetness) {
	}

	private static Where where(String name, WeatherShots.Poses p) {
		Pose g = p.ground();
		return switch (name) {
			case "ground" -> new Where(6000, "rain", g, null);
			case "ground_low" -> g == null ? null : new Where(6000, "rain", new Pose(g.x(), g.y() - 1, g.z(), g.yaw(), 6f), null);
			case "sunset" -> new Where(12000, "clear", g, SUNSET_WET);
			case "night" -> new Where(18000, "rain", g, null);
			case "storm" -> new Where(6000, "thunder", p.storm(), null);
			case "path" -> new Where(6000, "rain", found.path, null);
			case "sand" -> new Where(6000, "rain", found.sand, null);
			case "stone" -> new Where(6000, "rain", found.stone, null);
			default -> throw new IllegalArgumentException("mcbench.wetPerf: unknown scene " + name);
		};
	}

	/** Recorded phases (MiscShots' abN slots) at one scene, panning +-25 degrees, each with its own properties. */
	private static void perf(List<BenchDriver.Step> extra, String group, WeatherShots.Poses p) {
		String[] f = group.split("@", 3);
		if (f.length < 3) throw new IllegalArgumentException("mcbench.wetPerf: bad group " + group + " (scene@seconds@phase;phase...)");
		Where w = where(f[0].trim(), p);
		if (w == null || w.pose == null) {
			BenchDriver.warnings.add("wetPerf: no pose for " + f[0]);
			return;
		}
		double sec = Double.parseDouble(f[1].trim());
		List<MiscShots.Phase> phases = new ArrayList<>();
		for (String s : f[2].split(";")) if (!s.isBlank()) phases.add(MiscShots.Phase.parse(s));
		if (phases.isEmpty()) return;
		BenchDriver.results.put("ab", MiscShots.AB_JSON);
		boolean rain = !w.weather.equals("clear");
		String sc = "wetperf-" + f[0].trim();
		String[] wetBefore = new String[1];
		if (w.wetness != null) extra.add(prop(sc + "-wet", () -> {
			wetBefore[0] = System.getProperty("mcopt.shade.wetness");
			System.setProperty("mcopt.shade.wetness", w.wetness);
		}));
		extra.add(new CloudShots.WeatherScene(sc, w.time, w.weather, () -> w.pose));
		extra.add(new BenchDriver.Settle(sc, rain, 3, 150));
		extra.add(new BenchDriver.Hold(sc, rain ? 4 : 1));
		extra.add(new BenchDriver.Warmup(1));
		phase(extra, sc + "-warm", null, w.pose, sec, phases.get(0));
		for (MiscShots.Phase ph : phases) {
			if (MiscShots.abCount >= MiscShots.MAX_AB) throw new IllegalArgumentException("mcbench.wetPerf: more than " + MiscShots.MAX_AB + " ab phases in all");
			String name = "ab" + (++MiscShots.abCount);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("scene", sc);
			m.put("seconds", sec);
			m.put("label", ph.label());
			m.put("props", ph.props());
			MiscShots.AB_JSON.put(name, m);
			phase(extra, name, name, w.pose, sec, ph);
		}
		if (w.wetness != null) extra.add(prop(sc + "-unwet", () -> set("mcopt.shade.wetness", wetBefore[0])));
	}

	private static void phase(List<BenchDriver.Step> extra, String name, String rec, Pose o, double sec, MiscShots.Phase ph) {
		Map<String, String> before = new LinkedHashMap<>();
		extra.add(new BenchDriver.Step(name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.setPhase(rec == null ? "WARMUP" : name.toUpperCase(java.util.Locale.ROOT));
				before.clear();
				for (var e : ph.props().entrySet()) {
					before.put(e.getKey(), System.getProperty(e.getKey()));
					set(e.getKey(), e.getValue());
				}
				long t0 = now;
				BenchDriver.pose = t -> new Pose(o.x(), o.y(), o.z(), (float) (o.yaw() + 25.0 * Math.sin(BenchDriver.secs(t - t0) * 2 * Math.PI / sec)), o.pitch());
				if (rec != null) {
					BenchDriver.recPhase = BenchDriver.REC_PHASES.indexOf(rec);
					if (BenchDriver.recPhase < 0) throw new IllegalStateException("mcbench: " + rec + " is not in BenchDriver.REC_PHASES");
					BenchDriver.phaseRanges.put(rec, new long[] {now, 0});
				}
				BenchDriver.log("wetPerf " + name + " (" + ph.describe() + ") " + sec + " s");
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				if (BenchDriver.secs(now - BenchDriver.curStart) < sec) return false;
				if (rec != null) {
					BenchDriver.recPhase = -1;
					BenchDriver.phaseRanges.get(rec)[1] = now;
				}
				for (var e : before.entrySet()) set(e.getKey(), e.getValue());
				Pose last = BenchDriver.pose.apply(now);
				BenchDriver.pose = t -> last;
				return true;
			}
		});
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
