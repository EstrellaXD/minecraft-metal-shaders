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
package mcopt.metal.shade;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mcopt.metal.MetalShaderRegistry;
import mcopt.metal.shade.water.ShadeWater;

/**
 * The shaded MSL, assembled from resources/mcopt/shade the same way for the game and for validation tools (mslcheck):
 * every shaded stage is "#define MCOPT_SHADE" + shade_common.metal + the stage's own source (MetalPipeline prepends the
 * MCOPT_SLOT_* uniform slot defines in front of that). Registered by pipeline name in MetalShaderRegistry:
 * <ul>
 * <li>sodium:pipeline/{solid,cutout,translucent}_terrain: mcopt's own Sodium MSL (plain and pulled vertex, fragment) with
 * MCOPT_SHADE: per-pixel lighting (sodium_terrain.{vs,fs}.metal).</li>
 * <li>minecraft:pipeline/sky: the atmosphere (sky.vs.metal, sky.fs.metal).</li>
 * <li>minecraft:pipeline/celestial: vanilla's sun quad dropped, moon kept (celestial.vs.metal).</li>
 * <li>minecraft:pipeline/sunrise_sunset: dropped in the overworld (sunrise_sunset.vs.metal).</li>
 * <li>minecraft:pipeline/clouds, flat_clouds: lit clouds (clouds.fs.metal).</li>
 * <li>minecraft:pipeline/weather: rain and snow re-lit (weather.metal, patched into the generated fragment).</li>
 * <li>minecraft:pipeline/stars: vanilla's star quads dropped when the sky draws M2's procedural stars (stars.vs.metal).</li>
 * </ul>
 * M2 (mcopt.shade.hdr): the prelude is shade_look.metal + shade_common.metal + shade_m2.metal, and every pipeline that draws
 * into the HDR world target gets an RGBA16Float variant through {@link #formatAdapter}: the stages above are compiled with
 * "#define MCOPT_HDR" (pre-exposed radiance out), the game's own fragments convert their display-referred result with
 * shadeLdrToHdr ({@link #hdrFragment}).
 */
public final class ShadeMsl {
	/** Must match SHADE_LUT_W / SHADE_LUT_H in shade_common.metal. */
	public static final int LUT_WIDTH = 128, LUT_HEIGHT = 80;
	public static final String SKY = "minecraft:pipeline/sky", CELESTIAL = "minecraft:pipeline/celestial", SUNRISE = "minecraft:pipeline/sunrise_sunset";
	public static final List<String> TERRAIN = List.of("sodium:pipeline/solid_terrain", "sodium:pipeline/cutout_terrain", "sodium:pipeline/translucent_terrain");

	public static final String WEATHER = "minecraft:pipeline/weather", STARS = "minecraft:pipeline/stars";
	public static final List<String> CLOUDS = List.of("minecraft:pipeline/clouds", "minecraft:pipeline/flat_clouds");

	private static volatile String prelude, look, skyVertex, skyFragment, celestialVertex, sunriseVertex, starsVertex, cloudFragment;

	private ShadeMsl() {
	}

