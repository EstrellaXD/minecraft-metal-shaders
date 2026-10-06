/*
 * Copyright 2026 mcbench contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package mcbench.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The bench never sends input and the window may be unfocused/occluded: vanilla would throttle to 30 fps after 60 s
 * "AFK", 10 fps when an exclusive-fullscreen window loses focus, 60 fps in menus. While mcbench is active the user's
 * maxFps option is always the limit.
 */
@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitTrackerMixin {
	@Shadow
	private int framerateLimit;

	@Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
	private void mcbench$noThrottle(CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(this.framerateLimit);
	}
}
