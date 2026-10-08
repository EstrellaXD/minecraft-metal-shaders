/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade.clouds;

import mcopt.metal.shade.Shade;

/**
 * Volumetric cloud settings (M3, bible E05), read once at startup (system properties or config/mcopt.properties). Free of
 * game types. Everything needs mcopt.shade=true and the HDR pipeline (mcopt.shade.hdr, default on).
 *
 * <ul>
 * <li>{@code mcopt.shade.clouds} (true): volumetric cumulus + cirrus replace vanilla's clouds (false: vanilla's, lit by
 * M2's clouds.fs.metal, exactly as before).</li>
 * <li>{@code mcopt.shade.clouds.scale}: history resolution relative to the output (P 0.4, B 0.45, Q 0.5, U 0.67).</li>
 * <li>{@code mcopt.shade.clouds.checker} (P 3, B 3, Q 2, U 2): march one pixel of each N x N block per frame, the rest
 * from the reprojected history (1 = every pixel every frame).</li>
 * <li>{@code mcopt.shade.clouds.steps} / {@code .lightSteps}: primary and light samples (P 20/3, B 20/3, Q 40/4, U 56/5).</li>
 * <li>{@code mcopt.shade.clouds.shadows} (true) / {@code .shadowStrength} (0.8): cloud shadows on terrain, water and
 * god rays.</li>
 * <li>{@code mcopt.shade.clouds.dome} (true): the reflection dome (clouds in water reflections of directions off
 * screen), {@code .domeSteps} (P 8, B 10, Q 12, U 16).</li>
 * <li>{@code mcopt.shade.clouds.cirrus} (true): the high cirrus layer.</li>
 * <li>{@code mcopt.shade.clouds.base} / {@code .thickness}: the cumulus layer (default: vanilla's cloud height + 8, 150
 * blocks thick).</li>
 * <li>{@code mcopt.shade.clouds.coverage} / {@code .type} (-1 = the daily weather): force coverage 0..1 or the cloud
 * type (0 flat .. 1 towering).</li>
 * <li>{@code mcopt.shade.clouds.wind} (1): wind speed multiplier; {@code .seed} (0): changes the daily weather.</li>
 * <li>{@code mcopt.shade.clouds.density} (1), {@code .detail} (1): extinction and fine-detail multipliers.</li>
 * <li>{@code mcopt.shade.clouds.gpuTimer} (false) / {@code .log} (false): a log line every 2 s with the passes' GPU ms and
 * the weather.</li>
 * </ul>
 */
