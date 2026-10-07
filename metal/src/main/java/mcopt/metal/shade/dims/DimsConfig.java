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
package mcopt.metal.shade.dims;

import java.util.Locale;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeConfig;

/**
 * M3 "sky, dimensions and XDR" flags (read once at startup; config/mcopt.properties becomes system properties first). Every
 * one needs mcopt.shade=true; the dimension looks and camera media also need the HDR frame (mcopt.shade.hdr, default on).
 * <ul>
 * <li>{@code mcopt.shade.dims} (true): the Nether and End looks (bible E24, E25): terrain lit per pixel with a dimension
 * ambient (Nether biome palettes, lava up-light; the End's violet key light with shadows), our own fog from depth in the final
 * pass (Nether: layered glowing haze; End: a thin violet haze), the End sky (gradient, nebula, stars, beams, flash).</li>
 * <li>{@code mcopt.shade.aurora} (auto): auto = some clear nights in cold biomes (hashed per night, ~35%); always = every
 * night in any biome (look-dev); off.</li>
 * <li>{@code mcopt.shade.media} (true): camera inside lava / powder snow (E26): a glowing, churning lava medium with heat
 * shimmer; frosted cold powder snow.</li>
 * <li>{@code mcopt.shade.endShadows} (true): the End key light casts shadows (the shadow pass runs in the End; its far
 * cascade stays cached since the light never moves).</li>
 * <li>{@code mcopt.shade.netherShimmer} (preset: quality/ultra on): heat shimmer over distant Nether terrain.</li>
 * <li>{@code mcopt.shade.xdr} (auto): EDR output for the Liquid Retina XDR panel (E23): auto = on when a screen reports EDR
 * headroom potential above 1 (the MacBook Pro's built-in panel), on, off. Highlights (sun, lava, torches, glints, bloom
 * cores) go above SDR white up to the screen's current headroom; the GUI, hand and screenshots stay SDR.</li>
 * <li>{@code mcopt.shade.xdrPeak} (4): cap on the highlight peak, in multiples of SDR white (1..16).</li>
 * </ul>
 */
public final class DimsConfig {
	public static final boolean DIMS = Shade.ENABLED && ShadeConfig.HDR && flag("mcopt.shade.dims", true);
	/** 0 off, 1 auto (cold biomes, some nights), 2 always. */
	public static final int AURORA = Shade.ENABLED ? switch (System.getProperty("mcopt.shade.aurora", "auto").trim().toLowerCase(Locale.ROOT)) {
		case "off", "false" -> 0;
		case "always", "on", "true" -> 2;
		default -> 1;
	} : 0;
	public static final boolean MEDIA = Shade.ENABLED && ShadeConfig.HDR && flag("mcopt.shade.media", true);
	public static final boolean END_SHADOWS = DIMS && flag("mcopt.shade.endShadows", true);
	public static final boolean NETHER_SHIMMER = DIMS && flag("mcopt.shade.netherShimmer", Shade.TIER >= 2);
	/** 0 off, 1 auto, 2 on. */
	public static final int XDR = Shade.ENABLED && ShadeConfig.HDR ? switch (System.getProperty("mcopt.shade.xdr", "auto").trim().toLowerCase(Locale.ROOT)) {
		case "off", "false" -> 0;
		case "on", "true" -> 2;
		default -> 1;
	} : 0;
	public static final float XDR_PEAK = floatFlag("mcopt.shade.xdrPeak", 4f, 1f, 16f);

	private DimsConfig() {
	}

	static boolean flag(String key, boolean fallback) {
		String v = System.getProperty(key);
		return v == null ? fallback : Boolean.parseBoolean(v.trim());
	}

	static float floatFlag(String key, float fallback, float min, float max) {
		try {
			String v = System.getProperty(key);
			return v == null ? fallback : Math.max(min, Math.min(max, Float.parseFloat(v.trim())));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	public static String describe() {
		return "dims " + DIMS + ", aurora " + new String[] {"off", "auto", "always"}[AURORA] + ", media " + MEDIA + ", end shadows " + END_SHADOWS
			+ ", nether shimmer " + NETHER_SHIMMER + ", xdr " + new String[] {"off", "auto", "on"}[XDR] + " (peak cap " + XDR_PEAK + ")";
	}
}
