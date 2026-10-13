// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading, not part of the mod.
package mcopt.metal;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mcopt.metal.shade.ShadeCompile;
import org.lwjgl.system.MemoryUtil;

/**
 * Headless check of native shading's parallel compile (mcopt.metal.shade.ShadeCompile), outside the game, run by run.sh in
 * a process with a Metal shader cache of its own (jvmrun.m; emptied first for a cold run):
 * <ul>
 * <li>{@code modules seq}: every shade module's warm-up task one after another on this thread, i.e. the libraries and
 * pipelines the modules compiled on the render thread at the first world frame before;</li>
 * <li>{@code modules par}: ShadeCompile.start (the pool and its warm-up), waiting for it, then every task again on this
 * thread: what each module's own setup then costs the render thread (cache hits);</li>
 * <li>{@code prewarm seq|par VS FS...}: terrain fragment variants built into pipelines as MetalPipeline.prewarm builds them
 * (Sodium's vertex layout, RG11B10Float + Depth32Float): one after another on this thread as before, or through the prewarm
 * runner ShadeCompile registers (failed builds made again alone, as MetalPipeline does).</li>
 * </ul>
 */
public final class CompileCheck {
	public static void main(String[] a) throws Exception {
		long ctx = MetalBridge.createHeadlessContext();
		String mode = a[0] + " " + a[1];
		// the module tasks below are timed as the game's render thread would time them (ShadeCompile.setup counts that thread only)
		Thread.currentThread().setName("Render thread");
		switch (mode) {
			case "modules seq" -> {
				shade("worldStart");
				report("one after another (the render thread before)", runEach(ShadeCompile.warmUpTasks(ctx)), -1);
				summary();
			}
			case "modules par" -> {
				long t0 = System.nanoTime();
				ShadeCompile.start(ctx);
				while (!ShadeCompile.warmUpDone()) Thread.sleep(2);
				long wall = System.nanoTime() - t0;
				shade("worldStart");
				report("after the warm-up (the render thread now)", runEach(ShadeCompile.warmUpTasks(ctx)), wall);
				summary();
			}
			case "prewarm seq", "prewarm par" -> prewarm(ctx, a[1].equals("par"), a[2], List.of(a).subList(3, a.length));
			default -> throw new IllegalArgumentException("modes: modules seq|par, prewarm seq|par VS FS...");
		}
	}

	/** ShadeCompile's package-private world hooks, as ShadeBindings.init / ShadeFrameUpdater call them. */
	private static void shade(String method) throws Exception {
		var m = ShadeCompile.class.getDeclaredMethod(method);
		m.setAccessible(true);
		m.invoke(null);
	}

	/** The startup summary the game logs after mcopt.shade.compile.summaryFrames world frames. */
	private static void summary() throws Exception {
		for (int i = 0; i < Integer.getInteger("mcopt.shade.compile.summaryFrames", 300); i++) shade("frame");
	}

	private static List<String> runEach(List<Map.Entry<String, Runnable>> tasks) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, Runnable> t : tasks) {
			long t0 = System.nanoTime();
			String failed = "";
			try {
				t.getValue().run();
			} catch (Throwable e) {
				failed = " FAILED: " + e;
			}
			ShadeCompile.setup(t.getKey(), t0);
			out.add(String.format(Locale.ROOT, "%.1f %s%s", (System.nanoTime() - t0) / 1e6, t.getKey(), failed));
		}
		return out;
	}

	private static void report(String what, List<String> lines, long warmWall) {
		double total = 0;
		for (String l : lines) {
			System.out.println("  " + l.replaceFirst(" ", " ms  "));
			total += Double.parseDouble(l.substring(0, l.indexOf(' ')));
		}
		System.out.println(String.format(Locale.ROOT, "MODULES %s: %.0f ms on this thread%s", what, total,
			warmWall < 0 ? "" : String.format(Locale.ROOT, " (the warm-up took %.0f ms of wall time on the pool before)", warmWall / 1e6)));
	}

	/** Sodium's terrain vertex layout (buffer 27, 20-byte vertices) into RG11B10Float + Depth32Float, in mc_pipeline_new's format. */
	private static final int[] TERRAIN = {1, 27, 20, 0, 4, 0, 27, 0, 37, 1, 27, 8, 9, 2, 27, 12, 13, 3, 27, 16, 3, 1, 92, 15, 0, 1, 0, 0, 1, 0, 0, 252, 3};

	private static void prewarm(long ctx, boolean parallel, String vs, List<String> fragments) throws Exception {
		String vsrc = Files.readString(Path.of(vs));
		List<Runnable> builds = new ArrayList<>();
		for (String f : fragments) {
			String fsrc = Files.readString(Path.of(f));
			builds.add(() -> build(ctx, vsrc, fsrc, f));
		}
		long t0 = System.nanoTime();
		int again = 0;
		if (parallel) {
			ShadeCompile.start(ctx); // with -Dmcopt.shade.compile.warm=false: the pool and the runner only
			MetalShaderRegistry.PrewarmRunner runner = MetalShaderRegistry.prewarmRunner();
			if (runner == null) throw new IllegalStateException("no prewarm runner (mcopt.shade.compile.parallel off?)");
			for (int i : runner.runAll(builds)) {
				again++;
				try {
					builds.get(i).run();
				} catch (RuntimeException e) {
					System.out.println("  failed again alone: " + e.getMessage());
				}
			}
		} else {
			for (Runnable b : builds) {
				try {
					b.run();
				} catch (RuntimeException e) {
					System.out.println("  failed: " + e.getMessage());
				}
			}
		}
		System.out.println(String.format(Locale.ROOT, "PREWARM %d variants %s: %.0f ms%s", builds.size(), parallel ? "through the prewarm runner" : "one after another",
			(System.nanoTime() - t0) / 1e6, again > 0 ? " (" + again + " made again alone)" : ""));
	}

	private static void build(long ctx, String vsrc, String fsrc, String name) {
		ByteBuffer v = MemoryUtil.memUTF8(vsrc), f = MemoryUtil.memUTF8(fsrc), entry = MemoryUtil.memUTF8("main0");
		IntBuffer desc = MemoryUtil.memAllocInt(TERRAIN.length).put(TERRAIN).flip();
		long err = MemoryUtil.nmemCalloc(1, 4096), vlib = 0, flib = 0;
		try {
			vlib = Native.libraryNew(ctx, MemoryUtil.memAddress(v), err, 4096);
			if (vlib == 0) throw new IllegalStateException(name + " vertex: " + MemoryUtil.memUTF8(err));
			flib = Native.libraryNew(ctx, MemoryUtil.memAddress(f), err, 4096);
			if (flib == 0) throw new IllegalStateException(name + " fragment: " + MemoryUtil.memUTF8(err));
			long pso = Native.pipelineNew(ctx, vlib, MemoryUtil.memAddress(entry), flib, MemoryUtil.memAddress(entry), MemoryUtil.memAddress(desc), err, 4096);
			if (pso == 0) throw new IllegalStateException(name + ": " + MemoryUtil.memUTF8(err).lines().findFirst().orElse(""));
			Native.release(pso);
		} finally {
			if (vlib != 0) Native.release(vlib);
			if (flib != 0) Native.release(flib);
			MemoryUtil.memFree(v);
			MemoryUtil.memFree(f);
			MemoryUtil.memFree(entry);
			MemoryUtil.memFree(desc);
			MemoryUtil.nmemFree(err);
		}
	}
}
