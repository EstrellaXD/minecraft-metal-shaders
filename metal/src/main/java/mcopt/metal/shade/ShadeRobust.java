/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import mcopt.metal.MetalBridge;

/**
 * M3.3 robustness checks (dev tools, inert by default):
 * <ul>
 * <li>{@code -Dmcopt.shade.dev.failCompile=<comma list>} (dev only, default empty = nothing happens): makes the native shading's
 * shader builds fail on purpose, to check the fallbacks. Each entry is a substring of what is being built, or {@code *} for
 * all: a registry (shaded) pipeline by its name (e.g. {@code terrain}, {@code minecraft:pipeline/sky}; the game's own shaders
 * are then used for it: MetalPipeline.compile), {@code library:<Class>} for the MSL libraries built through
 * ShadeRender.library ({@code library:ShadePost}, {@code library:ShadeWater}, {@code library:ShadeWeather}), {@code lut}
 * (the sky LUT: ShadeBindings' vanilla-look fallback), {@code taa}, {@code vl}, {@code clouds}, {@code shadow}. Read at every
 * build, so it can be set live: a resource reload (F3+T) rebuilds the registry pipelines (mcbench RobustShots does that).</li>
 * <li>{@link #deviceAllocatedBytes()}: MTLDevice.currentAllocatedSize, read reflectively by the bench's rejoin leak check.</li>
 * </ul>
 */
public final class ShadeRobust {
	private ShadeRobust() {
	}

	/** Whether -Dmcopt.shade.dev.failCompile asks the build of what to fail (dev only). */
	public static boolean failCompile(String what) {
		String v = System.getProperty("mcopt.shade.dev.failCompile");
		if (v == null || v.isBlank()) return false;
		for (String k : v.split(",")) {
			k = k.trim();
			if (!k.isEmpty() && (k.equals("*") || what.contains(k))) return true;
		}
		return false;
	}

	/** Throws an injected build failure for what when -Dmcopt.shade.dev.failCompile asks for it (dev only; a no-op otherwise). */
	public static void maybeFail(String what) {
		if (failCompile(what)) throw new IllegalStateException("injected shader build failure for " + what + " (-Dmcopt.shade.dev.failCompile)");
	}

	/** Bytes the Metal device has allocated now (MTLDevice.currentAllocatedSize), or -1 off the Metal backend. Render thread. */
	public static long deviceAllocatedBytes() {
		Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
		return encoder == null ? -1 : MetalBridge.deviceAllocated(MetalBridge.ctx(encoder));
	}
}
