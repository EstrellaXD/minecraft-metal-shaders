// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// minecraft:pipeline/sky (vertex). The overworld sky disc (y = +16) becomes one full-screen triangle at the far plane
// carrying the view direction, so the atmosphere covers every pixel the world doesn't, horizon and below included. The
// game draws the disc as a 10-vertex triangle fan (indexed as {0, k+1, k+2} triangles): vertices 0-2 make the triangle and
// every later one repeats vertex 2, so the other triangles have no area. The dark disc below the horizon (y = -16, drawn
// when the camera is below the horizon height) keeps its geometry.
struct SkyIn { float3 Position [[attribute(0)]]; };
struct SkyOut {
	float4 position [[position]];
	float3 dir [[user(locn0)]];     // camera-relative view direction (not normalized)
	float3 vanilla [[user(locn1)]]; // x 1 on the dark disc; y, z its spherical and cylindrical fog distances
};

vertex SkyOut main0(SkyIn in [[stage_in]], uint vid [[vertex_id]], constant ShadeFrame& sf [[buffer(16)]],
                    constant DynamicTransforms& dt [[buffer(MCOPT_SLOT_DynamicTransforms)]], constant Projection& pr [[buffer(MCOPT_SLOT_Projection)]])
{
	SkyOut out;
	if (in.Position.y < 0.0 || sf.flags.x != SHADE_DIM_OVERWORLD) {
		out.position = pr.ProjMat * dt.ModelViewMat * float4(in.Position, 1.0);
		out.position.y = -out.position.y; // like SPIRV-Cross's vertex Y flip
		out.dir = in.Position + float3(0.0, 12.0, 0.0); // the dark disc is drawn 12 blocks up
		out.vanilla = float3(1.0, length(in.Position), max(length(in.Position.xz), abs(in.Position.y)));
		return out;
	}
	uint corner = min(vid, 2u);
	float2 ndc = float2(corner == 1u ? 3.0 : -1.0, corner == 2u ? 3.0 : -1.0);
	out.dir = shadeViewRay(ndc, sf.invViewProj); // from the eye: view bobbing moves it (shade_common.metal)
	out.position = float4(ndc.x, -ndc.y, 0.0, 1.0); // reversed Z: 0 is the far plane
	out.vanilla = float3(0.0);
	return out;
}
