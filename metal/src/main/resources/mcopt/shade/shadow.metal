// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
//
// The sun shadow pass (mcshadow.m, mcopt.metal.shade.shadow.ShadowPass): depth-only replays of Sodium's solid and cutout
// terrain from the light, one render pass per cascade into one slice of a Depth32Float 2D array texture.
//
// Conventions (see also shade_shadow.metal and SHADOW.md):
//  - Positions are camera-relative world space (the same origin as Sodium's terrain vertices this frame).
//  - cascade.viewProj maps them to an orthographic light clip space: x, y in [-1, 1], z in [0, 1] with REVERSED Z
//    (1 = nearest to the light, 0 = farthest; the map is cleared to 0). No Y flip: clip y = +1 is texel row 0.
//  - Depth clip mode is clamp ("pancaking"): casters between the light and the cascade's near plane land at depth 1.
//
// Geometry is pulled, not fetched: one instanced draw per (arena, pass kind) list. Each instance is a chunk of up to 64
// consecutive quads of one Sodium section facing range; the 384-entry uint16 index buffer turns vertex_id into
// quad * 4 + corner (corners {0, 1, 2, 2, 3, 0}). Vertices are read straight from Sodium's geometry arena, in its
// 20-byte compact format, which is decoded here from its documented bit layout:
//   word 0: position high 10 bits of x, y, z (bits 0-9, 10-19, 20-29)
//   word 1: position low 10 bits of x, y, z
//           -> 20-bit fixed point q per axis, model position = q * 32 / 2^20 - 8 (section-local, block units)
//   word 2: RGBA8 color (alpha byte last)
//   word 3: texture u (bits 0-14) and v (bits 16-30), 15-bit fractions of the atlas; bits 15 and 31 are a rounding sign
//   word 4: block light, sky light, material, section index in the region (x: bits 5-7, y: bits 0-1, z: bits 2-4)
#include <metal_stdlib>
using namespace metal;

// firstVertex: the chunk's first vertex in its arena; packed: (quads - 1) in bits 0-5, region index in bits 6-31.
struct ShadowChunk { uint firstVertex; uint packed; };
// Camera-relative region origin (Sodium's region offset, computed the way its renderer does), 16 bytes.
struct ShadowRegion { packed_float3 origin; float pad; };
// Per cascade: camera-relative world -> light clip, and a constant depth bias (depth units, pushes casters away from the light).
struct ShadowCascade { float4x4 viewProj; float depthBias; float pad0; float pad1; float pad2; };

struct ShadowOut { float4 pos [[position]]; };
struct ShadowCutoutOut { float4 pos [[position]]; float2 uv; half alpha; };

static float3 shadowVertexPosition(const device uint *v, ShadowRegion region)
{
    uint3 hi = (uint3(v[0]) >> uint3(0u, 10u, 20u)) & 0x3FFu;
    uint3 lo = (uint3(v[1]) >> uint3(0u, 10u, 20u)) & 0x3FFu;
    float3 model = float3((hi << 10u) | lo) * (32.0 / 1048576.0) - 8.0;
    uint section = v[4] >> 24;
    float3 sectionOffset = float3(uint3(section >> 5, section, section >> 2) & uint3(7u, 3u, 7u)) * 16.0;
    return model + sectionOffset + float3(region.origin);
}

// The quad's vertices, or null for the padding past the chunk's last quad (whose triangles then collapse to a point).
static const device uint *shadowVertex(uint vid, uint iid, const device uint *arena, const device ShadowChunk *chunks,
                                       thread uint &region)
{
    ShadowChunk c = chunks[iid];
    uint quad = vid >> 2;
    if (quad > (c.packed & 63u)) return nullptr;
    region = c.packed >> 6;
    return arena + (c.firstVertex + vid) * 5u;
}

