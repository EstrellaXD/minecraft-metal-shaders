// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// minecraft:pipeline/celestial (vertex; vanilla's position_tex fragment is kept). The overworld sun's textured square is
// dropped (the sky draws a round sun with limb darkening); the moon and the End flash draw as vanilla. Bodies are told
// apart by size: SkyRenderer scales the sun's quad by 30, the moon's by 20 and the End flash's by 60 (the length of
// ModelViewMat's first column, rotations leaving it unchanged).
struct CelestialIn { float3 Position [[attribute(0)]]; float2 UV0 [[attribute(1)]]; };
struct CelestialOut { float4 gl_Position [[position]]; float2 texCoord0 [[user(locn0)]]; };

vertex CelestialOut main0(CelestialIn in [[stage_in]], constant ShadeFrame& sf [[buffer(16)]],
                          constant DynamicTransforms& dt [[buffer(MCOPT_SLOT_DynamicTransforms)]], constant Projection& pr [[buffer(MCOPT_SLOT_Projection)]])
{
	CelestialOut out;
	out.texCoord0 = in.UV0;
	float size = length(dt.ModelViewMat[0].xyz);
	if (sf.flags.x == SHADE_DIM_OVERWORLD && abs(size - 30.0) < 1.0) {
		out.gl_Position = SHADE_DROPPED;
		return out;
	}
	out.gl_Position = pr.ProjMat * dt.ModelViewMat * float4(in.Position, 1.0);
	out.gl_Position.y = -out.gl_Position.y;
	return out;
}
