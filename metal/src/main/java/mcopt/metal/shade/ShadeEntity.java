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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * M3 (bible E17): the game's entity, item, moving-block and particle pipelines lit by the shade lighting model in the HDR
 * world target. Their HDR variants (MetalPipeline.variant through ShadeMsl's format adapter) get the game's own SPIRV-Cross
 * stages patched (shade_entity.metal has the shading):
 * <ul>
 * <li>vertex: four more outputs (locn 12-15): camera-relative world position (the expression the game projects), world
 * normal (0 without a Normal attribute), the raw vertex colour (no cardinal light, no lightmap) and the lightmap
 * coordinates / 240;</li>
 * <li>fragment: the same four inputs, the shade globals (buffers 16-19, shadow map and sky LUT at textures 16/17) and
 * {@code [[position]]} / {@code [[front_facing]]} appended to the entry, and before its return the colour replaced by
 * shadeEntityShade (the game's alpha, cutout discard and dissolve are kept).</li>
 * </ul>
 * Only pipelines named in {@link #PIPELINES} whose stages have the expected shape are patched; a fragment is patched only
 * if the same variant's vertex stage was (MetalPipeline.variant adapts the vertex first, on the same thread), so the stage
 * interfaces always match. Everything else keeps the M2 path (ShadeMsl.hdrFragment: vanilla lighting converted to scene-linear). Glint,
 * emissive (eyes, energy swirl), text, lines and the vanilla blob shadow are left alone. mcopt.shade.entities=false turns it
 * off.
 */
public final class ShadeEntity {
	/** mcopt.shade.entities (default true; needs the HDR target). */
	public static final boolean ON = ShadeConfig.HDR && ShadeConfig.flag("mcopt.shade.entities", true);

	/**
	 * mcopt.shade.handLight (default true): the first-person hand and held items lit the same way (ShadePost redirects the
	 * "Item in hand" pass through variants tagged {@link #HAND_FORMAT}: RGBA8 like the main target, shaded in HDR and
	 * tone-mapped in the fragment, no fog).
	 */
	public static final boolean HAND = ON && ShadeConfig.flag("mcopt.shade.handLight", true);
	/** The hand's redirect format: RGBA8Unorm with variant tag 1 (MetalHooks.TAG_SHIFT). */
	public static final int HAND_FORMAT = ShadeRender.RGBA8 | (1 << mcopt.metal.MetalHooks.TAG_SHIFT);

	/** Patched pipelines: kind 0 entity (vertex normals), 1 moving block (flat normal), 2 particle. */
	static final Map<String, Integer> PIPELINES = Map.ofEntries(
		Map.entry("minecraft:pipeline/entity_solid", 0), Map.entry("minecraft:pipeline/entity_solid_offset_forward", 0),
		Map.entry("minecraft:pipeline/entity_cutout", 0), Map.entry("minecraft:pipeline/entity_cutout_cull", 0),
		Map.entry("minecraft:pipeline/entity_cutout_z_offset", 0), Map.entry("minecraft:pipeline/entity_cutout_dissolve", 0),
		Map.entry("minecraft:pipeline/entity_translucent", 0), Map.entry("minecraft:pipeline/entity_translucent_cull", 0),
		Map.entry("minecraft:pipeline/armor_cutout_no_cull", 0), Map.entry("minecraft:pipeline/armor_decal_cutout_no_cull", 0),
		Map.entry("minecraft:pipeline/armor_translucent", 0), Map.entry("minecraft:pipeline/banner_pattern", 0),
		Map.entry("minecraft:pipeline/item_cutout", 0), Map.entry("minecraft:pipeline/item_translucent", 0),
		Map.entry("minecraft:pipeline/solid_block", 1), Map.entry("minecraft:pipeline/cutout_block", 1), Map.entry("minecraft:pipeline/translucent_block", 1),
		Map.entry("minecraft:pipeline/opaque_particle", 2), Map.entry("minecraft:pipeline/translucent_particle", 2));

	/**
	 * Whether the variant being built on this thread had its vertex stage patched (MetalPipeline.variant adapts the vertex,
	 * then the fragment, on one thread): the fragment is patched only then. Per variant, not per pipeline name: MC 26.3 has
	 * three pipelines named item_cutout (plain, glint, glint special), and a resource pack can change one stage.
	 */
	private static final ThreadLocal<boolean[]> VERTEX_LIT = ThreadLocal.withInitial(() -> new boolean[1]);
	private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
	private static volatile @Nullable String library;

	private ShadeEntity() {
	}

	/** The shading library prepended to patched fragments: the shade prelude + shade_entity.metal. */
	static String library() {
		String l = library;
		if (l == null) library = l = ShadeMsl.prelude() + ShadeMsl.resource("shade_entity.metal") + "\n";
		return l;
	}

	private static final Pattern PROJECT = Pattern.compile("out\\.gl_Position = \\(_\\w+\\.ProjMat \\* _\\w+\\.ModelViewMat\\) \\* float4\\(([\\w.]+(?: \\+ _\\w+\\.ModelOffset)?), 1\\.0\\);");
	private static final String FLIP = "out.gl_Position.y = -(out.gl_Position.y);";
	private static final String VERTEX_ENTRY = "vertex main0_out main0(";
	private static final String FRAGMENT_ENTRY = "fragment main0_out main0(";
	private static final String OUT_MEMBERS = "    float3 mcoptPos [[user(locn12)]];\n    float3 mcoptNrm [[user(locn13)]];\n    float4 mcoptColor [[user(locn14)]];\n"
		+ "    float2 mcoptLight [[user(locn15)]];\n";

	private static @Nullable String struct(String msl, String name) {
		int a = msl.indexOf("struct " + name + "\n{");
		if (a < 0) return null;
		int b = msl.indexOf("};", a);
		return b < 0 ? null : msl.substring(a, b);
	}

	private static void log(String name, String why) {
		if (LOGGED.add(name + why)) System.out.println("[mcopt] shade: entity lighting skips " + name + " (" + why + ")");
	}

	/** The HDR variant's vertex stage for pipelineName, patched, or null to keep msl as it is. */
	static @Nullable String vertex(String pipelineName, String msl) {
		if (!ON) return null;
		String v = vertexPatch(pipelineName, msl);
		VERTEX_LIT.get()[0] = v != null;
		return v;
	}

	/** A new variant starts (ShadeMsl.FORMAT_ADAPTER.vertex): no vertex stage patched yet. */
	static void beginVariant() {
		VERTEX_LIT.get()[0] = false;
	}

	static @Nullable String vertexPatch(String pipelineName, String msl) {
		if (!PIPELINES.containsKey(pipelineName)) return null;
		String in = struct(msl, "main0_in"), out = struct(msl, "main0_out");
		Matcher m = PROJECT.matcher(msl);
		if (in == null || out == null || !m.find() || !msl.contains(FLIP) || !msl.contains(VERTEX_ENTRY) || !out.contains("float4 gl_Position [[position]];")) {
			log(pipelineName, "vertex shape");
			return null;
		}
		if (!in.contains(" Color [[attribute(") || !in.contains(" UV2 [[attribute(")) {
			log(pipelineName, "no Color / UV2 attribute");
			return null;
		}
		String pos = m.group(1); // in.Position, pos or in.Position + _N.ModelOffset
		String nrm = in.contains(" Normal [[attribute(") ? "in.Normal" : "float3(0.0)";
		String patched = msl.replaceFirst(Pattern.quote("    float4 gl_Position [[position]];"), Matcher.quoteReplacement(OUT_MEMBERS + "    float4 gl_Position [[position]];"));
		patched = patched.replace(FLIP, "out.mcoptPos = " + pos + ";\n    out.mcoptNrm = " + nrm + ";\n    out.mcoptColor = in.Color;\n"
			+ "    out.mcoptLight = float2(in.UV2) * (1.0 / 240.0);\n    " + FLIP);
		return patched;
	}

	private static final Pattern DT_PARAM = Pattern.compile("constant DynamicTransforms& (\\w+) \\[\\[buffer\\(\\d+\\)\\]\\]");
	private static final Pattern FOG_PARAM = Pattern.compile("constant Fog& (\\w+) \\[\\[buffer\\(\\d+\\)\\]\\]");
	private static final Pattern FRONT = Pattern.compile("bool (\\w+) \\[\\[front_facing\\]\\]");
	private static final Pattern FRAG_COLOR = Pattern.compile("float4 (\\w+) \\[\\[color\\(0\\)\\]\\]");

	/** The HDR variant's fragment stage, patched, or null to take the M2 path (ShadeMsl.hdrFragment). */
	static @Nullable String fragment(String pipelineName, String msl, boolean hand) {
		boolean[] lit = VERTEX_LIT.get();
		if (!ON || !lit[0]) return null;
		lit[0] = false;
		return fragmentPatch(pipelineName, msl, hand);
	}

	static @Nullable String fragmentPatch(String pipelineName, String msl) {
		return fragmentPatch(pipelineName, msl, false);
	}

	/** hand: the first-person hand's variant (display-referred output into RGBA8, no fog). */
	static @Nullable String fragmentPatch(String pipelineName, String msl, boolean hand) {
		if (!PIPELINES.containsKey(pipelineName)) return null;
		String in = struct(msl, "main0_in"), outS = struct(msl, "main0_out");
		Matcher dt = DT_PARAM.matcher(msl), fog = FOG_PARAM.matcher(msl), color = FRAG_COLOR.matcher(outS == null ? "" : outS);
		int sig = msl.indexOf(FRAGMENT_ENTRY);
		if (in == null || sig < 0 || !color.find() || !msl.contains("texture2d<float> Sampler0 [[texture(") || !in.contains(" texCoord0 [[user(")
			|| msl.contains("GlintSampler") || msl.contains("[[position]]")) {
			log(pipelineName, "fragment shape");
			return null;
		}
		boolean hasDt = dt.find(sig), hasFog = fog.find(sig);
		int open = sig + FRAGMENT_ENTRY.length() - 1, depth = 0, close = -1;
		for (int i = open; i < msl.length(); i++) {
			char c = msl.charAt(i);
			if (c == '(') depth++;
			else if (c == ')' && --depth == 0) {
				close = i;
				break;
			}
		}
		int ret = msl.lastIndexOf("return out;");
		if (close < 0 || ret < close) {
			log(pipelineName, "fragment entry");
			return null;
		}
		String member = color.group(1);
		Matcher fr = FRONT.matcher(msl.substring(sig, close));
		String front = fr.find() ? fr.group(1) : "mcoptFront";
		StringBuilder params = new StringBuilder();
		params.append(", constant ShadeFrame& mcoptSF [[buffer(16)]], constant ShadeLook& mcoptSL [[buffer(17)]], constant ShadeGpu& mcoptSG [[buffer(18)]]")
			.append(", constant ShadeResources& mcoptSR [[buffer(19)]], depth2d_array<float> mcoptShadow [[texture(16)]], sampler mcoptShadowCmp [[sampler(12)]]")
			.append(", texture2d<half> mcoptLut [[texture(17)]], sampler mcoptLutSmplr [[sampler(13)]], float4 mcoptFrag [[position]]");
		if (front.equals("mcoptFront")) params.append(", bool mcoptFront [[front_facing]]");
		int kind = hand ? 3 : PIPELINES.get(pipelineName);
		String overlay = in.contains(" overlayColor [[user(") ? "in.overlayColor" : "float4(0.0, 0.0, 0.0, 1.0)";
		String modulator = hasDt ? dt.group(1) + ".ColorModulator" : "float4(1.0)";
		String fogV = hasFog ? "float4(" + fog.group(1) + ".FogEnvironmentalStart, " + fog.group(1) + ".FogEnvironmentalEnd, " + fog.group(1)
			+ ".FogRenderDistanceStart, " + fog.group(1) + ".FogRenderDistanceEnd)" : "float4(1e6, 1e6, 1e6, 1e6)";
		String shade = "out." + member + " = shadeEntityShade(out." + member + ", Sampler0.sample(Sampler0Smplr, in.texCoord0) * in.mcoptColor * " + modulator + ", "
			+ overlay + ", in.mcoptPos, in.mcoptNrm, in.mcoptLight, " + front + ", mcoptFrag, " + kind + ", " + fogV
			+ ", mcoptLut, mcoptLutSmplr, mcoptShadow, mcoptShadowCmp, mcoptSF, mcoptSL, mcoptSG, mcoptSR);\n    ";
		String patched = msl.substring(0, close) + params + msl.substring(close, ret) + shade + msl.substring(ret);
		// the four extra inputs, at the end of main0_in
		int inAt = patched.indexOf("struct main0_in\n{");
		int inEnd = patched.indexOf("};", inAt);
		patched = patched.substring(0, inEnd) + OUT_MEMBERS + patched.substring(inEnd);
		return "#define MCOPT_HDR\n" + (hand ? "#define MCOPT_HAND\n" : "") + library() + patched;
	}

}
