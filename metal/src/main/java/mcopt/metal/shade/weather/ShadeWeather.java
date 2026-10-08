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
package mcopt.metal.shade.weather;

import java.nio.ByteBuffer;
import java.util.Locale;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeLook;
import mcopt.metal.shade.ShadeMsl;

/**
 * M3 weather (shader bible E13, and E08's coloured shadows): wet surfaces, puddles with reflections, raindrop ripples on
 * puddles and water, slanted lit rain, snow cover, lightning light, and light tinted by stained glass and water. See
 * WEATHER.md (branch m3-weather) for the integration.
 * <ul>
 * <li>CPU state (this class, no game types): wetness rises while it rains where rain falls (the camera's biome), puddles
 * follow it with a lag and dry slowly, snow cover builds where it snows; a wind direction and slant; lightning flashes.
 * {@link WeatherFrame#update} (game-typed) feeds it once per frame; {@link #pack} writes ShadeLook.wet / wind / flash.</li>
 * <li>Shaders: {@link #prelude} (the SHADE_* defines + weather_surface.metal) goes in front of the Sodium terrain stages
 * (ShadeMsl.terrain); sodium_terrain.fs.metal applies the wet layer, the flash and the shadow tint, water.metal the ripples;
 * weather.metal re-lights rain and slants it (ShadeMsl.weather / weatherVertex); lightning bolts glow in HDR
 * (ShadeMsl.lightning).</li>
 * <li>Coloured shadows: the shadow pass draws the translucent terrain (water, stained glass, ice) of the near cascade into a
 * tint map (mcshadow.m mc_shadow_tint_*), published as ShadeResources entry {@link #RES_SHADOW_TINT}.</li>
 * <li>Puddle reflections march last frame's TAA history with its depth (ShadeResources entry {@link #RES_PREV_DEPTH},
 * ShadeLook.ssr.y = 1 when valid; ShadePost.beginWorld publishes it).</li>
 * </ul>
 *
 * <p>Flags (system properties or config/mcopt.properties; read once at startup; all need mcopt.shade=true):
 * <ul>
 * <li>{@code mcopt.shade.weather} (true): the module (false: M2.1's rain, dry surfaces; the shaders are byte-identical to
 * M2.1's except for the unused tint bindings).</li>
 * <li>{@code mcopt.shade.puddles} (true), {@code mcopt.shade.puddleSsr} (preset: false for performance, true otherwise),
 * {@code mcopt.shade.puddleQuad} (true except ultra: a 2x2 quad shares one ray), {@code mcopt.shade.puddleSteps} (quad: 12 / 12 / 20 / 24;
 * per pixel: 8 / 10 / 14 / 20), {@code mcopt.shade.puddleDist} (64 blocks).</li>
 * <li>{@code mcopt.shade.ripples} (true): raindrop rings on puddles and on water under open sky; {@code mcopt.shade.rippleLayers}
 * (1 on performance/balanced, 2 on quality/ultra): drop grids.</li>
 * <li>{@code mcopt.shade.snowCover} (true): snow builds up on top faces while it snows at the camera.</li>
 * <li>{@code mcopt.shade.rainSlant} (true): rain leans with the wind (more in thunderstorms).</li>
 * <li>{@code mcopt.shade.lightning} (true): bolts light the scene and the sky, and glow (the game's "Hide Lightning Flashes"
 * option turns the flash light off).</li>
 * <li>{@code mcopt.shade.colorShadows} (true) and {@code mcopt.shade.colorShadowRes} (1024; 2048 on ultra): coloured light
 * through stained glass, ice and water (near cascade).</li>
 * <li>{@code mcopt.shade.weatherTime} (1): time scale of wetting and drying (the bench uses 10).</li>
 * <li>{@code mcopt.shade.wetness} (-1 = from the weather): force wetness and puddles to this value (look development).</li>
 * <li>{@code mcopt.shade.snowAmount} (-1 = from the weather): force the snow cover to this value (look development).</li>
 * </ul>
 */
