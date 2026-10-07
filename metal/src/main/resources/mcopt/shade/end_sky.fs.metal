// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// minecraft:pipeline/end_sky (fragment), M3 (mcopt.shade.dims): the End sky of shade_dims.metal (shadeEndSky: gradient,
// nebula, stars, beams, the End flash) through shadeOut (pre-exposed radiance in the HDR target). Without the dims module:
// vanilla's (texture x vertex colour x ColorModulator, converted for the HDR target).
struct EndSkyOut {
	float4 position [[position]];
	float3 dir [[user(locn0)]];
	float2 uv [[user(locn1)]];
	float4 color [[user(locn2)]];
};

fragment float4 main0(EndSkyOut in [[stage_in]], constant ShadeFrame& sf [[buffer(16)]], constant ShadeLook& sl [[buffer(17)]],
                      constant ShadeGpu& sg [[buffer(18)]], constant ShadeResources& sr [[buffer(19)]],
                      constant DynamicTransforms& dt [[buffer(MCOPT_SLOT_DynamicTransforms)]],
                      texture2d<float> Sampler0 [[texture(MCOPT_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(MCOPT_SLOT_Sampler0)]])
{
	if (!shadeDimsLit(sf)) {
		float4 c = Sampler0.sample(Sampler0Smplr, in.uv) * in.color * dt.ColorModulator;
		return float4(shadeVanillaOut(c.rgb, sl), c.a);
	}
	float ex = shadeExposure(sf, sg);
	float pixelAngle = 2.0 * atan(1.0 / max(abs(sf.proj[1][1]), 1e-3)) * sf.screen.w;
	float3 sky = shadeEndSky(normalize(in.dir), sf, sl, sr.milkyWay, pixelAngle);
	return float4(shadeOut(sky, sf, ex), 1.0);
}
