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
import mcopt.metal.MetalShaderRegistry;

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
 * </ul>
 */
public final class ShadeMsl {
	/** Must match SHADE_LUT_W / SHADE_LUT_H in shade_common.metal. */
	public static final int LUT_WIDTH = 128, LUT_HEIGHT = 80;
	public static final String SKY = "minecraft:pipeline/sky", CELESTIAL = "minecraft:pipeline/celestial", SUNRISE = "minecraft:pipeline/sunrise_sunset";
	public static final List<String> TERRAIN = List.of("sodium:pipeline/solid_terrain", "sodium:pipeline/cutout_terrain", "sodium:pipeline/translucent_terrain");

	private static volatile String prelude, skyVertex, skyFragment, celestialVertex, sunriseVertex;

	private ShadeMsl() {
	}

	public static String resource(String name) {
		try (InputStream in = ShadeMsl.class.getResourceAsStream("/mcopt/shade/" + name)) {
			return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** "#define MCOPT_SHADE" and shade_common.metal: what goes in front of every shaded stage. */
	public static String prelude() {
		if (prelude == null) prelude = "#define MCOPT_SHADE\n" + resource("shade_common.metal") + "\n";
		return prelude;
	}

	/** A Sodium terrain stage (the source MetalPipeline would compile: built-in MSL, with its defines) with shading. */
	public static String terrain(String source) {
		return prelude() + source;
	}

	/** One of the sky pipelines' replacement stages (the vanilla uniform blocks come with them). */
	public static String skyStage(String file) {
		return prelude() + resource("vanilla_uniforms.metal") + "\n" + resource(file);
	}

	/** The sky-view LUT pass (compiled natively by mcshade.m). */
	public static String lut() {
		return resource("shade_common.metal") + "\n" + resource("sky_lut.metal");
	}

	static void register() {
		skyVertex = skyStage("sky.vs.metal");
		skyFragment = skyStage("sky.fs.metal");
		celestialVertex = skyStage("celestial.vs.metal");
		sunriseVertex = skyStage("sunrise_sunset.vs.metal");
		for (String name : TERRAIN) MetalShaderRegistry.register(name, new MetalShaderRegistry.Variant(ShadeMsl::terrain, ShadeMsl::terrain, List.of()));
		List<String> matrices = List.of("DynamicTransforms", "Projection");
		MetalShaderRegistry.register(SKY, new MetalShaderRegistry.Variant(generated -> skyVertex, generated -> skyFragment,
			List.of("DynamicTransforms", "Projection", "Fog")));
		MetalShaderRegistry.register(CELESTIAL, new MetalShaderRegistry.Variant(generated -> celestialVertex, null, matrices));
		MetalShaderRegistry.register(SUNRISE, new MetalShaderRegistry.Variant(generated -> sunriseVertex, null, matrices));
	}
}
