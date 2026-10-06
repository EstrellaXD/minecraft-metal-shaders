// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
//
// Receiver side of the sun shadow map (mcopt.metal.shade.shadow.ShadowPass). Prepend after <metal_stdlib> (and after
// shade_common.metal if used) to any shader that wants sun shadows. Independent of the ShadeFrame layout: pass the
// fields in. The conventions are the contract between the shadow pass and every receiver:
//
//  * Binding: texture(16) = the map, depth2d_array<float> with SHADE_SHADOW_CASCADES slices (0 = near cascade, 1 = far),
//    sampler(12) = the compare sampler made by mcshadow.m. Both bound for the vertex and fragment stages.
//  * Matrices: ShadeFrame.shadowViewProj[i] (= ShadowPass.matrices()) maps CAMERA-RELATIVE world positions (the same
//    origin as Sodium's terrain vertices this frame: world position minus the camera position) to orthographic light
//    clip space: x, y in [-1, 1], z in [0, 1]. Texture coordinates: uv = (0.5 + 0.5 x, 0.5 - 0.5 y).
//  * Depth: REVERSED Z. 1 = nearest to the light, 0 = farthest; the map is cleared to 0 (no caster).
//  * Compare: the sampler's compareFunction is GreaterEqual, and Metal compares (reference OP texel): sample_compare
//    returns 1 (lit) when the receiver's depth >= the stored caster depth, i.e. the receiver is at least as close to the
//    light as the nearest caster. Linear min/mag filter: every tap is a hardware 2x2 PCF. Border: depth 0 (lit).
//  * Bias: casters are already pushed away from the light at raster time (constant + slope-scaled). Receivers add a normal
//    offset of about one texel (shadowTexelWorld[i] blocks, ShadowPass.texelSize) along the surface normal; see below.
#ifndef SHADE_SHADOW_METAL
#define SHADE_SHADOW_METAL

#define SHADE_SHADOW_TEXTURE 16
#define SHADE_SHADOW_SAMPLER 12
#define SHADE_SHADOW_CASCADES 2

// Light clip -> map texture coordinates.
static inline float2 shade_shadow_uv(float4 clip)
{
    return float2(0.5 + 0.5 * clip.x, 0.5 - 0.5 * clip.y);
}

// 1 = lit, 0 = in shadow, for one cascade; 4 bilinear compare taps half a texel apart (a 3x3 tent).
static inline float shade_shadow_pcf(depth2d_array<float> map, sampler cmp, uint layer, float2 uv, float ref, float texel)
{
    float h = 0.5 * texel;
    float s = map.sample_compare(cmp, uv + float2(-h, -h), layer, ref);
    s += map.sample_compare(cmp, uv + float2(h, -h), layer, ref);
    s += map.sample_compare(cmp, uv + float2(-h, h), layer, ref);
    s += map.sample_compare(cmp, uv + float2(h, h), layer, ref);
    return s * 0.25;
}

// Sun visibility at a camera-relative world position.
//  posCam:  camera-relative world position of the receiver
//  nrm:     its unit world-space normal (geometric normal is best)
//  light:   unit world direction TO the light (ShadowPass.lightDir / ShadeFrame.sunDirWorld when the sun is up)
//  vp0/vp1: ShadeFrame.shadowViewProj[0] / [1]
//  texelWorld: (ShadowPass.texelSize(0), ShadowPass.texelSize(1)) in blocks per texel
//  invRes:  1 / shadow map resolution (ShadeFrame.shadowTexelSize)
// Returns 1 outside both cascades (no shadow information: lit).
static inline float shade_shadow(depth2d_array<float> map, sampler cmp, float3 posCam, float3 nrm, float3 light,
                                 float4x4 vp0, float4x4 vp1, float2 texelWorld, float invRes)
{
    float nl = dot(nrm, light);
    if (nl <= 0.0) return 0.0; // facing away from the light: self-shadowed (pass a light-facing normal for two-sided foliage)
    // Normal offset grows as the surface turns away from the light (where depth slopes are steep).
    float slope = clamp(sqrt(max(1.0 - nl * nl, 0.0)) / max(nl, 0.05), 0.0, 8.0);
    float k = 0.6 + 0.4 * slope;
    float4 c0 = vp0 * float4(posCam + nrm * (texelWorld.x * k), 1.0);
    float2 a0 = abs(c0.xy);
    float m0 = max(a0.x, a0.y);
    float near = 1.0;
    if (m0 < 1.0 && c0.z > 0.0 && c0.z < 1.0) near = shade_shadow_pcf(map, cmp, 0u, shade_shadow_uv(c0), c0.z, invRes);
    if (m0 < 0.85) return near;
    float4 c1 = vp1 * float4(posCam + nrm * (texelWorld.y * k), 1.0);
    float2 a1 = abs(c1.xy);
    float m1 = max(a1.x, a1.y);
    float far = 1.0;
    if (m1 < 1.0 && c1.z > 0.0 && c1.z < 1.0) far = shade_shadow_pcf(map, cmp, 1u, shade_shadow_uv(c1), c1.z, invRes);
    far = mix(far, 1.0, smoothstep(0.85, 1.0, m1)); // fade out at the far cascade's edge
    if (m0 >= 1.0) return far;
    return mix(near, far, smoothstep(0.85, 1.0, m0)); // blend band between cascades
}

#endif