vertex ShadowOut shadow_vs(uint vid [[vertex_id]], uint iid [[instance_id]], const device uint *arena [[buffer(0)]],
                           const device ShadowChunk *chunks [[buffer(1)]], const device ShadowRegion *regions [[buffer(2)]],
                           constant ShadowCascade &cascade [[buffer(3)]])
{
    ShadowOut out;
    uint region = 0;
    const device uint *v = shadowVertex(vid, iid, arena, chunks, region);
    if (v == nullptr) {
        out.pos = float4(0.0);
        return out;
    }
    float3 p = shadowVertexPosition(v, regions[region]);
    out.pos = cascade.viewProj * float4(p, 1.0);
    out.pos.z -= cascade.depthBias * out.pos.w;
    return out;
}

vertex ShadowCutoutOut shadow_cutout_vs(uint vid [[vertex_id]], uint iid [[instance_id]], const device uint *arena [[buffer(0)]],
                                        const device ShadowChunk *chunks [[buffer(1)]], const device ShadowRegion *regions [[buffer(2)]],
                                        constant ShadowCascade &cascade [[buffer(3)]])
{
    ShadowCutoutOut out;
    uint region = 0;
    const device uint *v = shadowVertex(vid, iid, arena, chunks, region);
    if (v == nullptr) {
        out.pos = float4(0.0);
        out.uv = float2(0.0);
        out.alpha = 0.0h;
        return out;
    }
    float3 p = shadowVertexPosition(v, regions[region]);
    out.pos = cascade.viewProj * float4(p, 1.0);
    out.pos.z -= cascade.depthBias * out.pos.w;
    uint tex = v[3];
    out.uv = float2(uint2(tex, tex >> 16) & 0x7FFFu) * (1.0 / 32768.0);
    out.alpha = half(float(v[2] >> 24) * (1.0 / 255.0));
    return out;
}

// Cutout (leaves, grass, flowers): keep the texel when the atlas alpha (level 0, nearest) times the vertex alpha passes
// the cutout pass's 0.5 test, as the camera pass does at full detail.
fragment void shadow_cutout_fs(ShadowCutoutOut in [[stage_in]], texture2d<float> atlas [[texture(0)]], sampler s [[sampler(0)]])
{
    float a = atlas.sample(s, in.uv, level(0.0)).w * float(in.alpha);
    if (a < 0.5) discard_fragment();
}

// ---- debug view (mcopt.shade.debug=1): one cascade per tile, drawn into the game's main color target ----

struct ShadowDebugParams { uint layer; float centerDepth; float scale; float pad; };
struct ShadowDebugOut { float4 pos [[position]]; float2 uv; };

vertex ShadowDebugOut shadow_debug_vs(uint vid [[vertex_id]])
{
    float2 ndc = float2(vid == 1u ? 3.0 : -1.0, vid == 2u ? 3.0 : -1.0);
    ShadowDebugOut out;
    out.pos = float4(ndc, 0.0, 1.0);
    // The main target holds GL row order (presented flipped): this shows light-space +x right and +y up on screen.
    out.uv = ndc * 0.5 + 0.5;
    return out;
}

fragment float4 shadow_debug_fs(ShadowDebugOut in [[stage_in]], depth2d_array<float> map [[texture(0)]],
                                constant ShadowDebugParams &p [[buffer(0)]])
{
    constexpr sampler nearest(filter::nearest, address::clamp_to_edge);
    // The map's texel row 0 is light clip y = +1; on screen the tile's top is in.uv.y = 0 (the target is presented flipped).
    float d = map.sample(nearest, in.uv, p.layer);
    float edge = step(0.995, max(abs(in.uv.x * 2.0 - 1.0), abs(in.uv.y * 2.0 - 1.0)));
    if (edge > 0.0) return float4(1.0, 0.85, 0.2, 1.0);
    if (d <= 0.0) return float4(0.05, 0.08, 0.25, 1.0); // cleared: no caster
    float g = saturate(0.5 + (d - p.centerDepth) * p.scale);
    // a faint 4-block contour band helps read heights
    float band = fract((d - p.centerDepth) * p.scale * 8.0) < 0.08 ? 0.85 : 1.0;
    return float4(float3(g * band), 1.0);
}
