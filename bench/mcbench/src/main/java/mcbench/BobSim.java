/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench;

import java.util.Locale;

/**
 * {@code -Dmcbench.bob} (read every frame, so a variantShots / ab phase can set it): view bobbing as if the player walked on
 * the ground, through vanilla's own GameRenderer.bobView (CameraBobMixin, GameRendererBobMixin). The bench's spectator camera
 * never bobs by itself (the bob decays to 0 off the ground), and the dev instances have the option off; players walking
 * with it on (the default) saw what the bench never did (mcopt-shade 2026-10-08: the sky turning while walking).
 * <ul>
 * <li>{@code <amplitude>}: the walk cycle animated at walking pace (walk distance 2.6 per second = 4.3 blocks/s x 0.6, wall
 * time); the amplitude is the player's bob, 0.1 when walking or sprinting.</li>
 * <li>{@code <amplitude>@<walk distance>}: one phase of it held (0 = the camera 0.1 block down, the strongest tilt;
 * 0.5 = 0.05 block to the side, the strongest roll).</li>
 * <li>{@code -} or unset: vanilla.</li>
 * </ul>
 * While it is set the bobView option counts as on.
 */
public final class BobSim {
	/** Walk distance per second while walking (Entity.walkDist grows by 0.6 per block; walking speed 4.317 blocks/s). */
	private static final float WALK_RATE = 2.6f;
	private static final long T0 = System.nanoTime();
	private static String spec;
	private static boolean valid, animate;
	private static float amplitude, walk;

	private BobSim() {
	}

	/** Whether the override is on now (the property is set to something other than "-"). */
	public static boolean active() {
		String s = System.getProperty("mcbench.bob");
		if (s == null || s.isBlank() || s.trim().equals("-")) return false;
		if (!s.equals(spec)) parse(s);
		return valid;
	}

	/** The bob this frame (call after {@link #active()}). */
	public static float amplitude() {
		return amplitude;
	}

	/** The walk distance this frame (call after {@link #active()}). */
	public static float walkDistance() {
		return animate ? (System.nanoTime() - T0) / 1e9f * WALK_RATE % 2000f : walk;
	}

	private static void parse(String s) {
		spec = s;
		String t = s.trim();
		int at = t.indexOf('@');
		try {
			amplitude = Float.parseFloat(at < 0 ? t : t.substring(0, at));
			animate = at < 0;
			walk = at < 0 ? 0f : Float.parseFloat(t.substring(at + 1));
			valid = true;
			BenchDriver.log(String.format(Locale.ROOT, "bob: %.3f %s", amplitude, animate ? "walking" : "at walk distance " + walk));
		} catch (NumberFormatException e) {
			valid = false;
			BenchDriver.log("bob: bad -Dmcbench.bob=" + s + " (want <amplitude> or <amplitude>@<walk distance>), ignored");
		}
	}
}
