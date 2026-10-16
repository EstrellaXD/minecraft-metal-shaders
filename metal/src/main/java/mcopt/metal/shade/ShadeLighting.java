// Copyright 2026 mcopt-shade contributors. Apache-2.0.
package mcopt.metal.shade;

/** Optional daylight contrast, independent of the colour/style preset. No extra render passes. */
public final class ShadeLighting {
	public static final String KEY = "mcopt.shade.lighting";

	private ShadeLighting() {
	}

	/** Fade the effect through dawn, weather and night vision; other dimensions and submerged views keep their lighting. */
	static float strength(ShadeFrame f) {
		if (!"natural".equals(Live.s(KEY, "current")) || f.dimension != ShadeFrame.DIM_OVERWORLD || f.underwater
			|| f.fogType != ShadeFrame.FOG_AIR || !Live.b("mcopt.shade.light2", ShadeConfig.LIGHT2)) return 0f;
		float day = clamp((f.sunDir.y - 0.02f) / 0.33f);
		day = day * day * (3f - 2f * day);
		return day * (1f - 0.65f * clamp(f.rain)) * (1f - 0.25f * clamp(f.thunder)) * (1f - clamp(f.nightVision));
	}

	private static float clamp(float v) {
		return Math.max(0f, Math.min(1f, v));
	}
}
