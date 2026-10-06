// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading, not part of the mod.
// What SPIRV-Cross makes of vanilla's position_tex.fsh (the stage the celestial/sunrise overrides keep), for pipecheck.
#include <metal_stdlib>
using namespace metal;
struct DynamicTransforms { float4x4 ModelViewMat; float4x4 TextureMat; float4 ColorModulator; packed_float3 ModelOffset; };
struct main0_out { float4 fragColor [[color(0)]]; };
struct main0_in { float2 texCoord0 [[user(locn0)]]; };
fragment main0_out main0(main0_in in [[stage_in]], constant DynamicTransforms& _21 [[buffer(1)]], texture2d<float> Sampler0 [[texture(2)]], sampler Sampler0Smplr [[sampler(2)]]) {
    main0_out out = {}; float4 color = Sampler0.sample(Sampler0Smplr, in.texCoord0); if (color.w == 0.0) discard_fragment(); out.fragColor = color * _21.ColorModulator; return out; }
