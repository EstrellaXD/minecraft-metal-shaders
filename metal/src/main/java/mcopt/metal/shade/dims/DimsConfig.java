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
import mcopt.metal.shade.Live;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeSettings;
import mcopt.metal.shade.ShadeConfig;

/**
 * M3 "sky, dimensions and XDR" flags (read once at startup; config/mcopt.properties becomes system properties first). Every
 * one needs mcopt.shade=true; the dimension looks and camera media also need the HDR frame (mcopt.shade.hdr, default on).
 * <ul>
 * <li>{@code mcopt.shade.skydims} (true): the whole module; false turns every flag below off (= M2.1: no Nether/End looks,
 * aurora, camera media or XDR output). For the after-merge check "every module flag off = M2.1".</li>
 * <li>{@code mcopt.shade.dims} (true): the Nether and End looks (bible E24, E25): terrain lit per pixel with a dimension
 * ambient (Nether biome palettes, lava up-light; the End's violet key light with shadows), our own fog from depth in the final
 * pass (Nether: layered glowing haze; End: a thin violet haze), the End sky (gradient, nebula, stars, beams, flash).</li>
 * <li>{@code mcopt.shade.aurora} (auto): auto = every clear night in cold biomes (fainter in cool ones) and about one clear
 * night in three anywhere else, at half strength (Gael, 2026-10-08: "frecuentes"); rare = M3's auto, only some clear nights
 * (hashed per night, ~35%) in cold biomes; always = every night in any biome (look-dev); off.</li>
 * <li>{@code mcopt.shade.media} (true): camera inside lava / powder snow (E26): a glowing, churning lava medium with heat
 * shimmer; frosted cold powder snow. (1.1) live ({@link #media()}).</li>
 * <li>{@code mcopt.shade.endShadows} (true): the End key light casts shadows (the shadow pass runs in the End; its far
 * cascade stays cached since the light never moves). (1.1) live ({@link #endShadows()}).</li>
 * <li>{@code mcopt.shade.netherShimmer} (preset: quality/ultra on): heat shimmer over distant Nether terrain. (1.1) live
 * ({@link #netherShimmer()}).</li>
 * <li>{@code mcopt.shade.xdr} (auto): EDR output for the Liquid Retina XDR panel (E23): auto = on when a screen reports EDR
 * headroom potential above 1 (the MacBook Pro's built-in panel), on, off. Highlights (sun, lava, torches, glints, bloom
 * cores) go above SDR white up to the screen's current headroom; the GUI, hand and screenshots stay SDR.</li>
 * <li>{@code mcopt.shade.xdrPeak} (4): cap on the highlight peak, in multiples of SDR white (1..16). (1.1) live (ShadeXdr).</li>
 * <li>{@code mcopt.shade.xdrKnee} (2): the scene luminance where highlights start to use the headroom (0.5..16). (1.1) live.</li>
 * <li>(1.1, live) the player's multipliers ({@link #user}: 1 = as released, 0..3): {@code mcopt.shade.user.netherFog} (the
 * Nether fog's density), {@code .netherFogGlow} (its glow, NETHER_FOG_GAIN), {@code .netherLight} (the Nether's ambient),
 * {@code .lavaGlow} (the lava sea's up-light), {@code .endFog} (the End haze's density), {@code .endLight} (the End's key light
 * and ambient), {@code .endSky} (the End sky's gradient, beams and nebula), {@code .auroraBrightness} (the aurora and its
 * green in the night ambient).</li>
 * </ul>
 */
public final class DimsConfig {
	/** mcopt.shade.skydims: the module's master switch. */
	public static final boolean MODULE = Shade.ENABLED && flag("mcopt.shade.skydims", true);
	public static final boolean DIMS = MODULE && ShadeConfig.HDR && flag("mcopt.shade.dims", true);
	/** 0 off, 1 auto (every clear night in cold biomes, some elsewhere), 2 always, 3 rare (M3's auto: some nights, cold biomes). */
	public static final int AURORA = MODULE ? switch (System.getProperty("mcopt.shade.aurora", "auto").trim().toLowerCase(Locale.ROOT)) {
		case "off", "false" -> 0;
		case "always", "on", "true" -> 2;
		case "rare" -> 3;
		default -> 1;
	} : 0;
	/** (1.1) The aurora's code this frame: the settings screen's choice (ShadeSettings "Auroras", live), AURORA at startup. */
	public static int aurora() {
		return MODULE ? ShadeSettings.aurora() : 0;
	}
	public static final boolean MEDIA = MODULE && ShadeConfig.HDR && flag("mcopt.shade.media", true);
	public static final boolean END_SHADOWS = DIMS && flag("mcopt.shade.endShadows", true);
	public static final boolean NETHER_SHIMMER = DIMS && flag("mcopt.shade.netherShimmer", Shade.TIER >= 2);
	/** 0 off, 1 auto, 2 on. */
	public static final int XDR = MODULE && ShadeConfig.HDR ? switch (System.getProperty("mcopt.shade.xdr", "auto").trim().toLowerCase(Locale.ROOT)) {
		case "off", "false" -> 0;
		case "on", "true" -> 2;
		default -> 1;
	} : 0;
	public static final float XDR_PEAK = floatFlag("mcopt.shade.xdrPeak", 4f, 1f, 16f);

	/**
	 * (1.1) live: the camera media this frame (mcopt.shade.media; MEDIA at startup). Their code is in the final pass's dims
	 * variant, made when DIMS or MEDIA was on at startup (ShadePost), and switched by a feature bit (ShadeDims.pack).
	 */
	public static boolean media() {
		return (DIMS || MEDIA) && Live.b("mcopt.shade.media", true);
	}

	/** (1.1) live: the End key light's shadows this frame (mcopt.shade.endShadows; END_SHADOWS at startup). */
	public static boolean endShadows() {
		return DIMS && Live.b("mcopt.shade.endShadows", true);
	}

	/** (1.1) live: the Nether's heat shimmer this frame (mcopt.shade.netherShimmer; the preset's default, NETHER_SHIMMER at startup). */
	public static boolean netherShimmer() {
		return DIMS && Live.b("mcopt.shade.netherShimmer", Shade.TIER >= 2);
	}

	/** (1.1) live: a player multiplier of the dimension looks (key: mcopt.shade.user.*; 1 = as released, 0..3). */
	static float user(String key) {
		return Live.f(key, 1f, 0f, 3f);
	}

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
		return (MODULE ? "" : "module off (mcopt.shade.skydims=false): ") + "dims " + DIMS + ", aurora " + new String[] {"off", "auto", "always", "rare"}[AURORA] + ", media " + MEDIA + ", end shadows " + END_SHADOWS
			+ ", nether shimmer " + NETHER_SHIMMER + ", xdr " + new String[] {"off", "auto", "on"}[XDR] + " (peak cap " + XDR_PEAK + ")";
	}
}
