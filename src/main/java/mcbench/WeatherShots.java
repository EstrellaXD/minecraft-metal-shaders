/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import mcbench.Spot.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * Opt-in weather screenshots ({@code -Dmcbench.weather=true} for all of them, or a comma list of
 * {@code perf,rain,sunset,night,water,storm,glass,caustics}; off by default, so the standard plan is unchanged), owned by the mcopt-shade M3
 * weather work (branch m3-weather). Poses are found in the loaded world around the bench spot (server thread):
 * <ul>
 * <li>{@code perf}: two recorded perf phases at the ground pose (results.json perf.wxdry / perf.wxrain, gpu.*): 10 s panning
 * +-25 degrees, clear noon, then in rain (the weather's cost; -Dmcbench.wxSeconds).</li>
 * <li>{@code wx_rain_ground} (noon, rain): 2.6 blocks above the flat torch-grid site, looking across it toward the village
 * side: wet ground, puddles with reflections and raindrop ripples, slanted lit rain.</li>
 * <li>{@code wx_wet_sunset} (it rains for a while, then clears; shot at 12000): the same pose, the ground still wet after
 * the rain: sunset light in the puddles.</li>
 * <li>{@code wx_night_rain} (18000, rain): the same pose at night: torch light reflected in the puddles.</li>
 * <li>{@code wx_rain_water} (noon, rain): 2.5 blocks above open water near the standard sunset spot, looking down at it:
 * raindrop rings on water.</li>
 * <li>{@code wx_storm} (noon, rain): the sunset spot looking west over the water; a lightning bolt is summoned on the water
 * 45-90 blocks ahead and the shot is taken while it flashes.</li>
 * <li>{@code wx_caustics} (noon, clear): 5 blocks above shallow water (1-3 deep) near the sunset spot, looking down at its
 * floor: water caustics in the coloured-shadow map.</li>
 * <li>{@code wx_glass_noon} / {@code wx_glass_evening} (6000 / 11000, clear): a 6x6 roof of six colours of stained glass, 4
 * blocks above a flat grass patch beside the torch grid: coloured shadows. The glass is set back to air afterwards (and
 * before, in case an earlier run was interrupted: only stained glass is replaced, natural blocks are never touched).</li>
 * </ul>
 * mcopt flags that go well with it: {@code -Dmcopt.shade.weatherTime=10} (wetness builds and dries 10x faster, so the
 * rain shots show soaked ground after the usual hold). Poses go to results.json "weatherShots".
 */
final class WeatherShots {
	static final String SPEC = System.getProperty("mcbench.weather", "").trim();
	static final String[] GLASS = {"red", "orange", "yellow", "lime", "light_blue", "magenta"};
	static final Map<String, Object> JSON = new LinkedHashMap<>();
	/** The glass roof: corner x, z, y of the glass layer (null until placed). */
	static int[] roof;

	private WeatherShots() {
	}

	static boolean on(String scene) {
		if (SPEC.isEmpty() || SPEC.equals("false")) return false;
		if (SPEC.equals("true") || SPEC.equals("all")) return true;
		for (String s : SPEC.split(",")) if (s.trim().equals(scene)) return true;
		return false;
	}

	record Poses(Pose ground, Pose water, Pose storm, double[] bolt, Pose glassNoon, Pose glassEvening, int[] glassSite, Pose shallow) {
	}

	static Poses poses;

	/** Called from BenchDriver.buildPlan after the other shots. */
	static void plan(java.util.Deque<BenchDriver.Step> steps) {
		if (!(on("perf") || on("rain") || on("sunset") || on("night") || on("water") || on("storm") || on("glass") || on("caustics"))) return;
		BenchDriver.results.put("weatherShots", JSON);
		// the torch grid's area must be loaded on the server to read the ground (and place the glass)
		steps.add(new BenchDriver.Scene("wx-site", 6000, false, () -> BenchDriver.spot.night));
		steps.add(new BenchDriver.Settle("wx-site", false, 3, 120));
		steps.add(new BenchDriver.Step("wx-find", 60) {
			CompletableFuture<Poses> f;

			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				f = CompletableFuture.supplyAsync(() -> find(s, s.overworld()), s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (!f.isDone()) return false;
				poses = f.get();
				BenchDriver.log("weather poses " + poses);
				List<BenchDriver.Step> extra = new ArrayList<>();
				Poses p = poses;
				// glass first: the ground is still dry (then perf: dry, rain)
				if (on("glass") && p.glassSite != null) {
					extra.add(new BenchDriver.Step("wx-glass-build", 0) {
						@Override
						void start(Minecraft mc2, long now2) {
							build(BenchDriver.server(mc2), p.glassSite);
						}

						@Override
						boolean poll(Minecraft mc2, long now2) {
							return BenchDriver.secs(now2 - BenchDriver.curStart) >= 1.0;
						}
					});
					shot(extra, "wx_glass_noon", 6000, false, p.glassNoon);
					shot(extra, "wx_glass_evening", 11000, false, p.glassEvening);
				}
				if (on("caustics") && p.shallow != null) shot(extra, "wx_caustics", 6000, false, p.shallow);
				if (on("perf") && p.ground != null) {
					// the cost of the weather: the same 10 s pan over the ground pose, dry, then soaked in rain
					perf(extra, "wxdry", false, p.ground);
					perf(extra, "wxrain", true, p.ground);
				}
				if (on("rain") && p.ground != null) shot(extra, "wx_rain_ground", 6000, true, p.ground);
				if (on("sunset") && p.ground != null) {
					// rain first (the ground soaks), then the sky clears for the sunset
					extra.add(new BenchDriver.Scene("wx-soak", 11000, true, () -> p.ground));
					extra.add(new BenchDriver.Settle("wx-soak", true, 3, 120));
					extra.add(new BenchDriver.Hold("wx-soak", 4));
					shot(extra, "wx_wet_sunset", 12000, false, p.ground);
				}
				if (on("night") && p.ground != null) shot(extra, "wx_night_rain", 18000, true, p.ground);
				if (on("water") && p.water != null) shot(extra, "wx_rain_water", 6000, true, p.water);
				if (on("storm") && p.storm != null) storm(extra, p);
				extra.add(new BenchDriver.Step("wx-cleanup", 0) {
					@Override
					void start(Minecraft mc2, long now2) {
						cleanup(BenchDriver.server(mc2));
					}

					@Override
					boolean poll(Minecraft mc2, long now2) {
						return BenchDriver.secs(now2 - BenchDriver.curStart) >= 1.0;
					}
				});
				for (int i = extra.size() - 1; i >= 0; i--) steps.addFirst(extra.get(i));
				return true;
			}
		});
	}

	private static void shot(List<BenchDriver.Step> extra, String name, int time, boolean rain, Pose p) {
		extra.add(new BenchDriver.Scene(name, time, rain, () -> p));
		extra.add(new BenchDriver.Settle(name, rain, 3, 120));
		extra.add(new BenchDriver.Hold(name, Math.max(BenchDriver.SHOT_WAIT, rain ? 4 : 2.5)));
		extra.add(new BenchDriver.Shot(name));
		Map<String, Object> m = p.json();
		m.put("time", time);
		m.put("rain", rain);
		JSON.put(name, m);
	}

	/** A recorded perf phase (results.json perf.name / gpu.name): -Dmcbench.wxSeconds (10) s panning +-25 degrees. */
	private static void perf(List<BenchDriver.Step> extra, String name, boolean rain, Pose o) {
		double sec = Double.parseDouble(System.getProperty("mcbench.wxSeconds", "10"));
		extra.add(new BenchDriver.Scene(name, 6000, rain, () -> o));
		extra.add(new BenchDriver.Settle(name, rain, 3, 120));
		extra.add(new BenchDriver.Hold(name, rain ? 4 : 1));
		extra.add(new BenchDriver.Warmup(1));
		extra.add(new BenchDriver.Step(name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.setPhase(name.toUpperCase(Locale.ROOT));
				long t0 = now;
				BenchDriver.pose = t -> new Pose(o.x(), o.y(), o.z(), (float) (o.yaw() + 25.0 * Math.sin(BenchDriver.secs(t - t0) * 2 * Math.PI / sec)), o.pitch());
				BenchDriver.recPhase = BenchDriver.REC_PHASES.indexOf(name);
				BenchDriver.phaseRanges.put(name, new long[] {now, 0});
				BenchDriver.log("perf " + name + " " + sec + " s");
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				if (BenchDriver.secs(now - BenchDriver.curStart) < sec) return false;
				BenchDriver.recPhase = -1;
				BenchDriver.phaseRanges.get(name)[1] = now;
				return true;
			}
		});
	}

	/** Rain over the water, a bolt on the water ahead; the shot 0.12 s after it is summoned (the bolt lives ~0.4 s). */
	private static void storm(List<BenchDriver.Step> extra, Poses p) {
		extra.add(new BenchDriver.Scene("wx_storm", 6000, true, () -> p.storm));
		extra.add(new BenchDriver.Settle("wx_storm", true, 3, 120));
		extra.add(new BenchDriver.Hold("wx_storm", 4));
		extra.add(new BenchDriver.Step("wx-strike", 5) {
			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.cmd(BenchDriver.server(mc), String.format(Locale.ROOT, "summon minecraft:lightning_bolt %.1f %.1f %.1f", p.bolt[0], p.bolt[1], p.bolt[2]));
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= 0.12;
			}
		});
		extra.add(new BenchDriver.Shot("wx_storm"));
		Map<String, Object> m = p.storm.json();
		m.put("time", 6000);
		m.put("bolt", p.bolt);
		JSON.put("wx_storm", m);
	}

	/** Ground y (the top solid block, glass ignored) at x, z, or MIN_VALUE where it is water or not loaded. */
	private static int ground(ServerLevel l, int x, int z) {
		if (!l.hasChunk(x >> 4, z >> 4)) return Integer.MIN_VALUE;
		int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		for (int i = 0; i < 12; i++) {
			var st = l.getBlockState(new BlockPos(x, y, z));
			if (st.getBlock().getDescriptionId().contains("stained_glass")) {
				y--;
				continue;
			}
			if (!st.getFluidState().isEmpty()) return Integer.MIN_VALUE;
			return y;
		}
		return Integer.MIN_VALUE;
	}

	private static boolean air(ServerLevel l, int x, int y, int z) {
		var st = l.getBlockState(new BlockPos(x, y, z));
		return st.isAir() || st.getBlock().getDescriptionId().contains("stained_glass");
	}

	/** Server thread. */
	static Poses find(MinecraftServer s, ServerLevel l) {
		Spot spot = BenchDriver.spot;
		int tx = spot.torchX, tz = spot.torchZ;
		int sea = l.getSeaLevel() - 1;
		// 1. the ground pose: diagonally outside the torch grid's corner, eye 2.6 blocks up, looking across the grid
		Pose ground = null;
		int g = ground(l, tx, tz);
		if (g != Integer.MIN_VALUE) {
			int ex = tx - 13, ez = tz - 13;
			int ge = ground(l, ex, ez);
			if (ge == Integer.MIN_VALUE) ge = g;
			ground = Pose.lookAt(ex + 0.5, ge + 1 + 2.6, ez + 0.5, tx + 6.5, g + 1, tz + 6.5);
		}
		// 2. water: the standard sunset spot, 2.5 blocks above the surface, looking 35 degrees down to the west
		Pose sp = spot.sunset;
		Pose water = new Pose(sp.x(), sea + 1 + 2.5 - Spot.EYE, sp.z(), 90f, 35f);
		// 3. storm: the sunset spot 6 blocks up looking west; the bolt on the first water 45-90 blocks west
		Pose storm = new Pose(sp.x(), sea + 1 + 6 - Spot.EYE, sp.z(), 90f, -4f);
		double[] bolt = null;
		for (int d = 45; d <= 90 && bolt == null; d += 3) {
			int x = (int) Math.floor(sp.x()) - d, z = (int) Math.floor(sp.z());
			if (!l.hasChunk(x >> 4, z >> 4)) continue;
			int top = l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
			if (l.getFluidState(new BlockPos(x, top, z)).getType() == Fluids.WATER) bolt = new double[] {x + 0.5, top + 1, z + 0.5};
		}
		if (bolt == null) storm = null;
		// 4. glass: a 6x6 patch beside the torch grid (outside its +-15 blocks and the entity zoo's line) that is land, flat
		// within two blocks, open from 2 blocks above it
		int[] site = null;
		int[][] cands = {{-24, -3}, {18, -3}, {-3, -24}, {-3, 18}, {-24, 12}, {18, 12}, {-24, -20}, {18, -20}, {-30, -3}, {24, -3}, {-3, -30}, {-3, 24}};
		for (int[] c : cands) {
			int x0 = tx + c[0], z0 = tz + c[1];
			int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
			boolean ok = true;
			for (int i = 0; i < 6 && ok; i++) {
				for (int j = 0; j < 6 && ok; j++) {
					int y = ground(l, x0 + i, z0 + j);
					if (y == Integer.MIN_VALUE) {
						ok = false;
						break;
					}
					lo = Math.min(lo, y);
					hi = Math.max(hi, y);
				}
			}
			if (!ok || hi - lo > 2) continue;
			int gy = hi + 5;
			// open sky over the patch from 2 blocks up (grass and flowers below that are fine: the glass never touches them)
			for (int i = 0; i < 6 && ok; i++) for (int j = 0; j < 6 && ok; j++) for (int y = hi + 3; y <= gy + 2; y++) ok &= air(l, x0 + i, y, z0 + j);
			if (!ok) continue;
			site = new int[] {x0, z0, gy, hi};
			break;
		}
		Pose glassNoon = null, glassEvening = null;
		if (site != null) {
			double cx = site[0] + 3, cz = site[1] + 3;
			// noon: from the south-east, above the roof's height, looking down at the patch north of it (the tilted sun's shadow)
			glassNoon = Pose.lookAt(cx + 8.5, site[3] + 1 + 7.5, cz + 7.5, cx, site[3] + 1, cz - 1.5);
			// evening (sun in the west): the shadow falls east; from the south looking at the area east of the roof
			glassEvening = Pose.lookAt(cx + 4, site[3] + 1 + 6.5, cz + 11, cx + 7, site[3] + 1, cz);
		}
		// 5. shallow water (caustics): the nearest column within 48 blocks of the sunset spot whose water is 1-3 blocks deep with
		// water around it (+-2 blocks); 5 blocks above the surface, 4 blocks south of it, looking down at it
		Pose shallow = null;
		int bx = (int) Math.floor(sp.x()), bz = (int) Math.floor(sp.z());
		for (int r = 0; r <= 48 && shallow == null; r += 2) {
			for (int dx = -r; dx <= r && shallow == null; dx += 2) {
				for (int dz = -r; dz <= r && shallow == null; dz += 2) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					int x = bx + dx, z = bz + dz;
					if (shallowDepth(l, x, z, sea) == 0) continue;
					boolean ok = true;
					for (int i = -2; i <= 2 && ok; i += 2) for (int j = -2; j <= 2 && ok; j += 2) ok = shallowDepth(l, x + i, z + j, sea) > 0;
					if (ok) shallow = Pose.lookAt(x + 0.5, sea + 1 + 5, z + 4.5, x + 0.5, sea - 1, z + 0.5);
				}
			}
		}
		return new Poses(ground, water, storm, bolt, glassNoon, glassEvening, site, shallow);
	}

	/** Depth of the water column at x, z when it is 1-3 blocks over a solid floor at sea level, else 0. */
	private static int shallowDepth(ServerLevel l, int x, int z, int sea) {
		if (!l.hasChunk(x >> 4, z >> 4)) return 0;
		int d = 0;
		for (int y = sea; y > sea - 5; y--) {
			BlockPos bp = new BlockPos(x, y, z);
			if (l.getFluidState(bp).getType() == Fluids.WATER) {
				d++;
				continue;
			}
			return d >= 1 && d <= 3 && l.getBlockState(bp).isSolidRender() && l.getBlockState(new BlockPos(x, sea + 1, z)).isAir() ? d : 0;
		}
		return 0;
	}

	/** Places the roof (one colour per 1-block column of 6), clearing an earlier one there first. */
	static void build(MinecraftServer s, int[] site) {
		clearGlass(s, site);
		roof = site;
		for (int i = 0; i < 6; i++) {
			BenchDriver.cmd(s, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:%s_stained_glass", site[0] + i, site[2], site[1], site[0] + i, site[2],
				site[1] + 5, GLASS[i]));
		}
		JSON.put("glassRoof", site);
	}

	/** Stained glass back to air in the roof's box (nothing else is touched). */
	private static void clearGlass(MinecraftServer s, int[] site) {
		for (String c : GLASS) {
			BenchDriver.cmd(s, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air replace minecraft:%s_stained_glass", site[0], site[3] + 1, site[1],
				site[0] + 5, site[2] + 2, site[1] + 5, c));
		}
	}

	static void cleanup(MinecraftServer s) {
		if (roof != null) clearGlass(s, roof);
		if (poses != null && poses.glassSite != null) clearGlass(s, poses.glassSite);
		roof = null;
		BenchDriver.cmd(s, "kill @e[type=minecraft:lightning_bolt]");
	}
}
