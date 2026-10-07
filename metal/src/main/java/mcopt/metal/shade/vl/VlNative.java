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

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import mcopt.metal.MetalBridge;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Downcalls into native/mcvl.m (pointers cross as longs). */
final class VlNative {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle NEW = fn("mc_vl_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle RENDER = fn("mc_vl_render", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT,
		JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle CANOPY = fn("mc_vl_canopy", null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle FREE = fn("mc_vl_free", null, JAVA_LONG);
	private static final MethodHandle TIMES = fn("mc_vl_times", null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle OUTPUTS = fn("mc_vl_outputs", null, JAVA_LONG, JAVA_LONG);

	private VlNative() {
	}

	private static MethodHandle fn(String name, MemoryLayout ret, MemoryLayout... args) {
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("libmcmetal has no " + name));
		return LINKER.downcallHandle(symbol, ret == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(ret, args));
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}

	/** flags: 1 lossy VL targets, 2 HDR (M2 composite and ShadeGpu.scene.y). */
	static long create(long ctx, long source, int flags, long err, int errCap) {
		try {
			return (long) NEW.invokeExact(ctx, source, flags, err, errCap);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** width x height: the VL targets' size; liveWidth x liveHeight the part of them this frame covers (VlParams.size.xy). */
	static int render(long enc, long state, long color, long depth, long params, int paramBytes, int width, int height, int liveWidth, int liveHeight,
		int flags, long err, int errCap) {
		try {
			return (int) RENDER.invokeExact(enc, state, color, depth, params, paramBytes, width, height, liveWidth, liveHeight, flags, err, errCap);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void canopy(long state, long out) {
		try {
			CANOPY.invokeExact(state, out);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** out: 4 floats, average GPU ms of march, temporal, composite since the last call, and the frame count. */
	static void times(long state, long out) {
		try {
			TIMES.invokeExact(state, out);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** out: 2 pointers, the fold outputs of the last render (light + T, the matching history). */
	static void outputs(long state, long out) {
		try {
			OUTPUTS.invokeExact(state, out);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void free(long state) {
		try {
			FREE.invokeExact(state);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}
}
