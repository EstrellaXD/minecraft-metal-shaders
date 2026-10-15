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
package mcopt.metal.shade.dh;

import java.lang.reflect.Method;
import java.util.List;
import mcopt.metal.MetalShaderRegistry;
import mcopt.metal.shade.ShadeMsl;

/**
 * FT1: Distant Horizons under the shade frame.
 * <ul>
 * <li><b>Its LODs lit by the shade model</b> ({@link #LIGHT}, mcopt.shade.dh.light, default true): our own stages for its
 * two terrain pipelines (dh_terrain.{vs,fs}.metal) through MetalShaderRegistry, so the LODs get the sun / moon, sky and
 * torch light, cloud shadows and aerial perspective of the near terrain instead of DH's lightmap look and grey fog.</li>
 * <li><b>Its own screen effects off</b> while the shade frame runs: SSAO (two full-screen passes), TAA (a full-screen pass
 * plus a history copy) cost ~2.5 ms on the M4 at Retina
 * with DH at 40 chunks (run ft1-1: 54 -> 62 fps); our frame has its own AA (TAAU). With our LOD lighting also DH's fog
 * (ours replaces it) and its baked face shading (lodShading; ours lights the faces by N.L).</li>
 * </ul>
 * The settings go through DH's config API overrides (values DH holds in memory only: its DistantHorizons.toml and its settings
 * screen keep the player's choice); they are cleared when the shade frame stops (fallback, shading off), which gives DH its
 * own settings back. Live flags, read every frame (the bench's in-session A/B sets them): {@code mcopt.shade.dh.ssao},
 * {@code .aa}, {@code .fog}, {@code .shading} = "off" (default) or "dh" (DH's own setting); {@code mcopt.shade.dh.fade} =
 * "dh" (default: run ft1-1 priced DH's SINGLE_PASS fade at ~0 ms), "NONE", "SINGLE_PASS" or "DOUBLE_PASS". Reflection on DH's internal config entries
 * (Config.Client.Advanced.Graphics; DH 3.3.3-3.3.4, the versions the Metal path is gated to): no compile-time dependency on
 * DH (LGPL, shipped unmodified). Any failure turns the overrides off for the session and leaves DH's settings alone.
 */
public final class ShadeDh {
	public static final boolean LOADED = mcopt.metal.DhCompat.loaded();
	/** Our stages for DH's LOD terrain (registered by {@link #register}). */
	public static final boolean LIGHT = LOADED && !"false".equals(System.getProperty("mcopt.shade.dh.light"));
	public static final List<String> PIPELINES = List.of("distanthorizons:opaque_terrain", "distanthorizons:transparent_terrain");
	private static final String CFG = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics";
	private static final String USER = "mcopt-shade";

	/** One overridable DH setting: its ConfigEntry, what was last applied (null = no override). */
	private static final class Entry {
		final String cls, field;
		Object entry, applied;

		Entry(String cls, String field) {
			this.cls = cls;
			this.field = field;
		}
	}

