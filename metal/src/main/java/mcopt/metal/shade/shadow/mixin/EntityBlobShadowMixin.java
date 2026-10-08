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
package mcopt.metal.shade.shadow.mixin;

import mcopt.metal.shade.shadow.EntityCasters;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M3 entity shadows: vanilla's blob shadow pieces are left out where the real (shadow-map) entity shadow replaces them
 * (EntityCasters.dropBlob: sunlit ground with sky light >= 5); caves, dark interiors, the night, the Nether and the End keep
 * them. Applied only with mcopt.shade=true and shadows on (ShadowMixinPlugin); inert while entity shadows are off.
 */
@Mixin(EntityRenderer.class)
abstract class EntityBlobShadowMixin {
	@Inject(method = "extractShadowPiece", at = @At("HEAD"), cancellable = true)
	private void mcopt$realShadowHere(EntityRenderState state, Level level, float pow, BlockPos.MutableBlockPos pos, ChunkAccess chunk, CallbackInfo ci) {
		if (EntityCasters.ON && EntityCasters.dropBlob(level, pos)) ci.cancel();
	}
}
