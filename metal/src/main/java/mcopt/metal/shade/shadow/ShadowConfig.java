// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow;

/**
 * The sun shadow pass's switches. All are system properties, so they can also be set in config/mcopt.properties (Profile
 * applies those before any mixin plugin or class here reads them). Nothing here does anything unless mcopt.shade=true.
 * <ul>
 * <li>{@code mcopt.shade} (false): the native shading pipeline as a whole.</li>
 * <li>{@code mcopt.shade.shadows} (true): the sun shadow map.</li>
 * <li>{@code mcopt.shade.shadowRes} (2048): texels per side of each cascade (512-8192).</li>
 * <li>{@code mcopt.shade.shadowDist} (128): half-width in blocks of the far cascade, centered on the camera.</li>
 * <li>{@code mcopt.shade.shadowNear} (shadowDist / 4): half-width in blocks of the near cascade, which is redrawn every frame
 * and pushed 40% of its half-width ahead of the camera.</li>
 * <li>{@code mcopt.shade.shadowFarEvery} (4): the far cascade is redrawn every N frames, or sooner when the light direction
 * (quantized to {@code mcopt.shade.shadowSunStep} degrees, default 0.1) changes or the camera moved an eighth of the
 * cascade.</li>
 * <li>{@code mcopt.shade.shadowCull} (camera): which sections cast. {@code camera}: Sodium's render list for the camera this
 * frame (cheap, proven; misses casters outside the view, e.g. behind the player). {@code light}: every loaded section
 * inside the cascade's light-space box (a read-only scan of Sodium's regions, independent of the camera's occlusion
 * culling and of mcopt's list mixins).</li>
 * <li>{@code mcopt.shade.shadowFaces} (sun): {@code sun} draws only the face directions that face the light (plus
 * unassigned quads), using Sodium's per-facing vertex ranges; {@code all} draws every face.</li>
 * <li>{@code mcopt.shade.shadowBias} (1.0): slope-scaled raster bias (per unit of depth slope, pushes casters away).</li>
 * <li>{@code mcopt.shade.shadowBiasTexels} (1.0): constant raster bias, in shadow texels of world distance along the light.</li>
 * <li>{@code mcopt.shade.debug} (0): 1 overlays both cascades in the top-right corner of the screen.</li>
 * </ul>
 */
public final class ShadowConfig {
	private ShadowConfig() {
	}

	public static final boolean SHADE = Boolean.getBoolean("mcopt.shade");
	public static final boolean SHADOWS = SHADE && !"false".equals(System.getProperty("mcopt.shade.shadows"));
	public static final int RES = clamp(Integer.getInteger("mcopt.shade.shadowRes", 2048), 512, 8192);
	public static final float DIST = clamp(floatProp("mcopt.shade.shadowDist", 128f), 16f, 1024f);
	public static final float NEAR = clamp(floatProp("mcopt.shade.shadowNear", DIST / 4f), 4f, DIST);
	public static final int FAR_EVERY = clamp(Integer.getInteger("mcopt.shade.shadowFarEvery", 4), 1, 64);
	public static final double SUN_STEP_DEGREES = clamp(floatProp("mcopt.shade.shadowSunStep", 0.1f), 0f, 5f);
	public static final boolean CULL_LIGHT = "light".equals(System.getProperty("mcopt.shade.shadowCull", "camera"));
	public static final boolean ALL_FACES = "all".equals(System.getProperty("mcopt.shade.shadowFaces", "sun"));
	public static final float BIAS_SLOPE = floatProp("mcopt.shade.shadowBias", 1.0f);
	public static final float BIAS_TEXELS = floatProp("mcopt.shade.shadowBiasTexels", 1.0f);
	public static final int DEBUG = Integer.getInteger("mcopt.shade.debug", 0);

	private static float floatProp(String key, float fallback) {
		String v = System.getProperty(key);
		if (v == null) return fallback;
		try {
			return Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			System.out.println("[mcopt] shade: bad " + key + "=" + v + ", using " + fallback);
			return fallback;
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
