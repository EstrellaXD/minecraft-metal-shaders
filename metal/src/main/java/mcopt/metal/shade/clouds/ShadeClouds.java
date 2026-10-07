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
package mcopt.metal.shade.clouds;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import mcopt.metal.shade.ShadeFrame;
import mcopt.metal.shade.ShadeRender;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ARGB;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * M3 volumetric clouds (bible E05): cumulus with multiple scattering, silver linings and dark storm bases, a cirrus layer,
 * cloud shadows and a daily weather, in place of vanilla's clouds. Each frame, before the sky pass (ShadeFrameUpdater,
 * right after the sky LUT): the daily weather and wind (CloudWeather), CloudParams, then on the GPU (mcclouds.m) the cloud
 * shadow map, the checkerboard march at a fraction of the output resolution, its temporal resolve, and the reflection dome.
 * The outputs go to ShadeResources 12-14 and ShadeLook (cloudShadowVP, clouds0/1, SHADE_FEATURE_CLOUDS); the sky pass
 * composites the clouds behind the world (sky.fs.metal), terrain/water/god rays read the shadow map (shade_clouds.metal).
 * Vanilla's cloud draw is skipped while this draws (MetalRenderPass, {@link #replacesVanilla}). Render thread only.
 * See CLOUDS.md.
 */
public final class ShadeClouds {
	/** The module is on (flags); with it off nothing here runs and vanilla's clouds draw as in M2. */
	public static final boolean ON = CloudConfig.ENABLED;
	public static final int RESOURCE_SHADOW = 12, RESOURCE_DOME = 13, RESOURCE_SCREEN = 14;
	private static final int ERR = 4096;

	private static long state;
	private static boolean failed, active, wasActive;
	private static final CloudWeather WEATHER = new CloudWeather();
	private static final CloudParams PARAMS = new CloudParams();
	private static final ByteBuffer PACKED = MemoryUtil.memAlloc(CloudParams.SIZE).order(ByteOrder.nativeOrder());
	private static final long OUT = MemoryUtil.nmemCalloc(3, 8);
	private static final long TIMES = MemoryUtil.nmemCalloc(5, 4);
	private static long lastNanos, lastLog;
	private static int frame, domeFrames;
	private static double lastX, lastY, lastZ;
	private static int lastDimension = -1;

	private ShadeClouds() {
	}

	/** Whether vanilla's clouds are replaced this frame (MetalRenderPass skips their draw). */
	public static boolean replacesVanilla() {
		return ON && active;
	}

