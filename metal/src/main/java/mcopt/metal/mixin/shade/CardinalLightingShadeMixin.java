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

import mcopt.metal.shade.Shade;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's directional face shade (sides 0.6-0.8, bottoms 0.5) off while native shading lights faces itself: every face
 * gets the upward face's value, as Sodium's light pipelines and vanilla's block lighter both ask byFace. Chunks built while
 * it changes are rebuilt (Shade.setFaceShadeOff). The terrain shaders put vanilla's shade back outside the overworld.
 */
@Mixin(CardinalLighting.class)
abstract class CardinalLightingShadeMixin {
	@Inject(method = "byFace", at = @At("HEAD"), cancellable = true)
	private void mcopt$faceShadeOff(Direction face, CallbackInfoReturnable<Float> cir) {
		if (Shade.faceShadeOff()) cir.setReturnValue(((CardinalLighting) (Object) this).up());
	}
}
