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
import mcopt.metal.shade.weather.ShadeWeather;

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

	public static final String WEATHER = "minecraft:pipeline/weather", STARS = "minecraft:pipeline/stars", LIGHTNING = "minecraft:pipeline/lightning";
	/** M3 (mcopt.shade.dims): the End sky box (end_sky.{vs,fs}.metal). */
	public static final String END_SKY = "minecraft:pipeline/end_sky";
	public static final List<String> CLOUDS = List.of("minecraft:pipeline/clouds", "minecraft:pipeline/flat_clouds");

	private static volatile String prelude, look, skyVertex, skyFragment, celestialVertex, sunriseVertex, starsVertex, cloudFragment, endSkyVertex, endSkyFragment;

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
	 * "#define MCOPT_SHADE", shade_look.metal (M2 art layer, display transform), shade_common.metal (M1), shade_clouds.metal
	 * (M3: what shaders read of the volumetric clouds), shade_m2.metal (M2 shading), shade_dims.metal (M3 sky / dimensions),
	 * the dynamic-lights module (M3, empty when off) and shade_dynlight.metal (M3: the dynamic-lights seam): what goes in front
	 * of every shaded stage.
	 */
	public static String prelude() {
		if (prelude == null) {
			prelude = "#define MCOPT_SHADE\n" + resource("shade_look.metal") + "\n" + resource("shade_common.metal") + "\n" + resource("shade_clouds.metal") + "\n"
				+ resource("shade_m2.metal") + "\n"
				+ resource("shade_dims.metal") + "\n" // M3 sky / dimensions (skydims)
				+ mcopt.metal.shade.light.DynLightMsl.prelude() // M3 dynamic lights (empty when off)
				+ resource("shade_dynlight.metal") + "\n";
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
		// M3 weather (ShadeWeather.prelude: wet surfaces, puddles, ripples, shadow tint) goes in front of every terrain fragment,
		// before the water module (whose surface uses its ripples)
		String weather = source.contains("fragment main0_out main0(") ? ShadeWeather.prelude(source.startsWith(TRANSLUCENT_DEFINE)) : "";
		String lean = source.contains("fragment main0_out main0(") ? LEAN_TEST : "";
		if (water && source.startsWith(TRANSLUCENT_DEFINE) && source.contains("fragment main0_out main0(")) return lean + prelude() + weather + ShadeWater.prelude() + source;
		return lean + prelude() + weather + source;
	}

	private static final String TRANSLUCENT_DEFINE = "#define ALPHA_CUTOUT 0.01";
	/**
	 * M3.1 lean terrain tags (ShadeLean) on the HDR world format, bits 17-19 (the lit hand's tag is bit 16, on RGBA8): the Sodium
	 * terrain's variant for a tag compiles that code out (sodium_terrain.fs.metal SHADE_LEAN_*); other pipelines drop the bits.
	 */
	public static final int LEAN_SHIFT = mcopt.metal.MetalHooks.TAG_SHIFT + 1, LEAN_OW = 1, LEAN_DRY = 2, LEAN_NODL = 4, LEAN_MASK = 7 << LEAN_SHIFT;

	static boolean leanTerrain(String pipelineName) {
		return pipelineName.startsWith("sodium:pipeline/") && pipelineName.endsWith("_terrain");
	}

	static String leanDefines(int format) {
		int t = (format & LEAN_MASK) >>> LEAN_SHIFT;
		return ((t & LEAN_OW) != 0 ? "#define SHADE_LEAN_OW\n" : "") + ((t & LEAN_DRY) != 0 ? "#define SHADE_LEAN_DRY\n" : "")
			+ ((t & LEAN_NODL) != 0 ? "#define SHADE_LEAN_NODL\n" : "");
	}
	/** M3.1 experiment (-Dmcopt.shade.leanTest=ow,dry,nodl): those lean defines in every terrain fragment, always. */
	private static final String LEAN_TEST = leanTest(System.getProperty("mcopt.shade.leanTest", ""));

	private static String leanTest(String v) {
		StringBuilder b = new StringBuilder();
		for (String t : v.split(",")) {
			switch (t.trim()) {
				case "ow" -> b.append("#define SHADE_LEAN_OW\n");
				case "dry" -> b.append("#define SHADE_LEAN_DRY\n");
				case "nodl" -> b.append("#define SHADE_LEAN_NODL\n");
				default -> { }
			}
		}
		return b.toString();
	}

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
		boolean m3 = ShadeWeather.MODULE && !generated.contains("[[position]]");
		String call = m3 ? "shadeWeather3(out.fragColor, mcoptFragCoord, mcoptShadeFrame, mcoptShadeLook, mcoptShadeGpu, mcoptSkyLut, mcoptSkyLutSmplr)"
			: "shadeWeather(out.fragColor, mcoptShadeFrame, mcoptShadeLook, mcoptShadeGpu)";
		String patched = generated.substring(0, end) + "\n    out.fragColor = " + call + ";" + generated.substring(end);
		patched = patched.replace(WEATHER_SIGNATURE, WEATHER_SIGNATURE
			+ ", constant ShadeFrame& mcoptShadeFrame [[buffer(16)]], constant ShadeLook& mcoptShadeLook [[buffer(17)]], constant ShadeGpu& mcoptShadeGpu [[buffer(18)]]"
			+ (m3 ? ", float4 mcoptFragCoord [[position]], texture2d<half> mcoptSkyLut [[texture(17)]], sampler mcoptSkyLutSmplr [[sampler(13)]]" : ""));
		return prelude() + (m3 ? "#define SHADE_WEATHER\n" + (ShadeWeather.LITE ? "#define SHADE_WX_LITE\n" : "") : "") + resource("weather.metal") + "\n" + patched;
	}

	private static final String VERTEX_SIGNATURE = "vertex main0_out main0(main0_in in [[stage_in]]";
	private static final String VERTEX_BODY = "main0_out out = {};";

	/**
	 * minecraft:pipeline/weather (vertex, M3 weather with mcopt.shade.rainSlant): vanilla's generated stage with the column
	 * vertices leaned along the wind before anything uses them (weather.metal shadeRainSlant). Unchanged if the source doesn't
	 * have the expected shape.
	 */
	public static String weatherVertex(String generated) {
		if (!ShadeWeather.MODULE || !ShadeWeather.SLANT || !generated.contains(VERTEX_SIGNATURE) || !generated.contains(VERTEX_BODY)
			|| !generated.contains("in.Position")) return generated;
		String patched = generated.replace(VERTEX_BODY, VERTEX_BODY + "\n    in.Position = shadeRainSlant(in.Position, mcoptShadeLook);")
			.replace(VERTEX_SIGNATURE, VERTEX_SIGNATURE + ", constant ShadeLook& mcoptShadeLook [[buffer(17)]]");
		return prelude() + resource("weather.metal") + "\n" + patched;
	}

	private static final String LIGHTNING_RESULT = "out.fragColor = calculateFinalColor(";

	/**
	 * minecraft:pipeline/lightning (fragment, M3 weather): the bolt is light, not paint. In the HDR world target its colour is
	 * written as bright pre-exposed radiance (about 10x the display white), so bloom makes it glow; in RGBA8 it stays as it was.
	 */
	public static String lightning(String generated) {
		int at = generated.indexOf(LIGHTNING_RESULT);
		if (!ShadeWeather.MODULE || !ShadeWeather.LIGHTNING || !generated.contains(FRAGMENT_ENTRY) || at < 0) return generated;
		int end = generated.indexOf(';', at) + 1;
		String patched = generated.substring(0, end)
			+ "\n#ifdef MCOPT_HDR\n    out.fragColor.rgb = min(out.fragColor.rgb * float3(30.0, 33.0, 40.0), float3(6.0e4));\n#endif" + generated.substring(end);
		return patched;
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

	/** Whether colorFormats are the lit hand's tagged RGBA8 (ShadeEntity.HAND_FORMAT). */
	static boolean handTarget(int[] colorFormats) {
		return colorFormats.length == 1 && colorFormats[0] == ShadeEntity.HAND_FORMAT;
	}

	/** Whether colorFormats are an HDR world target's (one color attachment of the HDR format). */
	static boolean hdrTarget(int[] colorFormats) {
		if (colorFormats.length != 1) return false;
		int f = colorFormats[0] & ~LEAN_MASK;
		return f == ShadeRender.RGBA16F || f == ShadeRender.RG11B10F;
	}

	/** MetalShaderRegistry's format adapter for the HDR world target (see the class comment). */
	static final MetalShaderRegistry.FormatAdapter FORMAT_ADAPTER = new MetalShaderRegistry.FormatAdapter() {
		@Override
		public String vertex(String pipelineName, String msl, boolean registry, int[] colorFormats) {
			ShadeEntity.beginVariant(); // M3: this variant's fragment follows its own vertex stage (MetalPipeline.variant)
			if (handTarget(colorFormats)) { // M3: the lit first-person hand (ShadeEntity.HAND)
				String lit = registry ? null : ShadeEntity.vertex(pipelineName, msl);
				return lit != null ? lit : msl;
			}
			if (!hdrTarget(colorFormats)) return msl;
			if (registry) return "#define MCOPT_HDR\n" + msl;
			// (M3: vanilla's blob shadow is left out per piece on the CPU where real entity shadows replace it:
			// EntityCasters.dropBlob, EntityBlobShadowMixin)
			// M3: the game's own world stages (entities, items, block entities, particles) take the dynamic lights in their lightmap
			// (DynLightMsl, per vertex: the game's colour keeps them where ShadeEntity does not shade, outside the overworld and
			// in the pipelines it leaves alone: glint, text, leash...), then ShadeEntity's per-pixel lighting (overworld)
			String dyn = mcopt.metal.shade.light.DynLightMsl.patchVertex(pipelineName, msl);
			String lit = ShadeEntity.vertex(pipelineName, dyn); // M3: entities, items, moving blocks, particles lit by shade
			return lit != null ? lit : dyn;
		}

		@Override
		public String fragment(String pipelineName, String msl, boolean registry, int[] colorFormats) {
			if (handTarget(colorFormats)) {
				String lit = registry ? null : ShadeEntity.fragment(pipelineName, msl, true);
				return lit != null ? lit : msl;
			}
			if (!hdrTarget(colorFormats)) return msl;
			if (registry) return "#define MCOPT_HDR\n" + leanDefines(colorFormats[0]) + msl; // (M3.1 lean terrain tags: canonical keeps them only for the terrain)
			String lit = ShadeEntity.fragment(pipelineName, msl, false);
			return lit != null ? lit : hdrFragment(msl);
		}

		@Override
		public int[] canonical(String pipelineName, int[] colorFormats) {
			// M3.1: the lean tags only mean something to the Sodium terrain; any other pipeline keeps one HDR variant
			if (colorFormats.length == 1 && (colorFormats[0] & LEAN_MASK) != 0 && !leanTerrain(pipelineName)) return new int[] {colorFormats[0] & ~LEAN_MASK};
			return colorFormats;
		}
	};

	/**
	 * Which pipelines get their HDR variant built as soon as they compile (on the frontend's compile worker), so the first
	 * world frame that uses one doesn't compile it on the render thread: everything but the GUI and full-screen game passes.
	 */
	static int @org.jspecify.annotations.Nullable [] @org.jspecify.annotations.Nullable [] prewarm(String name) {
		if (!ShadeConfig.HDR) return null;
		String n = name.toLowerCase(java.util.Locale.ROOT);
		if (n.contains("gui") || n.contains("panorama") || n.contains("lightmap") || n.contains("blit") || n.contains("blur") || n.contains("oit_")
			|| n.contains("animate_sprite") || n.contains("crosshair") || n.contains("distanthorizons") || n.contains("post/")) return null;
		// M3: the lit hand's tagged variants too (the "Item in hand" pass: arm, held items and their glint), so its first frame
		// doesn't compile ~80 KB fragments on the render thread
		if (ShadeEntity.HAND && (ShadeEntity.PIPELINES.containsKey(name) || n.contains("glint"))) return new int[][] {{ShadeConfig.HDR_FORMAT}, {ShadeEntity.HAND_FORMAT}};
		// M3.1: the Sodium terrain's eight lean variants (ShadeLean), so a change of tag never compiles on the render thread
		if (ShadeLean.ON && leanTerrain(name)) {
			int[][] sets = new int[8][];
			for (int t = 0; t < 8; t++) sets[t] = new int[] {ShadeConfig.HDR_FORMAT | t << LEAN_SHIFT};
			return sets;
		}
		return new int[][] {{ShadeConfig.HDR_FORMAT}};
	}

	static void register() {
		skyVertex = skyStage("sky.vs.metal");
		skyFragment = skyStage("sky.fs.metal");
		celestialVertex = skyStage("celestial.vs.metal");
		sunriseVertex = skyStage("sunrise_sunset.vs.metal");
		starsVertex = skyStage("stars.vs.metal");
		cloudFragment = prelude() + resource("clouds.fs.metal");
		endSkyVertex = skyStage("end_sky.vs.metal");
		endSkyFragment = skyStage("end_sky.fs.metal");
		for (String name : TERRAIN) MetalShaderRegistry.register(name, new MetalShaderRegistry.Variant(ShadeMsl::terrain, ShadeMsl::terrain, List.of()));
		List<String> matrices = List.of("DynamicTransforms", "Projection");
		MetalShaderRegistry.register(SKY, new MetalShaderRegistry.Variant(generated -> skyVertex, generated -> skyFragment,
			List.of("DynamicTransforms", "Projection", "Fog")));
		MetalShaderRegistry.register(CELESTIAL, new MetalShaderRegistry.Variant(generated -> celestialVertex, null, matrices));
		MetalShaderRegistry.register(SUNRISE, new MetalShaderRegistry.Variant(generated -> sunriseVertex, null, matrices));
		for (String name : CLOUDS) MetalShaderRegistry.register(name, new MetalShaderRegistry.Variant(null, generated -> cloudFragment, List.of("CloudInfo")));
		MetalShaderRegistry.register(WEATHER, new MetalShaderRegistry.Variant(ShadeWeather.MODULE && ShadeWeather.SLANT ? ShadeMsl::weatherVertex : null,
			ShadeMsl::weather, List.of()));
		if (ShadeWeather.MODULE && ShadeWeather.LIGHTNING) MetalShaderRegistry.register(LIGHTNING, new MetalShaderRegistry.Variant(null, ShadeMsl::lightning, List.of()));
		MetalShaderRegistry.register(STARS, new MetalShaderRegistry.Variant(generated -> starsVertex, null, matrices));
		if (mcopt.metal.shade.dims.DimsConfig.DIMS) {
			MetalShaderRegistry.register(END_SKY, new MetalShaderRegistry.Variant(generated -> endSkyVertex, generated -> endSkyFragment,
				List.of("DynamicTransforms", "Projection", "Sampler0")));
		}
		if (ShadeConfig.HDR) {
			MetalShaderRegistry.setFormatAdapter(FORMAT_ADAPTER);
			MetalShaderRegistry.setPrewarm(ShadeMsl::prewarm);
		}
	}
}
