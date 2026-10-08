/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade.taa;

import org.jspecify.annotations.Nullable;

/**
 * M3.3: upstream's frame generation (mcopt.metal.FrameGen, -Dmcopt.metal.framegen=true) under the native shading's TAAU. When
 * the level has drawn, GameRenderer's main target is still TAAU's world target: its depth holds this frame's render only in
 * the live sub-rect (0, 0, width(), height()) (dynamic resolution / the pause menu's resolution) and with a jittered
 * projection. Upstream's motion pass reads that depth texel for texel at the output size, so most of the frame got the
 * motion of another pixel. With {@code mcopt.shade.framegen.taau} (default true, read every frame; false = M3.2 = upstream's
 * reading) FrameGen sizes itself to the output target, reads the depth under each output pixel's uv in the live sub-rect
 * (mcframegen.m fg_motion_depth_sub) and reprojects with the unjittered matrices.
 */
public final class ShadeFrameGen {
	private ShadeFrameGen() {
	}

	/** TAAU when it stands in for the main target right now and the fix is on (mcopt.shade.framegen.taau), else null. */
	public static @Nullable ShadeTaa swapped() {
		if (!ShadeTaa.ON || "false".equals(System.getProperty("mcopt.shade.framegen.taau"))) return null;
		ShadeTaa t = ShadeTaa.get();
		return t != null && t.swapped() && t.outputTarget() != null ? t : null;
	}
}