	public static String resource(String name) {
		try (InputStream in = ShadeMsl.class.getResourceAsStream("/mcopt/shade/" + name)) {
			return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * "#define MCOPT_SHADE", shade_look.metal (M2 art layer, display transform), shade_common.metal (M1) and shade_m2.metal (M2
	 * shading): what goes in front of every shaded stage.
	 */
	public static String prelude() {
		if (prelude == null) {
			prelude = "#define MCOPT_SHADE\n" + resource("shade_look.metal") + "\n" + resource("shade_common.metal") + "\n" + resource("shade_m2.metal") + "\n"
				+ mcopt.metal.shade.light.DynLightMsl.prelude(); // M3 dynamic lights (empty when off)
		}
		return prelude;
	}

	/** shade_look.metal alone (self-contained): what the game's own fragments need for shadeLdrToHdr. */
	public static String look() {
		if (look == null) look = resource("shade_look.metal") + "\n";
		return look;
	}

	/** The post chain's library (ShadePost): the prelude + shade_post.metal. */
	public static String post() {
		return prelude() + resource("shade_post.metal");
	}

	/**
	 * A Sodium terrain stage (the source MetalPipeline would compile: built-in MSL, with its defines) with shading. The
	 * translucent fragment (the only stage with "#define ALPHA_CUTOUT 0.01" and a fragment entry) also gets the water
	 * module (ShadeWater.prelude: SHADE_WATER and water.metal) unless mcopt.shade.water=false.
	 */
	public static String terrain(String source) {
		return terrain(source, ShadeWater.MODULE);
	}

	/** terrain(source) with the water module forced on or off (validation tools build both). */
	public static String terrain(String source, boolean water) {
		if (water && source.startsWith(TRANSLUCENT_DEFINE) && source.contains("fragment main0_out main0(")) return prelude() + ShadeWater.prelude() + source;
		return prelude() + source;
	}

	private static final String TRANSLUCENT_DEFINE = "#define ALPHA_CUTOUT 0.01";

	/** One of the sky pipelines' replacement stages (the vanilla uniform blocks come with them). */
	public static String skyStage(String file) {
		return prelude() + resource("vanilla_uniforms.metal") + "\n" + resource(file);
	}

	/** The sky-view LUT pass (compiled natively by mcshade.m). */
	public static String lut() {
		return resource("shade_look.metal") + "\n" + resource("shade_common.metal") + "\n" + resource("sky_lut.metal");
	}

	private static final String WEATHER_SIGNATURE = "fragment main0_out main0(main0_in in [[stage_in]]";
	private static final String WEATHER_RESULT = "out.fragColor = calculateFinalColor(";

	/**
	 * minecraft:pipeline/weather (fragment): vanilla's generated rain and snow shader, with its result re-lit at the end
	 * (shadeWeather in weather.metal: grey-white streaks in the light the terrain gets, through the same tone curve, instead
	 * of vanilla's saturated blue). A textual patch of the generated source, since its texture slots are numbered by the
	 * game; if the source doesn't have the expected shape, it's left as it is.
	 */
	public static String weather(String generated) {
		int at = generated.indexOf(WEATHER_RESULT);
		if (!generated.contains(WEATHER_SIGNATURE) || at < 0) return generated;
		int end = generated.indexOf(';', at) + 1;
		String patched = generated.substring(0, end) + "\n    out.fragColor = shadeWeather(out.fragColor, mcoptShadeFrame, mcoptShadeLook, mcoptShadeGpu);"
			+ generated.substring(end);
		patched = patched.replace(WEATHER_SIGNATURE, WEATHER_SIGNATURE
			+ ", constant ShadeFrame& mcoptShadeFrame [[buffer(16)]], constant ShadeLook& mcoptShadeLook [[buffer(17)]], constant ShadeGpu& mcoptShadeGpu [[buffer(18)]]");
		return prelude() + resource("weather.metal") + "\n" + patched;
	}

	private static final String FRAGMENT_ENTRY = "fragment main0_out main0(";
	private static final Pattern COLOR0 = Pattern.compile("(?:float4|half4)\\s+(\\w+)\\s*\\[\\[color\\(0\\)\\]\\]");

	/**
	 * A game fragment stage (SPIRV-Cross output) for the HDR world target: its color(0) result, display-referred sRGB as it
	 * would have stored into RGBA8, goes through shadeLdrToHdr (ShadeLook at buffer(17)) before each return, so vanilla-lit
	 * entities, particles, block outlines... land in the HDR image at the value the tone map turns back into the same colour.
	 * Stages of another shape (no color output, a void entry) are returned unchanged.
	 */
	public static String hdrFragment(String msl) {
		int sig = msl.indexOf(FRAGMENT_ENTRY);
		if (sig < 0) return msl;
		Matcher m = COLOR0.matcher(msl);
		if (!m.find() || m.start() > sig) return msl;
		String member = m.group(1);
		int open = sig + FRAGMENT_ENTRY.length() - 1, depth = 0, close = -1;
		for (int i = open; i < msl.length(); i++) {
			char c = msl.charAt(i);
			if (c == '(') depth++;
			else if (c == ')' && --depth == 0) {
				close = i;
				break;
			}
		}
		if (close < 0) return msl;
		String head = msl.substring(0, close) + ", constant ShadeLook& mcoptShadeLook [[buffer(17)]]";
		String body = msl.substring(close).replace("return out;", "{ out." + member + " = shadeLdrToHdr(out." + member + ", mcoptShadeLook); return out; }");
		return look() + head + body;
	}

	/** Whether colorFormats are an HDR world target's (one color attachment of the HDR format). */
	static boolean hdrTarget(int[] colorFormats) {
		return colorFormats.length == 1 && (colorFormats[0] == ShadeRender.RGBA16F || colorFormats[0] == ShadeRender.RG11B10F);
	}

	/** MetalShaderRegistry's format adapter for the HDR world target (see the class comment). */
	static final MetalShaderRegistry.FormatAdapter FORMAT_ADAPTER = new MetalShaderRegistry.FormatAdapter() {
		@Override
		public String vertex(String pipelineName, String msl, boolean registry, int[] colorFormats) {
			if (!hdrTarget(colorFormats)) return msl;
			// M3: the game's own world stages (entities, items, block entities, particles) take the dynamic lights in their lightmap
			return registry ? "#define MCOPT_HDR\n" + msl : mcopt.metal.shade.light.DynLightMsl.patchVertex(pipelineName, msl);
		}

		@Override
		public String fragment(String pipelineName, String msl, boolean registry, int[] colorFormats) {
			if (!hdrTarget(colorFormats)) return msl;
			return registry ? "#define MCOPT_HDR\n" + msl : hdrFragment(msl);
		}
	};

	/**
	 * Which pipelines get their HDR variant built as soon as they compile (on the frontend's compile worker), so the first
	 * world frame that uses one doesn't compile it on the render thread: everything but the GUI and full-screen game passes.
	 */
	static int @org.jspecify.annotations.Nullable [] prewarm(String name) {
		if (!ShadeConfig.HDR) return null;
		String n = name.toLowerCase(java.util.Locale.ROOT);
		if (n.contains("gui") || n.contains("panorama") || n.contains("lightmap") || n.contains("blit") || n.contains("blur") || n.contains("oit_")
			|| n.contains("animate_sprite") || n.contains("crosshair") || n.contains("distanthorizons") || n.contains("post/")) return null;
		return new int[] {ShadeConfig.HDR_FORMAT};
	}

	static void register() {
		skyVertex = skyStage("sky.vs.metal");
		skyFragment = skyStage("sky.fs.metal");
		celestialVertex = skyStage("celestial.vs.metal");
		sunriseVertex = skyStage("sunrise_sunset.vs.metal");
		starsVertex = skyStage("stars.vs.metal");
		cloudFragment = prelude() + resource("clouds.fs.metal");
		for (String name : TERRAIN) MetalShaderRegistry.register(name, new MetalShaderRegistry.Variant(ShadeMsl::terrain, ShadeMsl::terrain, List.of()));
		List<String> matrices = List.of("DynamicTransforms", "Projection");
		MetalShaderRegistry.register(SKY, new MetalShaderRegistry.Variant(generated -> skyVertex, generated -> skyFragment,
			List.of("DynamicTransforms", "Projection", "Fog")));
		MetalShaderRegistry.register(CELESTIAL, new MetalShaderRegistry.Variant(generated -> celestialVertex, null, matrices));
		MetalShaderRegistry.register(SUNRISE, new MetalShaderRegistry.Variant(generated -> sunriseVertex, null, matrices));
		for (String name : CLOUDS) MetalShaderRegistry.register(name, new MetalShaderRegistry.Variant(null, generated -> cloudFragment, List.of("CloudInfo")));
		MetalShaderRegistry.register(WEATHER, new MetalShaderRegistry.Variant(null, ShadeMsl::weather, List.of()));
		MetalShaderRegistry.register(STARS, new MetalShaderRegistry.Variant(generated -> starsVertex, null, matrices));
		if (ShadeConfig.HDR) {
			MetalShaderRegistry.setFormatAdapter(FORMAT_ADAPTER);
			MetalShaderRegistry.setPrewarm(ShadeMsl::prewarm);
		}
	}
}
