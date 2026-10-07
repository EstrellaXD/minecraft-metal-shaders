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
package mcopt.metal.shade.water;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeMsl;
import org.lwjgl.system.MemoryUtil;

/**
 * Native shading's water and reflections module (shader bible E14, E15 basics, E26 surface): animated waves, Fresnel,
 * screen-space reflections with a sky fallback, refraction, Beer-Lambert absorption from the biome's water colour,
 * shoreline foam, caustics on the bottom, and the surface seen from below (Snell's window, total internal reflection).
 *
 * <p>How it plugs in:
 * <ul>
 * <li>The scene copy (bible hook H6): MetalRenderPass.setPipeline calls {@link #capture} when a main pass (one with depth)
 * switches to {@code sodium:pipeline/translucent_terrain}; mcwater.m copies colour attachment 0 and depth there.</li>
 * <li>The shader: ShadeMsl.terrain prepends {@link #prelude} (defines + water.metal) to the translucent terrain fragment
 * only; sodium_terrain.fs.metal's water branch calls shadeWaterSurface (water.metal) when SHADE_WATER is defined.</li>
 * <li>The bindings (texture 18/19, buffer 23, fragment stage): {@link #bind} on every encoder ShadeBindings binds.</li>
 * <li>Per frame: {@link #frame} from ShadeFrameUpdater (WaterFrame has the game-typed part: sprite rectangles, the water
 * surface above the eye).</li>
 * </ul>
 *
 * <p>Flags (system properties or config/mcopt.properties, read once at startup; all need mcopt.shade=true):
 * <ul>
 * <li>{@code mcopt.shade.water} (true): the module. False = M1's still-water stand-in, byte-identical shaders.</li>
 * <li>{@code mcopt.shade.ssr} (true): screen-space reflections (false: sky and sun reflections only).</li>
 * <li>{@code mcopt.shade.ssrSteps} (24): SSR march steps (4-64), plus 4 binary refinement steps. With quad SSR the four
 * pixels of a quad share them (6 each).</li>
 * <li>{@code mcopt.shade.ssrQuad} (true): the 2x2 pixels of a quad trace one reflection ray together (half-resolution SSR
 * at a quarter of the cost; false: one ray per pixel).</li>
 * <li>{@code mcopt.shade.ssrDist} (512): SSR maximum ray length in blocks (the march is in screen space, so a long ray costs
 * no more steps; it lets distant hills and shores mirror in open water at sunset).</li>
 * <li>{@code mcopt.shade.waterWaves} (1.0): wave strength (0 = flat mirror).</li>
 * <li>{@code mcopt.shade.caustics} (true): caustics on the bottom seen through the surface.</li>
 * <li>{@code mcopt.shade.waterFoam} (true): shoreline foam.</li>
 * <li>{@code mcopt.shade.waterReflect} (1.0): reflection strength (artistic scale on the Fresnel term).</li>
 * <li>{@code mcopt.shade.glossy} (false, opt-in until checked in game: the bench world has no ice or stained glass): stained
 * glass, ice, slime and honey get mirror reflections (sky, SSR, sun) too.</li>
 * </ul>
 *
 * <p>Free of game types (ShadeMsl, which the shade mixin plugin loads early, reads {@link #prelude}).
 */
public final class ShadeWater {
	/** The module's flag on its own (what ShadeMsl assembles the MSL by; validation tools run without mcopt.shade). */
	public static final boolean MODULE = Boolean.parseBoolean(System.getProperty("mcopt.shade.water", "true"));
	/** The module is live: shading on and the flag on. The backend's hooks test this static final. */
	public static final boolean ON = Shade.ENABLED && MODULE;
	public static final boolean SSR = Boolean.parseBoolean(System.getProperty("mcopt.shade.ssr", "true"));
	public static final boolean SSR_QUAD = Boolean.parseBoolean(System.getProperty("mcopt.shade.ssrQuad", "true"));
	public static final int SSR_STEPS = Math.clamp(Integer.getInteger("mcopt.shade.ssrSteps", 24), 4, 64);
	public static final float SSR_DIST = Math.clamp(floatProperty("mcopt.shade.ssrDist", 512f), 8f, 1024f);
	public static final float WAVES = Math.clamp(floatProperty("mcopt.shade.waterWaves", 1f), 0f, 4f);
	public static final boolean CAUSTICS = Boolean.parseBoolean(System.getProperty("mcopt.shade.caustics", "true"));
	public static final boolean FOAM = Boolean.parseBoolean(System.getProperty("mcopt.shade.waterFoam", "true"));
	public static final float REFLECT = Math.clamp(floatProperty("mcopt.shade.waterReflect", 1f), 0f, 2f);
	public static final boolean GLOSSY = Boolean.parseBoolean(System.getProperty("mcopt.shade.glossy", "false"));
	/** The pipeline whose first use in a main pass triggers the scene copy. */
	public static final String TRANSLUCENT = "sodium:pipeline/translucent_terrain";

