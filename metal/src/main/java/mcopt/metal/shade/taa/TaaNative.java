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
package mcopt.metal.shade.taa;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import mcopt.metal.MetalBridge;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Downcalls into native/mctaa.m (linked into libmcmetal with the backend; pointers cross as longs). */
final class TaaNative {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle NEW = fn("mc_taa_new", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle RESIZE = fn("mc_taa_resize", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle RESOLVE = fn("mc_taa_resolve", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle HISTORY = fn("mc_taa_history", true, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle HISTORY_DEPTH = fn("mc_taa_history_depth", true, JAVA_LONG, JAVA_LONG);

	private TaaNative() {
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

	static long create(long ctx, long source, int hdr, long err, int errCap) { try { return (long) NEW.invokeExact(ctx, source, hdr, err, errCap); } catch (Throwable t) { throw rethrow(t); } }
	static int resize(long taa, long ctx, int width, int height) { try { return (int) RESIZE.invokeExact(taa, ctx, width, height); } catch (Throwable t) { throw rethrow(t); } }
	static void resolve(long enc, long taa, long color, long depth, long reactive, long dst, long params, int length) { try { RESOLVE.invokeExact(enc, taa, color, depth, reactive, dst, params, length); } catch (Throwable t) { throw rethrow(t); } }
	static long history(long taa) { try { return (long) HISTORY.invokeExact(taa); } catch (Throwable t) { throw rethrow(t); } }
	static long historyDepth(long taa) { try { return (long) HISTORY_DEPTH.invokeExact(taa); } catch (Throwable t) { throw rethrow(t); } }
}
