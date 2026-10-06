// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// The sky-view LUT pass (mcshade.m, mc_shade_lut_render): once per frame, before the sky draws, the atmosphere's radiance for
// every texel's direction (shade_common.metal: shadeLutDirection, shadeSkyRadiance) into a SHADE_LUT_W x SHADE_LUT_H
// RGBA16Float target. 10k pixels of the 8-sample scattering integral instead of 6 million: ~0.01 ms instead of ~3 ms at
// 3024x1964. Loaded after shade_common.metal.

struct ShadeLutOut { float4 position [[position]]; };

vertex ShadeLutOut shade_lut_vs(uint vid [[vertex_id]]) {
	ShadeLutOut out;
	out.position = float4(vid == 1 ? 3.0 : -1.0, vid == 2 ? 3.0 : -1.0, 0.0, 1.0);
	return out;
}

fragment half4 shade_lut_fs(ShadeLutOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]]) {
	float2 uv = (in.position.xy - 0.5) / float2(SHADE_LUT_W - 1, SHADE_LUT_H - 1);
	float3 transmittance;
	float3 sky = shadeSkyRadiance(shadeLutDirection(uv, f), f, transmittance);
	return half4(half3(min(sky, 60000.0)), 1.0h);
}