	private static final Entry SSAO = new Entry(CFG, "enableSsao"), AA = new Entry(CFG, "enableAntiAliasing"),
		FADE = new Entry(CFG + "$Quality", "vanillaFadeMode"), FOG = new Entry(CFG + "$Fog", "enableDhFog"),
		SHADING = new Entry(CFG + "$Quality", "lodShading"), OVERDRAW = new Entry(CFG + "$Culling", "overdrawPrevention"),
		// (1.2.2) the LODs' water through DH's translucent pipeline (our water stage, SHADE_DH_TRANSLUCENT) and the presets
		TRANSPARENCY = new Entry(CFG + "$Quality", "transparency"), RADIUS = new Entry(CFG + "$Quality", "lodChunkRenderDistanceRadius"),
		HQUALITY = new Entry(CFG + "$Quality", "horizontalQuality"), VQUALITY = new Entry(CFG + "$Quality", "verticalQuality"),
		MAXRES = new Entry(CFG + "$Quality", "maxHorizontalResolution");
	private static final Entry[] ALL = {SSAO, AA, FADE, FOG, SHADING, OVERDRAW, TRANSPARENCY, RADIUS, HQUALITY, VQUALITY, MAXRES};
	/**
	 * (1.2.2, Gael 2026-10-15: "presets de DH para que la gente no tenga que estar 30 minutos experimentando") Shaders... >
	 * Performance > Distant Horizons (mcopt.shade.dh.preset, live): "dh" = DH's own quality settings, else LOD distance (chunks),
	 * horizontal quality, vertical quality and the finest horizontal LOD resolution, held as API overrides like the others
	 * (DH's own file and screen keep the player's values). Values chosen on the M4 with the shaders (notes/v122-2026-10-15).
	 */
	private static final String[][] PRESETS = {
		{"performance", "32", "LOW", "LOW", "TWO_BLOCKS"},
		{"balanced", "64", "MEDIUM", "MEDIUM", "BLOCK"},
		{"quality", "96", "HIGH", "MEDIUM", "BLOCK"},
		{"ultra", "128", "HIGH", "HIGH", "BLOCK"}};
	/** PRESETS resolved to DH's value objects (the same instances every frame: apply() compares by identity). */
	private static final java.util.Map<String, Object[]> PRESET_VALUES = new java.util.HashMap<>();
	private static Object transparencyComplete;
	private static Method setApi, getTrue;
	private static boolean warnedFade;
	private static Class<?> fadeEnum, shadingEnum;
	private static boolean failed, resolved, logged;
	/**
	 * FT2 (2026-10-08 night; mcopt.shade.dh.direct, default true): DH's composite goes straight into the HDR world target
	 * instead of through ShadePost's bridge (the HDR image tone-mapped into main before DH's first pass and decoded back after
	 * its last: two full-screen passes, 0.7 ms at 0.80 scale, profdh1). Our stages for DH's far fade and copy
	 * (dh_far_fade.fs, dh_copy.fs): the far fade leaves the LODs' coverage in alpha instead of mixing them over main, and DH's
	 * last copy, redirected into the HDR target, composites by it there. Live: mcopt.shade.dh.direct=false (read every frame)
	 * goes back to the bridge, as does anything that keeps the HDR frame from running.
	 */
	public static final boolean DIRECT = LIGHT && !"false".equals(System.getProperty("mcopt.shade.dh.direct"));
	private static volatile boolean directFrame;

	/** FT2: this frame DH composites straight into the HDR target (ShadePost: no bridge; ShadeLook.waterShadow.y = 1). */
	public static boolean directFrame() {
		return directFrame;
	}

	/** Once per frame when ShadeLook is packed: whether this frame composites DH directly (1) or through the bridge (0). */
	public static float directFlag(boolean hdrFrame) {
		directFrame = DIRECT && hdrFrame && !"false".equals(System.getProperty("mcopt.shade.dh.direct"));
		return directFrame ? 1f : 0f;
	}

	private ShadeDh() {
	}

