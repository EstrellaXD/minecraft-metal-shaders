/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import mcbench.Spot.Pose;
import net.minecraft.client.Minecraft;

/**
 * Opt-in screenshots taken while the camera moves ({@code -Dmcbench.motionShots=...}; off by default, so the standard plan
 * is unchanged), owned by the temporal anti-aliasing work (mcopt-shade M2, branch m2-taau): ghosting, smearing and
 * shimmer only show in motion. Each entry settles at its start pose, holds still for -Dmcbench.shotWait, then moves at a
 * constant velocity for {@code -Dmcbench.motionSeconds} (1.5) and takes the screenshot while still moving. Entries,
 * separated by ';':
 * <ul>
 * <li>{@code forest}: {@code motion_forest_pan}, the forest close-up turning 30 deg/s (leaves against the sky, shadows).</li>
 * <li>{@code overview}: {@code motion_overview_strafe}, the overview strafing right at 8 blocks/s (parallax, thin edges).</li>
 * <li>{@code sunset}: {@code motion_sunset_pan}, the sunset over water turning 15 deg/s (glint, horizon).</li>
 * <li>{@code name@time@x,y,z,yaw,pitch@vx,vy,vz,yawPerSecond}: a custom one (absolute pose, blocks/s, deg/s).</li>
 * </ul>
 * Each entry's start pose and motion go to results.json "motionShots".
 */
final class MotionShots {
	static final String SPEC = System.getProperty("mcbench.motionShots", "").trim();
	static final double SECONDS = BenchDriver.dbl("mcbench.motionSeconds", 1.5);

	private MotionShots() {
	}

	/** Called from BenchDriver.buildPlan after the other shots. */
	static void plan(java.util.Deque<BenchDriver.Step> steps) {
		if (SPEC.isEmpty()) return;
		Map<String, Object> json = new LinkedHashMap<>();
		BenchDriver.results.put("motionShots", json);
		for (String one : SPEC.split(";")) {
			String e = one.trim();
			if (e.isEmpty()) continue;
			switch (e) {
				case "forest" -> add(steps, json, "motion_forest_pan", 6000, () -> BenchDriver.spot.forest, 0, 0, 0, 30);
				case "overview" -> add(steps, json, "motion_overview_strafe", 6000, () -> BenchDriver.spot.overview, Double.NaN, 0, 8, 0);
				case "sunset" -> add(steps, json, "motion_sunset_pan", 12400, () -> BenchDriver.spot.sunset, 0, 0, 0, 15);
				default -> {
					String[] f = e.split("@");
					if (f.length < 4) throw new IllegalArgumentException("mcbench.motionShots: bad entry " + e);
					String[] c = f[2].split(","), v = f[3].split(",");
					Pose p = new Pose(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]), Float.parseFloat(c[3]), Float.parseFloat(c[4]));
					add(steps, json, f[0], Integer.parseInt(f[1]), () -> p, Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]),
						Double.parseDouble(v[3]));
				}
			}
		}
	}

	/**
	 * vx NaN: strafe sideways (to the camera's right) at vz blocks/s instead of moving along world axes.
	 */
	private static void add(java.util.Deque<BenchDriver.Step> steps, Map<String, Object> json, String name, int time, Supplier<Pose> start, double vx, double vy,
		double vz, double yawRate) {
		steps.add(new BenchDriver.Scene(name, time, false, start));
		steps.add(new BenchDriver.Settle(name, false, 3, 120));
		steps.add(new BenchDriver.Hold(name, BenchDriver.SHOT_WAIT));
		steps.add(new BenchDriver.Step("move-" + name, 0) {
			@Override
			void start(Minecraft mc, long now) {
				Pose o = start.get();
				double ux, uz;
				if (Double.isNaN(vx)) { // the camera's right: yaw 0 looks toward +z, so right is -x
					double yaw = Math.toRadians(o.yaw());
					ux = -Math.cos(yaw) * vz;
					uz = -Math.sin(yaw) * vz;
				} else {
					ux = vx;
					uz = vz;
				}
				double mx = ux, mz = uz;
				long t0 = now;
				BenchDriver.pose = t -> {
					double s = BenchDriver.secs(t - t0);
					return new Pose(o.x() + mx * s, o.y() + vy * s, o.z() + mz * s, (float) (o.yaw() + yawRate * s), o.pitch());
				};
				Map<String, Object> m = o.json();
				m.put("time", time);
				m.put("velocity", new double[] {mx, vy, mz});
				m.put("yawPerSecond", yawRate);
				m.put("seconds", SECONDS);
				json.put(name, m);
				BenchDriver.log("motion " + name + " from " + o + " v=(" + mx + ", " + vy + ", " + mz + ") yaw " + yawRate + "/s");
			}

			@Override
			boolean poll(Minecraft mc, long now) {
				return BenchDriver.secs(now - BenchDriver.curStart) >= SECONDS;
			}
		});
		steps.add(new BenchDriver.Shot(name));
	}
}
