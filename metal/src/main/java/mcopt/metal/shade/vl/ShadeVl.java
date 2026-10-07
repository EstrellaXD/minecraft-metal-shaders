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
package mcopt.metal.shade.vl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import mcopt.metal.MetalBridge;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Volumetric light (shader bible E07): god rays through the sun's shadow map, crepuscular streaks toward the sun, caustic
 * shafts under water, subtle moon shafts. Runs once per frame right before the hand pass (GameRenderer.render3dHud, hook
 * H7: the main depth still holds the level), on the game's main target: march at {@link VlConfig#SCALE} of it, temporal
 * accumulation, depth-aware upsample and a composite that adds the light before M1's output curve. The CPU side here
 * picks the medium (haze + ground mist by time of day, rain and biome humidity), the light colour (saturated at sunset),
 * the strength, the water surface above the camera, and packs VlParams (vl.metal). Render thread only.
 *
 * <p>On the HDR pipeline (M2) the same passes run with the composite's function constant VL_HDR (flag 4 of mc_vl_render):
 * scene x T + light (blend One, SourceAlpha) into the pre-exposed scene target before the tonemap. See VL.md.
 */
public final class ShadeVl {
	private static final int PARAM_BYTES = 256;
	private static final int ERR = 2048;
	private static final float SEA = 63f;

	private static long state;
	private static boolean failed, logged;
	private static final ByteBuffer PARAMS = MemoryUtil.memAlloc(PARAM_BYTES).order(ByteOrder.nativeOrder());
	private static final long CANOPY_OUT = MemoryUtil.nmemCalloc(2, 4);
	private static final long TIMES_OUT = MemoryUtil.nmemCalloc(4, 4);
	private static double prevX, prevY, prevZ;
	private static boolean hasPrevious;
	private static long lastNanos, lastLog;
	private static int frame;
	private static Object lastLevel;
	private static float humidity = -1f;
	private static boolean resetCanopy = true;

	private ShadeVl() {
	}

	/** Before GameRenderer.render3dHud (VlGameRendererMixin): the level is drawn, the hand isn't. */
	public static void beforeHand() {
		if (!VlConfig.ENABLED || failed) return;
		try {
			render();
		} catch (Throwable t) {
			failed = true;
			System.out.println("[mcopt] shade: volumetric light failed, off from now on: " + t);
			t.printStackTrace();
		}
	}

