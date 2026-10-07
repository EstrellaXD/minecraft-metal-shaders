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
 * The poses go to results.json "cloudShots".
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
		for (String n : names) {
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
