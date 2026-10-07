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
package mcopt.metal.mixin.shade;

import mcopt.metal.shade.taa.ShadeTaa;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Native shading's TAA jitter at the source: every world draw copies its projection from the camera state, so a sub-pixel
 * offset put in the projection matrix reaches vanilla, Sodium and Distant Horizons draws alike (far-terrain-plan §3.2-3).
 */
@Mixin(Camera.class)
abstract class ShadeTaaCameraMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void mcopt$taaJitter(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!ShadeTaa.ON) return;
		ShadeTaa taa = ShadeTaa.get();
		if (taa != null) taa.jitter(cameraState.projectionMatrix);
	}
}
