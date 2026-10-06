/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench.mixin;

import mcbench.BenchDriver;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	/** One call per main-loop iteration (= one frame): frame timing + the bench state machine. */
	@Inject(method = "runTick", at = @At("HEAD"))
	private void mcbench$frame(boolean advanceGameTime, CallbackInfo ci) {
		BenchDriver.frame((Minecraft) (Object) this);
	}

	/** After the client ticks, right before extraction/rendering: re-apply the scripted camera pose for this instant. */
	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void mcbench$beforeRender(boolean advanceGameTime, CallbackInfo ci) {
		BenchDriver.beforeRender((Minecraft) (Object) this);
	}
}
