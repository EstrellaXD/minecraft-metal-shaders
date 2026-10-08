// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// minecraft:pipeline/sky (fragment): the atmosphere (sky-view LUT) with M2's procedural sun disc and aureole, moon with
// phases, stars and the Milky Way (shadeSkyRadiance2), through shadeFinish2 (pre-exposed radiance in the HDR target,
// M1's tone curve in RGBA8), and the game's fog colour under water and the like. The dark disc stays black, fading into the
// sky's horizon the way vanilla fades it into its fog. Outside the overworld (not expected: other skyboxes don't use this
// pipeline) it is vanilla's sky.
struct SkyOut {
	float4 position [[position]];
	float3 dir [[user(locn0)]];
	float3 vanilla [[user(locn1)]];
};

static float3 shadeSkyPixel(float3 dir, texture2d<half> skyLut, sampler skyLutSmplr, constant ShadeFrame &sf, constant ShadeLook &sl, float ex,
                            constant ShadeResources &sr, float2 pixel) {
	float pixelAngle = 2.0 * atan(1.0 / max(abs(sf.proj[1][1]), 1e-3)) * sf.screen.w;
	float3 sky = shadeSkyRadiance2(dir, skyLut, skyLutSmplr, sf, sl, sr.milkyWay, pixelAngle);
	// M3 aurora (shade_dims.metal; ShadeLook.night.z, 0 unless it shows tonight): emission over the night sky
	if (sl.night.z > 0.002) sky += shadeAurora(dir, sf, sl, sr.milkyWay, pixel);
	// M3 weather: a lightning flash lights the clouds (the whole overcast sky glows, most toward the bolt)
	if (sl.flash.w > 0.0) sky += float3(0.78, 0.86, 1.0) * (sl.flash.w * 0.35 * (0.35 + 0.65 * pow(saturate(dot(dir, sl.flash.xyz) * 0.5 + 0.5), 3.0)));
#ifdef MCOPT_HDR
	// M3 volumetric clouds (shade_clouds.metal): in front of the sky, sun, moon and stars, behind everything the world draws
	float4 clouds = shadeCloudsScreen(dir, sl, sr.cloudScreen);
	sky = sky * clouds.a + clouds.rgb;
#endif
	float vanillaSky = dir.y <= 0.0 ? 1.0 : saturate(min(16.0 / max(dir.y, 1e-3), 512.0) / max(sf.fogSky.x, 1.0));
	return shadeFinish2(sky, vanillaSky, sf, sl, ex);
}

fragment float4 main0(SkyOut in [[stage_in]], constant ShadeFrame& sf [[buffer(16)]], constant ShadeLook& sl [[buffer(17)]],
                      constant ShadeGpu& sg [[buffer(18)]], constant ShadeResources& sr [[buffer(19)]],
                      texture2d<half> skyLut [[texture(17)]], sampler skyLutSmplr [[sampler(13)]],
                      constant Fog& fog [[buffer(MCOPT_SLOT_Fog)]], constant DynamicTransforms& dt [[buffer(MCOPT_SLOT_DynamicTransforms)]])
{
	float ex = shadeExposure(sf, sg);
	if (in.vanilla.x > 0.5 || sf.flags.x != SHADE_DIM_OVERWORLD) {
		float a = saturate(in.vanilla.y / max(fog.FogSkyEnd, 1e-3));
		float b = in.vanilla.z >= fog.FogSkyEnd ? 1.0 : 0.0;
		float fogValue = max(a, b);
		float3 behind = sf.flags.x == SHADE_DIM_OVERWORLD ? shadeSkyPixel(normalize(in.dir), skyLut, skyLutSmplr, sf, sl, ex, sr, in.position.xy)
			: shadeVanillaOut(fog.FogColor.rgb, sl);
		return float4(mix(shadeVanillaOut(dt.ColorModulator.rgb, sl), behind, fogValue * (sf.flags.x == SHADE_DIM_OVERWORLD ? 1.0 : fog.FogColor.a)),
			dt.ColorModulator.a);
	}
	return float4(shadeSkyPixel(normalize(in.dir), skyLut, skyLutSmplr, sf, sl, ex, sr, in.position.xy), 1.0);
}
