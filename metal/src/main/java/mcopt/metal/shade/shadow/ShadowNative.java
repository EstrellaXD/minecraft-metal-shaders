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
	// M3.3: far cascade spread (second map, swap), the shadow passes' GPU timers, the lite tint map
	private static final MethodHandle RENDER_EX = fn("mc_shadow_render_ex", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle SPREAD_INIT = fn("mc_shadow_spread_init", JAVA_INT, JAVA_LONG);
	private static final MethodHandle SWAP = fn("mc_shadow_swap", null, JAVA_LONG);
	private static final MethodHandle TIMES_FRAME = fn("mc_shadow_times_frame", JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle TIMES = fn("mc_shadow_times", null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle TINT_LITE_INIT = fn("mc_shadow_tint_lite_init", JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle TINT_RENDER_EX = fn("mc_shadow_tint_render_ex", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
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

	/**
	 * M3.3: render with a target (0 the bound map, 1 the back map), load (1 keep the slice's content) and a GPU timer kind
	 * (-1 none; 0 near, 1 far whole, 2 far part).
	 */
	static int renderEx(long enc, long shadow, int layer, long cascade, int cascadeBytes, long chunks, long chunksOffset, long regions, long regionsOffset,
		long draws, int drawCount, long atlas, float slopeScale, float clamp, int target, int load, int timerKind) {
		try {
			return (int) RENDER_EX.invokeExact(enc, shadow, layer, cascade, cascadeBytes, chunks, chunksOffset, regions, regionsOffset, draws, drawCount, atlas,
				slopeScale, clamp, target, load, timerKind);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** M3.3: makes the back map (0 ready, 1 failed). */
	static int spreadInit(long shadow) {
		try {
			return (int) SPREAD_INIT.invokeExact(shadow);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** M3.3: the back map becomes the bound one (texture() changes). */
	static void swap(long shadow) {
		try {
			SWAP.invokeExact(shadow);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** M3.3: starts a timed frame (0), or 1 when the GPU can't time passes. */
	static int timesFrame(long shadow, long frame) {
		try {
			return (int) TIMES_FRAME.invokeExact(shadow, frame);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** M3.3: the timers since the last call into out (9 floats: ms per run x4, runs x4, frames read back). */
	static void times(long shadow, long out) {
		try {
			TIMES.invokeExact(shadow, out);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** M3.3: the lite tint map (made on first call), 0 when it can't be made. */
	static long tintLiteInit(long shadow, int resolution) {
		try {
			return (long) TINT_LITE_INIT.invokeExact(shadow, resolution);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int tintRenderEx(long enc, long shadow, long cascade, int cascadeBytes, long chunks, long chunksOffset, long regions, long regionsOffset, long draws,
		int drawCount, long atlas, long params, int paramBytes, int lite, int timerKind) {
		try {
			return (int) TINT_RENDER_EX.invokeExact(enc, shadow, cascade, cascadeBytes, chunks, chunksOffset, regions, regionsOffset, draws, drawCount, atlas, params,
				paramBytes, lite, timerKind);
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
