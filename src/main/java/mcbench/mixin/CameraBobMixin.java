/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench.mixin;

import mcbench.BobSim;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dmcbench.bob (BobSim): the walk cycle vanilla's bobView reads, as if the camera's player walked on the ground. */
@Mixin(Camera.class)
abstract class CameraBobMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void mcbench$bob(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!BobSim.active() || !cameraState.entityRenderState.isPlayer) return;
		cameraState.entityRenderState.bob = BobSim.amplitude();
		cameraState.entityRenderState.backwardsInterpolatedWalkDistance = -BobSim.walkDistance();
	}
}
