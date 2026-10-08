// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Dynamic lights (M3, mcopt.shade.dynLights; Java side mcopt.metal.shade.light.DynLights, native side mcdynlight.m): light
// from things that move — the torch or lantern in either of the player's hands, other players and mobs holding luminous
// items, dropped luminous items, burning mobs, blazes, magma cubes, glow squid, fire and spectral arrows, fireballs, lit TNT,
// furnace minecarts, fireworks, lightning. Gathered on the CPU each frame (up to SHADE_DL_MAX within ~48 blocks), faded in
// and out, fire-like ones flicker gently.
//
// Bindings (vertex and fragment stages of every encoder native shading binds, mcdynlight.m mc_dl_bind):
//   buffer(24)   constant ShadeDynLights&  this frame's lights + a coarse light grid around the camera (ring of shared buffers)
//   texture(23)  texture3d<half>           visibility volumes: per occluded light a 32^3 R8 block around it, 1 where the
//                                          light's centre sees the voxel's centre through air (computed every frame from the
//                                          CPU's block occupancy, dynlight_vis.metal); slab s = z slices [32 s, 32 s + 32)
// Both slots are free in every pipeline that binds the shade globals (vanilla's own use buffers 0-7, textures 0-7; Sodium
// 20-22, 26, 27-30; water 18-22, 23).
//
// Model: a light of level l (vanilla's block light emission: torch 14, lantern 15, soul torch 10, ...) reaches l blocks like
// a placed one (euclidean distance instead of vanilla's taxicab flood); at distance d it acts as block light level l - d
// through the same curve the terrain uses for placed torches (shadeLit2: F(b) = (2.2 b^7 + 0.55 b^2.4) / 2.75), times the
// art layer's block-light colour (ShadeFrame.ambientBlock) and the light's own tint (1 for torch-coloured light, cyan for
// soul fire, red for redstone, ...), times a wrapped N.L (so faces turned toward the light catch it and the far side of a
// pillar falls off), times the visibility volume (no light through walls). Lights whose cell list doesn't name a pixel cost
// nothing there: the grid (8-block cells, 64-bit light masks) keeps the per-pixel loop to the few lights that can reach it.
#ifndef MCOPT_SHADE_DYNLIGHT_MODULE // not MCOPT_SHADE_DYNLIGHT: that is the m3 seam file's (shade_dynlight.metal) guard
#define MCOPT_SHADE_DYNLIGHT_MODULE
#include <metal_stdlib>
using namespace metal;

#define SHADE_DL_MAX 64        // lights per frame (64-bit cell masks)
#define SHADE_DL_VOL 32        // voxels per side of a visibility volume
#define SHADE_DL_CUT 2.0       // blocks short of the reach where a light stops (level < 2: F < 0.2 % of the core)
#define SHADE_DL_GRID_X 16     // light grid cells (8 blocks each): 128 x 96 x 128 blocks around the camera
#define SHADE_DL_GRID_Y 12
#define SHADE_DL_GRID_Z 16
#define SHADE_DL_IRR_X 80      // the irradiance volume: 80 x 48 x 80 blocks around the camera, one voxel per block
#define SHADE_DL_IRR_Y 48
#define SHADE_DL_IRR_Z 80

struct ShadeDynLight {
	float4 pos;    // xyz camera-relative position, w reach in blocks (the light level; the light is l - d at distance d)
	float4 color;  // rgb tint x intensity (fade, flicker) relative to ShadeFrame.ambientBlock, w specular strength (0..1)
	float4 vol;    // xyz camera-relative world position of its visibility volume's min corner (block aligned), w slab (< 0: none)
};

// Byte layout = mcopt.metal.shade.light.DynLights (Java packer). 27712 bytes.
struct ShadeDynLights {
	uint4 info;      //    0  x light count, y lights with a visibility volume, z frame, w 0
	float4 grid;     //   16  xyz camera-relative min corner of the light grid, w 1 / cell size (blocks)
	float4 params;   //   32  x visibility slabs in texture(23), y global strength, z specular strength, w entity strength
	float4 irr;      //   48  xyz camera-relative min corner of the irradiance volume (textures 24/25), w 1 when it was computed
	ShadeDynLight lights[SHADE_DL_MAX];                              // 64
	uint2 cells[SHADE_DL_GRID_X * SHADE_DL_GRID_Y * SHADE_DL_GRID_Z]; // 3136: per cell the lights that can reach into it
};

