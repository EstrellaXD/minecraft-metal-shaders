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
package mcopt.metal.shade.dims;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import mcopt.metal.MetalBridge;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * XDR / EDR output (bible E23; native side mcxdr.m, which documents the frame). Decided once, when the game first configures
 * its surface ({@link #configure}, from MetalSurface): mcopt.shade.xdr = auto (on when a screen can show EDR), on or off; never
 * with frame generation. While on, the final pass (ShadePost) writes the SDR image into main (alpha 0) plus the highlights'
 * excess above SDR white into its own RG11B10Float target, and the present composes them into the RGBA16Float extended
 * linear Display P3 drawable. The highlight peak follows the screen's current EDR headroom (smoothed, capped by
 * mcopt.shade.xdrPeak), so the brightness slider never makes highlights jump. Render thread only.
 */
public final class ShadeXdr {
	private static final Linker LINKER = Linker.nativeLinker();
	private static @Nullable MethodHandle enable, supported, headroom, frame, pso2, pass2;
	private static boolean decided, on;
	private static long layer;
	private static float peak = 1f, sinceQuery = 1f, target = 1f, headroomNow = 1f, potential = 1f;
	/** The scene luminance (pre-tonemap, exposed) where highlights start to use the headroom, and how many octaves above it they reach the peak. */
	static final float KNEE = 3.0f, RANGE = 4.0f;

	private ShadeXdr() {
	}

	private static MethodHandle fn(String name, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return LINKER.downcallHandle(symbol, d);
	}

	private static void link() {
		if (enable != null) return;
		enable = fn("mc_xdr_enable", null, JAVA_INT);
		supported = fn("mc_xdr_supported", JAVA_INT);
		headroom = fn("mc_xdr_headroom", JAVA_FLOAT, JAVA_LONG, JAVA_INT);
		frame = fn("mc_xdr_frame", null, JAVA_LONG, JAVA_LONG);
		pso2 = fn("mc_xdr_pso2", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);
		pass2 = fn("mc_xdr_pass2", null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG);
	}

	/** MetalSurface.configure, before the layer's format is set (every configure; the decision is taken the first time). */
	public static void configure(long metalLayer, boolean frameGen) {
		layer = metalLayer;
		if (decided) return;
		decided = true;
		try {
			link();
			int mode = DimsConfig.XDR;
			boolean can = (int) supported.invokeExact() != 0;
			potential = (float) headroom.invokeExact(metalLayer, 1);
			on = mode == 2 || mode == 1 && can;
			if (frameGen) on = false; // frame generation presents its own way
			enable.invokeExact(on ? 1 : 0);
			System.out.println("[mcopt] shade: XDR output " + (on ? "on" : "off") + " (mcopt.shade.xdr=" + new String[] {"off", "auto", "on"}[mode]
				+ ", EDR screen " + can + ", this screen's potential headroom " + potential + (frameGen ? ", frame generation: off" : "") + ")");
		} catch (Throwable t) {
			on = false;
			System.out.println("[mcopt] shade: XDR output unavailable: " + t);
		}
	}

	/** Whether the surface presents EDR (the final pass then writes the excess image). */
	public static boolean active() {
		return on;
	}

	/** Once per world frame (ShadeFrameUpdater): follows the screen's EDR headroom. */
	public static void frame(float dt) {
		sinceQuery += dt;
		if (sinceQuery >= 0.25f) {
			sinceQuery = 0f;
			try {
				headroomNow = (float) headroom.invokeExact(layer, 0);
			} catch (Throwable t) {
				headroomNow = 1f;
			}
			target = Math.max(1f, Math.min(headroomNow, DimsConfig.XDR_PEAK));
		}
		peak += (target - peak) * (1f - (float) Math.exp(-dt / 0.5f));
	}

	/** The highlight peak this frame, in multiples of SDR white (1 = SDR). */
	public static float peak() {
		return peak;
	}

	/** The screen's EDR headroom as last queried, for logs. */
	public static float headroom() {
		return headroomNow;
	}

	/** The final pass's XDR parameters (ShadeFinalParams.x): on, peak, knee, octaves to the peak. */
	public static void params(ByteBuffer b) {
		b.putFloat(on ? 1f : 0f).putFloat(peak).putFloat(KNEE).putFloat(RANGE);
	}

	/** A full-screen pipeline with two color targets (the final pass: main RGBA8 + the RG11B10F excess). */
	public static long pipeline2(long ctx, long library, String vertex, String fragment, int format0, int format1) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			MemoryUtil.memPutByte(err, (byte) 0);
			long pso = (long) pso2.invokeExact(ctx, library, MemoryUtil.memAddress(stack.UTF8(vertex)), MemoryUtil.memAddress(stack.UTF8(fragment)), format0, format1,
				err, 4096);
			if (pso == 0) throw new IllegalStateException("shade pipeline " + fragment + ": " + MemoryUtil.memUTF8(err));
			return pso;
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	/** ShadeRender.pass with two targets, then hands the excess (t1) to this frame's present. */
	public static void finalPass(long enc, long pipeline, long t0, long t1, long[] textures, @Nullable ByteBuffer params, String label) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int n = textures.length;
			long t = stack.nmalloc(8, Math.max(1, n) * 8);
			for (int i = 0; i < n; i++) MemoryUtil.memPutAddress(t + i * 8L, textures[i]);
			pass2.invokeExact(enc, pipeline, t0, t1, t, n, params == null ? 0L : MemoryUtil.memAddress(params), params == null ? 0 : params.remaining(),
				MemoryUtil.memAddress(stack.UTF8(label)));
			frame.invokeExact(enc, t1);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable th) {
			throw new IllegalStateException(th);
		}
	}
}
