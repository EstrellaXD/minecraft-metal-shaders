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
package mcopt.metal.shade;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import mcopt.metal.MetalBridge;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Downcalls into native/mcshade.m (linked into libmcmetal with the backend; pointers cross as longs, like Native). */
final class ShadeNative {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle NEW = fn("mc_shade_new", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle DUMMY_SHADOW = fn("mc_shade_dummy_shadow", true, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle COMPARE_SAMPLER = fn("mc_shade_compare_sampler", true, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle SET = fn("mc_shade_set", true, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle BIND = fn("mc_shade_bind", true, null, JAVA_LONG);
	private static final MethodHandle LUT_INIT = fn("mc_shade_lut_init", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle LUT_RENDER = fn("mc_shade_lut_render", false, null, JAVA_LONG, JAVA_LONG);

	private ShadeNative() {
	}

	private static MethodHandle fn(String name, boolean critical, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return critical ? LINKER.downcallHandle(symbol, d, Linker.Option.critical(false)) : LINKER.downcallHandle(symbol, d);
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}

	static long create(long ctx) { try { return (long) NEW.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); } }
	static long dummyShadow(long state) { try { return (long) DUMMY_SHADOW.invokeExact(state); } catch (Throwable t) { throw rethrow(t); } }
	static long compareSampler(long state) { try { return (long) COMPARE_SAMPLER.invokeExact(state); } catch (Throwable t) { throw rethrow(t); } }
	static void set(long frame, long offset, long shadow, long sampler) { try { SET.invokeExact(frame, offset, shadow, sampler); } catch (Throwable t) { throw rethrow(t); } }
	static void bind(long enc) { try { BIND.invokeExact(enc); } catch (Throwable t) { throw rethrow(t); } }
	static int lutInit(long state, long ctx, long source, int width, int height, long err, int errCap) { try { return (int) LUT_INIT.invokeExact(state, ctx, source, width, height, err, errCap); } catch (Throwable t) { throw rethrow(t); } }
	static void lutRender(long enc, long state) { try { LUT_RENDER.invokeExact(enc, state); } catch (Throwable t) { throw rethrow(t); } }
}