public final class ShadeWeather {
	/** The module's flag on its own (what ShadeMsl assembles the MSL by; validation tools run without mcopt.shade). */
	public static final boolean MODULE = flag("mcopt.shade.weather", true);
	public static final boolean ON = Shade.ENABLED && MODULE;
	public static final boolean PUDDLES = flag("mcopt.shade.puddles", true);
	public static final boolean PUDDLE_SSR = PUDDLES && flag("mcopt.shade.puddleSsr", Shade.TIER >= 1);
	/** The 2x2 quad shares one reflection ray (each lane marches a quarter of it): default except ultra. */
	public static final boolean PUDDLE_QUAD = flag("mcopt.shade.puddleQuad", Shade.TIER < 3);
	/** March steps per ray (quad-shared: split over 4 lanes) + 3 bisection steps. */
	public static final int PUDDLE_STEPS = Math.clamp(Integer.getInteger("mcopt.shade.puddleSteps", PUDDLE_QUAD ? new int[] {12, 12, 20, 24}[Shade.TIER]
		: new int[] {8, 10, 14, 20}[Shade.TIER]), 4, 64);
	public static final float PUDDLE_DIST = Math.clamp(floatFlag("mcopt.shade.puddleDist", 64f), 8f, 256f);
	public static final boolean RIPPLES = flag("mcopt.shade.ripples", true);
	/** Raindrop grids: 2 (quality, ultra) or 1 with faster drops (performance, balanced: about half the cost). */
	public static final int RIPPLE_LAYERS = Integer.getInteger("mcopt.shade.rippleLayers", Shade.TIER >= 2 ? 2 : 1) >= 2 ? 2 : 1;
	public static final boolean SNOW = flag("mcopt.shade.snowCover", true);
	public static final boolean SLANT = flag("mcopt.shade.rainSlant", true);
	public static final boolean LIGHTNING = flag("mcopt.shade.lightning", true);
	/** HDR only: the terrain reads the tint map through ShadeResources (no reader in LDR, so no tint pass either). */
	public static final boolean COLOR_SHADOWS = ON && Shade.SHADOWS && mcopt.metal.shade.ShadeConfig.HDR && flag("mcopt.shade.colorShadows", true);
	public static final int COLOR_SHADOW_RES = Integer.getInteger("mcopt.shade.colorShadowRes", Shade.TIER >= 3 ? 2048 : 1024) >= 2048 ? 2048 : 1024;
	public static final float TIME_SCALE = Math.clamp(floatFlag("mcopt.shade.weatherTime", 1f), 0.1f, 100f);
	public static final float FORCE_WET = floatFlag("mcopt.shade.wetness", -1f);
	public static final float FORCE_SNOW = floatFlag("mcopt.shade.snowAmount", -1f);

	/** ShadeResources entries (shade_look.metal): last frame's linear view depth (TAA history), the coloured-shadow map. */
	public static final int RES_PREV_DEPTH = 5, RES_SHADOW_TINT = 6, RES_NOISE = 4;

	/** The baked puddle/snow noise tile (ShadeResources entry 4), 0 until baked; bake failures fall back to the analytic noise. */
	private static long noiseTexture;
	private static boolean noiseFailed;

	/**
	 * Set by ShadowPass when the near cascade's tint pass drew translucent casters; packed into ShadeLook.ssr.w and reset
	 * (render thread). With none nearby (the usual case away from glass and water) the terrain skips the tint lookup. The
	 * shadow pass runs after the pack, so the flag is one frame late: a frame without tint when glass first comes into range.
	 */
	public static boolean tintCasters;

	// ---- state (render thread) -----------------------------------------------------------------------------------------
	/** Smoothed: wetness, puddles, snow cover, rain intensity. */
	static float wetness, puddles, snow, rain;
	/** Wind direction (radians from +x toward +z), slant, gust; snowfall at the camera 0..1 (smoothed: snow flakes' look). */
	static float windAngle, slant, gust, snowfall;
	/** This frame's flash: direction (camera-relative, unit) and its light. */
	static final float[] flashDir = {0f, 1f, 0f};
	static float flash;

	private ShadeWeather() {
	}

	static boolean flag(String key, boolean fallback) {
		String v = System.getProperty(key);
		return v == null ? fallback : Boolean.parseBoolean(v.trim());
	}

