// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import mcopt.metal.shade.shadow.ShadowPass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sun shadows (ShadowPass): cascades fitted at the top of the level's frame; the depth passes encoded right before its frame
 * graph runs (Sodium's lists and uploads for this frame are done by then, and no world pass has opened yet, so the
 * Sky + Main encoder merge is untouched); the debug view at the end.
 */
@Mixin(LevelRenderer.class)
abstract class ShadowLevelRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mcopt$shadowBegin(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState, GpuBufferSlice terrainFog,
		Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
		ShadowPass.beginFrame(cameraState);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
	private void mcopt$shadowRender(CallbackInfo ci) {
		ShadowPass.renderFrame();
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void mcopt$shadowEnd(CallbackInfo ci) {
		ShadowPass.endFrame();
	}
}
