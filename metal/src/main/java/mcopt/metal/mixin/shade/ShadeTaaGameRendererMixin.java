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

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import mcopt.metal.shade.taa.ShadeTaa;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Native shading's TAA / TAAU (mcopt.metal.shade.taa.ShadeTaa): the world part of each frame draws into the world target
 * (render scale, a live sub-rect for dynamic resolution) with a jittered projection, and is resolved into the real main
 * target at the start of render3dHud, before the hand, which (with screen effects and the GUI) then draws at native
 * resolution. Applies only with mcopt.shade=true (ShadeMixinPlugin); does nothing with mcopt.shade.taa=false.
 */
@Mixin(GameRenderer.class)
abstract class ShadeTaaGameRendererMixin {
	@Shadow @Final @Mutable private RenderTarget mainRenderTarget;
	@Shadow @Final private GameRenderState gameRenderState;
	/** The real main target while the world target stands in for it; null outside the world part of the frame. */
	@Unique private @Nullable RenderTarget mcopt$taaFull;

	// The first device call in render() clears the main target: the world target takes its place from there on.
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/renderpearl/api/device/GpuDevice;", ordinal = 0))
	private void mcopt$taaBeginWorld(CallbackInfo ci) {
		if (!ShadeTaa.ON || !this.gameRenderState.shouldRenderLevel) return;
		ShadeTaa taa = ShadeTaa.get();
		if (taa == null) return;
		this.mcopt$taaFull = this.mainRenderTarget;
		this.mainRenderTarget = taa.beginWorld(this.mainRenderTarget);
	}

	// The world is drawn and its depth is still there (the hand pass clears it next): resolve, and give the main target back.
	@Inject(method = "render3dHud", at = @At("HEAD"))
	private void mcopt$taaResolve(CallbackInfo ci) {
		if (this.mcopt$taaFull == null) return;
		this.mainRenderTarget = this.mcopt$taaFull;
		this.mcopt$taaFull = null;
		ShadeTaa.get().resolve(this.mainRenderTarget);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
	private void mcopt$taaEndFrame(CallbackInfo ci) {
		if (this.mcopt$taaFull != null) { // never reached render3dHud: nothing resolved, put the main target back anyway
			this.mainRenderTarget = this.mcopt$taaFull;
			this.mcopt$taaFull = null;
		}
		if (ShadeTaa.ON && ShadeTaa.get() != null) ShadeTaa.get().endFrame();
	}

	// ScreenSize is only read by world shaders (lines, texture sampling): it follows the live render size.
	@ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJFILnet/minecraft/world/phys/Vec3;Z)V"), index = 0)
	private int mcopt$taaWidth(int width) {
		return this.mcopt$taaFull != null ? ShadeTaa.get().width(width) : width;
	}

	@ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJFILnet/minecraft/world/phys/Vec3;Z)V"), index = 1)
	private int mcopt$taaHeight(int height) {
		return this.mcopt$taaFull != null ? ShadeTaa.get().height(height) : height;
	}

	// The projection every world draw uses (jittered, view bobbing applied), for reprojection.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V"))
	private void mcopt$taaCamera(CallbackInfo ci, @Local Matrix4f projectionMatrix, @Local CameraRenderState cameraState) {
		if (this.mcopt$taaFull != null) ShadeTaa.get().camera(projectionMatrix, cameraState.viewRotationMatrix, cameraState.pos);
	}
}
