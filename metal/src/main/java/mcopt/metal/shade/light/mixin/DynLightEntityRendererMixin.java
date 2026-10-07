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
package mcopt.metal.shade.light.mixin;

import mcopt.metal.shade.light.DynLights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The local player takes the light of what it holds: its packed light (which its first-person hands and held items, drawn
 * after the world with the game's own shaders, and its third-person model use) is raised to the dynamic block light level
 * at its light probe (DynLights.levelAt). Other entities get the lights per vertex in the world pass (DynLightMsl).
 */
@Mixin(EntityRenderer.class)
abstract class DynLightEntityRendererMixin {
	@Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
	private void mcopt$dynLightPlayer(Entity entity, float partialTickTime, CallbackInfoReturnable<Integer> cir) {
		if (!DynLights.HAND_LIGHT || entity != Minecraft.getInstance().player) return;
		int packed = cir.getReturnValueI();
		int block = (packed & 0xFFFF) >> 4;
		if (block >= 15) return;
		Vec3 p = entity.getLightProbePosition(partialTickTime);
		int dyn = DynLights.levelAt(p.x, p.y, p.z);
		if (dyn > block) cir.setReturnValue(packed & ~0xFFFF | dyn << 4);
	}
}