	static float floatFlag(String key, float fallback) {
		try {
			String v = System.getProperty(key);
			return v == null ? fallback : Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * One frame of the weather's slow state. dt: seconds since the last frame; rainTarget / snowTarget: the rain level where it
	 * rains / snows at the camera (0 elsewhere: deserts, other dimensions); thunder 0..1; snap: jump to the targets (world
	 * load, dimension change, teleport).
	 */
	static void step(float dt, float rainTarget, float snowTarget, float thunder, float dayIndex, float seconds, boolean snap) {
		float s = dt * TIME_SCALE;
		// wetness: soaks in over ~20 s of rain, dries over a couple of minutes; puddles fill once the ground is wet and dry
		// slower still; snow builds over ~40 s and lingers for minutes
		float wetTarget = rainTarget;
		float puddleTarget = rainTarget > 0.05f ? Math.min(Math.max((wetness - 0.35f) / 0.65f, 0f), rainTarget) : 0f;
		if (snap) {
			wetness = wetTarget;
			puddles = wetTarget;
			snow = snowTarget;
			rain = rainTarget;
			snowfall = snowTarget > 0.05f ? 1f : 0f;
		} else {
			wetness = approach(wetness, wetTarget, s, 14f, 75f);
			puddles = approach(puddles, puddleTarget, s, 10f, 110f);
			snow = approach(snow, snowTarget, s, 30f, 240f);
			rain = approach(rain, rainTarget, dt, 1.5f, 3f);
			snowfall = approach(snowfall, snowTarget > 0.05f ? 1f : 0f, dt, 1.5f, 1.5f);
		}
		if (FORCE_WET >= 0f) {
			wetness = Math.min(FORCE_WET, 1f);
			puddles = Math.min(FORCE_WET, 1f);
		}
		if (FORCE_SNOW >= 0f) snow = Math.min(FORCE_SNOW, 1f);
		// wind: a direction per day that wanders slowly; storms lean the rain more and gust
		double h = Math.sin(dayIndex * 12.9898 + 78.233) * 43758.5453;
		windAngle = (float) ((h - Math.floor(h)) * Math.PI * 2 + 0.35 * Math.sin(seconds / 97.0));
		gust = (float) (0.5 + 0.5 * Math.sin(seconds * 0.71) * Math.sin(seconds * 0.23 + 1.3));
		slant = SLANT ? (0.12f + 0.22f * thunder) * (0.75f + 0.5f * gust) : 0f;
	}

	/** Exponential approach with separate rise / fall time constants (s). */
	private static float approach(float v, float target, float dt, float rise, float fall) {
		float tau = target > v ? rise : fall;
		return target + (v - target) * (float) Math.exp(-dt / tau);
	}

	/** Writes ShadeLook.wet, wind and flash (call from ShadeLook.pack, b = the struct's bytes, little-endian). */
	public static void pack(ByteBuffer b) {
		if (!ON) {
			put4(b, ShadeLook.WET, 0f, 0f, 0f, 0f);
			put4(b, ShadeLook.WIND, 1f, 0f, 0f, 0f);
			put4(b, ShadeLook.FLASH, 0f, 1f, 0f, 0f);
			return;
		}
		put4(b, ShadeLook.WET, wetness, PUDDLES ? puddles : 0f, SNOW ? snow : 0f, rain);
		put4(b, ShadeLook.WIND, (float) Math.cos(windAngle), (float) Math.sin(windAngle), slant, snowfall); // w: snowfall (gust only shapes the slant)
		put4(b, ShadeLook.FLASH, flashDir[0], flashDir[1], flashDir[2], LIGHTNING ? flash : 0f);
		b.putFloat(ShadeLook.SSR + 8, noiseTexture != 0 ? 1f : 0f); // ssr.z: ShadeResources.weatherNoise is baked
		b.putFloat(ShadeLook.SSR + 12, tintCasters ? 1f : 0f); // ssr.w: the tint map holds translucent casters (else receivers skip it)
		tintCasters = false;
	}

	/**
	 * Bakes the puddle field and snow drifts into one 256-block tile (weather_surface.metal shade_wx_noise_fs, RG16F 1024^2)
	 * once, into enc before the world passes, and publishes it as ShadeResources entry 4. HDR only (the terrain reads it
	 * through ShadeResources); idempotent.
	 */
	public static void bakeNoise(long enc) {
		if (!ON || noiseTexture != 0 || noiseFailed || enc == 0 || !(PUDDLES || SNOW)) return;
		try {
			long lib = mcopt.metal.shade.ShadeRender.library(mcopt.metal.shade.ShadeRender.prelude() + mcopt.metal.shade.ShadeRender.postSource() + "#define SHADE_WX_NOISE_BAKE\n" + prelude());
			long pso = mcopt.metal.shade.ShadeRender.pipeline(lib, "shade_fs_vs", "shade_wx_noise_fs", mcopt.metal.shade.ShadeRender.RG16F,
				mcopt.metal.shade.ShadeRender.BLEND_NONE);
			long tex = mcopt.metal.shade.ShadeRender.texture(mcopt.metal.shade.ShadeRender.RG16F, 1024, 1024, false, "mcopt shade weather noise");
			mcopt.metal.shade.ShadeRender.pass(enc, pso, tex, mcopt.metal.shade.ShadeRender.DONT_CARE, null, null, "mcopt shade weather: noise bake");
			mcopt.metal.shade.ShadeRender.resource(RES_NOISE, tex);
			noiseTexture = tex;
		} catch (Throwable e) {
			noiseFailed = true;
			System.out.println("[mcopt] shade: weather noise bake failed, per-pixel noise from now on: " + e);
		}
	}

	private static void put4(ByteBuffer b, int at, float x, float y, float z, float w) {
		b.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, w);
	}

	/** The wetness the water module's ripples and roughness use (rain intensity while it rains). */
	public static float rainIntensity() {
		return rain;
	}

	/** For logs: wetness, puddles, snow, rain, flash. */
	public static String describeState() {
		return String.format(Locale.ROOT, "wet %.2f puddles %.2f snow %.2f rain %.2f flash %.2f", wetness, puddles, snow, rain, flash);
	}

	/** The MSL in front of the Sodium terrain stages (after ShadeMsl.prelude, before the water module): defines + weather_surface.metal. */
	public static String prelude() {
		return prelude(false);
	}

	/**
	 * translucent: for the translucent terrain fragment (stained glass, ice: wet, no puddles or puddle reflections; the HDR
	 * translucent pipeline with the water module is at the edge of what the Metal compiler service builds: more code there
	 * made it crash with XPC_ERROR_CONNECTION_INTERRUPTED, see WATER_M21.md).
	 */
	public static String prelude(boolean translucent) {
		if (!MODULE) return "";
		StringBuilder s = new StringBuilder("#define SHADE_WEATHER\n");
		if (PUDDLES && !translucent) s.append("#define SHADE_PUDDLES\n");
		if (PUDDLE_SSR && !translucent) s.append("#define SHADE_PUDDLE_SSR\n#define SHADE_PUDDLE_STEPS ").append(PUDDLE_STEPS).append('\n')
			.append("#define SHADE_PUDDLE_DIST ").append(String.format(Locale.ROOT, "%.1f", PUDDLE_DIST)).append('\n')
			.append(PUDDLE_QUAD ? "#define SHADE_PUDDLE_QUAD\n" : "");
		if (RIPPLES) s.append("#define SHADE_RIPPLES\n#define SHADE_RIPPLE_LAYERS ").append(RIPPLE_LAYERS).append('\n');
		if (SNOW) s.append("#define SHADE_SNOW_COVER\n");
		if (LIGHTNING) s.append("#define SHADE_LIGHTNING\n");
		if (COLOR_SHADOWS || (!Shade.ENABLED && flag("mcopt.shade.colorShadows", true))) s.append("#define SHADE_COLOR_SHADOW\n");
		return s.append(ShadeMsl.resource("weather_surface.metal")).append('\n').toString();
	}

	static String describe() {
		return "puddles " + PUDDLES + (PUDDLE_SSR ? " (ssr " + PUDDLE_STEPS + " steps" + (PUDDLE_QUAD ? ", quad" : "") + ")" : "") + ", ripples " + RIPPLES + ", snow " + SNOW + ", slant " + SLANT
			+ ", lightning " + LIGHTNING + ", coloured shadows " + (COLOR_SHADOWS ? COLOR_SHADOW_RES + "^2" : "off");
	}
}