// The block-light response the terrain uses for placed lights (shadeLit2's F(b), normalized to 1 at b = 1).
static inline float shadeDlCurve(float b) {
	float b7 = b * b * b; b7 = b7 * b7 * b;
	return (2.2 * b7 + 0.55 * pow(max(b, 1e-3), 2.4)) * (1.0 / 2.75);
}

// The 64-bit mask of lights that may reach camera-relative p (0 outside the grid or without lights).
static inline uint2 shadeDlMask(constant ShadeDynLights &D, float3 p) {
	if (D.info.x == 0u) return uint2(0u);
	int3 c = int3(floor((p - D.grid.xyz) * D.grid.w));
	if (any(c < 0) || any(c >= int3(SHADE_DL_GRID_X, SHADE_DL_GRID_Y, SHADE_DL_GRID_Z))) return uint2(0u);
	return D.cells[(c.y * SHADE_DL_GRID_Z + c.z) * SHADE_DL_GRID_X + c.x];
}

// How much of light L reaches camera-relative point q (the air just in front of the surface): its visibility volume,
// trilinear over the voxel centres (soft block-sized edges). 1 for lights without a volume.
static inline float shadeDlVisibility(constant ShadeDynLights &D, constant ShadeDynLight &L, float3 q, texture3d<half> vis) {
	if (L.vol.w < 0.0) return 1.0;
	constexpr sampler s(filter::linear, address::clamp_to_edge, coord::normalized);
	float3 v = clamp(q - L.vol.xyz, 0.5, float(SHADE_DL_VOL) - 0.5); // voxel units, kept inside the slab (no bleed into the next)
	float3 uvw = float3(v.xy * (1.0 / SHADE_DL_VOL), (L.vol.w * SHADE_DL_VOL + v.z) / (D.params.x * SHADE_DL_VOL));
	return float(vis.sample(s, uvw, level(0.0)).r);
}

// One light's contribution at p (normal n, unit; thin: two-sided plants), in block-light units: times albedo x
// ambientBlock it is radiance like shadeLit2's torch term. l returns the unit direction toward the light and d its
// distance. 0 outside its reach.
static inline float3 shadeDlOne(constant ShadeDynLights &D, constant ShadeDynLight &L, float3 p, float3 n, bool thin, texture3d<half> vis,
                                thread float3 &l, thread float &d) {
	float3 v = L.pos.xyz - p;
	float d2 = dot(v, v), reach = L.pos.w, cut = reach - SHADE_DL_CUT;
	if (d2 >= cut * cut) return float3(0.0);
	d = sqrt(d2);
	l = v / max(d, 1e-3);
	float nl = dot(n, l);
	// wrapped N.L: faces turned toward the light catch it fully, edge-on ones keep a little (the light is a flame, not a
	// point, and bounces off what's around), faces turned away get nothing
	float w = thin ? 0.55 + 0.45 * abs(nl) : saturate((nl + 0.3) * (1.0 / 1.3));
	if (w <= 0.0) return float3(0.0);
	// as block light level reach - d; within the first block the hot core is capped (no singular spot under the hand)
	float b = saturate((reach - max(d, 0.75)) * (1.0 / 15.0));
	float e = shadeDlCurve(b) * w * saturate(cut - d); // faded out over the last block before the cut
	if (e <= 1e-4) return float3(0.0);
	return L.color.rgb * (e * shadeDlVisibility(D, L, p + n * 0.5, vis));
}

// Diffuse dynamic light at camera-relative p with unit face normal n: the factor that multiplies albedo x ambientBlock
// (the terrain's block-light colour) like shadeLit2's torch term. block: the surface's own (vanilla) block light 0..1 —
// where placed torches already light it brightly, a carried one adds less (E09: E_blk + E_dyn (1 - 0.5 F(b))).
static float3 shadeDynamicLights(constant ShadeDynLights &D, texture3d<half> vis, float3 p, float3 n, bool thin, half block) {
	uint2 m = shadeDlMask(D, p);
	if ((m.x | m.y) == 0u) return float3(0.0);
	float3 sum = float3(0.0), l;
	float d;
	while (m.x != 0u) {
		uint i = ctz(m.x);
		m.x &= m.x - 1u;
		sum += shadeDlOne(D, D.lights[i], p, n, thin, vis, l, d);
	}
	while (m.y != 0u) {
		uint i = ctz(m.y);
		m.y &= m.y - 1u;
		sum += shadeDlOne(D, D.lights[32u + i], p, n, thin, vis, l, d);
	}
	return sum * (D.params.y * (1.0 - 0.5 * shadeDlCurve(float(block))));
}

