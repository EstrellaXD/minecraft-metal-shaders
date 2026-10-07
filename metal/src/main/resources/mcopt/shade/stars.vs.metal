// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// minecraft:pipeline/stars (vertex; vanilla's fragment is kept). With M2's procedural night sky (SHADE_FEATURE_STARS) the
// overworld's vanilla star quads are dropped (the sky pass draws stars and the Milky Way); otherwise vanilla's.
struct StarsIn { float3 Position [[attribute(0)]]; };
struct StarsOut { float4 gl_Position [[position]]; };

vertex StarsOut main0(StarsIn in [[stage_in]], constant ShadeFrame& sf [[buffer(16)]], constant ShadeLook& sl [[buffer(17)]],
                      constant DynamicTransforms& dt [[buffer(MCOPT_SLOT_DynamicTransforms)]], constant Projection& pr [[buffer(MCOPT_SLOT_Projection)]])
{
	StarsOut out;
	if (sf.flags.x == SHADE_DIM_OVERWORLD && (sl.tier.y & SHADE_FEATURE_STARS) != 0) {
		out.gl_Position = SHADE_DROPPED;
		return out;
	}
	float4 viewPos = dt.ModelViewMat * float4(in.Position, 1.0);
	if (sf.flags.x == SHADE_DIM_OVERWORLD && sf.sunPath.x != 0.0) { // follow the tilted sun path, like the moon
		float3x3 v = float3x3(sf.view[0].xyz, sf.view[1].xyz, sf.view[2].xyz);
		float3 w = transpose(v) * viewPos.xyz;
		float s = sf.sunPath.x, c = sf.sunPath.y;
		w = float3(w.x, c * w.y - s * w.z, s * w.y + c * w.z);
		viewPos.xyz = v * w;
	}
	out.gl_Position = pr.ProjMat * viewPos;
	out.gl_Position.y = -out.gl_Position.y;
	return out;
}
