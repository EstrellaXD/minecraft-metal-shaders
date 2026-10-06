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
package mcopt.metal.shade.vl;

import mcopt.metal.shade.Shade;

/**
 * Volumetric light settings (read once at startup; any of them can also go in config/mcopt.properties). Free of game types:
 * the mixin plugin reads it while mixins bootstrap.
 *
 * <ul>
 * <li>{@code mcopt.shade.vl} (true with mcopt.shade): god rays on.</li>
 * <li>{@code mcopt.shade.preset} (balanced): performance | balanced | quality | ultra picks the defaults below.</li>
 * <li>{@code mcopt.shade.vl.scale}: march resolution relative to the render target (P 0.5, B 0.5, Q 0.5, U 0.5;
 * 0.25 = quarter resolution, cheapest).</li>
 * <li>{@code mcopt.shade.vl.steps}: shadow-map samples per ray (P 12, B 14, Q 20, U 28).</li>
 * <li>{@code mcopt.shade.vl.ssTaps}: screen-space crepuscular taps toward the sun (P 8, B 8, Q 12, U 16); 0 = off.</li>
 * <li>{@code mcopt.shade.vl.dist}: march distance in blocks (P 160, B 160, Q 224, U 224).</li>
 * <li>{@code mcopt.shade.vl.strength} (1): overall brightness of the shafts.</li>
 * <li>{@code mcopt.shade.vl.moon} (1): moon shafts relative to sun shafts (they're dim anyway).</li>
 * <li>{@code mcopt.shade.vl.underwater} (true): caustic shafts under water.</li>
 * <li>{@code mcopt.shade.vl.temporal} (true): temporal accumulation (off: raw noisy march, for comparisons).</li>
 * <li>{@code mcopt.shade.vl.canopy} (-1): force the canopy factor 0..1 (debug); negative = measured on the GPU.</li>
 * <li>{@code mcopt.shade.vl.lossy} (false): lossy-compressed VL targets.</li>
 * <li>{@code mcopt.shade.vl.debug} (0): 1 the light alone, 2 the marched distance.</li>
 * <li>{@code mcopt.shade.vl.log} (false): a line every 2 s (canopy, density, strength).</li>
 * </ul>
 */
public final class VlConfig {
	public static final boolean ENABLED = Shade.ENABLED && Boolean.parseBoolean(System.getProperty("mcopt.shade.vl", "true"));
	public static final String PRESET = System.getProperty("mcopt.shade.preset", "balanced").trim().toLowerCase(java.util.Locale.ROOT);
	private static final int TIER = switch (PRESET) {
		case "performance", "p" -> 0;
		case "quality", "q" -> 2;
		case "ultra", "u" -> 3;
		default -> 1;
	};
	public static final float SCALE = clamp(floatProperty("mcopt.shade.vl.scale", 0.5f), 0.125f, 1f);
	public static final int STEPS = Math.max(4, Math.min(64, Integer.getInteger("mcopt.shade.vl.steps", new int[] {12, 14, 20, 28}[TIER])));
	public static final int SS_TAPS = Math.max(0, Math.min(32, Integer.getInteger("mcopt.shade.vl.ssTaps", new int[] {8, 8, 12, 16}[TIER])));
	public static final float DIST = clamp(floatProperty("mcopt.shade.vl.dist", TIER >= 2 ? 224f : 160f), 16f, 512f);
	public static final float STRENGTH = Math.max(0f, floatProperty("mcopt.shade.vl.strength", 1f));
	public static final float MOON = Math.max(0f, floatProperty("mcopt.shade.vl.moon", 1f));
	public static final boolean UNDERWATER = Boolean.parseBoolean(System.getProperty("mcopt.shade.vl.underwater", "true"));
	public static final boolean TEMPORAL = Boolean.parseBoolean(System.getProperty("mcopt.shade.vl.temporal", "true"));
	public static final float CANOPY = floatProperty("mcopt.shade.vl.canopy", -1f);
	public static final boolean LOSSY = Boolean.getBoolean("mcopt.shade.vl.lossy");
	public static final int DEBUG = Integer.getInteger("mcopt.shade.vl.debug", 0);
	public static final boolean LOG = Boolean.getBoolean("mcopt.shade.vl.log");

	private VlConfig() {
	}

	/** The MSL the passes compile from: shade_common.metal + vl.metal (mcvl.m; also tools/shade/validate.sh). */
	public static String source() {
		return mcopt.metal.shade.ShadeMsl.resource("shade_common.metal") + "\n" + mcopt.metal.shade.ShadeMsl.resource("vl.metal");
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

	static String describe() {
		return "preset " + PRESET + ", scale " + SCALE + ", " + STEPS + " steps + " + SS_TAPS + " screen taps, " + (int) DIST + " blocks"
			+ (TEMPORAL ? "" : ", no temporal") + (LOSSY ? ", lossy" : "") + (DEBUG != 0 ? ", debug " + DEBUG : "");
	}
}
