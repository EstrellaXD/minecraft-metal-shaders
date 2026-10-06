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
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla's directional face shade (sides 0.6-0.8, bottoms 0.5) off in Sodium's chunk meshing while native shading lights
 * terrain faces itself: every face gets the upward face's value. Only Sodium's light pipelines are touched; vanilla's own
 * block lighter (falling blocks, pistons, block entities) keeps its shade. Gated on the Metal backend actually running
 * (ShadeFrameUpdater.faceShadeOff), so a fallback to OpenGL keeps vanilla's look. The terrain shaders put vanilla's shade
 * back outside the overworld.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.model.light.flat.FlatLightPipeline", remap = false)
abstract class SodiumFlatFaceShadeMixin {
	@Redirect(method = "getShade", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/CardinalLighting;byFace(Lnet/minecraft/core/Direction;)F"))
	private float mcopt$faceShadeOff(CardinalLighting lighting, Direction face) {
		return ShadeFrameUpdater.faceShadeOff() ? lighting.up() : lighting.byFace(face);
	}
}