	private static void render() {
		ShadeFrame f = Shade.frame();
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || f.dimension != ShadeFrame.DIM_OVERWORLD) {
			hasPrevious = false;
			return;
		}
		// the game's fog wins (lava, powder snow, blindness, darkness): nothing to light
		if (f.fogColor[3] > 0.5f && !f.underwater) return;
		if (f.underwater && !VlConfig.UNDERWATER) return;
		Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
		if (encoder == null || MetalBridge.inRenderPass(encoder)) return;
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		var color = main.getColorTexture();
		var depth = main.getDepthTexture();
		if (color == null || depth == null) return;
		if (state == 0 && !init(MetalBridge.ctx(encoder))) return;

		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0f : Math.min((now - lastNanos) / 1e9f, 1f);
		lastNanos = now;
		boolean reset = !hasPrevious || lastLevel != level || !VlConfig.TEMPORAL;
		double dx = f.cameraX - prevX, dy = f.cameraY - prevY, dz = f.cameraZ - prevZ;
		if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 8) reset = true;
		if (reset) {
			dx = dy = dz = 0;
		}
		prevX = f.cameraX;
		prevY = f.cameraY;
		prevZ = f.cameraZ;
		hasPrevious = true;
		if (lastLevel != level) resetCanopy = true;
		lastLevel = level;

		int width = main.width, height = main.height;
		int vw = Math.max(1, Math.round(width * VlConfig.SCALE)), vh = Math.max(1, Math.round(height * VlConfig.SCALE));
		if (!pack(f, level, width, height, vw, vh, dx, dy, dz, dt, reset)) {
			hasPrevious = false; // nothing to draw (deep in a cave, no light): no passes at all
			return;
		}

		MetalBridge.flushClear(encoder, color);
		MetalBridge.flushClear(encoder, depth);
		int flags = (VlConfig.CANOPY < 0 ? 1 : 0) | (reset ? 2 : 0) | (VlConfig.GPU_TIMER ? 8 : 0);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, ERR, 1);
			int r = VlNative.render(MetalBridge.enc(encoder), state, MetalBridge.textureHandle(color), MetalBridge.textureHandle(depth),
				MemoryUtil.memAddress0(PARAMS), PARAM_BYTES, vw, vh, flags, err, ERR);
			if (r != 0) throw new IllegalStateException("volumetric light: " + MemoryUtil.memUTF8(err));
		}
		resetCanopy = false;
		frame++;
		if (!logged) {
			logged = true;
			System.out.println("[mcopt] shade: volumetric light " + vw + "x" + vh + " (" + VlConfig.describe() + ")");
		}
		if (VlConfig.LOG && now - lastLog > 2_000_000_000L) {
			lastLog = now;
			VlNative.canopy(state, CANOPY_OUT);
			System.out.println(String.format(java.util.Locale.ROOT, "[mcopt] shade: vl canopy %.2f (shadowed %.2f) haze %.5f mist %.5f canopy mist %.5f strength %.2f g %.2f humidity %.2f underwater %b sunY %.3f",
				MemoryUtil.memGetFloat(CANOPY_OUT), MemoryUtil.memGetFloat(CANOPY_OUT + 4), PARAMS.getFloat(32), PARAMS.getFloat(36), PARAMS.getFloat(76),
				PARAMS.getFloat(16), PARAMS.getFloat(28), humidity, f.underwater, f.sunDir.y));
			if (VlConfig.GPU_TIMER) {
				VlNative.times(state, TIMES_OUT);
				float m = MemoryUtil.memGetFloat(TIMES_OUT), t = MemoryUtil.memGetFloat(TIMES_OUT + 4), c = MemoryUtil.memGetFloat(TIMES_OUT + 8);
				System.out.println(String.format(java.util.Locale.ROOT, "[mcopt] shade: vl gpu march %.3f temporal %.3f composite %.3f = %.3f ms (%dx%d of %dx%d, %.0f frames)",
					m, t, c, m + t + c, vw, vh, width, height, MemoryUtil.memGetFloat(TIMES_OUT + 12)));
			}
		}
	}

	private static boolean init(long ctx) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer source = MemoryUtil.memUTF8(VlConfig.source());
			try {
				long err = stack.ncalloc(1, ERR, 1);
				long s = VlNative.create(ctx, MemoryUtil.memAddress(source), VlConfig.LOSSY, err, ERR);
				if (s == 0) throw new IllegalStateException("volumetric light setup: " + MemoryUtil.memUTF8(err));
				state = s;
				return true;
			} finally {
				MemoryUtil.memFree(source);
			}
		} catch (Throwable t) {
			failed = true;
			System.out.println("[mcopt] shade: volumetric light off: " + t);
			return false;
		}
	}

	// ---- parameters ----------------------------------------------------------------------------------------------------

	private static float smoothstep(float a, float b, float x) {
		float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
		return t * t * (3f - 2f * t);
	}

	private static float mix(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/** 0 (arid) .. 1 (jungle, swamp) at the camera, smoothed over a few seconds (biome borders don't pop). */
	private static float humidity(ClientLevel level, BlockPos pos, float dt) {
		Biome b = level.getBiome(pos).value();
		float t = b.getBaseTemperature();
		float target = !b.hasPrecipitation() ? 0.1f : t >= 0.9f ? 0.95f : t >= 0.75f ? 0.75f : t < 0.2f ? 0.45f : 0.6f;
		humidity = humidity < 0f ? target : mix(humidity, target, 1f - (float) Math.exp(-dt / 6f));
		return humidity;
	}

	/** The water surface above the camera (absolute y), scanning up through water blocks; NaN if none within 32 blocks. */
	private static float waterSurface(ClientLevel level, ShadeFrame f) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(Math.floor(f.cameraX), Math.floor(f.cameraY), Math.floor(f.cameraZ));
		for (int i = 0; i < 32; i++) {
			FluidState fs = level.getFluidState(p);
			boolean water = fs.getType() == Fluids.WATER || fs.getType() == Fluids.FLOWING_WATER;
			if (!water) return i == 0 ? Float.NaN : p.getY();
			FluidState above = level.getFluidState(p.above());
			boolean waterAbove = above.getType() == Fluids.WATER || above.getType() == Fluids.FLOWING_WATER;
			if (!waterAbove) return p.getY() + fs.getHeight(level, p);
			p.move(0, 1, 0);
		}
		return Float.NaN;
	}

	/** Packs VlParams; false when the frame would add nothing (no light and no canopy mist): then no pass runs. */
	private static boolean pack(ShadeFrame f, ClientLevel level, int width, int height, int vw, int vh, double dx, double dy, double dz, float dt,
		boolean reset) {
		ByteBuffer b = PARAMS;
		float sunY = f.sunDir.y;
		boolean sunLight = f.sky.sunLight;
		boolean morning = f.dayTime > 0.5f; // dayTime: 0 noon, 0.25 sunset, 0.5 midnight, 0.75 sunrise
		float rain = f.rain;
		float hum = humidity(level, BlockPos.containing(f.cameraX, f.cameraY, f.cameraZ), dt);
		// golden hour: the sun below ~30 degrees (sunY 0.5), full below ~6 (with the default 30-degree sun path tilt the sun
		// never climbs above sunY 0.87)
		float golden = sunLight ? 1f - smoothstep(0.05f, 0.55f, sunY) : 0f;
		float day = smoothstep(-0.05f, 0.25f, sunY);

		// medium (1/block): a thin haze everywhere plus ground mist, thick at dawn, lighter at dusk, faint at noon
		float mistLow = morning ? 0.0105f : 0.0065f;
		float mist = sunLight ? mix(0.0016f, mistLow, golden) : 0.0045f;
		float haze = 0.00085f * (1f + 0.6f * golden) * (sunLight ? 1f : 1.3f);
		mist *= 0.6f + 0.8f * hum;
		mist *= 1f + 3f * rain;
		haze *= 1f + 2f * rain;
		// canopy mist (vl.metal: around the camera's height, scaled by the canopy factor): what makes the shafts under
		// trees and overhangs; densest in the golden hours, humid biomes and rain
		// (none deep in caves: the canopy factor is 1 there, but the mist would only dim the cave toward black)
		float canopyMist = (sunLight ? mix(0.008f, 0.03f, golden) : 0.01f) * (0.7f + 0.6f * hum) * (1f + rain) * VlConfig.MIST * eyeLight(f);
		// its sky-lit colour in the shade (the canopy hides most of the sky): what it dims the scene behind it toward
		float[] amb = f.sky.ambientSky;
		float ambK = 0.2f * (f.underwater ? 0f : eyeLight(f));
		float camY = (float) f.cameraY;

		// strength: rain flattens the light; caves have no sun; the sun -> moon handover fades through ~0
		float eye = eyeLight(f);
		float lightUp = smoothstep(0.0f, 0.08f, sunLight ? sunY : f.moonDir.y);
		float strength = VlConfig.STRENGTH * (1f - 0.6f * rain) * (f.underwater ? 1f : eye) * lightUp * (sunLight ? 1f : 2.0f * VlConfig.MOON);

		// light colour (irradiance / pi, pre-exposure): saturated toward the low sun (golden hour), plain moonlight
		float[] c = f.sky.light;
		float lum = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
		float sat = 1f + 0.55f * golden * day;
		float lr = Math.max(lum + (c[0] - lum) * sat, 0f), lg = Math.max(lum + (c[1] - lum) * sat, 0f), lb = Math.max(lum + (c[2] - lum) * sat, 0f);

		// forward scattering: sharper toward the low sun
		float g = sunLight ? mix(0.5f, 0.72f, golden) : 0.6f;

		// the light on screen (crepuscular streaks)
		float[] l = f.sky.lightDir;
		Vector4f clip = f.viewProj.transform(new Vector4f(l[0], l[1], l[2], 0f));
		float onScreen = 0f, sx = 0f, sy = 0f;
		if (clip.w > 1e-4f && VlConfig.SS_TAPS > 0 && !f.underwater) {
			float nx = clip.x / clip.w, ny = clip.y / clip.w;
			onScreen = 1f - smoothstep(1.05f, 1.9f, Math.max(Math.abs(nx), Math.abs(ny)));
			sx = (nx * 0.5f + 0.5f) * width;
			sy = (ny * 0.5f + 0.5f) * height;
		}
		onScreen *= smoothstep(-0.02f, 0.08f, l[1]) * eye;

		// water
		float surface = f.underwater ? waterSurface(level, f) : Float.NaN;
		float surfaceRel = Float.isNaN(surface) ? 4f : (float) (surface - f.cameraY);

		float canopyUp = 1f - (float) Math.exp(-dt / 1.5f), canopyDown = 1f - (float) Math.exp(-dt / 4f);

		b.clear();
		put(b, vw, vh, width, height);                                                   // size
		put(b, strength, VlConfig.DIST, VlConfig.STEPS, g);                              // shape
		put(b, haze, mist, SEA + 2f - camY, 12f);                                        // medium
		put(b, SEA + 16f - camY, 48f, 0.35f, 0.05f);                                     // haze
		put(b, lr, lg, lb, canopyMist);                                                  // light
		put(b, VlConfig.SS_TAPS > 0 ? 0.12f + 0.2f * golden : 0f, VlConfig.SS_TAPS, onScreen, 6f); // crep
		put(b, sx, sy, 0f, 0f);                                                          // sunPx
		put(b, 0.4f, 0.11f, 0.06f, 0.022f);                                              // water: extinction rgb, scattering
		put(b, surfaceRel, f.underwater ? 1f : 0f, 48f, 1f);                             // water2
		put(b, VlConfig.TEMPORAL ? 0.9f : 0f, reset ? 1f : 0f, frame & 1023, f.gameTimeSeconds); // temporal
		put(b, (float) dx, (float) dy, (float) dz, canopyUp);                            // camDelta
		put(b, 0.7f, 1.6f, VlConfig.CANOPY, VlConfig.DEBUG);                             // gate: open air, canopy mist, override, debug
		put(b, resetCanopy ? 1f : 0f, 8f, canopyDown, 0f);                               // extra
		put(b, 16f, 4f, VlConfig.EXTINCTION ? 1f : 0f, 0.5f);                            // mist: range, top, extinction on, its scale
		float sunK = 0.05f * ambK / 0.2f; // plus a little sunlight scattered in from the lit air around (warm at dawn and dusk)
		put(b, amb[0] * ambK + lr * sunK, amb[1] * ambK + lg * sunK, amb[2] * ambK + lb * sunK, 0.5f); // mistAmb, open glow left out
		put(b, 0f, 0f, 0f, 0f);
		return strength > 1e-3f || canopyMist > 1e-6f || f.underwater;
	}

	/** How much sky the camera sees, 0 (cave) .. 1 (outdoors), from the sky light at the eye. */
	private static float eyeLight(ShadeFrame f) {
		return smoothstep(2.5f / 15f, 9.5f / 15f, f.eyeSkyLight / 15f);
	}

	private static void put(ByteBuffer b, float x, float y, float z, float w) {
		b.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
	}
}
