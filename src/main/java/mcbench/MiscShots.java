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
import java.util.function.LongFunction;
import java.util.function.Supplier;
import mcbench.Spot.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Generic in-session A/B tools (opt-in, off by default so the standard plan is unchanged), owned by the mcopt-shade M3.3
 * misc work (branch m3.3-misc: sunset god rays, dynamic resolution, rain budget).
 * <ul>
 * <li>{@code -Dmcbench.ab=<scene>@<seconds>@<phase>;<phase>;...} (several groups separated by {@code /}): recorded perf
 * phases {@code ab1}, {@code ab2}, ... (results.json perf.abN / gpu.abN; the mapping in "ab") at one scene, each with its own
 * system properties (mcopt-shade reads some every frame), set at the phase's start and restored after it. A phase is
 * {@code <label>} or {@code <label>:<key>=<value>,<key>=<value>} (a value {@code -} clears the property). Each group starts
 * with the scene's settle and an unrecorded warm-up phase of the same length with the first phase's properties. Scenes:
 * {@code spin} (the perf route's spin: overview point, 360 degrees in the phase, 10 degrees down), {@code route} (that spin,
 * then the perf route's flight, each for the phase's length: one phase of twice the seconds), {@code wxrain} / {@code wxdry}
 * (WeatherShots' ground pose panning +-25 degrees, noon, in rain / clear).</li>
 * <li>{@code -Dmcbench.variantShots=<tag>@<time>@<pose>@<weather>@<variant>;<variant>...} (groups separated by {@code /}):
 * at one pose and time of day, after the settle, the ticks are frozen and each variant (same syntax as an ab phase) is set,
 * held {@code -Dmcbench.variantHold} (2.5) s and shot as {@code <tag>_<label>}; the ticks are unfrozen afterwards. Poses:
 * {@code rose_sky} (the sunset_water pose 25 degrees up), {@code rose_water} (the sunset_water pose), {@code sunset_west}
 * (the overview point toward the setting sun, 8 degrees up), {@code wxground} (WeatherShots' ground pose) or
 * {@code x,y,z,yaw,pitch}. Weather: clear, rain or thunder. {@code -Dmcbench.variantPair=<seconds>}: a second shot
 * {@code <shot>_b} that long after each (frame-to-frame noise at a still pose).</li>
 * </ul>
 */
final class MiscShots {
	static final String AB = System.getProperty("mcbench.ab", "").trim();
	static final String VARIANTS = System.getProperty("mcbench.variantShots", "").trim();
	static final Map<String, Object> AB_JSON = new LinkedHashMap<>();
	static final Map<String, Object> VAR_JSON = new LinkedHashMap<>();
	/** -Dmcbench.variantPair=<seconds> (0 = off): each variant shot gets a second one, {@code <shot>_b}, this long after it. */
	static final double PAIR = Double.parseDouble(System.getProperty("mcbench.variantPair", "0"));
	/** How many ab phases BenchDriver.REC_PHASES has room for (ab1..abN). */
	static final int MAX_AB = 24;
	static int abCount;

	private MiscShots() {
	}

	record Phase(String label, Map<String, String> props) {
		static Phase parse(String spec) {
			String s = spec.trim();
			int c = s.indexOf(':');
			Map<String, String> props = new LinkedHashMap<>();
			if (c >= 0) {
				for (String kv : s.substring(c + 1).split(",")) {
					if (kv.isBlank()) continue;
					int e = kv.indexOf('=');
					if (e <= 0) throw new IllegalArgumentException("mcbench: bad property " + kv + " in " + spec);
					props.put(kv.substring(0, e).trim(), kv.substring(e + 1).trim());
				}
			}
			return new Phase(c >= 0 ? s.substring(0, c) : s, props);
		}

		String describe() {
			return this.props.isEmpty() ? this.label : this.label + ": " + this.props;
		}
	}

	/** Called from BenchDriver.buildPlan after the other shots. */
	static void plan(java.util.Deque<BenchDriver.Step> steps) {
		if (!AB.isEmpty()) {
			BenchDriver.results.put("ab", AB_JSON);
			for (String group : AB.split("/")) if (!group.isBlank()) ab(steps, group.trim());
		}
		if (!VARIANTS.isEmpty()) {
			BenchDriver.results.put("variantShots", VAR_JSON);
			for (String group : VARIANTS.split("/")) if (!group.isBlank()) variants(steps, group.trim());
		}
	}

	// ---------------------------------------------------------------------------------------------- weather poses

	/** WeatherShots' poses (found by it when its own shots ran, else here). */
	private static void needWeatherPoses(java.util.Deque<BenchDriver.Step> steps) {
		steps.add(new BenchDriver.Scene("misc-wx-site", 6000, false, () -> BenchDriver.spot.night));
		steps.add(new BenchDriver.Settle("misc-wx-site", false, 3, 120));
		steps.add(new BenchDriver.Step("misc-wx-find", 60) {
			CompletableFuture<WeatherShots.Poses> f;

			@Override
			void start(Minecraft mc, long now) {
				if (WeatherShots.poses != null) return;
				MinecraftServer s = BenchDriver.server(mc);
				f = CompletableFuture.supplyAsync(() -> WeatherShots.find(s, s.overworld()), s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (WeatherShots.poses != null) return true;
				if (!f.isDone()) return false;
				WeatherShots.poses = f.get();
				BenchDriver.log("misc: weather poses " + WeatherShots.poses);
				return true;
			}
		});
	}

	private static Pose ground() {
		WeatherShots.Poses p = WeatherShots.poses;
		if (p == null || p.ground() == null) throw new IllegalStateException("mcbench: no weather ground pose found");
		return p.ground();
	}

	// ---------------------------------------------------------------------------------------------- A/B perf phases

	private static void ab(java.util.Deque<BenchDriver.Step> steps, String group) {
		String[] f = group.split("@", 3);
		if (f.length < 3) throw new IllegalArgumentException("mcbench.ab: bad group " + group + " (scene@seconds@phase;phase...)");
		String scene = f[0].trim();
		double sec = Double.parseDouble(f[1].trim());
		List<Phase> phases = new ArrayList<>();
		for (String p : f[2].split(";")) if (!p.isBlank()) phases.add(Phase.parse(p));
		if (phases.isEmpty()) return;
		boolean rain = scene.equals("wxrain");
		Supplier<Pose> start;
		int time = 6000;
		// (2026-10-09) still=x,y,z,yaw,pitch[,time]: a fixed pose (a player's screenshot spot), the camera held still
		if (scene.startsWith("still=")) {
			String[] v = scene.substring(6).split(",");
			Pose pose = new Pose(Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]), Float.parseFloat(v[3]), Float.parseFloat(v[4]));
			if (v.length > 5) time = Integer.parseInt(v[5].trim());
			STILL.put(scene, pose);
			start = () -> pose;
		} else switch (scene) {
			case "spin", "route" -> start = () -> BenchDriver.spot.overview;
			case "wxrain", "wxdry" -> {
				needWeatherPoses(steps);
				start = MiscShots::ground;
			}
			default -> throw new IllegalArgumentException("mcbench.ab: unknown scene " + scene + " (spin, route, wxrain, wxdry, still=x,y,z,yaw,pitch[,time])");
		}
		steps.add(new BenchDriver.Scene("ab-" + (scene.startsWith("still=") ? "still" : scene), time, rain, start));
		steps.add(new BenchDriver.Settle("ab-" + scene, rain, 3, 150));
		steps.add(new BenchDriver.Hold("ab-" + scene, rain ? 4 : 1));
		steps.add(new BenchDriver.Warmup(1));
		phase(steps, "abwarm", null, scene, sec, phases.get(0));
		for (Phase p : phases) {
			if (abCount >= MAX_AB) throw new IllegalArgumentException("mcbench.ab: more than " + MAX_AB + " phases");
			String name = "ab" + (++abCount);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("scene", scene);
			m.put("seconds", scene.equals("route") ? 2 * sec : sec);
			m.put("label", p.label());
			m.put("props", p.props());
			AB_JSON.put(name, m);
			phase(steps, name, name, scene, sec, p);
		}
	}

	/** One phase: properties set, the scene's motion for its length (recorded as rec unless null), properties restored. */
	private static void phase(java.util.Deque<BenchDriver.Step> steps, String name, String rec, String scene, double sec, Phase p) {
		double len = scene.equals("route") ? 2 * sec : sec;
		Map<String, String> before = new LinkedHashMap<>();
		steps.add(new BenchDriver.Step(name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.setPhase(rec == null ? "WARMUP" : name.toUpperCase(Locale.ROOT));
				before.clear();
				for (var e : p.props().entrySet()) {
					before.put(e.getKey(), System.getProperty(e.getKey()));
					set(e.getKey(), e.getValue());
				}
				long t0 = now;
				BenchDriver.pose = motion(scene, sec, t0);
				if (rec != null) {
					BenchDriver.recPhase = BenchDriver.REC_PHASES.indexOf(rec);
					if (BenchDriver.recPhase < 0) throw new IllegalStateException("mcbench: " + rec + " is not in BenchDriver.REC_PHASES");
					BenchDriver.phaseRanges.put(rec, new long[] {now, 0});
				}
				BenchDriver.log("ab " + name + " (" + p.describe() + ") " + scene + " " + len + " s");
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				if (BenchDriver.secs(now - BenchDriver.curStart) < len) return false;
				if (rec != null) {
					BenchDriver.recPhase = -1;
					BenchDriver.phaseRanges.get(rec)[1] = now;
				}
				for (var e : before.entrySet()) {
					if (e.getValue() == null) System.clearProperty(e.getKey());
					else System.setProperty(e.getKey(), e.getValue());
				}
				Pose last = BenchDriver.pose.apply(now);
				BenchDriver.pose = t -> last;
				return true;
			}
		});
	}

	/** The poses of the still=... A/B scenes. */
	private static final Map<String, Pose> STILL = new java.util.HashMap<>();

	private static LongFunction<Pose> motion(String scene, double sec, long t0) {
		Pose still = STILL.get(scene);
		if (still != null) return t -> still;
		return switch (scene) {
			case "spin" -> {
				Pose o = BenchDriver.spot.overview;
				yield t -> new Pose(o.x(), o.y(), o.z(), (float) (o.yaw() + 360.0 * Math.min(1, BenchDriver.secs(t - t0) / sec)), 10f);
			}
			case "route" -> {
				Pose o = BenchDriver.spot.overview;
				double yaw = Math.toRadians(BenchDriver.spot.flightYaw);
				double dx = -Math.sin(yaw), dz = Math.cos(yaw);
				yield t -> {
					double s = BenchDriver.secs(t - t0);
					if (s < sec) return new Pose(o.x(), o.y(), o.z(), (float) (o.yaw() + 360.0 * s / sec), 10f);
					double d = BenchDriver.SPEED * Math.min(sec, s - sec);
					return new Pose(o.x() + dx * d, o.y(), o.z() + dz * d, (float) BenchDriver.spot.flightYaw, 12f);
				};
			}
			default -> { // wxrain / wxdry: WeatherShots' perf pan
				Pose o = ground();
				yield t -> new Pose(o.x(), o.y(), o.z(), (float) (o.yaw() + 25.0 * Math.sin(BenchDriver.secs(t - t0) * 2 * Math.PI / sec)), o.pitch());
			}
		};
	}

	private static void set(String key, String value) {
		if (value == null || value.equals("-")) System.clearProperty(key);
		else System.setProperty(key, value);
	}

	// ---------------------------------------------------------------------------------------------- variant shots

	private static boolean setupDone;

	private static void variants(java.util.Deque<BenchDriver.Step> steps, String group) {
		String[] f = group.split("@", 5);
		if (f.length < 5) throw new IllegalArgumentException("mcbench.variantShots: bad group " + group + " (tag@time@pose@weather@variant;...)");
		String tag = f[0].trim();
		int time = Integer.parseInt(f[1].trim());
		String poseSpec = f[2].trim();
		String weather = f[3].trim();
		List<Phase> vs = new ArrayList<>();
		for (String p : f[4].split(";")) if (!p.isBlank()) vs.add(Phase.parse(p));
		Supplier<Pose> pose = switch (poseSpec) {
			case "rose_sky" -> () -> {
				Pose o = BenchDriver.spot.sunset;
				return new Pose(o.x(), o.y(), o.z(), o.yaw(), -25f);
			};
			case "rose_water" -> () -> BenchDriver.spot.sunset;
			case "sunset_west" -> () -> {
				Pose o = BenchDriver.spot.overview;
				return new Pose(o.x(), o.y(), o.z(), 90f, -8f);
			};
			case "wxground" -> {
				needWeatherPoses(steps);
				yield MiscShots::ground;
			}
			default -> {
				String[] c = poseSpec.split(",");
				Pose p = new Pose(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]), Float.parseFloat(c[3]), Float.parseFloat(c[4]));
				yield () -> p;
			}
		};
		boolean rain = !weather.equals("clear");
		double hold = Double.parseDouble(System.getProperty("mcbench.variantHold", "2.5"));
		String scene = "var-" + tag;
		steps.add(new CloudShots.WeatherScene(scene, time, weather, pose));
		// -Dmcbench.variantSetup=cmd;;cmd (2026-10-09; '+' = space): server commands run once, at the first group's pose before it settles (e.g.
		// plant a dense test forest around it with execute positioned ... over motion_blocking_no_leaves run place feature)
		if (!setupDone) {
			setupDone = true;
			String setup = System.getProperty("mcbench.variantSetup", "").trim();
			int k = 0;
			// ('+' stands for a space: mcbench-run splits its --jvm string on spaces)
			if (!setup.isEmpty()) for (String c : setup.split(";;")) if (!c.isBlank()) steps.add(command("var-setup-" + (k++), c.trim().replace('+', ' ')));
		}
		steps.add(new BenchDriver.Settle(scene, rain, 3, 120));
		steps.add(new BenchDriver.Hold(scene, rain ? Math.max(BenchDriver.SHOT_WAIT, 5) : Math.max(BenchDriver.SHOT_WAIT, 3)));
		steps.add(command("var-freeze-" + tag, "tick freeze"));
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("time", time);
		json.put("weather", weather);
		json.put("poseSpec", poseSpec);
		for (Phase v : vs) {
			String shot = tag + "_" + v.label();
			Map<String, String> before = new LinkedHashMap<>();
			steps.add(new BenchDriver.Step("var-set-" + shot, 0) {
				@Override
				void start(Minecraft mc, long now) {
					before.clear();
					for (var e : v.props().entrySet()) {
						before.put(e.getKey(), System.getProperty(e.getKey()));
						set(e.getKey(), e.getValue());
					}
					BenchDriver.log("variant " + shot + " (" + v.describe() + ")");
				}

				@Override
				boolean poll(Minecraft mc, long now) {
					return true;
				}
			});
			steps.add(new BenchDriver.Hold(shot, hold));
			steps.add(new BenchDriver.Shot(shot));
			if (PAIR > 0) { // a second shot a moment later: what changed between them at a frozen pose is temporal noise (flicker)
				steps.add(new BenchDriver.Hold(shot + "_b", PAIR));
				steps.add(new BenchDriver.Shot(shot + "_b"));
			}
			steps.add(new BenchDriver.Step("var-restore-" + shot, 0) {
				@Override
				void start(Minecraft mc, long now) {
					for (var e : before.entrySet()) {
						if (e.getValue() == null) System.clearProperty(e.getKey());
						else System.setProperty(e.getKey(), e.getValue());
					}
				}

				@Override
				boolean poll(Minecraft mc, long now) {
					return true;
				}
			});
			json.put(shot, v.props());
		}
		steps.add(command("var-unfreeze-" + tag, "tick unfreeze"));
		steps.add(new BenchDriver.Step("var-record-" + tag, 0) {
			@Override
			boolean poll(Minecraft mc, long now) {
				json.put("pose", pose.get().json());
				VAR_JSON.put(tag, json);
				return true;
			}
		});
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
}