	private static long state;
	private static boolean failed;
	private static final ByteBuffer PARAMS = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
	private static int captures;

	private ShadeWater() {
	}

	private static float floatProperty(String key, float def) {
		try {
			return Float.parseFloat(System.getProperty(key, Float.toString(def)));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/** The MSL that goes after shade_common.metal in front of the translucent terrain fragment: flag defines + water.metal. */
	public static String prelude() {
		StringBuilder b = new StringBuilder("#define SHADE_WATER 1\n");
		if (SSR) b.append("#define SHADE_SSR 1\n");
		if (SSR_QUAD) b.append("#define SHADE_SSR_QUAD 1\n");
		b.append("#define SHADE_SSR_STEPS ").append(SSR_STEPS).append('\n');
		if (CAUSTICS) b.append("#define SHADE_CAUSTICS 1\n");
		if (FOAM) b.append("#define SHADE_FOAM 1\n");
		if (GLOSSY) b.append("#define SHADE_GLOSSY 1\n");
		return b.append(ShadeMsl.resource("water.metal")).append('\n').toString();
	}

	/** Lazily creates the native state (first bind or frame). False after a failure: the shaders then fall back by themselves. */
	private static boolean ready(long ctx) {
		if (state != 0) return true;
		if (failed || ctx == 0) return false;
		try {
			state = WaterNative.create(ctx);
			System.out.println("[mcopt] shade: water on (ssr " + SSR + (SSR_QUAD ? " quad" : "") + ", " + SSR_STEPS + " steps, " + SSR_DIST + " blocks; waves " + WAVES
				+ ", caustics " + CAUSTICS + ", foam " + FOAM + ", glossy " + GLOSSY + ")");
		} catch (Throwable e) {
			failed = true;
			System.out.println("[mcopt] shade: water setup failed, M1 water from now on: " + e);
		}
		return state != 0;
	}

	/** Binds the copies and this frame's parameters into enc's open render encoder (fragment stage). */
	public static void bind(long ctx, long enc) {
		if (ready(ctx)) WaterNative.bind(enc);
	}

	/**
	 * The scene copy at the switch to translucent terrain (enc's open encoder is ended, copied and reopened). True when it
	 * happened: the caller must restore its encoder state (pipeline, uniforms, scissor, vertex buffers, shade globals).
	 */
	public static boolean capture(long ctx, long enc) {
		if (!ready(ctx)) return false;
		boolean done = WaterNative.capture(enc, state) != 0;
		if (done && captures++ == 0) System.out.println("[mcopt] shade: water scene copy active");
		return done;
	}

	/**
	 * This frame's parameters, before anything draws (ShadeFrameUpdater). still / flow: the water sprites' atlas uv
	 * rectangles (u0, v0, u1, v1; zeros = unknown, the shader then guesses water from the vertex colour like M1);
	 * surfaceY: the water surface above the eye, camera-relative (under water; 0 otherwise); wetness 0..1.
	 */
	public static void frame(long ctx, float[] still, float[] flow, float surfaceY, float wetness) {
		if (!ready(ctx)) return;
		ByteBuffer p = PARAMS;
		for (int i = 0; i < 4; i++) p.putFloat(i * 4, still[i]);
		for (int i = 0; i < 4; i++) p.putFloat(16 + i * 4, flow[i]);
		p.putFloat(32, 0f).putFloat(36, surfaceY).putFloat(40, wetness).putFloat(44, 0f);
		p.putFloat(48, WAVES).putFloat(52, SSR_DIST).putFloat(56, REFLECT).putFloat(60, CAUSTICS ? 1f : 0f);
		WaterNative.params(state, MemoryUtil.memAddress(p));
	}
}