public final class CloudConfig {
	private static final int TIER = Shade.TIER;
	public static final boolean ENABLED = Shade.ENABLED && flag("mcopt.shade.clouds", true) && flag("mcopt.shade.hdr", true)
		&& !Boolean.parseBoolean(System.getProperty("mcopt.lod", "false"));
	public static final float SCALE = clamp(floatProperty("mcopt.shade.clouds.scale", new float[] {0.4f, 0.45f, 0.5f, 0.67f}[TIER]), 0.2f, 1f);
	/** Checkerboard N: one pixel of each N x N block is marched per frame (P 3, B 3, Q 2, U 2; 1 = every pixel). */
	public static final int CHECKER = clampInt(Integer.getInteger("mcopt.shade.clouds.checker", new int[] {3, 3, 2, 2}[TIER]), 1, 3);
	public static final int STEPS = clampInt(Integer.getInteger("mcopt.shade.clouds.steps", new int[] {20, 20, 40, 56}[TIER]), 6, 128);
	public static final int LIGHT_STEPS = clampInt(Integer.getInteger("mcopt.shade.clouds.lightSteps", new int[] {3, 3, 4, 5}[TIER]), 1, 8);
	public static final boolean SHADOWS = flag("mcopt.shade.clouds.shadows", true);
	public static final float SHADOW_STRENGTH = clamp(floatProperty("mcopt.shade.clouds.shadowStrength", 0.8f), 0f, 1f);
	public static final float SHADOW_EXTENT = clamp(floatProperty("mcopt.shade.clouds.shadowExtent", 1024f), 256f, 4096f);
	public static final boolean DOME = flag("mcopt.shade.clouds.dome", true);
	public static final int DOME_STEPS = clampInt(Integer.getInteger("mcopt.shade.clouds.domeSteps", new int[] {8, 10, 12, 16}[TIER]), 4, 64);
	public static final int DOME_W = 128, DOME_H = 64;
	/** Frames between dome updates (4 or 8; mcopt.shade.clouds.domeEvery): M3.1 8 at performance/balanced, else 4; 4 (M3) without lean. */
	public static final int DOME_EVERY = Integer.getInteger("mcopt.shade.clouds.domeEvery", TIER <= 1 ? 8 : 4) >= 8 ? 8 : 4;
	public static final boolean CIRRUS = flag("mcopt.shade.clouds.cirrus", true);
	/**
	 * Cloud style: volumetric (default) or blocky (vanilla-style 12-block cells, ray-traced and lit: the Storybook look's
	 * default, mcopt.shade.look=storybook).
	 */
	public static final boolean BLOCKY = System.getProperty("mcopt.shade.clouds.style", "storybook".equalsIgnoreCase(System.getProperty("mcopt.shade.look", "").trim())
		? "blocky" : "volumetric").trim().equalsIgnoreCase("blocky");
	/** Blocky style: slab thickness (blocks; vanilla's fancy clouds are 4). */
	public static final float BLOCK_THICKNESS = clamp(floatProperty("mcopt.shade.clouds.blockThickness", 8f), 2f, 64f);
	/** Absolute y of the cumulus base; NaN = vanilla's cloud height + 8. */
	public static final float BASE = floatProperty("mcopt.shade.clouds.base", Float.NaN);
	public static final float THICKNESS = clamp(floatProperty("mcopt.shade.clouds.thickness", 150f), 20f, 600f);
	public static final float CIRRUS_ABOVE = clamp(floatProperty("mcopt.shade.clouds.cirrusAbove", 900f), 100f, 4000f);
	public static final float COVERAGE = floatProperty("mcopt.shade.clouds.coverage", -1f);
	public static final float TYPE = floatProperty("mcopt.shade.clouds.type", -1f);
	public static final float WIND = Math.max(0f, floatProperty("mcopt.shade.clouds.wind", 1f));
	/**
	 * Speed of the 3D (billow) noise relative to the cumulus field (0.82: the billows slide a little through the shapes, "boil";
	 * 1 = the clouds drift as rigid shapes, which the history reprojection follows exactly). Tried 1 as the default on
	 * 2026-10-07 against the still-camera comb on soft edges (gone with wind 0): the comb stayed the same (runs cf7/cf2), so
	 * the boil is kept; the comb comes from the resolve's reprojection of the wind (see CLOUDS.md, open items).
	 */
	public static final float NOISE_DRIFT = clamp(floatProperty("mcopt.shade.clouds.noiseDrift", 0.82f), 0f, 2f);
	public static final long SEED = Long.getLong("mcopt.shade.clouds.seed", 0L);
	public static final float DENSITY = clamp(floatProperty("mcopt.shade.clouds.density", 1f), 0.1f, 10f);
	public static final float DETAIL = clamp(floatProperty("mcopt.shade.clouds.detail", 1f), 0f, 3f);
	/** Blocks of world per 1 km of the sky model's air for the light the layer gets (sunset light lingers on the clouds). */
	public static final float ALTITUDE_SCALE = clamp(floatProperty("mcopt.shade.clouds.altitudeScale", 4f), 0f, 50f);
	/** Most visits a history pixel averages (its march samples are jittered): fewer with a larger checkerboard (slower visits). */
	public static final int MAX_AGE = clampInt(Integer.getInteger("mcopt.shade.clouds.maxAge", CHECKER >= 3 ? 10 : 12), 1, 63);
	public static final int DEBUG = Integer.getInteger("mcopt.shade.clouds.debug", 0);
	/**
	 * M3.1 performance pass (CLOUDS.md "M3.1 performance"): the cheaper ways of the passes and consumers (fog lookup only where
	 * it shows, VL cloud shadow every other step, the resolve's distance taps by gather, the march's sky lookup only where a
	 * cloud is). false = M3 exactly (the dev bits OLD_* all set).
	 */
	public static final boolean LEAN = flag("mcopt.shade.clouds.lean", true);
	/** Dev bits (mcopt.shade.clouds.dev, read every frame; 0 normally): price parts of the clouds inside one session. */
	public static final int DEV_NO_SCREEN = 1, DEV_NO_MAPS = 2, DEV_NO_SKY = 4, DEV_NO_FOG = 8, DEV_NO_SHADOW_USE = 16, DEV_NO_VL = 32,
		DEV_OLD_FOG = 256, DEV_OLD_VL = 512, DEV_OLD_RESOLVE = 1024, DEV_OLD_MARCH = 2048, DEV_SCALE_ALT = 4096, DEV_OLD_MIP = 8192,
		DEV_OLD_DOME = 16384;
	public static final int DEV_OLD_ALL = DEV_OLD_FOG | DEV_OLD_VL | DEV_OLD_RESOLVE | DEV_OLD_MARCH | DEV_OLD_MIP | DEV_OLD_DOME;
	/** The history scale dev bit 4096 switches to (pricing another scale inside one session). */
	public static final float SCALE_ALT = clamp(floatProperty("mcopt.shade.clouds.scaleAlt", 0.4f), 0.2f, 1f);

	/** This frame's dev bits: mcopt.shade.clouds.dev (read every frame: the bench's price phases set it), plus OLD_* without LEAN. */
	public static int devBits() {
		return Integer.getInteger("mcopt.shade.clouds.dev", 0) | (LEAN ? 0 : DEV_OLD_ALL);
	}
	public static final boolean GPU_TIMER = Boolean.getBoolean("mcopt.shade.clouds.gpuTimer");
	public static final boolean LOG = GPU_TIMER || Boolean.getBoolean("mcopt.shade.clouds.log");

	private CloudConfig() {
	}

	/** The MSL of the passes: ShadeMsl.prelude() + clouds.metal (mcclouds.m; validate.sh). */
	public static String source() {
		return mcopt.metal.shade.ShadeMsl.prelude() + "\n" + mcopt.metal.shade.ShadeMsl.resource("clouds.metal");
	}

	static String describe() {
		return (BLOCKY ? "blocky, " : "") + "preset " + Shade.PRESET + ", scale " + SCALE + " (" + CHECKER + "x" + CHECKER + " checkerboard)" + ", " + STEPS + "/" + LIGHT_STEPS + " steps"
			+ (SHADOWS ? ", shadows " + SHADOW_STRENGTH : ", no shadows") + (DOME ? ", dome " + DOME_STEPS + " steps" : "") + (CIRRUS ? ", cirrus" : "")
			+ (LEAN ? "" : ", not lean (M3 passes)");
	}

	static boolean flag(String key, boolean def) {
		String v = System.getProperty(key);
		return v == null ? def : Boolean.parseBoolean(v.trim());
	}

	static float floatProperty(String key, float def) {
		String v = System.getProperty(key);
		if (v == null) return def;
		try {
			return Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static int clampInt(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
