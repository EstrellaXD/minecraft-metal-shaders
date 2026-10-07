// This file is copied or adapted from Sodium (https://github.com/CaffeineMC/sodium), Copyright JellySquid
// (jellysquid3) and contributors. Unlike the rest of mcopt, it is licensed under the PolyForm Shield License 1.0.0
// (LICENSES/PolyForm-Shield-1.0.0.md, https://polyformproject.org/licenses/shield/1.0.0), not Apache-2.0.
// See NOTICE.
//
// Sodium's block_layer_opaque.vsh (USE_VERTEX_COMPRESSION, USE_FOG) with lean outputs. The vertex stage bounds terrain
// rendering on Apple GPUs and every output byte is written to and read back from the tiler's parameter buffer, so:
// color is half (it lands in an 8-bit target), fog distances are pre-normalized per fog range into half2 (the fog curve is
// linear between its ends, so interpolating the normalized value is the same as normalizing the interpolated one), and
// the per-chunk fade rides in the environmental term instead of an output of its own: once a chunk has faded in it adds
// nothing, and while it fades in max(environmental, 1 - fade) is taken per vertex (vertex output bytes cost about the same
// at any resolution, and the fade only matters for the second a new chunk fades in). 52 -> 36 bytes per vertex.
//
// With PULLED defined (MetalTerrain's twin pipeline) the shader fetches its own vertices: the backend draws a whole Sodium
// terrain pass as one instanced draw over the quads that survived culling (mcterrain.m), 64 per instance; the 384-entry
// index buffer turns vertex_id into quad * 4 + corner. Vertices come from Sodium's geometry arenas through their GPU
// addresses, decoded as the vertex fetch would (RG32_UINT, RGBA8_UNORM, RG16_UINT, RGBA8_UINT: 20 bytes).
//
// With MCOPT_SHADE defined (mcopt native shading, -Dmcopt.shade=true; the loader prepends the define and
// mcopt/shade/shade_common.metal) the outputs carry what per-pixel lighting needs instead of a lightmapped colour and fog
// distances: the camera-relative position, the block and sky light levels and the chunk fade (the fragment stage derives
// the face normal from the position's screen derivatives: exact for planar quads, and no corner fetches or normal output
// here). 52 bytes per vertex with the position. Outside the overworld the colour keeps vanilla's lightmap; the fragment
// stage puts back vanilla's directional face shade (the face-shade-off mixins removed it from the vertices).
#include <metal_stdlib>
using namespace metal;

struct u_Globals {
    float4x4 u_ProjectionMatrix;
    float4x4 u_ModelViewMatrix;
    float4 u_FogColor;
    float2 u_EnvironmentFog;
    float2 u_RenderFog;
    float2 u_TexelSize;
    float2 u_TexCoordShrink;
    float u_FadePeriodInv;
    uint u_UseRGSS;
};

#ifdef MCOPT_SHADE
struct main0_out {
    half4 v_Color [[user(locn0)]];      // biome tint x AO (no face shade), alpha; outside the overworld x lightmap
    float2 v_TexCoord [[user(locn1)]];
    float3 v_Pos [[user(locn2)]];       // camera-relative world position
    half3 v_Light [[user(locn3)]];      // block, sky light 0..1, chunk fade-in (1 = faded in)
    float4 gl_Position [[position]];
};
#else
struct main0_out {
    half4 v_Color [[user(locn0)]];
    float2 v_TexCoord [[user(locn1)]];
    half2 v_Fog [[user(locn2)]];
    float4 gl_Position [[position]];
};
#endif

// What Sodium's push constants carry per region: camera-relative origin, ms since the region was created, region id.
struct Region { packed_float3 origin; int time; uint id; };