	/** ShadeMsl.register (HDR shading on): our stages for DH's two terrain pipelines. */
	public static void register() {
		if (!LIGHT) return;
		String defines = (Boolean.getBoolean("mcopt.shade.dh.jitter") ? "#define SHADE_DH_JITTER\n" : "") // (audit idx 109: doubles the jitter, DH already copies the jittered projection; dev only, off)
			+ (Boolean.getBoolean("mcopt.shade.dh.skyHigh") ? "#define SHADE_DH_SKY_HIGH\n" : "")
			+ (Boolean.getBoolean("mcopt.shade.dh.noclip") ? "#define SHADE_DH_NOCLIP\n" : ""); // (dev, FT2: the cost of the discard)
		String vertex = ShadeMsl.prelude() + defines + ShadeMsl.resource("dh_terrain.vs.metal");
		for (String name : PIPELINES) {
			// FT2.5: the translucent pipeline's water mirrors the sky (mcopt.shade.dh.water=false: lit as terrain, FT2's look)
			boolean water = name.endsWith("transparent_terrain") && !"false".equals(System.getProperty("mcopt.shade.dh.water"));
			String fragment = ShadeMsl.prelude() + defines + (water ? "#define SHADE_DH_TRANSLUCENT\n" : "") + ShadeMsl.resource("dh_terrain.fs.metal");
			MetalShaderRegistry.register(name, new MetalShaderRegistry.Variant(generated -> vertex, generated -> fragment,
				List.of("vertSharedUniformBlock", "vertUniqueUniformBlock", "fragUniformBlock", "uBlockAtlas", "uLightMap")));
		}
		if (DIRECT) { // FT2: the far fade and the copy, fragment stages only (DH's full-screen vertex stage stays)
			String fade = ShadeMsl.prelude() + ShadeMsl.resource("dh_far_fade.fs.metal");
			String copy = ShadeMsl.prelude() + ShadeMsl.resource("dh_copy.fs.metal");
			MetalShaderRegistry.register("distanthorizons:far_fade", new MetalShaderRegistry.Variant(null, generated -> fade,
				List.of("fragUniformBlock", "uMcColorTexture", "uDhColorTexture", "uDhDepthTexture")));
			MetalShaderRegistry.register("distanthorizons:copy", new MetalShaderRegistry.Variant(null, generated -> copy, List.of("uCopyTexture")));
		}
		System.out.println("[mcopt] shade dh: Distant Horizons' LOD terrain lit by the shade model (mcopt.shade.dh.light=false: DH's own look)");
	}

	/** Once per level frame. shading: the HDR shade frame draws this frame (not fallback). */
	public static void frame(boolean shading) {
		if (!LOADED || failed) return;
		if (!resolved && !resolve()) return;
		try {
			apply(SSAO, shading ? off("mcopt.shade.dh.ssao") : null);
			apply(AA, shading ? off("mcopt.shade.dh.aa") : null);
			// (FT2.8, audit idx 100) DH's DOUBLE_PASS vanilla fade (DH's default) wipes the near world while DH composites
			// straight into the HDR target (fade28-DOUBLE_PASS: only the LODs' dark silhouette left). An override to SINGLE_PASS from the
			// first frame changed nothing (fade28-DOUBLE-fixed: the same image), and switching SINGLE_PASS to NONE live broke the far LODs
			// (*ft28-dh): DH settles it at startup. NONE and SINGLE_PASS from startup are fine (fade28-NONE): only a warning here; since
			// v1.0-alpha.2 Profile takes DOUBLE_PASS out of DistantHorizons.toml before DH reads it (DhCompat.fixVanillaFade), so this
			// shows only when the player picks DOUBLE_PASS in DH's settings during the session
			apply(FADE, shading ? named(fadeEnum, "mcopt.shade.dh.fade", "dh") : null);
			if (shading && DIRECT && !warnedFade && "DOUBLE_PASS".equals(String.valueOf(getTrue.invoke(FADE.entry)))) {
				warnedFade = true;
				System.out.println("[mcopt] shade dh: WARNING: Distant Horizons' vanilla fade is DOUBLE_PASS, which hides the near world with mcopt-shade's"
					+ " direct composite. Set it to SINGLE_PASS or NONE (DH settings > Graphics > Quality > Vanilla fade) and restart the game.");
			}
			apply(FOG, shading && LIGHT ? off("mcopt.shade.dh.fog") : null);
			apply(SHADING, shading && LIGHT && off("mcopt.shade.dh.shading") != null ? named(shadingEnum, "-", "DISABLED") : null);
			// FT2 (2026-10-08 night): how far around the camera DH skips LODs that the vanilla terrain covers (0..1 of the vanilla
			// render distance; "dh" = DH's own setting): LODs there are rasterized and lit for nothing
			// (pricedh1, DH 40 chunks, render distance 16: 0.5 -0.48 to -0.69 ms, 0.8 -1.04 / -1.07 ms; no holes in the still shots;
			// DH warns of holes when flying into terrain not loaded yet: 0.5 by default, with its fast-movement reduction on)
			apply(OVERDRAW, shading ? number("mcopt.shade.dh.overdraw", "0.5") : null);
			// (1.2.2) Gael's DH "Low" (transparency DISABLED) drew the LODs' water opaque in DH's terrain pass: flat vanilla
			// blue far away, a hard edge where the vanilla water ends (v122-ring-gael). COMPLETE while the shade frame runs
			// (mcopt.shade.dh.transparency=dh: DH's own); the LODs rebuild with the water in the translucent pipeline
			apply(TRANSPARENCY, shading && !"dh".equalsIgnoreCase(System.getProperty("mcopt.shade.dh.transparency", "complete")) ? transparencyComplete : null);
			Object[] preset = shading ? PRESET_VALUES.get(System.getProperty("mcopt.shade.dh.preset", "balanced")) : null;
			apply(RADIUS, preset == null ? null : preset[0]);
			apply(HQUALITY, preset == null ? null : preset[1]);
			apply(VQUALITY, preset == null ? null : preset[2]);
			apply(MAXRES, preset == null ? null : preset[3]);
		} catch (Throwable t) {
			fail(t);
			return;
		}
		if (!logged && shading) {
			logged = true;
			StringBuilder b = new StringBuilder("[mcopt] shade dh: while the shade frame runs, Distant Horizons'");
			for (Entry e : ALL) b.append(' ').append(e.field).append('=').append(e.applied == null ? "(DH's)" : e.applied);
			System.out.println(b);
		}
	}

