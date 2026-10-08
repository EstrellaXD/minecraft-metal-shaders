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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block and section changes into the dynamic lights' block occupancy (no light through a wall just built, at once through one just dug). */
@Mixin(ClientLevel.class)
abstract class DynLightClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void mcopt$dynLightBlock(BlockPos pos, BlockState old, BlockState current, int updateFlags, CallbackInfo ci) {
		DynLights.blockChanged(pos, current);
	}

	@Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
	private void mcopt$dynLightSection(int chunkX, int chunkY, int chunkZ, CallbackInfo ci) {
		DynLights.sectionChanged(chunkX, chunkY, chunkZ);
	}

	@Inject(method = "setSectionRangeDirty", at = @At("HEAD"))
	private void mcopt$dynLightSections(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
		DynLights.sectionsChanged(minX, minY, minZ, maxX, maxY, maxZ);
	}
}