static main0_out shade(uint2 a_Position, float4 a_Color, uint2 a_TexCoord, uint4 a_LightAndData, Region r, constant u_Globals& g,
                       texture_buffer<int> sectionTimeInfo, texture2d<float> lightTex, sampler lightSampler
#ifdef MCOPT_SHADE
                       , constant ShadeFrame& sf
#endif
                       )
{
    main0_out out;
    uint3 hi = (uint3(a_Position.x) >> uint3(0u, 10u, 20u)) & uint3(1023u);
    uint3 lo = (uint3(a_Position.y) >> uint3(0u, 10u, 20u)) & uint3(1023u);
    float3 local = float3((hi << uint3(10u)) | lo) * 3.0517578125e-05 + float3(-8.0);
    uint drawId = a_LightAndData.w;
    float3 position = local + (float3(r.origin) + float3((uint3(drawId) >> uint3(5u, 0u, 2u)) & uint3(7u, 3u, 7u)) * 16.0);

#ifdef MCOPT_SHADE
    int chunkFade = sectionTimeInfo.read(r.id * 256u + drawId).x;
    half fade = chunkFade < 0 ? 1.0h : half(clamp(float(r.time - chunkFade) * g.u_FadePeriodInv, 0.0, 1.0));
    out.v_Pos = position;
    out.v_Light = half3(half2(saturate(float2(a_LightAndData.xy) * (1.0 / 240.0))), fade);
    half4 color = half4(a_Color);
    // outside the overworld vanilla's lightmap, unless the dims module lights the Nether/End per pixel (shade_dims.metal)
    if (sf.flags.x != SHADE_DIM_OVERWORLD && !shadeDimsLit(sf)) color *= half4(lightTex.sample(lightSampler, float2(a_LightAndData.xy) / 256.0, level(0.0)));
    out.v_Color = color;
#else

    float cylindrical = max(length(position.xz), abs(position.y));
    float spherical = length(position);
    // Sodium's linear fog tests d <= start before d >= end, so a range with start >= end means "no fog up to start" (Distant
    // Horizons turns vanilla fog off that way: start 4.2e14, end = render distance). Dividing by end - start would give ~1
    // there (every chunk in fog colour); a tiny positive span gives a huge negative value instead, which saturates to 0
    // like Sodium, and start == end still steps from 0 to 1 at that distance. Clamped before narrowing so half stays
    // finite. (Fog-span clamp after mcopt PR #4 by CazzaCoder.)
    float2 fogSpan = max(float2(g.u_EnvironmentFog.y - g.u_EnvironmentFog.x, g.u_RenderFog.y - g.u_RenderFog.x), float2(1e-4));
    float2 fogT = (float2(spherical, cylindrical) - float2(g.u_EnvironmentFog.x, g.u_RenderFog.x)) / fogSpan;
    out.v_Fog = half2(clamp(fogT, float2(-HALF_MAX), float2(HALF_MAX)));

    int chunkFade = sectionTimeInfo.read(r.id * 256u + drawId).x;
    half fade = chunkFade < 0 ? 1.0h : half(clamp(float(r.time - chunkFade) * g.u_FadePeriodInv, 0.0, 1.0));
    if (fade < 1.0h) out.v_Fog.x = max(out.v_Fog.x, half(1.0 - float(fade)));
#endif

    out.gl_Position = (g.u_ProjectionMatrix * g.u_ModelViewMatrix) * float4(position, 1.0);
    out.gl_Position.y = -out.gl_Position.y; // the backend flips vertex Y (see MetalPipeline)
#ifndef MCOPT_SHADE
    out.v_Color = half4(a_Color * lightTex.sample(lightSampler, float2(a_LightAndData.xy) / 256.0, level(0.0)));
#endif
    float2 bias = select(float2(-1.0), float2(1.0), (a_TexCoord >> uint2(15u)) != uint2(0u));
    out.v_TexCoord = bias * g.u_TexCoordShrink + float2(a_TexCoord & uint2(32767u)) / 32768.0;
    return out;
}

#ifdef PULLED

// firstVertex: the quad's first vertex in its arena, ~0u for the padding after the last one. packed: arena (bits 6-8), region (9-31).
struct Quad { uint firstVertex; uint packed; };
struct Arenas { device const uint *vertices[8]; };

vertex main0_out main0(uint vid [[vertex_id]], uint iid [[instance_id]], constant u_Globals& g [[buffer(0)]],
                       texture_buffer<int> u_SectionTimeInfo [[texture(1)]], texture2d<float> u_LightTex [[texture(2)]], sampler u_LightTexSmplr [[sampler(2)]],
                       const device Quad *quads [[buffer(20)]], const device Region *regions [[buffer(21)]], constant Arenas &arenas [[buffer(22)]]
#ifdef MCOPT_SHADE
                       , constant ShadeFrame& sf [[buffer(16)]]
#endif
                       )
{
    Quad q = quads[iid * 64u + (vid >> 2)];
    if (q.firstVertex == 0xFFFFFFFFu) {
        main0_out out;
        out.gl_Position = float4(0.0); // padding: both triangles collapse to a point and are dropped
        return out;
    }
    device const uint *quad = arenas.vertices[(q.packed >> 6) & 7u] + q.firstVertex * 5u;
    device const uint *v = quad + (vid & 3u) * 5u;
    uint tex = v[3], light = v[4];
    return shade(uint2(v[0], v[1]), unpack_unorm4x8_to_float(v[2]), uint2(tex & 0xFFFFu, tex >> 16), (uint4(light) >> uint4(0u, 8u, 16u, 24u)) & 0xFFu,
                 regions[q.packed >> 9], g, u_SectionTimeInfo, u_LightTex, u_LightTexSmplr
#ifdef MCOPT_SHADE
                 , sf
#endif
                 );
}

#else

struct PC { packed_float3 u_RegionOffset; int u_CurrentTime; uint u_RegionID; };

struct main0_in {
    uint2 a_Position [[attribute(0)]];
    float4 a_Color [[attribute(1)]];
    uint2 a_TexCoord [[attribute(2)]];
    uint4 a_LightAndData [[attribute(3)]];
};

vertex main0_out main0(main0_in in [[stage_in]], constant u_Globals& g [[buffer(0)]], constant PC& pc [[buffer(26)]],
                       texture_buffer<int> u_SectionTimeInfo [[texture(1)]], texture2d<float> u_LightTex [[texture(2)]], sampler u_LightTexSmplr [[sampler(2)]]
#ifdef MCOPT_SHADE
                       , constant ShadeFrame& sf [[buffer(16)]]
#endif
                       )
{
    return shade(in.a_Position, in.a_Color, in.a_TexCoord, in.a_LightAndData, Region {pc.u_RegionOffset, pc.u_CurrentTime, pc.u_RegionID}, g,
                 u_SectionTimeInfo, u_LightTex, u_LightTexSmplr
#ifdef MCOPT_SHADE
                 , sf
#endif
                 );
}

#endif
