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
// Geometry is pulled, not fetched: one instanced draw per (arena, pass kind) list. Each instance is a chunk of up to 32
// consecutive quads of one Sodium section facing range; the 192-entry uint16 index buffer turns vertex_id into
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

// ---- M3 entity casters (mcshadow.m mc_shadow_entity_*) ----
// Last frame's entity draws (mobs, block entities, items, armour, moving blocks), copied at the end of the world from the
// game's own vertex buffers: quads of `stride`-byte vertices, camera-relative position (float3) at offset 0, uv (float2) at
// uvOffset; shift moves them onto this frame's camera. Two triangles per quad from vertex_id ({0, 1, 2, 2, 3, 0}, the game's
// quad index order); the texture's alpha below cutoff casts nothing (skin overlays, item sprites).
struct EntityCast { packed_float3 shift; uint stride; uint uvOffset; float cutoff; float pad0; float pad1; };

vertex ShadowCutoutOut shadow_entity_vs(uint vid [[vertex_id]], const device uchar *verts [[buffer(0)]], constant ShadowCascade &cascade [[buffer(3)]],
                                        constant EntityCast &e [[buffer(4)]])
{
    uint c = vid % 6u;
    uint corner = c < 3u ? c : (c == 3u ? 2u : (c == 4u ? 3u : 0u));
    const device uchar *v = verts + ((vid / 6u) * 4u + corner) * e.stride;
    float3 p = float3(*(const device packed_float3 *) v) + float3(e.shift);
    ShadowCutoutOut out;
    out.pos = cascade.viewProj * float4(p, 1.0);
    out.pos.z -= cascade.depthBias * out.pos.w;
    out.uv = float2(*(const device packed_float2 *) (v + e.uvOffset));
    out.alpha = 1.0h;
    return out;
}

fragment void shadow_entity_fs(ShadowCutoutOut in [[stage_in]], texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]],
                               constant EntityCast &e [[buffer(4)]])
{
    if (tex.sample(s, in.uv, level(0.0)).w < e.cutoff) discard_fragment();
}

// ---- coloured shadows (M3 weather, mcopt.shade.colorShadows): the translucent casters of the near cascade ----
// Sodium's translucent terrain (water, stained glass and panes, ice, slime, honey) drawn from the light into a small tint map
// (RGBA16Float, cleared to (1, 1, 1, 0)): rgb = the light they let through, blended by MIN (two faces of one pane, or panes
// behind each other, don't multiply into black; the darkest wins per channel), a = their reversed-Z light depth, blended by
// MAX (the one nearest the light). Receivers (weather_surface.metal shadeShadowTint) take the tint only when they lie a
// quarter block or more beyond that depth, i.e. below the glass. No depth attachment: the opaque cascade decides whether
// light arrives at all. No raster bias: receivers compare their own depth.
struct ShadowTintOut { float4 pos [[position]]; float2 uv; half4 color; };
// Water sprite rectangles in the block atlas (ShadeWater: still, flowing; all 0 when unknown); mode.x = 1: leave water out
// (the water module is on: M2.1 absorbs the light on its way down to the floor, a tint here would absorb it twice).
struct ShadowTintParams { float4 still; float4 flow; float4 mode; };

vertex ShadowTintOut shadow_tint_vs(uint vid [[vertex_id]], uint iid [[instance_id]], const device uint *arena [[buffer(0)]],
                                    const device ShadowChunk *chunks [[buffer(1)]], const device ShadowRegion *regions [[buffer(2)]],
                                    constant ShadowCascade &cascade [[buffer(3)]])
{
    ShadowTintOut out;
    uint region = 0;
    const device uint *v = shadowVertex(vid, iid, arena, chunks, region);
    if (v == nullptr) {
        out.pos = float4(0.0);
        out.uv = float2(0.0);
        out.color = half4(0.0h);
        return out;
    }
    float3 p = shadowVertexPosition(v, regions[region]);
    out.pos = cascade.viewProj * float4(p, 1.0);
    uint tex = v[3];
    out.uv = float2(uint2(tex, tex >> 16) & 0x7FFFu) * (1.0 / 32768.0);
    out.color = unpack_unorm4x8_to_half(v[2]);
    return out;
}

fragment float4 shadow_tint_fs(ShadowTintOut in [[stage_in]], texture2d<float> atlas [[texture(0)]], sampler s [[sampler(0)]],
                               constant ShadowTintParams &tp [[buffer(0)]])
{
    float4 t = atlas.sample(s, in.uv, level(0.0));
    float3 vc = float3(in.color.rgb);
    bool water = tp.still.z > 0.0 && ((all(in.uv >= tp.still.xy) && all(in.uv <= tp.still.zw)) || (all(in.uv >= tp.flow.xy) && all(in.uv <= tp.flow.zw)));
    float3 T;
    if (water && tp.mode.x > 0.5) discard_fragment();
    if (water) {
        // about two blocks of water from the biome's colour (the vertex tint): clear teal shallows, green swamps
        float3 tint = max(vc * vc, float3(0.004));
        T = exp(-(float3(0.26, 0.07, 0.035) + 0.09 * -log(tint)) * 2.0);
    } else {
        float a = t.a * float(in.color.a);
        if (a < 0.03) discard_fragment(); // the clear parts of glass
        float3 c = t.rgb * vc;
        c = c * c * c; // linear, and deeper: light through coloured glass is purer than the glass looks
        // the hue at full strength in its brightest channel (red glass lets red through, not a dim brown): bright, saturated
        // pools of colour like light through a church window; tinted (dark) glass stays dark through its luminance
        float m = max(max(c.r, c.g), max(c.b, 1e-4));
        float l = dot(t.rgb * vc, float3(0.2126, 0.7152, 0.0722));
        c = c / m * smoothstep(0.02, 0.25, l);
        // stained glass is evenly coloured: its lighter interior texels (alpha ~0.3) tint as fully as the frame (the colour of
        // the light comes from the pane, not from the texture's alpha pattern)
        T = mix(float3(1.0), saturate(c), smoothstep(0.03, 0.3, a));
    }
    return float4(T, in.pos.z);
}

// ---- debug view (mcopt.shade.debug=1): one cascade per tile, drawn into the game's main color target ----

// centerDepth: the camera's depth in this cascade; range: the cascade's depth range in blocks.
struct ShadowDebugParams { uint layer; float centerDepth; float range; float pad; };
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
    // blocks toward the light from the camera: -96 (black) .. +96 (white), with a contour line every 4 blocks
    float blocks = (d - p.centerDepth) * p.range;
    float g = saturate(0.5 + blocks / 192.0);
    float band = fract(blocks * 0.25) < 0.1 ? 0.75 : 1.0;
    return float4(float3(g * band), 1.0);
}
