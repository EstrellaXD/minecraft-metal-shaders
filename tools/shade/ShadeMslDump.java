// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading, not part of the mod.
import java.nio.file.*;
import java.util.*;
import mcopt.metal.MetalShaderRegistry;
import mcopt.metal.shade.ShadeMsl;

/** Writes every shaded MSL variant exactly as MetalPipeline assembles it at runtime (plus the upstream ones), for mslcheck/pipecheck. */
public class ShadeMslDump {
	static String msl(String name) throws Exception {
		return new String(ShadeMslDump.class.getResourceAsStream("/mcopt/metal/msl/" + name).readAllBytes());
	}
	public static void main(String[] a) throws Exception {
		Path out = Path.of(a[0]);
		Files.createDirectories(out);
		String slotsT = MetalShaderRegistry.slotDefines(List.of("u_Globals", "u_SectionTimeInfo", "u_LightTex", "u_BlockTex"));
		String vs = msl("sodium_terrain.vs.metal"), fs = msl("sodium_terrain.fs.metal");
		Map<String, String> defs = new LinkedHashMap<>();
		defs.put("solid", ""); defs.put("cutout", "#define ALPHA_CUTOUT 0.5\n"); defs.put("translucent", "#define ALPHA_CUTOUT 0.01\n");
		for (var e : defs.entrySet()) {
			// upstream (flag off)
			Files.writeString(out.resolve("up_" + e.getKey() + ".vs.metal"), vs);
			Files.writeString(out.resolve("up_" + e.getKey() + ".fs.metal"), e.getValue() + fs);
			// shaded
			Files.writeString(out.resolve("shade_" + e.getKey() + ".vs.metal"), slotsT + ShadeMsl.terrain(vs));
			Files.writeString(out.resolve("shade_" + e.getKey() + ".fs.metal"), slotsT + ShadeMsl.terrain(e.getValue() + fs));
			if (!e.getKey().equals("translucent")) {
				Files.writeString(out.resolve("up_" + e.getKey() + ".pulled.vs.metal"), "#define PULLED\n" + vs);
				Files.writeString(out.resolve("shade_" + e.getKey() + ".pulled.vs.metal"), slotsT + ShadeMsl.terrain("#define PULLED\n" + vs));
			}
		}
		// sky pipelines, with two different uniform orders (renderpearl numbers them by use)
		List<List<String>> orders = List.of(List.of("Fog", "DynamicTransforms", "Projection"), List.of("Projection", "DynamicTransforms", "Fog"));
		for (int i = 0; i < orders.size(); i++) {
			String s = MetalShaderRegistry.slotDefines(orders.get(i));
			Files.writeString(out.resolve("sky" + i + ".vs.metal"), s + ShadeMsl.skyStage("sky.vs.metal"));
			Files.writeString(out.resolve("sky" + i + ".fs.metal"), s + ShadeMsl.skyStage("sky.fs.metal"));
		}
		String s2 = MetalShaderRegistry.slotDefines(List.of("Projection", "DynamicTransforms", "Sampler0"));
		Files.writeString(out.resolve("celestial.vs.metal"), s2 + ShadeMsl.skyStage("celestial.vs.metal"));
		String s3 = MetalShaderRegistry.slotDefines(List.of("DynamicTransforms", "Projection"));
		Files.writeString(out.resolve("sunrise.vs.metal"), s3 + ShadeMsl.skyStage("sunrise_sunset.vs.metal"));
		Files.writeString(out.resolve("sky_lut.metal"), ShadeMsl.lut());
	}
}
