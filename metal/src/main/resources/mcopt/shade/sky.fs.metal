// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// minecraft:pipeline/sky (fragment): the atmosphere (shadeSkyColor: sky-view LUT, sun disc, the game's fog colour under
// water and the like). The dark disc stays black, fading into the sky's horizon the way vanilla fades it into its fog.
// Outside the overworld (not expected: other skyboxes don't use this pipeline) it is vanilla's sky.
struct SkyOut {
	float4 position [[position]];
	float3 dir [[user(locn0)]];
	float3 vanilla [[user(locn1)]];
};

fragment float4 main0(SkyOut in [[stage_in]], constant ShadeFrame& sf [[buffer(16)]], texture2d<half> skyLut [[texture(17)]], sampler skyLutSmplr [[sampler(13)]],
                      constant Fog& fog [[buffer(MCOPT_SLOT_Fog)]], constant DynamicTransforms& dt [[buffer(MCOPT_SLOT_DynamicTransforms)]])
{
	if (in.vanilla.x > 0.5 || sf.flags.x != SHADE_DIM_OVERWORLD) {
		float a = saturate(in.vanilla.y / max(fog.FogSkyEnd, 1e-3));
		float b = in.vanilla.z >= fog.FogSkyEnd ? 1.0 : 0.0;
		float fogValue = max(a, b);
		float3 behind = sf.flags.x == SHADE_DIM_OVERWORLD ? shadeSkyColor(normalize(in.dir), skyLut, skyLutSmplr, sf) : fog.FogColor.rgb;
		return float4(mix(dt.ColorModulator.rgb, behind, fogValue * (sf.flags.x == SHADE_DIM_OVERWORLD ? 1.0 : fog.FogColor.a)), dt.ColorModulator.a);
	}
	return float4(shadeSkyColor(normalize(in.dir), skyLut, skyLutSmplr, sf), 1.0);
}
