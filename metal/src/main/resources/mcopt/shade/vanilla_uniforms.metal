// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Vanilla's uniform blocks as the sky pipelines declare them (std140; include/fog.glsl, dynamictransforms.glsl,
// projection.glsl). Slots come from the MCOPT_SLOT_* defines MetalPipeline prepends: renderpearl numbers a pipeline's
// uniforms in the order its shaders use them. Loaded after shade_common.metal by the sky, celestial and sunrise overrides.
#ifndef MCOPT_SHADE_VANILLA
#define MCOPT_SHADE_VANILLA
struct Fog {
	float4 FogColor;
	float FogEnvironmentalStart;
	float FogEnvironmentalEnd;
	float FogRenderDistanceStart;
	float FogRenderDistanceEnd;
	float FogSkyEnd;
	float FogCloudsEnd;
};
struct DynamicTransforms {
	float4x4 ModelViewMat;
	float4x4 TextureMat;
	float4 ColorModulator;
	packed_float3 ModelOffset;
};
struct Projection {
	float4x4 ProjMat;
};
// Outside the view volume: a primitive with every vertex here is clipped away (how the overrides drop vanilla geometry).
#define SHADE_DROPPED float4(2.0, 2.0, 2.0, 1.0)
#endif
