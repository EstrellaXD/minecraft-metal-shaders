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
package mcopt.metal.shade.dims.mixin;

import mcopt.metal.shade.dims.ShadeDims;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In the Nether and the End (open air, no blindness or darkness) the fog is ours: shade_final applies a layered haze from
 * depth to every pixel once (bible E24/E25), so vanilla's environmental fog, which every vanilla pipeline (entities,
 * particles, block entities) would add on top in its own colour, is pushed out of reach (start beyond end: "no fog" for the
 * game's shaders and Sodium's). Its distances are kept for our fog's density (ShadeDims.vanillaFog). The render distance
 * fog is left as it is.
 */
@Mixin(FogRenderer.class)
abstract class DimsFogMixin {
	@Shadow
	private FogType getFogType(Camera camera) {
		throw new AssertionError();
	}

	@Inject(method = "setupFog", at = @At("RETURN"))
	private void mcopt$dimsFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level,
		CallbackInfoReturnable<FogData> cir) {
		if (level == null || !mcopt.metal.shade.dims.ShadeDims.on()) return;
		var dim = level.dimension();
		if (dim != Level.NETHER && dim != Level.END) return;
		if (this.getFogType(camera) != FogType.ATMOSPHERIC) return;
		FogData fog = cir.getReturnValue();
		if (fog == null || fog.environmentalEnd < 24f) return; // blindness, darkness: vanilla's
		ShadeDims.vanillaFog(fog.environmentalStart, fog.environmentalEnd);
		// the Nether: vanilla's fog only at the view distance's edge (entities, particles out there fade into the fog colour
		// where our border fog is already opaque); its end is where Sodium stops drawing chunks (ShadeDims.netherCull)
		float cull = dim == Level.NETHER ? ShadeDims.netherCull(fog.environmentalEnd) : 0f;
		if (cull > 0f) {
			fog.environmentalStart = cull * 0.96f;
			fog.environmentalEnd = cull;
		} else {
			fog.environmentalStart = 1e6f;
			fog.environmentalEnd = 1e6f + 1f;
		}
	}
}
