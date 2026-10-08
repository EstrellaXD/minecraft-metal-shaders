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

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.jspecify.annotations.Nullable;

/**
 * M3.3 dev switches for the End + Distant Horizons investigation (all read live, all off by default; nothing here changes a
 * frame unless a property is set):
 * <ul>
 * <li>{@code mcopt.shade.passLog=<tag>}: when the value changes, the next world phase logs every render pass the HDR redirect
 * sees (label, colour target, clear, depth) and the bridge / final-pass events, prefixed "[mcopt] passLog".</li>
 * <li>{@code mcopt.shade.dhDebug} bits: 1 = Distant Horizons' passes do not go through the HDR bridge (they draw into the
 * display-referred main target, which the final pass then overwrites: their LODs vanish, the HDR frame is the game's alone).</li>
 * </ul>
 */
public final class DimsDebug {
	private static String lastTag = "";
	private static boolean logging;
	private static int passes;

	private DimsDebug() {
	}

	/** ShadePost.beginWorld. */
	public static void beginFrame() {
		String t = System.getProperty("mcopt.shade.passLog", "");
		logging = !t.isEmpty() && !t.equals(lastTag);
		if (!logging) return;
		lastTag = t;
		passes = 0;
		System.out.println("[mcopt] passLog " + t + ": world phase starts");
	}

	public static boolean logging() {
		return logging;
	}

	/** Every render pass the redirect sees in the world phase (main: the game's main colour texture this frame). */
	public static void pass(RenderPassDescriptor d, @Nullable GpuTexture main) {
		if (!logging) return;
		StringBuilder s = new StringBuilder("[mcopt] passLog   #").append(passes++).append(" '").append(d.label().get()).append("' color:");
		for (var a : d.colorAttachments()) {
			if (a == null) {
				s.append(" -");
				continue;
			}
			GpuTexture t = a.textureView().texture();
			s.append(' ').append(t == main ? "MAIN" : t.getLabel()).append('[').append(t.getFormat()).append(' ').append(t.getWidth(0)).append('x')
				.append(t.getHeight(0)).append(" mip").append(a.textureView().baseMipLevel()).append(']');
			if (a.clearValue().isPresent()) s.append(" clear=").append(a.clearValue().get());
		}
		var dep = d.depthAttachment();
		if (dep != null) {
			s.append(" depth: ").append(dep.textureView().texture().getLabel());
			if (dep.clearValue().isPresent()) s.append(" clear=").append(dep.clearValue().getAsDouble());
		}
		System.out.println(s);
	}

	public static void note(String what) {
		if (logging) System.out.println("[mcopt] passLog   " + what);
	}

	/** ShadePost.beforeHand: the world phase ended. */
	public static void endFrame() {
		if (!logging) return;
		System.out.println("[mcopt] passLog   world phase ends (" + passes + " passes)");
		logging = false;
	}

	/**
	 * mcopt.shade.dhClear (default true, read live; false = M3.2): ShadePost.openBridge applies main's pending clear to the HDR
	 * target when Distant Horizons' first pass comes before any world pass (the End + DH fix). Not a dev switch: the fix's flag.
	 */
	public static boolean dhClear() {
		return !"false".equals(System.getProperty("mcopt.shade.dhClear"));
	}

	/** mcopt.shade.dhDebug bit 1: keep Distant Horizons' passes out of the HDR bridge. */
	public static boolean noBridge() {
		return (Integer.getInteger("mcopt.shade.dhDebug", 0) & 1) != 0;
	}
}
