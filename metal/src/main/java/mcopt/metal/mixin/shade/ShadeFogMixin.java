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

import mcopt.metal.shade.ShadeFrameUpdater;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Under water in the overworld, the game's fog colour (which is also the frame's clear colour, seen behind distant
 * geometry) becomes the shaded water colour (ShadeFrameUpdater.waterFog: the light in the water through the same exposure
 * and tone curve as everything else) instead of vanilla's vivid blue, so the clear colour and the terrain's fog agree.
 */
@Mixin(FogRenderer.class)
abstract class ShadeFogMixin {
	@Shadow
	private FogType getFogType(Camera camera) {
		throw new AssertionError();
	}

	@Inject(method = "computeFogColor", at = @At("TAIL"))
	private void mcopt$waterFog(Camera camera, float partialTicks, ClientLevel level, int renderDistance, float darkenWorldAmount, Vector4f dest, CallbackInfo ci) {
		if (this.getFogType(camera) == FogType.WATER) ShadeFrameUpdater.waterFog(level, dest);
	}
}
