// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
//
// Shared MSL header of the native shading pipeline (-Dmcopt.shade=true). The ShadeFrame struct and its Java packer are
// owned by the infrastructure branch; this copy (from branch m1-shadow) only carries the shadow section of the contract.
// When merging, keep the infrastructure's file and paste the block below into it.
//
// ---- SHADOW CONTRACT (m1-shadow; details and rationale in SHADOW.md, receiver code in shade_shadow.metal) ----
//
// Bindings, vertex + fragment stages, rebound with the other shade globals after every new render encoder:
//   texture(16) = sun shadow map: depth2d_array<float>, 2 slices (0 = near cascade, 1 = far cascade), square,
//                 ShadowPass.resolution() texels (default 2048), Depth32Float (or Depth16Unorm with
//                 -Dmcopt.shade.shadowDepth=16). Java: ShadowPass.texture() (id<MTLTexture>), or ShadowPass.bind(enc),
//                 which binds both the map and the sampler.
//   sampler(12) = compare sampler made by mcshadow.m: compareFunction GreaterEqual, linear min/mag (hardware 2x2 PCF),
//                 clamp to border, border depth 0. Java: ShadowPass.sampler() (id<MTLSamplerState>).
//
// ShadeFrame fields fed by ShadowPass (Java), all for the CURRENT frame once ShadowPass.beginFrame(cameraState) ran
// (idempotent; call it before packing):
//   float4x4 shadowViewProj[2]  ShadowPass.matrices(): column-major, cascade 0 then 1. Maps CAMERA-RELATIVE world
//                               positions (world - camera position, the origin of Sodium's terrain vertices this frame)
//                               to orthographic light clip space: x, y in [-1, 1], z in [0, 1].
//   float4   cascadeSplits      ShadowPass.splits(): {near half-width, far half-width, near texel, far texel} in blocks.
//   float    shadowTexelSize    ShadowPass.texelUv() = 1 / resolution (one texel in uv).
//   uint     shadowsEnabled     ShadowPass.enabled(): 0 in the Nether / End, before the first map, or after a failure;
//                               receivers must then treat everything as lit.
//   (light)  ShadowPass.lightDir(): unit world direction TO the light the map was rendered from: the sun, or the moon
//            while the sun is below the horizon (ShadowPass.lightIsMoon()); quantized to 0.1 degree steps and raised to at
//            least 2 degrees of elevation. Use it for N.L of the shadowed light so lighting and shadows agree.
//
// Conventions:
//   * Z is REVERSED in the map, as in the game's own depth: 1 = nearest the light, 0 = farthest; cleared to 0 (no caster).
//   * sample_compare(cmp, uv, slice, ref) returns 1 (lit) when ref >= stored depth (Metal compares ref OP texel; checked
//     on this M4). ref = the receiver's clip z.
//   * uv = (0.5 + 0.5 * clip.x, 0.5 - 0.5 * clip.y): the map is rendered without a Y flip (clip y = +1 is texel row 0).
//   * Casters are pushed away from the light at raster time (constant ShadowConfig.BIAS_TEXELS texel + slope-scaled);
//     receivers add shade_shadow_offset() (normal + toward-light offset in texels of world size) before projecting.
//   * Only light-facing faces cast (Sodium's per-facing ranges): surfaces facing away from the light are in shadow by
//     N.L <= 0, which shade_shadow() returns as 0.
//
// ---- end SHADOW CONTRACT ----
#ifndef SHADE_COMMON_METAL
#define SHADE_COMMON_METAL
#endif
