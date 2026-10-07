// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// minecraft:pipeline/clouds and flat_clouds (fragment; vanilla's vertex stage is kept). Vanilla's clouds are flat-shaded
// boxes in a colour the game derives from the time of day; here they are lit like the terrain: the sun or moon per face
// (tops and sun-facing sides bright, a dark underside), a forward-scattering silver lining on the sides toward the light,
// the sky's fill light from above and the ground's from below,
// then aerial perspective into the atmosphere behind them and the same exposure and tone curve as everything else. The
// face's direction comes from vanilla's per-face shade in the vertex colour (top 1.0, east/west 0.9, north/south 0.8,
// bottom 0.7, times CloudColor), turned toward the camera; the view direction from the pixel position. Vanilla's fade by
// distance (FogCloudsEnd) is kept on the alpha.
struct CloudInfo {
	float4 CloudColor;
	float3 CloudOffset;
	float3 CellSize;
};

struct CloudIn {
	float4 position [[position]];
	float vertexDistance [[user(locn0)]];
	float4 vertexColor [[user(locn1)]];
};

fragment float4 main0(CloudIn in [[stage_in]], constant ShadeFrame& sf [[buffer(16)]], constant ShadeLook& sl [[buffer(17)]], constant ShadeGpu& sg [[buffer(18)]],
                      texture2d<half> skyLut [[texture(17)]], sampler skyLutSmplr [[sampler(13)]],
                      constant CloudInfo& ci [[buffer(MCOPT_SLOT_CloudInfo)]])
{
	float cloudEnd = max(sf.fogSky.y, 1.0);
	float alpha = in.vertexColor.a * (1.0 - saturate(in.vertexDistance / cloudEnd));
	float cover = min(in.vertexColor.a * 1.12, 0.95) * (1.0 - saturate(in.vertexDistance / cloudEnd)); // a little denser than vanilla's
	if (sf.flags.x != SHADE_DIM_OVERWORLD || sf.fogColor.a > 0.5) return float4(shadeVanillaOut(in.vertexColor.rgb, sl), alpha);
	// GL row order: the pixel's row counts from the bottom of the view, so NDC y = row / height * 2 - 1 (no flip)
	float2 ndc = float2(in.position.x * sf.screen.z * 2.0 - 1.0, in.position.y * sf.screen.w * 2.0 - 1.0);
	float4 wp = sf.invViewProj * float4(ndc, 0.5, 1.0);
	float3 dir = normalize(wp.xyz / wp.w);
	float k = in.vertexColor.g / max(ci.CloudColor.g, 1e-3);
	float3 n = k > 0.95 ? float3(0.0, 1.0, 0.0) : k > 0.85 ? float3(-sign(dir.x), 0.0, 0.0) : k > 0.75 ? float3(0.0, 0.0, -sign(dir.z)) : float3(0.0, -1.0, 0.0);
	float3 L = sf.lightDirWorld.xyz;
	float rain = sf.weather.x;
	float ndl = dot(n, L);
	// Per-face light with real contrast: tops and sun-facing sides bright, the far sides and the underside in the sky's
	// (and the ground's) fill; a forward-scattering silver lining on the sides when looking toward the light.
	// (a low sun reaches the undersides too: sunset clouds glow from below)
	float under = 0.15 + 0.55 * (1.0 - smoothstep(0.0, 0.3, L.y));
	float3 direct = sf.sunColor.rgb * saturate(ndl * 0.6 + 0.4) * (n.y < -0.5 ? under : 1.0);
	float mu = dot(dir, L), g = 0.6;
	float hg = (1.0 - g * g) / (12.566 * pow(1.0 + g * g - 2.0 * g * mu, 1.5)); // Henyey-Greenstein
	float lining = n.y > 0.5 ? 0.0 : hg * 2.5 * (1.0 - rain);
	float3 ambient = n.y > 0.5 ? sf.ambientSky.rgb * 1.1 : n.y < -0.5 ? mix(sf.ambientGround.rgb, sf.ambientSky.rgb, 0.4) * 0.8 : sf.ambientSky.rgb * 0.85;
	float albedo = (n.y > 0.5 ? 1.0 : 0.9) * (1.0 - 0.45 * rain);
	float3 radiance = albedo * (direct * (1.0 + lining) + ambient);
	// aerial perspective toward the atmosphere behind: near clouds keep their own colour
	float3 sky = shadeSkyLookup(dir, skyLut, skyLutSmplr, sf);
	float t = saturate(in.vertexDistance / cloudEnd);
	radiance = mix(radiance, sky, 0.04 + 0.7 * t * t * t);
	return float4(shadeOut(radiance, sf, shadeExposure(sf, sg)), cover);
}