// The terrain's path (cost independent of the light count): the irradiance volume (shade_dl_irr computes every light's
// light, visibility included, once per block voxel per frame) sampled trilinearly half a block in front of the surface,
// with a wrapped N.L against the voxels' mean light direction (dir: sum of directions weighted by each light's luminance,
// divided by the total, so its length is 1 for one light and shrinks where lights come from several sides: the term then
// blends toward 0.6, as if lit from around). Same units and day/placed-light attenuation as shadeDynamicLights.
static float3 shadeDynamicLightsVol(constant ShadeDynLights &D, texture3d<half> irr, texture3d<half> dirs, float3 p, float3 n, bool thin, half block) {
	if (D.info.x == 0u || D.irr.w <= 0.0) return float3(0.0);
	float3 v = (p + n * 0.5 - D.irr.xyz) * float3(1.0 / SHADE_DL_IRR_X, 1.0 / SHADE_DL_IRR_Y, 1.0 / SHADE_DL_IRR_Z);
	if (any(v <= 0.0) || any(v >= 1.0)) return float3(0.0);
	constexpr sampler s(filter::linear, address::clamp_to_edge, coord::normalized);
	float3 e = float3(irr.sample(s, v, level(0.0)).rgb);
	if (e.x + e.y + e.z < 1e-5) return float3(0.0);
	float3 dir = float3(dirs.sample(s, v, level(0.0)).xyz);
	float r = length(dir);
	float c = dot(n, dir) / max(r, 1e-3);
	float w = thin ? 0.55 + 0.45 * abs(c) : mix(0.6, saturate((c + 0.3) * (1.0 / 1.3)), saturate(r));
	return e * (w * D.params.y * (1.0 - 0.5 * shadeDlCurve(float(block))));
}

// The irradiance volume (compute, mcdynlight.m, after the visibility slabs): per block voxel around the camera, every light
// of its grid cell as shadeDlOne computes it at the voxel centre without the N.L term (that waits for the surface normal),
// its visibility read exactly from the light's slab; rgb into irr, the luminance-weighted mean direction into dirs.
#ifdef SHADE_DL_KERNELS
kernel void shade_dl_irr(uint3 t0 [[thread_position_in_grid]], constant ShadeDynLights &D [[buffer(0)]], constant uint4 &box [[buffer(1)]],
                         texture3d<half> vis [[texture(0)]], texture3d<half, access::write> irr [[texture(1)]],
                         texture3d<half, access::write> dirs [[texture(2)]]) {
	uint3 t = t0 + box.xyz; // only the box the lights can reach (and last frame's, to clear it) is dispatched
	if (any(t >= uint3(SHADE_DL_IRR_X, SHADE_DL_IRR_Y, SHADE_DL_IRR_Z))) return;
	float3 c = D.irr.xyz + float3(t) + 0.5;
	uint2 m = shadeDlMask(D, c);
	float3 e = float3(0.0), ds = float3(0.0);
	float ls = 0.0;
	for (uint k = 0u; k < 2u; k++) {
		uint bits = k == 0u ? m.x : m.y;
		while (bits != 0u) {
			uint i = ctz(bits) + 32u * k;
			bits &= bits - 1u;
			constant ShadeDynLight &L = D.lights[i];
			float3 v = L.pos.xyz - c;
			float d2 = dot(v, v), reach = L.pos.w, cut = reach - SHADE_DL_CUT;
			if (d2 >= cut * cut) continue;
			float d = sqrt(d2);
			float b = saturate((reach - max(d, 0.75)) * (1.0 / 15.0));
			float f = shadeDlCurve(b) * saturate(cut - d);
			if (L.vol.w >= 0.0) {
				int3 q = int3(floor(c - L.vol.xyz));
				if (any(q < 0) || any(q >= SHADE_DL_VOL)) continue;
				f *= float(vis.read(uint3(uint(q.x), uint(q.y), uint(L.vol.w) * SHADE_DL_VOL + uint(q.z))).r);
			}
			if (f <= 1e-5) continue;
			float3 ci = L.color.rgb * f;
			float w = dot(ci, float3(0.2126, 0.7152, 0.0722));
			e += ci;
			ds += v * (w / max(d, 1e-3));
			ls += w;
		}
	}
	irr.write(half4(half3(min(e, 60000.0)), 0.0h), t);
	dirs.write(half4(half3(ls > 0.0 ? ds / ls : float3(0.0)), 0.0h), t);
}
#endif

