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

import com.mojang.blaze3d.pipeline.RenderTarget;
import mcopt.metal.shade.ShadeHooks;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M2's HDR frame (ShadePost): the world phase ends right before the hand (render3dHud, H7: depth-based post, bloom,
 * exposure and the tone map into the main target while the world depth is still there), with FogRenderer.endFrame (H8,
 * before the GUI) as the fallback for frames that skip render3dHud. mainRenderTarget is MetalFX's scaled world target while
 * that is on (GameRendererFxMixin swaps the field for the world part of the frame).
 */
@Mixin(GameRenderer.class)
abstract class ShadePostMixin {
	@Shadow @Final private RenderTarget mainRenderTarget;

	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void mcopt$shadeBeforeHand(CallbackInfo ci) {
		ShadeHooks.beforeHand(this.mainRenderTarget);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
	private void mcopt$shadeBeforeGui(CallbackInfo ci) {
		ShadeHooks.beforeHand(this.mainRenderTarget);
	}
}
