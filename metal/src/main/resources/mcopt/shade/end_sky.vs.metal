// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// minecraft:pipeline/end_sky (vertex), M3 (mcopt.shade.dims): vanilla's textured End sky box (6 quads, +-100 blocks around the
// camera, world-axis aligned) keeps its geometry and carries each fragment's view direction to end_sky.fs.metal. Without the
// dims module (sunPath.z bit unset) it draws as vanilla (the fragment samples the texture).
struct EndSkyIn { float3 Position [[attribute(0)]]; float2 UV0 [[attribute(1)]]; float4 Color [[attribute(2)]]; };
struct EndSkyOut {
	float4 position [[position]];
	float3 dir [[user(locn0)]];
	float2 uv [[user(locn1)]];
	float4 color [[user(locn2)]];
};

vertex EndSkyOut main0(EndSkyIn in [[stage_in]], constant ShadeFrame& sf [[buffer(16)]],
                       constant DynamicTransforms& dt [[buffer(MCOPT_SLOT_DynamicTransforms)]], constant Projection& pr [[buffer(MCOPT_SLOT_Projection)]])
{
	EndSkyOut out;
	out.position = pr.ProjMat * dt.ModelViewMat * float4(in.Position, 1.0);
	out.position.y = -out.position.y; // like SPIRV-Cross's vertex Y flip
	out.dir = in.Position;
	out.uv = in.UV0;
	out.color = in.Color;
	return out;
}