// Diffuse plus a glossy highlight (water, wet or polished surfaces): v = unit direction from p toward the camera,
// shininess the Blinn-Phong exponent, spec its strength (Fresnel-weighted by the caller). Returns diffuse in rgb as
// shadeDynamicLights and adds the specular radiance factor (same units) into specOut.
static float3 shadeDynamicLightsSpec(constant ShadeDynLights &D, texture3d<half> vis, float3 p, float3 n, half block, float3 v, float shininess,
                                     thread float3 &specOut) {
	uint2 m = shadeDlMask(D, p);
	specOut = float3(0.0);
	if ((m.x | m.y) == 0u) return float3(0.0);
	float3 sum = float3(0.0), l;
	float d;
	float norm = (shininess + 8.0) * (1.0 / 8.0);
	for (uint k = 0u; k < 2u; k++) {
		uint bits = k == 0u ? m.x : m.y;
		while (bits != 0u) {
			uint i = ctz(bits) + 32u * k;
			bits &= bits - 1u;
			float3 c = shadeDlOne(D, D.lights[i], p, n, false, vis, l, d);
			sum += c;
			float3 h = normalize(l + v);
			specOut += c * (D.lights[i].color.w * norm * pow(saturate(dot(n, h)), shininess));
		}
	}
	float k = D.params.y * (1.0 - 0.5 * shadeDlCurve(float(block)));
	specOut *= k * D.params.z;
	return sum * k;
}

// For the game's own vertex stages (entities, items, block entities, particles, text, rain; patched by
// mcopt.metal.shade.light.DynLightMsl): raise a vertex's lightmap colour toward what the vanilla lightmap gives at the
// dynamic block light level reaching it (the larger of the two per light, so a mob standing in placed torchlight isn't
// lit twice), tinted by each light's colour, with a wrapped N.L (n = 0: no normal, omnidirectional) and the same
// visibility. lm: the vertex's own sample_lightmap result; uv2: its lightmap coordinates (0..240 block, sky).
static float4 shadeDynLightmap(float4 lm, int2 uv2, float3 p, float3 n, texture2d<float> lightMap, sampler lightSampler, constant ShadeDynLights &D,
                               texture3d<half> vis) {
	uint2 m = shadeDlMask(D, p);
	if ((m.x | m.y) == 0u) return lm;
	bool hasN = dot(n, n) > 0.01;
	float3 nn = hasN ? normalize(n) : float3(0.0, 1.0, 0.0);
	float3 add = float3(0.0);
	float sky = clamp(float(uv2.y) / 256.0 + 0.03125, 0.03125, 0.96875);
	for (uint k = 0u; k < 2u; k++) {
		uint bits = k == 0u ? m.x : m.y;
		while (bits != 0u) {
			uint i = ctz(bits) + 32u * k;
			bits &= bits - 1u;
			constant ShadeDynLight &L = D.lights[i];
			float3 v = L.pos.xyz - p;
			float d2 = dot(v, v), reach = L.pos.w, cut = reach - SHADE_DL_CUT;
			if (d2 >= cut * cut) continue;
			float d = sqrt(d2);
			float w = hasN ? 0.45 + 0.55 * saturate(dot(nn, v / max(d, 1e-3))) : 1.0;
			float lv = max(reach - max(d, 0.75), 0.0); // block light level 0..15
			float3 dyn = lightMap.sample(lightSampler, float2(clamp(lv * (16.0 / 256.0) + 0.03125, 0.03125, 0.96875), sky), level(0.0)).rgb;
			float a = dot(L.color.rgb, float3(0.2126, 0.7152, 0.0722));
			float3 tint = L.color.rgb / max(a, 1e-3);
			add += max(dyn - lm.rgb, 0.0) * tint * (min(a, 1.0) * w * shadeDlVisibility(D, L, p + nn * 0.3, vis));
		}
	}
	return float4(lm.rgb + add * D.params.w, lm.a);
}

#endif