	/**
	 * Called once per level frame before the sky pass, after ShadeLook was packed (look: this frame's ShadeLook bytes).
	 * ctx/enc: the backend context and the frame's native encoder.
	 */
	public static void update(long ctx, long enc, ShadeFrame f, ByteBuffer look) {
		active = false;
		if (failed) {
			PARAMS.packLook(look, false);
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			var game = mc.gameRenderer.gameRenderState();
			boolean want = level != null && f.dimension == ShadeFrame.DIM_OVERWORLD && game.optionsRenderState.cloudStatus != CloudStatus.OFF
				&& ARGB.alpha(game.levelRenderState.cloudColor) > 0;
			if (!want) {
				wasActive = false;
				PARAMS.packLook(look, false);
				return;
			}
			if (state == 0 && !init(ctx)) {
				PARAMS.packLook(look, false);
				return;
			}
			long now = System.nanoTime();
			float dt = lastNanos == 0 ? 0f : Math.min((now - lastNanos) / 1e9f, 0.25f);
			boolean longGap = lastNanos != 0 && now - lastNanos > 1_000_000_000L;
			lastNanos = now;
			double dx = f.cameraX - lastX, dy = f.cameraY - lastY, dz = f.cameraZ - lastZ;
			boolean teleport = dx * dx + dy * dy + dz * dz > 64.0 * 64.0;
			boolean snap = !wasActive || longGap || teleport || lastDimension != f.dimension;
			lastX = f.cameraX;
			lastY = f.cameraY;
			lastZ = f.cameraZ;
			lastDimension = f.dimension;

			// sizes: a fraction of the output (window framebuffer), independent of TAAU's render scale
			var window = mc.getWindow();
			int ow = Math.max(1, window.getWidth()), oh = Math.max(1, window.getHeight());
			int hw = Math.max(16, Math.round(ow * CloudConfig.SCALE)), hh = Math.max(16, Math.round(oh * CloudConfig.SCALE));
			boolean checker = CloudConfig.CHECKER;
			if (checker) {
				hw += hw & 1;
				hh += hh & 1;
			}
			int mw = checker ? hw / 2 : hw, mh = checker ? hh / 2 : hh;
			boolean resized = CloudNative.size(state, ctx, hw, hh, mw, mh, CloudConfig.DOME_W, CloudConfig.DOME_H) != 0;

			// weather and wind
			long ticks = level.getDefaultClockTime();
			double days = (ticks + f.partialTick) / 24000.0;
			WEATHER.update(days, f.rain, f.thunder, dt, snap, CloudConfig.SEED);
			float sx = WEATHER.windX * WEATHER.windSpeed * dt, sz = WEATHER.windZ * WEATHER.windSpeed * dt;
			CloudParams p = PARAMS;
			p.windX += sx;
			p.windZ += sz;
			p.wind3X += sx * 0.82;
			p.wind3Z += sz * 0.82;
			p.cirrusX += sx * 1.7;
			p.cirrusZ += sz * 1.7;
			// keep the accumulators small (each wraps at a multiple of its noise period)
			p.windX %= CloudParams.WEATHER_PERIOD * 64;
			p.windZ %= CloudParams.WEATHER_PERIOD * 64;
			p.wind3X %= CloudParams.NOISE_PERIOD * 64;
			p.wind3Z %= CloudParams.NOISE_PERIOD * 64;
			p.cirrusX %= CloudParams.WEATHER_PERIOD * 64;
			p.cirrusZ %= CloudParams.WEATHER_PERIOD * 64;
			p.windStepX = sx;
			p.windStepZ = sz;
			float vanillaHeight = game.levelRenderState.cloudHeight;
			p.base = Float.isNaN(CloudConfig.BASE) ? (Float.isFinite(vanillaHeight) && vanillaHeight > 0f ? vanillaHeight + 8f : 200f) : CloudConfig.BASE;
			p.top = p.base + CloudConfig.THICKNESS;
			p.histW = hw;
			p.histH = hh;
			p.marchW = mw;
			p.marchH = mh;
			p.checker = checker;
			p.frame = frame++;
			p.reset = snap || resized;
			if (p.reset) domeFrames = 0;
			p.domeValid = CloudConfig.DOME && domeFrames > 0;
			p.opacity = 1f;
			p.pack(PACKED, f, WEATHER);
			// the shadow map and the dome change slowly: each is redrawn every other frame, alternating (both after a reset)
			boolean even = (p.frame & 1) == 0;
			int flags = 2 | (CloudConfig.SHADOWS && (even || p.reset) ? 1 : 0) | (CloudConfig.DOME && (!even || p.reset) ? 4 : 0)
				| (CloudConfig.GPU_TIMER ? 8 : 0);
			CloudNative.render(enc, state, MemoryUtil.memAddress(PACKED), CloudParams.SIZE, flags);
			if ((flags & 4) != 0) domeFrames++;
			CloudNative.outputs(state, OUT);
			ShadeRender.resource(RESOURCE_SHADOW, MemoryUtil.memGetAddress(OUT));
			ShadeRender.resource(RESOURCE_DOME, MemoryUtil.memGetAddress(OUT + 8));
			ShadeRender.resource(RESOURCE_SCREEN, MemoryUtil.memGetAddress(OUT + 16));
			active = true;
			wasActive = true;
			PARAMS.packLook(look, true);
			if (CloudConfig.LOG && now - lastLog > 2_000_000_000L) {
				lastLog = now;
				String times = "";
				if (CloudConfig.GPU_TIMER) {
					CloudNative.times(state, TIMES);
					times = String.format(java.util.Locale.ROOT, ", gpu ms shadow %.3f march %.3f resolve %.3f dome %.3f (%d frames)", MemoryUtil.memGetFloat(TIMES),
						MemoryUtil.memGetFloat(TIMES + 4), MemoryUtil.memGetFloat(TIMES + 8), MemoryUtil.memGetFloat(TIMES + 12), (int) MemoryUtil.memGetFloat(TIMES + 16));
				}
				System.out.println(String.format(java.util.Locale.ROOT, "[mcopt] shade clouds: day %.2f, %s, layer %.0f-%.0f, %dx%d (march %dx%d), light %.2f %.2f %.2f %s%s",
					days, WEATHER.describe(), p.base, p.top, hw, hh, mw, mh, p.light[0], p.light[1], p.light[2], p.sunLit ? "sun" : "moon", times));
			}
		} catch (Throwable t) {
			failed = true;
			active = false;
			System.out.println("[mcopt] shade: clouds failed, vanilla clouds from now on: " + t);
			t.printStackTrace();
			PARAMS.packLook(look, false);
		}
	}

	private static boolean init(long ctx) {
		ByteBuffer src = MemoryUtil.memUTF8(CloudConfig.source());
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, ERR);
			MemoryUtil.memPutByte(err, (byte) 0);
			long s = CloudNative.create(ctx, MemoryUtil.memAddress(src), err, ERR);
			if (s == 0) {
				failed = true;
				System.out.println("[mcopt] shade: clouds setup failed, vanilla clouds: " + MemoryUtil.memUTF8(err));
				return false;
			}
			state = s;
			System.out.println("[mcopt] shade: volumetric clouds on (" + CloudConfig.describe() + ")");
			return true;
		} finally {
			MemoryUtil.memFree(src);
		}
	}
}
