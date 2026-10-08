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
package mcopt.metal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * In-jar MSL registry, keyed by pipeline name (e.g. "minecraft:pipeline/sky", "sodium:pipeline/solid_terrain"): what
 * MetalPipeline.compile uses instead of (or on top of) the SPIR-V translation or the built-in Sodium MSL. Empty unless
 * something registers into it (the native shading package does, only with -Dmcopt.shade=true), so with nothing
 * registered every pipeline compiles exactly as before. -Dmcopt.metal.overrideMsl stays a dev overlay applied after it.
 *
 * Deliberately free of game and renderpearl types, so it can be filled from a mixin config plugin before the game's
 * classes load.
 */
public final class MetalShaderRegistry {
	/**
	 * How a pipeline's stages are replaced. Each stage function receives the source the backend would otherwise compile
	 * (SPIRV-Cross output, the built-in Sodium MSL, or the pulled terrain twin with its "#define PULLED") and returns the
	 * source to compile; null keeps that stage as it is. Every replaced stage gets "#define MCOPT_SLOT_<uniform> <slot>"
	 * lines for the pipeline's uniforms prepended (renderpearl numbers a pipeline's uniforms in the order its shaders use
	 * them, so hand-written MSL must not hard-code slots). required: uniform names the replacement reads; if the pipeline
	 * lacks any of them the variant is skipped (logged once) and the pipeline compiles as without the registry.
	 * vertexEntry/fragmentEntry: the entry points of the replaced stages (SPIRV-Cross names "main" as "main0").
	 */
	public record Variant(@Nullable UnaryOperator<String> vertex, @Nullable UnaryOperator<String> fragment, List<String> required,
		String vertexEntry, String fragmentEntry) {
		public Variant(@Nullable UnaryOperator<String> vertex, @Nullable UnaryOperator<String> fragment, List<String> required) {
			this(vertex, fragment, required, "main0", "main0");
		}
	}

	private static final Map<String, Variant> VARIANTS = new ConcurrentHashMap<>();

	/**
	 * Rewrites a pipeline's final MSL for a variant with other color formats (MetalPipeline.variant): e.g. native shading
	 * turns its own stages to HDR output and converts the game's display-referred colours. registry: whether that stage
	 * came from this registry (or is the pulled terrain twin). Returns the source to compile.
	 */
	public interface FormatAdapter {
		String vertex(String pipelineName, String msl, boolean registry, int[] colorFormats);

		String fragment(String pipelineName, String msl, boolean registry, int[] colorFormats);

		/**
		 * The formats a pipeline's variant is keyed and built by (default: as given): lets an adapter drop tag bits a pipeline
		 * does not use, so one tagged pass does not build duplicate variants of every other pipeline drawn in it.
		 */
		default int[] canonical(String pipelineName, int[] colorFormats) {
			return colorFormats;
		}
	}

	private static volatile @Nullable FormatAdapter adapter;
	private static volatile java.util.function.@Nullable Function<String, int @Nullable [] @Nullable []> prewarm;

	public static void setFormatAdapter(@Nullable FormatAdapter a) {
		adapter = a;
	}

	static @Nullable FormatAdapter formatAdapter() {
		return adapter;
	}

	/** Which format variants to build as soon as a pipeline compiles (pipeline name -> one formats array per variant, or null for none). */
	public static void setPrewarm(java.util.function.@Nullable Function<String, int @Nullable [] @Nullable []> p) {
		prewarm = p;
	}

	static int @Nullable [] @Nullable [] prewarmFormats(String pipelineName) {
		var p = prewarm;
		return p == null ? null : p.apply(pipelineName);
	}

	private MetalShaderRegistry() {
	}

	/** Replaces any earlier registration for pipelineName. Pipelines already compiled keep their code until the next resource reload (F3+T). */
	public static void register(String pipelineName, Variant variant) {
		VARIANTS.put(pipelineName, variant);
	}

	public static void unregister(String pipelineName) {
		VARIANTS.remove(pipelineName);
	}

	static @Nullable Variant get(String pipelineName) {
		return VARIANTS.isEmpty() ? null : VARIANTS.get(pipelineName);
	}

	/** "#define MCOPT_SLOT_<name> <i>" for each uniform name in slot order (names sanitized to identifiers). */
	public static String slotDefines(List<String> uniformNames) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < uniformNames.size(); i++) {
			b.append("#define MCOPT_SLOT_").append(uniformNames.get(i).replaceAll("[^A-Za-z0-9_]", "_")).append(' ').append(i).append('\n');
		}
		return b.toString();
	}
}
