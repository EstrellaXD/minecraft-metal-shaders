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
 * Opt-in shadow-pass checks ({@code -Dmcbench.shadow=<list>} of {@code tint,glass,far}; off by default, so the standard plan
 * is unchanged), owned by the mcopt-shade M3.3 shadow work (branch m3.3-shadow).
 * <ul>
 * <li>{@code tint}: WeatherShots' 6x6 stained-glass roof is built beside the torch grid and kept for this block; then recorded
 * phases at WeatherShots' ground pose (noon, clear, panning +-25 degrees for {@code -Dmcbench.shadowTintSeconds} (10) s),
 * each with its own system properties (mcopt-shade reads them every frame): {@code shtint_m32} (M3.2: section rectangle),
 * {@code shtint_none} (no coloured shadows: mcopt.shade.dev 16384), {@code shtint_nolookup} (tintDev 1), {@code shtint_nopass}
 * (tintDev 2), {@code shtint_tight} (block rectangle), {@code shtint_lite} (tintDev 8: 512^2 map), {@code shtint_tightlite},
 * {@code shtint_m32b} (M3.2 again: drift), then {@code shtint_time} / {@code shtint_time_t} (M3.2 / tight with the shadow
 * GPU timers on). An unrecorded warm-up phase first.</li>
 * <li>{@code glass}: with the roof in place, frozen ticks, shots {@code sh_glass_<variant>} at WeatherShots' glass pose (noon)
 * and {@code sh_glasseve_<variant>} (11000) for variants m32, tight, lite.</li>
 * <li>{@code far}: shots of the far cascade with mcopt.shade.shadowFarSpread false / true: {@code sh_far_eve_off|on} (overview
 * point at 11500 looking east: long shadows far away, still), {@code sh_far_strafe_off|on} (overview point, noon, strafing
 * right at 8 blocks/s, shot after 1.5 s), {@code sh_far_turn_off|on} (overview point at 11500, turning 30 degrees/s, shot
 * after 1.5 s).</li>
 * </ul>
 * The roof is removed at the end of the block (WeatherShots.cleanup). Poses and the phase map go to results.json "shadowShots".
 */
final class ShadowShots {
	static final String SPEC = System.getProperty("mcbench.shadow", "").trim();
	static final Map<String, Object> JSON = new LinkedHashMap<>();
	static final String SPREAD = "mcopt.shade.shadowFarSpread", TIGHT = "mcopt.shade.tintTight", DEV = "mcopt.shade.tintDev", TIMES = "mcopt.shade.shadowTimes";

	private ShadowShots() {
	}

	static boolean on(String s) {
		if (SPEC.isEmpty() || SPEC.equals("false")) return false;
		if (SPEC.equals("true") || SPEC.equals("all")) return true;
		for (String x : SPEC.split(",")) if (x.trim().equals(s)) return true;
		return false;
	}

	/** Called from BenchDriver.buildPlan after the other shots. */
	static void plan(java.util.Deque<BenchDriver.Step> steps) {
		if (!(on("tint") || on("glass") || on("far"))) return;
		BenchDriver.results.put("shadowShots", JSON);
		if (on("far")) far(steps);
		if (on("tint") || on("glass")) glassBlock(steps);
	}

	// ---------------------------------------------------------------------------------------------- far cascade shots

	private static void far(java.util.Deque<BenchDriver.Step> steps) {
		Supplier<Pose> o = () -> BenchDriver.spot.overview;
		Supplier<Pose> east = () -> {
			Pose p = o.get();
			return new Pose(p.x(), p.y(), p.z(), -90f, 14f);
		};
		for (String v : new String[] {"off", "on"}) {
			Map<String, String> props = Map.of(SPREAD, v.equals("on") ? "true" : "false");
			still(steps, "sh_far_eve_" + v, 11500, east, props);
			moving(steps, "sh_far_strafe_" + v, 6000, () -> {
				Pose p = o.get();
				return new Pose(p.x(), p.y(), p.z(), p.yaw(), 14f);
			}, 8.0, 0f, props);
			moving(steps, "sh_far_turn_" + v, 11500, east, 0.0, 30f, props);
		}
	}

	/** A still shot with properties set before the settle and restored after the shot. */
	private static void still(java.util.Deque<BenchDriver.Step> steps, String name, int time, Supplier<Pose> pose, Map<String, String> props) {
		Map<String, String> before = new LinkedHashMap<>();
		steps.add(setProps(name + "-set", props, before));
		steps.add(new BenchDriver.Scene(name, time, false, pose));
		steps.add(new BenchDriver.Settle(name, false, 3, 120));
		steps.add(new BenchDriver.Hold(name, Math.max(BenchDriver.SHOT_WAIT, 2.5)));
		steps.add(new BenchDriver.Shot(name));
		steps.add(restoreProps(name + "-restore", before));
		record(name, time, pose, props, 0, 0);
	}

