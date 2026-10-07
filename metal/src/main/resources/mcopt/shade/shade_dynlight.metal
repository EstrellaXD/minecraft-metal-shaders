// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// M3: the seam where dynamic lights (held torches, burning mobs, glowing items, blazes...) plug into every lit surface.
// Loaded after shade_m2.metal in ShadeMsl.prelude(), so every shaded stage (Sodium terrain, water, the entity / item /
// particle variants of M3) sees it. THIS FILE IS THE BASE'S STUB: it adds nothing. The dynamic-lights module replaces the
// body of shadeDynamicLights (keep the signature and ShadeDynIn; add fields at the end of ShadeDynIn if needed, callers
// fill unknown ones with defaults through shadeDynIn()).
//
// Contract:
//   - called once per shaded fragment, after the surface's own lighting (shadeLit2), with the surface's camera-relative
//     world position, unit shading normal (world space, facing the camera for two-sided cutouts) and linear albedo;
//   - returns the RADIANCE to add (linear Rec.709, before exposure, same units as shadeLit2's result: a white Lambert
//     surface lit by irradiance E returns E / pi * albedo), so callers just do `radiance += shadeDynamicLights(...)`
//     before fog (shadeFogAir2) and exposure (shadeFinish2 / shadeOut);
//   - must be cheap when there are no lights: test a count (ShadeLook / a resource) first and return 0;
//   - sky, block: the surface's vanilla light levels 0..1, so the module can fade where block light is already strong
//     (bible E09 step 6: E_hand * (1 - 0.5 F(b) / 2.75));
//   - kind (SHADE_DYN_*): what is being lit, e.g. to skip self-lighting of the hand or to soften particles;
//   - pixel: the fragment's position (px), for noise / dithered contact shadows (vary it per frame with ShadeFrame.flags2.x);
//   - R: the ShadeResources argument buffer when the stage has it bound (buffer(19), HDR variants), else nullptr: the
//     module may keep its light list in a ShadeResources texture slot (4-7 are free) or in ShadeLook (heldLight is reserved
//     for the held light at offset 608).
#ifndef MCOPT_SHADE_DYNLIGHT
#define MCOPT_SHADE_DYNLIGHT

#define SHADE_DYN_TERRAIN 0     // Sodium terrain (solid, cutout, translucent)
#define SHADE_DYN_WATER 1       // the water surface (albedo 1: the module returns the light on the surface)
#define SHADE_DYN_ENTITY 2      // mobs, players, block entities (chests, signs, item frames...), dropped items, armour
#define SHADE_DYN_HAND 3        // the first-person hand and held items
#define SHADE_DYN_PARTICLE 4    // particles (no real normal: n faces the camera)

struct ShadeDynIn {
	float3 p;      // camera-relative world position
	float3 n;      // unit world normal (facing the camera for two-sided geometry)
	float3 albedo; // linear albedo (premultiplied by nothing; alpha handled by the caller)
	float2 pixel;  // fragment position, pixels
	half sky;      // vanilla sky light 0..1
	half block;    // vanilla block light 0..1
	int kind;      // SHADE_DYN_*
};

static inline ShadeDynIn shadeDynIn(float3 p, float3 n, float3 albedo, float2 pixel, half sky, half block, int kind) {
	ShadeDynIn d;
	d.p = p;
	d.n = n;
	d.albedo = albedo;
	d.pixel = pixel;
	d.sky = sky;
	d.block = block;
	d.kind = kind;
	return d;
}

// Base stub: no dynamic lights. (The dynamic-lights module replaces this function's body.)
static inline float3 shadeDynamicLights(ShadeDynIn d, constant ShadeFrame &f, constant ShadeLook &L, constant ShadeResources *R) {
	return float3(0.0);
}

#endif
