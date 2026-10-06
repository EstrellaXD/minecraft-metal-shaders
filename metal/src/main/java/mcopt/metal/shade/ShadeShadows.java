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

import mcopt.metal.shade.shadow.ShadowConfig;
import mcopt.metal.shade.shadow.ShadowPass;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;

/**
 * The glue between the shadow pass (mcopt.metal.shade.shadow) and the shared per-frame state: before ShadeFrame is packed
 * it takes this frame's cascade matrices, texel sizes and light direction from ShadowPass (so N.L and the map agree), and
 * once the depth passes are encoded it points the global texture(16) binding at the map, or turns shadow sampling off for
 * this frame when the pass couldn't draw. Render thread only.
 */
public final class ShadeShadows {
	private static final Matrix4f C0 = new Matrix4f(), C1 = new Matrix4f();
	private static final float[] SPLITS = new float[4], LIGHT = new float[3];
	private static long boundMap;

	private ShadeShadows() {
	}

	/** At LevelRenderer.render HEAD, after the atmosphere was updated and before the frame is packed. */
	static void beforePack(CameraRenderState camera, ShadeFrame f) {
		if (!ShadowConfig.SHADOWS || ShadeBindings.fallback()) return;
		ShadowPass.beginFrame(camera);
		boolean on = ShadowPass.enabled() && ShadowPass.texture() != 0;
		ShadowPass.matrices(C0, C1);
		ShadowPass.splits(SPLITS);
		f.setShadow(on, ShadowPass.CASCADES, C0, C1, SPLITS[0], SPLITS[1], ShadowPass.resolution(), ShadowPass.receiverDepthBias(), 1f);
		f.setShadowTexels(SPLITS[2], SPLITS[3]);
		if (on) {
			ShadowPass.lightDir(LIGHT);
			System.arraycopy(LIGHT, 0, f.sky.lightDir, 0, 3);
		}
	}

	/** Right after ShadowPass.renderFrame (before the level's frame graph opens its first world pass). */
	public static void afterRender() {
		if (!Shade.ENABLED || !ShadowConfig.SHADOWS) return;
		long map = ShadowPass.texture();
		if (map != boundMap) {
			boundMap = map;
			ShadeBindings.setShadowMap(map);
		}
		ShadeFrame f = ShadeFrameUpdater.FRAME;
		boolean on = map != 0 && ShadowPass.enabled() && !ShadeBindings.fallback();
		if (on != f.shadowsEnabled) {
			f.shadowsEnabled = on;
			ShadeBindings.updateShadow();
		}
	}
}