	/** Settle at pose, hold, then move (blocks/s to the camera's right, degrees/s of yaw) and shoot after 1.5 s. */
	private static void moving(java.util.Deque<BenchDriver.Step> steps, String name, int time, Supplier<Pose> pose, double speed, float yawRate,
		Map<String, String> props) {
		Map<String, String> before = new LinkedHashMap<>();
		steps.add(setProps(name + "-set", props, before));
		steps.add(new BenchDriver.Scene(name, time, false, pose));
		steps.add(new BenchDriver.Settle(name, false, 3, 120));
		steps.add(new BenchDriver.Hold(name, Math.max(BenchDriver.SHOT_WAIT, 2.5)));
		steps.add(new BenchDriver.Step(name + "-move", 0) {
			@Override
			void start(Minecraft mc, long now) {
				Pose p = pose.get();
				long t0 = now;
				double yr = Math.toRadians(p.yaw());
				double rx = -Math.cos(yr), rz = -Math.sin(yr); // the camera's right (yaw 0 looks +z)
				BenchDriver.pose = t -> {
					double s = BenchDriver.secs(t - t0);
					return new Pose(p.x() + rx * speed * s, p.y(), p.z() + rz * speed * s, (float) (p.yaw() + yawRate * s), p.pitch());
				};
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= 1.5;
			}
		});
		steps.add(new BenchDriver.Shot(name));
		steps.add(new BenchDriver.Step(name + "-stop", 0) {
			@Override
			void start(Minecraft mc, long now) {
				Pose last = BenchDriver.pose.apply(now);
				BenchDriver.pose = t -> last;
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return true;
			}
		});
		steps.add(restoreProps(name + "-restore", before));
		record(name, time, pose, props, speed, yawRate);
	}

