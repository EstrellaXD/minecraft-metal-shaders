/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mcbench.Spot.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Opt-in sky / cloud screenshots ({@code -Dmcbench.clouds=all} or a comma list of the names below without the
 * {@code clouds_} prefix; off by default, so the standard plan is unchanged), owned by the volumetric clouds work
 * (mcopt-shade M3, branch m3-clouds). Fixed poses at the bench spot's overview point (no world search), shot like
 * extraShots after the standard shots; names are prefixed {@code clouds_}:
 * <ul>
 * <li>{@code noon_south} / {@code noon_north}: noon, looking toward / away from the (tilted) sun, 15 degrees up.</li>
 * <li>{@code up}: noon, 70 degrees up.</li>
 * <li>{@code morning_east}: time 1000, toward the low morning sun.</li>
 * <li>{@code sunset_west} / {@code sunset_east}: time 12400, toward / away from the setting sun (silver linings, warm
 * sides).</li>
 * <li>{@code dusk}: time 13000, toward the afterglow.</li>
 * <li>{@code night}: time 18000, 25 degrees up (moonlit clouds over the stars).</li>
 * <li>{@code rain} / {@code thunder}: noon in rain / a thunderstorm (overcast, dark storm bases).</li>
 * <li>{@code above}: noon, 380 high looking out over the cloud tops (clouds in front of the world).</li>
 * <li>{@code inside}: noon, at 250, inside the cumulus layer.</li>
 * <li>{@code water}: time 11800, the standard sunset spot above water, looking down at the reflected clouds.</li>
 * </ul>
 * The poses go to results.json "cloudShots". {@code perf} (only by name) adds the recorded A/B phases clouds / clouds_off /
 * clouds2 before the shots (see {@link #perf}).
 * <p>
 * M3.3 (comb study, only by name, not part of {@code all}): {@code turn_south} (noon, the noon_south view reached by turning
 * at 30 degrees per second for 1.5 s, shot mid-turn) and {@code turn_sunset} (the same toward sunset_west).
 * {@code -Dmcbench.cloudsVariants=<suffix>:<key=value>[|<key=value>...][;<suffix2>:...]} shoots every listed scene once
 * per variant as {@code clouds_<scene>_<suffix>}, with those system properties set from the scene's start until its shot
 * (then restored), e.g. {@code old:mcopt.shade.clouds.stable=false;new:mcopt.shade.clouds.stable=true}.
 * {@code -Dmcbench.clouds2=<scenes>} with {@code -Dmcbench.cloudsVariants2=<variants>}: a second list, shot after the first
 * (e.g. still look shots with {@code mcopt.shade.clouds.freeze=true} next to turning shots in the wind; use other suffixes).
 */
final class CloudShots {
	static final String SPEC = System.getProperty("mcbench.clouds", "").trim();
	static final List<String> ALL = List.of("noon_south", "noon_north", "up", "morning_east", "sunset_west", "sunset_east", "dusk", "night", "rain",
		"thunder", "above", "inside", "water");

	private CloudShots() {
	}

	/** Called from BenchDriver.buildPlan after the standard shots. */
	static void plan(java.util.Deque<BenchDriver.Step> steps) {
		Map<String, Object> json = new LinkedHashMap<>();
		if (!SPEC.isEmpty() && !SPEC.equals("false")) plan(steps, SPEC, "mcbench.cloudsVariants", "cloudsVariants", json);
		// M3.3: a second scene list with its own variants (-Dmcbench.clouds2 / -Dmcbench.cloudsVariants2), shot after the first
		// (e.g. still look shots with the clouds frozen next to turning shots in the wind)
		String spec2 = System.getProperty("mcbench.clouds2", "").trim();
		if (!spec2.isEmpty() && !spec2.equals("false")) plan(steps, spec2, "mcbench.cloudsVariants2", "cloudsVariants2", json);
		if (!json.isEmpty()) BenchDriver.results.put("cloudShots", json);
	}

	@SuppressWarnings("unchecked")
	private static void plan(java.util.Deque<BenchDriver.Step> steps, String spec, String variantsKey, String variantsJson, Map<String, Object> json) {
		List<String> names = spec.equals("all") || spec.equals("true") ? ALL : Arrays.stream(spec.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
		if (names.contains("perf")) perf(steps);
		// M3.3: -Dmcbench.cloudsVariants (suffix -> properties); none = one shot per scene, as before
		Map<String, Map<String, String>> variants = new LinkedHashMap<>();
		String vSpec = System.getProperty(variantsKey, "").trim();
		for (String v : vSpec.isEmpty() ? new String[0] : vSpec.split(";")) {
			if (v.isBlank()) continue;
			int c = v.indexOf(':');
			Map<String, String> props = new LinkedHashMap<>();
			for (String kv : v.substring(c + 1).split("\\|")) {
				int e = kv.indexOf('=');
				if (e > 0) props.put(kv.substring(0, e).trim(), kv.substring(e + 1).trim());
			}
			variants.put(v.substring(0, c).trim(), props);
		}
		if (variants.isEmpty()) variants.put("", Map.of());
		else BenchDriver.results.put(variantsJson, variants);
		for (String n : names) {
			if (n.equals("perf")) continue;
			boolean turn = TURNS.contains(n);
			if (!ALL.contains(n) && !turn) throw new IllegalArgumentException("mcbench.clouds: unknown scene " + n + " (known: " + ALL + ", " + TURNS + ")");
			Object[] def = scene(turn ? (n.equals("turn_south") ? "noon_south" : "sunset_west") : n);
			int time = (Integer) def[0];
			String weather = (String) def[1];
			java.util.function.Supplier<Pose> end = (java.util.function.Supplier<Pose>) def[2];
			// a turn ends at the scene's view: it starts 45 degrees before it and turns at 30 degrees per second
			java.util.function.Supplier<Pose> pose = turn ? () -> {
				Pose e = end.get();
				return new Pose(e.x(), e.y(), e.z(), e.yaw() - 45f, e.pitch());
			} : end;
			for (Map.Entry<String, Map<String, String>> var : variants.entrySet()) {
				String name = "clouds_" + n + (var.getKey().isEmpty() ? "" : "_" + var.getKey());
				Map<String, String> props = var.getValue();
				Map<String, String> saved = new LinkedHashMap<>();
				boolean rain = !weather.equals("clear");
				steps.add(new WeatherScene(name, time, weather, pose) {
					@Override
					void start(Minecraft mc, long now) {
						for (Map.Entry<String, String> e : props.entrySet()) {
							saved.put(e.getKey(), System.getProperty(e.getKey()));
							System.setProperty(e.getKey(), e.getValue());
						}
						if (!props.isEmpty()) BenchDriver.log("cloud variant " + name + " " + props);
						super.start(mc, now);
					}
				});
				steps.add(new BenchDriver.Settle(name, rain, 3, 120));
				steps.add(new BenchDriver.Hold(name, rain ? Math.max(BenchDriver.SHOT_WAIT, 5) : Math.max(BenchDriver.SHOT_WAIT, 3)));
				if (turn) {
					steps.add(new BenchDriver.Step("turn-" + name, 0) {
						@Override
						void start(Minecraft mc, long now) {
							Pose o = pose.get();
							long t0 = now;
							BenchDriver.pose = t -> new Pose(o.x(), o.y(), o.z(), (float) (o.yaw() + 30.0 * BenchDriver.secs(t - t0)), o.pitch());
							BenchDriver.log("cloud turn " + name + " from " + o + " at 30 deg/s");
						}

						@Override
						boolean poll(Minecraft mc, long now) {
							return BenchDriver.secs(now - BenchDriver.curStart) >= 1.5;
						}
					});
				}
				steps.add(new BenchDriver.Shot(name));
				if (!props.isEmpty()) {
					steps.add(new BenchDriver.Step("restore-" + name, 0) {
						@Override
						boolean poll(Minecraft mc, long now) {
							for (Map.Entry<String, String> e : saved.entrySet()) {
								if (e.getValue() == null) System.clearProperty(e.getKey());
								else System.setProperty(e.getKey(), e.getValue());
							}
							return true;
						}
					});
				}
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("time", time);
				m.put("weather", weather);
				if (turn) m.put("turnDegPerSecond", 30);
				if (!props.isEmpty()) m.put("properties", props);
				json.put(name, m);
			}
		}
	}

	/** M3.3 turning shots (only by name). */
	static final List<String> TURNS = List.of("turn_south", "turn_sunset");

	/**
	 * Perf phases "clouds", "clouds_off", "clouds2" (opt-in name {@code perf}, not part of {@code all}): noon, clear, at the
	 * overview point turning a full circle in {@code -Dmcbench.cloudsSeconds} (12) s, 8 degrees up (about half the screen is
	 * sky), with the volumetric clouds paused in the middle phase (system property mcopt.shade.clouds.pause, read by
	 * mcopt-shade every frame: vanilla's clouds as with mcopt.shade.clouds=false): the clouds' whole cost in one session,
	 * bracketed so drift shows (results.json perf.* / gpu.*).
	 * <p>
	 * {@code -Dmcbench.cloudsDev=<bits>,<bits>,...} (M3.1 price breakdown, up to 8): after "clouds", one more phase per entry,
	 * "clouds_d1", "clouds_d2"..., each with the system property mcopt.shade.clouds.dev set to its bits (read by mcopt-shade
	 * every frame: passes or consumers switched off, or an optimisation switched back to the old way; see CLOUDS.md "M3.1
	 * performance"); an entry "off" pauses the clouds like clouds_off (several on/off pairs against drift); the mapping goes
	 * to results.json "cloudsDev".
	 */
	private static void perf(java.util.Deque<BenchDriver.Step> steps) {
		double sec = Double.parseDouble(System.getProperty("mcbench.cloudsSeconds", "12"));
		java.util.function.Supplier<Pose> o = () -> BenchDriver.spot.overview;
		steps.add(new WeatherScene("clouds_perf", 6000, "clear", at(o, 0, 0f, -8f)));
		steps.add(new BenchDriver.Settle("clouds_perf", false, 3, 120));
		steps.add(new BenchDriver.Hold("clouds_perf", 3));
		List<String> phases = new java.util.ArrayList<>(List.of("clouds"));
		Map<String, String> devs = new LinkedHashMap<>();
		String devSpec = System.getProperty("mcbench.cloudsDev", "").trim();
		if (!devSpec.isEmpty()) {
			String[] bits = devSpec.split(",");
			for (int i = 0; i < bits.length && i < 8; i++) {
				String name = "clouds_d" + (i + 1);
				String b = bits[i].trim();
				devs.put(name, b.equals("off") ? "off" : Integer.toString(Integer.parseInt(b)));
				phases.add(name);
			}
			BenchDriver.results.put("cloudsDev", devs);
		}
		phases.add("clouds_off");
		phases.add("clouds2");
		for (String phase : phases) {
			steps.add(new BenchDriver.Step(phase, 0) {
				@Override
				void start(Minecraft mc, long now) {
					BenchDriver.setPhase(phase.toUpperCase(java.util.Locale.ROOT));
					String dev = devs.getOrDefault(phase, "0");
					System.setProperty("mcopt.shade.clouds.pause", Boolean.toString(phase.equals("clouds_off") || dev.equals("off")));
					System.setProperty("mcopt.shade.clouds.dev", dev.equals("off") ? "0" : dev);
					Pose p = o.get();
					long t0 = now;
					BenchDriver.pose = t -> new Pose(p.x(), p.y(), p.z(), (float) (360.0 * Math.min(1.0, BenchDriver.secs(t - t0) / sec)), -8f);
					BenchDriver.recPhase = BenchDriver.REC_PHASES.indexOf(phase);
					BenchDriver.phaseRanges.put(phase, new long[] {now, 0});
					BenchDriver.log("perf " + phase + " " + sec + " s");
				}

				@Override
				boolean poll(Minecraft mc, long now) {
					if (BenchDriver.secs(now - BenchDriver.curStart) < sec) return false;
					BenchDriver.recPhase = -1;
					BenchDriver.phaseRanges.get(phase)[1] = now;
					System.setProperty("mcopt.shade.clouds.pause", "false");
					System.setProperty("mcopt.shade.clouds.dev", "0");
					return true;
				}
			});
		}
	}

	/** {time, weather (clear | rain | thunder), pose supplier} for a scene name. */
	private static Object[] scene(String n) {
		java.util.function.Supplier<Pose> o = () -> BenchDriver.spot.overview;
		return switch (n) {
			case "noon_south" -> new Object[] {6000, "clear", at(o, 0, 0f, -15f)};
			case "noon_north" -> new Object[] {6000, "clear", at(o, 0, 180f, -15f)};
			case "up" -> new Object[] {6000, "clear", at(o, 0, 30f, -70f)};
			case "morning_east" -> new Object[] {1000, "clear", at(o, 0, -90f, -8f)};
			case "sunset_west" -> new Object[] {12400, "clear", at(o, 0, 90f, -8f)};
			case "sunset_east" -> new Object[] {12400, "clear", at(o, 0, -90f, -12f)};
			case "dusk" -> new Object[] {13000, "clear", at(o, 0, 90f, -6f)};
			case "night" -> new Object[] {18000, "clear", at(o, 0, 0f, -25f)};
			case "rain" -> new Object[] {6000, "rain", at(o, 0, 0f, -12f)};
			case "thunder" -> new Object[] {6000, "thunder", at(o, 0, 0f, -12f)};
			case "above" -> new Object[] {6000, "clear", absY(o, 380, 0f, 12f)};
			case "inside" -> new Object[] {6000, "clear", absY(o, 250, 0f, 0f)};
			case "water" -> new Object[] {11800, "clear", (java.util.function.Supplier<Pose>) () -> {
				Pose s = BenchDriver.spot.sunset;
				return new Pose(s.x(), s.y() + 6, s.z(), s.yaw() + 25f, 22f);
			}};
			default -> throw new IllegalArgumentException(n);
		};
	}

	private static java.util.function.Supplier<Pose> at(java.util.function.Supplier<Pose> base, double dy, float yaw, float pitch) {
		return () -> {
			Pose p = base.get();
			return new Pose(p.x(), p.y() + dy, p.z(), yaw, pitch);
		};
	}

	private static java.util.function.Supplier<Pose> absY(java.util.function.Supplier<Pose> base, double y, float yaw, float pitch) {
		return () -> {
			Pose p = base.get();
			return new Pose(p.x(), y, p.z(), yaw, pitch);
		};
	}

	/** Like BenchDriver.Scene, with "weather thunder" too. */
	static class WeatherScene extends BenchDriver.Step {
		final int time;
		final String weather;
		final java.util.function.Supplier<Pose> p;

		WeatherScene(String name, int time, String weather, java.util.function.Supplier<Pose> p) {
			super(name, 0);
			this.time = time;
			this.weather = weather;
			this.p = p;
		}

		@Override
		void start(Minecraft mc, long now) {
			BenchDriver.setPhase("SETTLE");
			mcopt.bench.Bench.settledAt = 0;
			MinecraftServer s = BenchDriver.server(mc);
			BenchDriver.cmd(s, "time set " + this.time);
			BenchDriver.cmd(s, "weather " + this.weather);
			BenchDriver.hideHud(mc);
			BenchDriver.setStaticPose(s, this.p.get());
			BenchDriver.log("cloud scene " + this.name + " @" + this.time + " " + this.weather + " pose " + this.p.get());
		}

		@Override
		boolean poll(Minecraft mc, long now) {
			return true;
		}
	}
}
