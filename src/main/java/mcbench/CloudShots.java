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
 */
final class CloudShots {
	static final String SPEC = System.getProperty("mcbench.clouds", "").trim();
	static final List<String> ALL = List.of("noon_south", "noon_north", "up", "morning_east", "sunset_west", "sunset_east", "dusk", "night", "rain",
		"thunder", "above", "inside", "water");

	private CloudShots() {
	}

	/** Called from BenchDriver.buildPlan after the standard shots. */
	@SuppressWarnings("unchecked")
	static void plan(java.util.Deque<BenchDriver.Step> steps) {
		if (SPEC.isEmpty() || SPEC.equals("false")) return;
		List<String> names = SPEC.equals("all") || SPEC.equals("true") ? ALL : Arrays.stream(SPEC.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
		Map<String, Object> json = new LinkedHashMap<>();
		if (names.contains("perf")) perf(steps);
		for (String n : names) {
			if (n.equals("perf")) continue;
			if (!ALL.contains(n)) throw new IllegalArgumentException("mcbench.clouds: unknown scene " + n + " (known: " + ALL + ")");
			Object[] def = scene(n);
			int time = (Integer) def[0];
			String weather = (String) def[1];
			java.util.function.Supplier<Pose> pose = (java.util.function.Supplier<Pose>) def[2];
			String name = "clouds_" + n;
			boolean rain = !weather.equals("clear");
			steps.add(new WeatherScene(name, time, weather, pose));
			steps.add(new BenchDriver.Settle(name, rain, 3, 120));
			steps.add(new BenchDriver.Hold(name, rain ? Math.max(BenchDriver.SHOT_WAIT, 5) : Math.max(BenchDriver.SHOT_WAIT, 3)));
			steps.add(new BenchDriver.Shot(name));
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("time", time);
			m.put("weather", weather);
			json.put(name, m);
		}
		BenchDriver.results.put("cloudShots", json);
	}

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
	 * performance"); the mapping goes to results.json "cloudsDev".
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
				devs.put(name, Integer.toString(Integer.parseInt(bits[i].trim())));
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
					System.setProperty("mcopt.shade.clouds.pause", Boolean.toString(phase.equals("clouds_off")));
					System.setProperty("mcopt.shade.clouds.dev", devs.getOrDefault(phase, "0"));
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
	static final class WeatherScene extends BenchDriver.Step {
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
