/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench.mixin;

import mcbench.BobSim;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dmcbench.bob (BobSim): the bobView option counts as on while it is set (the dev instances have it off). */
@Mixin(GameRenderer.class)
abstract class GameRendererBobMixin {
	@Shadow @Final private GameRenderState gameRenderState;

	@Inject(method = "extractOptions", at = @At("TAIL"))
	private void mcbench$bobView(CallbackInfo ci) {
		if (BobSim.active()) this.gameRenderState.optionsRenderState.bobView = true;
	}
}
