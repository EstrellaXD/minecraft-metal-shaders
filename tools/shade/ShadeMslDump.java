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
			// M2 HDR variants (what MetalPipeline.variant builds through ShadeMsl's format adapter)
			Files.writeString(out.resolve("hdr_" + e.getKey() + ".vs.metal"), "#define MCOPT_HDR\n" + slotsT + ShadeMsl.terrain(vs));
			Files.writeString(out.resolve("hdr_" + e.getKey() + ".fs.metal"), "#define MCOPT_HDR\n" + slotsT + ShadeMsl.terrain(e.getValue() + fs));
			if (e.getKey().equals("translucent")) {
				// the translucent fragment without the water module (mcopt.shade.water=false: M1's water)
				Files.writeString(out.resolve("shade_translucentnowater.vs.metal"), slotsT + ShadeMsl.terrain(vs));
				Files.writeString(out.resolve("shade_translucentnowater.fs.metal"), slotsT + ShadeMsl.terrain(e.getValue() + fs, false));
				Files.writeString(out.resolve("hdr_translucentnowater.vs.metal"), "#define MCOPT_HDR\n" + slotsT + ShadeMsl.terrain(vs));
				Files.writeString(out.resolve("hdr_translucentnowater.fs.metal"), "#define MCOPT_HDR\n" + slotsT + ShadeMsl.terrain(e.getValue() + fs, false));
			}
			if (!e.getKey().equals("translucent")) {
				Files.writeString(out.resolve("up_" + e.getKey() + ".pulled.vs.metal"), "#define PULLED\n" + vs);
				Files.writeString(out.resolve("shade_" + e.getKey() + ".pulled.vs.metal"), slotsT + ShadeMsl.terrain("#define PULLED\n" + vs));
				Files.writeString(out.resolve("hdr_" + e.getKey() + ".pulled.vs.metal"), "#define MCOPT_HDR\n" + slotsT + ShadeMsl.terrain("#define PULLED\n" + vs));
			}
		}
		// sky pipelines, with two different uniform orders (renderpearl numbers them by use)
		List<List<String>> orders = List.of(List.of("Fog", "DynamicTransforms", "Projection"), List.of("Projection", "DynamicTransforms", "Fog"));
		for (int i = 0; i < orders.size(); i++) {
			String s = MetalShaderRegistry.slotDefines(orders.get(i));
			Files.writeString(out.resolve("sky" + i + ".vs.metal"), s + ShadeMsl.skyStage("sky.vs.metal"));
			Files.writeString(out.resolve("sky" + i + ".fs.metal"), s + ShadeMsl.skyStage("sky.fs.metal"));
			Files.writeString(out.resolve("hdrsky" + i + ".vs.metal"), "#define MCOPT_HDR\n" + s + ShadeMsl.skyStage("sky.vs.metal"));
			Files.writeString(out.resolve("hdrsky" + i + ".fs.metal"), "#define MCOPT_HDR\n" + s + ShadeMsl.skyStage("sky.fs.metal"));
		}
		String s2 = MetalShaderRegistry.slotDefines(List.of("Projection", "DynamicTransforms", "Sampler0"));
		Files.writeString(out.resolve("celestial.vs.metal"), s2 + ShadeMsl.skyStage("celestial.vs.metal"));
		String s3 = MetalShaderRegistry.slotDefines(List.of("DynamicTransforms", "Projection"));
		Files.writeString(out.resolve("sunrise.vs.metal"), s3 + ShadeMsl.skyStage("sunrise_sunset.vs.metal"));
		Files.writeString(out.resolve("stars.vs.metal"), s3 + ShadeMsl.skyStage("stars.vs.metal"));
		Files.writeString(out.resolve("sky_lut.metal"), ShadeMsl.lut());
		// M2 post chain (one library: bloom, final, bridge, exposure kernels, Milky Way bake)
		Files.writeString(out.resolve("post.metal"), ShadeMsl.post());
		// the water module's own passes (wave bake, SSR coarse depth): ShadeWater.library
		Files.writeString(out.resolve("water_passes.metal"), ShadeMsl.post() + mcopt.metal.shade.water.ShadeWater.prelude());
		Files.writeString(out.resolve("vl.metal"), mcopt.metal.shade.vl.VlConfig.source()); // volumetric light (mcvl.m)
		// clouds: own fragment (CloudInfo slot from the defines); vanilla's generated vertex stage comes from a dump if given
		String s4 = MetalShaderRegistry.slotDefines(List.of("CloudInfo", "Projection", "DynamicTransforms", "Fog"));
		Files.writeString(out.resolve("clouds.fs.metal"), s4 + ShadeMsl.prelude() + ShadeMsl.resource("clouds.fs.metal"));
		Files.writeString(out.resolve("hdrclouds.fs.metal"), "#define MCOPT_HDR\n" + s4 + ShadeMsl.prelude() + ShadeMsl.resource("clouds.fs.metal"));
		// weather: patched generated fragment, from a -Dmcopt.metal.dumpMsl directory (MCOPT_GEN_DIR) when available
		String gen = System.getenv("MCOPT_GEN_DIR");
		if (gen != null) {
			java.nio.file.Path w = java.nio.file.Path.of(gen, "minecraft_pipeline_weather.fs.metal");
			if (Files.exists(w)) {
				Files.writeString(out.resolve("weather.fs.metal"), ShadeMsl.weather(Files.readString(w)));
				Files.writeString(out.resolve("hdrweather.fs.metal"), "#define MCOPT_HDR\n" + ShadeMsl.weather(Files.readString(w)));
			}
			// M3 entity lighting: the HDR variants of the game's entity / item / block / particle stages, patched (ShadeEntity)
			Path ent = out.resolve("entity");
			Files.createDirectories(ent);
			for (String name : mcopt.metal.shade.ShadeEntityAccess.pipelines()) {
				String file = name.replaceAll("[^A-Za-z0-9._-]", "_");
				Path evs = Path.of(gen, file + ".vs.metal"), efs = Path.of(gen, file + ".fs.metal");
				if (!Files.exists(evs) || !Files.exists(efs)) continue;
				String v = mcopt.metal.shade.ShadeEntityAccess.vertex(name, Files.readString(evs));
				String f = mcopt.metal.shade.ShadeEntityAccess.fragment(name, Files.readString(efs));
				if (v == null || f == null) {
					System.out.println("entity lighting: not patched: " + name);
					continue;
				}
				Files.writeString(ent.resolve(file + ".vs.metal"), v);
				Files.writeString(ent.resolve(file + ".fs.metal"), f);
			}
			// every generated game fragment, converted for the HDR world target (ShadeMsl.hdrFragment)
			Path hdrGen = out.resolve("hdrgen");
			Files.createDirectories(hdrGen);
			try (var files = Files.list(Path.of(gen))) {
				for (Path f : files.filter(x -> x.getFileName().toString().endsWith(".fs.metal")).toList()) {
					Files.writeString(hdrGen.resolve(f.getFileName()), ShadeMsl.hdrFragment(Files.readString(f)));
				}
			}
		}
	}
}
