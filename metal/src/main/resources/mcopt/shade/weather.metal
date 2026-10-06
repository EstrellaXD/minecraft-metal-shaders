// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Rain and snow (minecraft:pipeline/weather), applied to the result of vanilla's generated fragment shader (ShadeMsl.weather
// patches the call in): in the overworld the streaks become grey-white, as bright as the light the terrain gets from the
// sky (plus a little sun), through the same exposure and tone curve, and a bit more transparent; vanilla's look elsewhere.
#ifndef MCOPT_SHADE_WEATHER
#define MCOPT_SHADE_WEATHER
static float4 shadeWeather(float4 c, constant ShadeFrame &f) {
	if (f.flags.x != SHADE_DIM_OVERWORLD || f.fogColor.a > 0.5) return c;
	float l = shadeLuma(c.rgb);
	float3 light = f.ambientSky.rgb + f.sunColor.rgb * 0.3;
	float3 lit = shadeOutput(light * (0.35 + 0.45 * l), f);
	return float4(lit * float3(0.96, 0.98, 1.0), c.a * 0.65);
}
#endif
