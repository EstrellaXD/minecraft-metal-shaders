// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import mcopt.metal.MetalBridge;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Downcalls into mcshadow.m (in libmcmetal, found through MetalBridge.library()). Pointers cross as longs. */
final class ShadowNative {
	private static final Linker LINKER = Linker.nativeLinker();

	private static final MethodHandle NEW = fn("mc_shadow_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle FREE = fn("mc_shadow_free", null, JAVA_LONG);
	private static final MethodHandle TEXTURE = fn("mc_shadow_texture", JAVA_LONG, JAVA_LONG);
	private static final MethodHandle COMPARE_SAMPLER = fn("mc_shadow_compare_sampler", JAVA_LONG, JAVA_LONG);
	private static final MethodHandle RENDER = fn("mc_shadow_render", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT);
	private static final MethodHandle BIND = fn("mc_shadow_bind", null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle DEBUG = fn("mc_shadow_debug", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);

	private ShadowNative() {
	}

	private static MethodHandle fn(String name, MemoryLayout ret, MemoryLayout... args) {
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("libmcmetal has no " + name));
		return LINKER.downcallHandle(symbol, ret == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(ret, args));
	}

	static long create(long ctx, long source, int resolution, int layers, int depthBits, long err, int errCap) {
		try {
			return (long) NEW.invokeExact(ctx, source, resolution, layers, depthBits, err, errCap);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void free(long shadow) {
		try {
			FREE.invokeExact(shadow);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long texture(long shadow) {
		try {
			return (long) TEXTURE.invokeExact(shadow);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long compareSampler(long shadow) {
		try {
			return (long) COMPARE_SAMPLER.invokeExact(shadow);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int render(long enc, long shadow, int layer, long cascade, int cascadeBytes, long chunks, long chunksOffset, long regions, long regionsOffset,
		long draws, int drawCount, long atlas, float slopeScale, float clamp) {
		try {
			return (int) RENDER.invokeExact(enc, shadow, layer, cascade, cascadeBytes, chunks, chunksOffset, regions, regionsOffset, draws, drawCount, atlas,
				slopeScale, clamp);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void bind(long enc, long shadow, int textureSlot, int samplerSlot) {
		try {
			BIND.invokeExact(enc, shadow, textureSlot, samplerSlot);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int debug(long enc, long shadow, long target, int x, int y, int size, long params, int paramsBytes) {
		try {
			return (int) DEBUG.invokeExact(enc, shadow, target, x, y, size, params, paramsBytes);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}
}
