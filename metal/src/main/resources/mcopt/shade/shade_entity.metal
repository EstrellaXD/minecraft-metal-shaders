// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// M3 (bible E17): entities, block entities (chests, signs, item frames, banners), dropped items, armour, moving blocks and
// particles lit by OUR lighting model in the HDR world target, instead of vanilla's two fixed "cardinal" lights times the
// lightmap. mcopt.metal.shade.ShadeEntity patches the game's own SPIR-V-Cross stages for the HDR variants of those pipelines
// (their RGBA8 pipelines, used by the GUI and the first-person hand, stay vanilla):
//   vertex:   + camera-relative world position, world normal (0 when the vertex format has none), the raw vertex colour (no
//               cardinal light) and the lightmap coordinates / 240 (x block, y sky) as extra outputs (locn 12-15);
//   fragment: after the game's own colour (alpha, cutout discard and dissolve stay the game's), the colour is replaced by
//               shadeEntityShade(): albedo = texture x raw colour x ColorModulator with the hurt / creeper overlay, lit by
//               shadeLit2 (sun or moon N.L with the shadow map, directional sky light, warm fill, block light curve, cave
//               minimum), + shadeDynamicLights, fogged like terrain (shadeFogAir2) and exposed (shadeFinish2).
// Outside the overworld the game's colour is kept (as terrain does), converted to scene-linear.
#ifndef MCOPT_SHADE_ENTITY
#define MCOPT_SHADE_ENTITY

#define SHADE_ENT_ENTITY 0    // mobs, players, block entities, armour, items: real vertex normals
#define SHADE_ENT_BLOCK 1     // moving / falling blocks (no normal attribute: flat normal from the position's derivatives)
#define SHADE_ENT_PARTICLE 2  // particles (camera-facing quads: lit as thin two-sided surfaces)

// vanilla: the game's final colour (display-referred sRGB, fogged), only its alpha is kept (and its colour outside the
// overworld); albedoSrgb: texture x raw vertex colour x ColorModulator (display-encoded, as vanilla multiplies it); overlay:
// vanilla's overlay texel (rgb, a = 1 - overlay strength; (0,0,0,1) for none); p: camera-relative world position; nv: world
// normal (0: derive one); light: lightmap coordinates / 240 (x block, y sky); fog: x/y environmental start/end, z/w render
// distance start/end (the Fog uniform).
static float4 shadeEntityShade(float4 vanilla, float4 albedoSrgb, float4 overlay, float3 p, float3 nv, float2 light, bool front, float4 frag,
                               int kind, float4 fog, texture2d<half> lut, sampler lutS, depth2d_array<float> shadowMap, sampler cmp,
                               constant ShadeFrame &f, constant ShadeLook &L, constant ShadeGpu &g, constant ShadeResources &R) {
	// flat normal for formats without one (before any early return: the whole 2x2 quad takes part)
	float3 dpx = dfdx(p), dpy = dfdy(p);
	if (f.flags.x != SHADE_DIM_OVERWORLD || shadeDev(f, SHADE_DEV_FLAT_LIGHT)) return float4(shadeVanillaOut(vanilla.rgb, L), vanilla.a);
	float3 n;
	bool thin = kind == SHADE_ENT_PARTICLE;
	if (kind == SHADE_ENT_ENTITY && dot(nv, nv) > 0.01) {
		n = normalize(nv);
		if (!front) n = -n; // two-sided parts (no-cull pipelines) seen from behind
	} else {
		float3 nf = cross(dpx, dpy);
		n = nf * rsqrt(max(dot(nf, nf), 1e-30));
		if (dot(n, p) > 0.0) n = -n; // toward the camera
	}
	half3 c = half3(saturate(mix(overlay.rgb, albedoSrgb.rgb, overlay.a)));
	half3 albedo = shadeToLinear(c);
	int debug = f.flags.w;
	if (debug == 3) albedo = half3(0.45h);
	half block = half(saturate(light.x)), sky = half(saturate(light.y));
	float ex = shadeExposure(f, g);
	// full-bright particles (flames, lava drips, sparks, enchanting glyphs: the game gives them lightmap 15/15) glow
	if (kind == SHADE_ENT_PARTICLE && light.x > 0.99 && light.y > 0.99) {
		float3 rad = float3(albedo) * float3(f.ambientBlock.xyz) * 2.6 + float3(albedo) * 0.6;
		return float4(shadeFinish2(rad, 0.0, f, L, ex), vanilla.a);
	}
	half3 hn = half3(n);
	half shadow = sky <= 0.3h ? half(1.0) : shadeShadow(shadowMap, cmp, f, p, hn, thin);
	float3 radiance = shadeLit2(albedo, hn, sky, block, shadow, thin, false, f, L);
	radiance += shadeDynamicLights(shadeDynIn(p, n, float3(albedo), frag.xy, sky, block, kind == SHADE_ENT_PARTICLE ? SHADE_DYN_PARTICLE : SHADE_DYN_ENTITY), f, L, &R);
	float vanillaFog = shadeVanillaFog(p, fog.xy, fog.zw);
	radiance = shadeFogAir2(radiance, p, vanillaFog, 0.0, lut, lutS, f, L);
	float3 rgb = shadeFinish2(radiance, vanillaFog, f, L, ex);
	if (debug == 2) rgb = shadeVanillaOut(n * 0.5 + 0.5, L);
	if (debug == 1) rgb = shadeVanillaOut(float3(0.25 + 0.75 * float(shadow)), L);
	return float4(rgb, vanilla.a);
}

#endif