	private static void apply(Entry e, Object want) throws ReflectiveOperationException {
		if (want != e.applied) setApi.invoke(e.entry, e.applied = want, USER);
	}

	/** Boolean.FALSE (override off) unless the flag says "dh". */
	private static Object off(String key) {
		return "dh".equalsIgnoreCase(System.getProperty(key, "off")) ? null : Boolean.FALSE;
	}

	/** A Float override from a property ("dh" or unparsable: DH's own setting); the same instance while the value is unchanged. */
	private static Object number(String key, String def) {
		String v = System.getProperty(key, def);
		if ("dh".equalsIgnoreCase(v)) return null;
		try {
			float f = Float.parseFloat(v);
			return OVERDRAW.applied instanceof Float a && a == f ? a : Float.valueOf(f);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object named(Class<?> type, String key, String def) {
		String v = System.getProperty(key, def);
		if ("dh".equalsIgnoreCase(v)) return null;
		try {
			return Enum.valueOf((Class<? extends Enum>) type, v.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return "dh".equalsIgnoreCase(def) ? null : Enum.valueOf((Class<? extends Enum>) type, def);
		}
	}

	private static boolean resolve() {
		try {
			ClassLoader cl = ShadeDh.class.getClassLoader();
			for (Entry e : ALL) {
				e.entry = Class.forName(e.cls, true, cl).getField(e.field).get(null);
				if (e.entry == null) return false; // DH's config not built yet: next frame
			}
			setApi = SSAO.entry.getClass().getMethod("setApiValue", Object.class, String.class);
			getTrue = SSAO.entry.getClass().getMethod("getTrueValue"); // the player's own value, under any API override
			fadeEnum = Class.forName("com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode", true, cl);
			shadingEnum = Class.forName("com.seibel.distanthorizons.api.enums.config.EDhApiLodShading", true, cl);
			transparencyComplete = constant(cl, "com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency", "COMPLETE");
			for (String[] p : PRESETS) {
				PRESET_VALUES.put(p[0], new Object[] {Integer.valueOf(p[1]),
					constant(cl, "com.seibel.distanthorizons.api.enums.config.EDhApiHorizontalQuality", p[2]),
					constant(cl, "com.seibel.distanthorizons.api.enums.config.EDhApiVerticalQuality", p[3]),
					constant(cl, "com.seibel.distanthorizons.api.enums.config.EDhApiMaxHorizontalResolution", p[4])});
			}
			resolved = true;
			return true;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object constant(ClassLoader cl, String type, String name) throws ClassNotFoundException {
		return Enum.valueOf((Class<? extends Enum>) Class.forName(type, true, cl), name);
	}

	private static void fail(Throwable t) {
		failed = true;
		System.out.println("[mcopt] shade dh: could not switch Distant Horizons' settings (" + t + "); its own settings stay");
	}
}
