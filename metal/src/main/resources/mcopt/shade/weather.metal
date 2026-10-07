// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Rain and snow (minecraft:pipeline/weather), applied to the result of vanilla's generated fragment shader (ShadeMsl.weather
// patches the call in): in the overworld the streaks become grey-white, about as bright as the sky behind them (plus a
// little sun), through the same exposure and tone curve, and more transparent; vanilla's look elsewhere.
#ifndef MCOPT_SHADE_WEATHER
#define MCOPT_SHADE_WEATHER
static float4 shadeWeather(float4 c, constant ShadeFrame &f, constant ShadeLook &L, constant ShadeGpu &g) {
	if (f.flags.x != SHADE_DIM_OVERWORLD || f.fogColor.a > 0.5) return float4(shadeVanillaOut(c.rgb, L), c.a);
	float l = shadeLuma(c.rgb);
	// drops scatter the overcast sky's light toward the eye (and a little sun, forward): at about the sky's own radiance, so
	// streaks read slightly brighter than the clouds behind them, never as dark bars
	float3 light = f.ambientSky.rgb * 1.15 + f.sunColor.rgb * 0.35;
	float3 lit = shadeOut(light * (0.8 + 0.35 * l) * float3(0.97, 0.99, 1.0), f, shadeExposure(f, g));
	return float4(lit, c.a * 0.55);
}
#endif
