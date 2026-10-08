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
	private static final MethodHandle ENT_RECORD = fn("mc_shadow_entity_record", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG,
		JAVA_FLOAT);
	private static final MethodHandle ENT_INIT = fn("mc_shadow_entity_init", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle ENT_COMMIT = fn("mc_shadow_entity_commit", null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle ENT_RESET = fn("mc_shadow_entity_reset", null, JAVA_LONG);
	private static final MethodHandle ENT_SHIFT = fn("mc_shadow_entity_shift", null, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT);
	private static final MethodHandle ENT_COUNT = fn("mc_shadow_entity_count", JAVA_INT, JAVA_LONG);
	private static final MethodHandle BIND = fn("mc_shadow_bind", null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle TINT_INIT = fn("mc_shadow_tint_init", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle TINT_TEXTURE = fn("mc_shadow_tint_texture", JAVA_LONG, JAVA_LONG);
	private static final MethodHandle TINT_RENDER = fn("mc_shadow_tint_render", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle DEBUG = fn("mc_shadow_debug", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);

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

	static int entityRecord(long shadow, long src, long srcOffset, int quads, int stride, int uvOffset, long texture, float cutoff) {
		try {
			return (int) ENT_RECORD.invokeExact(shadow, src, srcOffset, quads, stride, uvOffset, texture, cutoff);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int entityInit(long shadow, long err, int errCap) {
		try {
			return (int) ENT_INIT.invokeExact(shadow, err, errCap);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void entityCommit(long enc, long shadow) {
		try {
			ENT_COMMIT.invokeExact(enc, shadow);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void entityReset(long shadow) {
		try {
			ENT_RESET.invokeExact(shadow);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void entityShift(long shadow, float x, float y, float z, int clear) {
		try {
			ENT_SHIFT.invokeExact(shadow, x, y, z, clear);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int entityCount(long shadow) {
		try {
			return (int) ENT_COUNT.invokeExact(shadow);
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

	static int debug(long enc, long shadow, long target, int x, int y, int size, int gap, long params, int paramBytes) {
		try {
			return (int) DEBUG.invokeExact(enc, shadow, target, x, y, size, gap, params, paramBytes);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** M3 coloured shadows: the tint map and its pipeline (0, or 1 with err filled). */
	static int tintInit(long shadow, int resolution, long err, int errCap) {
		try {
			return (int) TINT_INIT.invokeExact(shadow, resolution, err, errCap);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long tintTexture(long shadow) {
		try {
			return (long) TINT_TEXTURE.invokeExact(shadow);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int tintRender(long enc, long shadow, long cascade, int cascadeBytes, long chunks, long chunksOffset, long regions, long regionsOffset, long draws,
		int drawCount, long atlas, long params, int paramBytes) {
		try {
			return (int) TINT_RENDER.invokeExact(enc, shadow, cascade, cascadeBytes, chunks, chunksOffset, regions, regionsOffset, draws, drawCount, atlas, params,
				paramBytes);
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