	private static void record(String name, int time, Supplier<Pose> pose, Map<String, String> props, double speed, float yawRate) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("time", time);
		m.put("props", props);
		if (speed != 0 || yawRate != 0) {
			m.put("rightBlocksPerSecond", speed);
			m.put("yawDegPerSecond", yawRate);
			m.put("shotAfterSeconds", 1.5);
		}
		JSON.put(name, m);
	}

	// ---------------------------------------------------------------------------------------------- the glass roof

	private static void glassBlock(java.util.Deque<BenchDriver.Step> steps) {
		// the torch grid's area must be loaded on the server to read the ground (and place the glass)
		steps.add(new BenchDriver.Scene("sh-site", 6000, false, () -> BenchDriver.spot.night));
		steps.add(new BenchDriver.Settle("sh-site", false, 3, 120));
		steps.add(new BenchDriver.Step("sh-find", 60) {
			CompletableFuture<WeatherShots.Poses> f;

			@Override
			void start(Minecraft mc, long now) {
				MinecraftServer s = BenchDriver.server(mc);
				f = CompletableFuture.supplyAsync(() -> WeatherShots.find(s, s.overworld()), s);
			}

			@Override
			boolean poll(Minecraft mc, long now) throws Exception {
				if (!f.isDone()) return false;
				WeatherShots.Poses p = f.get();
				WeatherShots.poses = p;
				BenchDriver.log("shadow: weather poses " + p);
				List<BenchDriver.Step> extra = new ArrayList<>();
				if (p.glassSite() == null || p.ground() == null) {
					BenchDriver.warnings.add("shadow: no glass site / ground pose found, tint phases skipped");
					return true;
				}
				extra.add(new BenchDriver.Step("sh-glass-build", 0) {
					@Override
					void start(Minecraft mc2, long now2) {
						WeatherShots.build(BenchDriver.server(mc2), p.glassSite());
					}

					@Override
					boolean poll(Minecraft mc2, long now2) {
						return BenchDriver.secs(now2 - BenchDriver.curStart) >= 1.0;
					}
				});
				if (on("tint")) tint(extra, p.ground());
				if (on("glass") && p.glassNoon() != null) {
					glassShots(extra, "sh_glass", 6000, p.glassNoon());
					if (p.glassEvening() != null) glassShots(extra, "sh_glasseve", 11000, p.glassEvening());
				}
				extra.add(new BenchDriver.Step("sh-glass-cleanup", 0) {
					@Override
					void start(Minecraft mc2, long now2) {
						WeatherShots.cleanup(BenchDriver.server(mc2));
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

	private static final String[][] TINT_PHASES = {
		{"shtint_m32", TIGHT + "=false"},
		{"shtint_none", TIGHT + "=false", "mcopt.shade.dev=16384"},
		{"shtint_nolookup", TIGHT + "=false", DEV + "=1"},
		{"shtint_nopass", TIGHT + "=false", DEV + "=2"},
		{"shtint_tight", TIGHT + "=true"},
		{"shtint_lite", TIGHT + "=false", DEV + "=8"},
		{"shtint_tightlite", TIGHT + "=true", DEV + "=8"},
		{"shtint_m32b", TIGHT + "=false"},
		{"shtint_time", TIGHT + "=false", TIMES + "=true"},
		{"shtint_time_t", TIGHT + "=true", TIMES + "=true"}};

	private static void tint(List<BenchDriver.Step> extra, Pose ground) {
		double sec = Double.parseDouble(System.getProperty("mcbench.shadowTintSeconds", "10"));
		extra.add(new BenchDriver.Scene("shtint", 6000, false, () -> ground));
		extra.add(new BenchDriver.Settle("shtint", false, 3, 120));
		extra.add(new BenchDriver.Hold("shtint", 1));
		extra.add(new BenchDriver.Warmup(1));
		Map<String, Object> phases = new LinkedHashMap<>();
		pan(extra, "shtint_warm", null, ground, sec, props(TINT_PHASES[0]));
		for (String[] ph : TINT_PHASES) {
			Map<String, String> pr = props(ph);
			pan(extra, ph[0], ph[0], ground, sec, pr);
			phases.put(ph[0], pr);
		}
		Map<String, Object> m = ground.json();
		m.put("seconds", sec);
		m.put("phases", phases);
		JSON.put("tint", m);
	}

	private static Map<String, String> props(String[] ph) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 1; i < ph.length; i++) {
			int e = ph[i].indexOf('=');
			m.put(ph[i].substring(0, e), ph[i].substring(e + 1));
		}
		return m;
	}

	/** One phase: properties set, WeatherShots' +-25 degree pan for sec (recorded as rec unless null), properties restored. */
	private static void pan(List<BenchDriver.Step> extra, String name, String rec, Pose o, double sec, Map<String, String> props) {
		Map<String, String> before = new LinkedHashMap<>();
		extra.add(new BenchDriver.Step(name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				BenchDriver.setPhase(rec == null ? "WARMUP" : name.toUpperCase(Locale.ROOT));
				before.clear();
				for (var e : props.entrySet()) {
					before.put(e.getKey(), System.getProperty(e.getKey()));
					System.setProperty(e.getKey(), e.getValue());
				}
				long t0 = now;
				LongFunction<Pose> f = t -> new Pose(o.x(), o.y(), o.z(), (float) (o.yaw() + 25.0 * Math.sin(BenchDriver.secs(t - t0) * 2 * Math.PI / sec)), o.pitch());
				BenchDriver.pose = f;
				if (rec != null) {
					BenchDriver.recPhase = BenchDriver.REC_PHASES.indexOf(rec);
					if (BenchDriver.recPhase < 0) throw new IllegalStateException("mcbench: " + rec + " is not in BenchDriver.REC_PHASES");
					BenchDriver.phaseRanges.put(rec, new long[] {now, 0});
				}
				BenchDriver.log("shadow phase " + name + " " + props + " " + sec + " s");
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				if (BenchDriver.secs(now - BenchDriver.curStart) < sec) return false;
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

	private static void glassShots(List<BenchDriver.Step> extra, String tag, int time, Pose pose) {
		extra.add(new BenchDriver.Scene(tag, time, false, () -> pose));
		extra.add(new BenchDriver.Settle(tag, false, 3, 120));
		extra.add(new BenchDriver.Hold(tag, Math.max(BenchDriver.SHOT_WAIT, 2.5)));
		String[][] variants = {{"m32", TIGHT + "=false"}, {"tight", TIGHT + "=true"}, {"lite", TIGHT + "=true", DEV + "=8"}};
		Map<String, Object> m = pose.json();
		m.put("time", time);
		for (String[] v : variants) {
			String name = tag + "_" + v[0];
			Map<String, String> pr = props(v);
			Map<String, String> before = new LinkedHashMap<>();
			extra.add(setProps(name + "-set", pr, before));
			extra.add(new BenchDriver.Hold(name, 1.5));
			extra.add(new BenchDriver.Shot(name));
			extra.add(restoreProps(name + "-restore", before));
			m.put(name, pr);
		}
		JSON.put(tag, m);
	}

	// ---------------------------------------------------------------------------------------------- properties

	private static BenchDriver.Step setProps(String name, Map<String, String> props, Map<String, String> before) {
		return new BenchDriver.Step(name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				before.clear();
				for (var e : props.entrySet()) {
					before.put(e.getKey(), System.getProperty(e.getKey()));
					System.setProperty(e.getKey(), e.getValue());
				}
				BenchDriver.log("shadow props " + props);
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return true;
			}
		};
	}

	private static BenchDriver.Step restoreProps(String name, Map<String, String> before) {
		return new BenchDriver.Step(name, 0) {
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
		};
	}
}
