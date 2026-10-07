// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// M3 volumetric clouds (E05): what the rest of shading reads of them, part of ShadeMsl.prelude() (after shade_common.metal,
// before shade_m2.metal: its air fog blends distant terrain into the sky with the clouds).
// The clouds module (clouds.metal, native/mcclouds.m, mcopt.metal.shade.clouds) renders, before the sky pass each frame:
//   ShadeResources.cloudShadow (12)  R16F transmittance of the cumulus toward the light, ShadeLook.cloudShadowVP maps a
//                                    camera-relative point onto it (an oblique projection along the light onto the layer)
//   ShadeResources.cloudDome (13)    RGBA16F scattered light (before exposure) + transmittance by direction (upper hemisphere)
//   ShadeResources.cloudScreen (14)  the same for this frame's view (by direction through ShadeLook.viewProjNoJitter)
// Everything is gated on ShadeLook.clouds0.w (clouds drawn this frame) / clouds0.z (shadow strength): with the module off
// or in the Nether/End these return "no cloud" without touching the textures.
#ifndef MCOPT_SHADE_CLOUDS
#define MCOPT_SHADE_CLOUDS

constexpr sampler shadeCloudSampler(filter::linear, address::clamp_to_edge);

// Light reaching camera-relative p through the cumulus (1 = open sky .. ~0.1 under a thick cloud), for the sun/moon term.
static inline float shadeCloudShadow(float3 p, constant ShadeLook &L, texture2d<half> map) {
	if (L.clouds0.z <= 0.0) return 1.0;
	float2 uv = (L.cloudShadowVP * float4(p, 1.0)).xy;
	float2 e = abs(uv - 0.5);
	float edge = saturate((0.5 - max(e.x, e.y)) * 12.0);
	// fades out for receivers up in (or above) the cloud layer
	float above = 1.0 - smoothstep(L.clouds0.x - 16.0, L.clouds0.x + 40.0, p.y);
	float t = float(map.sample(shadeCloudSampler, uv, level(0.0)).r);
	return mix(1.0, t, L.clouds0.z * edge * above);
}

// The clouds toward unit dir in this frame's view: rgb scattered light (before exposure), a transmittance; (0, 1) when off
// or off screen. Composite: c = c * a + rgb (before exposure).
static inline float4 shadeCloudsScreen(float3 dir, constant ShadeLook &L, texture2d<half> tex) {
	if (L.clouds0.w < 0.5) return float4(0.0, 0.0, 0.0, 1.0);
	float4 c = L.viewProjNoJitter * float4(dir, 0.0);
	if (c.w <= 1e-6) return float4(0.0, 0.0, 0.0, 1.0);
	float2 uv = c.xy / c.w * 0.5 + 0.5;
	return float4(tex.sample(shadeCloudSampler, uv, level(0.0)));
}

// The clouds toward unit dir from the reflection dome (any direction; nothing below the horizon).
static inline float4 shadeCloudsDome(float3 dir, constant ShadeLook &L, texture2d<half> dome) {
	if (L.clouds0.w < 0.5 || L.clouds1.x < 0.5 || dir.y <= 0.0) return float4(0.0, 0.0, 0.0, 1.0);
	float az = atan2(dir.z, dir.x) * 0.15915494;
	float2 uv = float2(az < 0.0 ? az + 1.0 : az, sqrt(saturate(dir.y)));
	constexpr sampler wrapU(filter::linear, s_address::repeat, t_address::clamp_to_edge);
	float4 c = float4(dome.sample(wrapU, uv, level(0.0)));
	// toward the horizon the dome's texels stretch: fade to "no cloud" over the last degree
	float k = smoothstep(0.0, 0.02, dir.y);
	return float4(c.rgb * k, mix(1.0, c.a, k));
}

#endif
