// This file is copied or adapted from Sodium (https://github.com/CaffeineMC/sodium), Copyright JellySquid
// (jellysquid3) and contributors. Unlike the rest of mcopt, it is licensed under the PolyForm Shield License 1.0.0
// (LICENSES/PolyForm-Shield-1.0.0.md, https://polyformproject.org/licenses/shield/1.0.0), not Apache-2.0.
// See NOTICE.
//
// Sodium's block_layer_opaque.fsh reading the lean outputs of sodium_terrain.vs.metal. The loader prepends
// "#define ALPHA_CUTOUT x" for the cutout and translucent passes, like Sodium's own shader defines.
//
// With MCOPT_SHADE defined (mcopt native shading; the loader prepends the define and mcopt/shade/shade_common.metal) the
// texel is lit per pixel in the overworld: sun or moon light with the shadow map, directional sky light by sky level, warm
// block light, vanilla's baked AO kept in the vertex colour, fog into the sky behind it, exposure and tone curve. Outside
// the overworld it is vanilla's lightmapped colour and fog. Debug views (mcopt.shade.debug): 1 shadow/cascades, 2 normals,
// 3 lighting only.
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

struct main0_out { float4 fragColor [[color(0)]]; };

#ifdef MCOPT_SHADE
struct main0_in {
    half4 v_Color [[user(locn0)]];
    float2 v_TexCoord [[user(locn1)]];
    float3 v_Pos [[user(locn2)]];
    half3 v_Light [[user(locn3)]]; // block, sky, chunk fade
};
#else
struct main0_in {
    half4 v_Color [[user(locn0)]];
    float2 v_TexCoord [[user(locn1)]];
    half2 v_Fog [[user(locn2)]]; // environmental (with 1 - the chunk's fade while it fades in), render distance
};
#endif

static float4 sampleNearest(texture2d<float> source, sampler s, float2 uv, float2 pixelSize, float2 du, float2 dv, float2 texelScreenSize)
{
    float2 uvTexelCoords = uv / pixelSize;
    float2 texelCenter = round(uvTexelCoords) - float2(0.5);
    float2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset = (((texelOffset - float2(0.5)) * pixelSize) / texelScreenSize) + float2(0.5);
    texelOffset = fast::clamp(texelOffset, float2(0.0), float2(1.0));
    return source.sample(s, (texelCenter + texelOffset) * pixelSize, gradient2d(du, dv));
}

constant float2 RGSS[4] = { float2(0.125, 0.375), float2(-0.125, -0.375), float2(0.375, -0.125), float2(-0.375, 0.125) };

fragment main0_out main0(main0_in in [[stage_in]], constant u_Globals& g [[buffer(0)]], texture2d<float> u_BlockTex [[texture(3)]], sampler u_BlockTexSmplr [[sampler(3)]]
#ifdef MCOPT_SHADE
                         , constant ShadeFrame& sf [[buffer(16)]], depth2d_array<float> shadowMap [[texture(16)]], sampler shadowCompare [[sampler(12)]],
                         texture2d<half> skyLut [[texture(17)]], sampler skyLutSmplr [[sampler(13)]], float4 fragCoord [[position]]
#endif
                         )
{
    float2 uv = in.v_TexCoord;
    float2 pixelSize = g.u_TexelSize;
    float2 du = dfdx(uv);
    float2 dv = dfdy(uv);
#ifdef MCOPT_SHADE
    float3 nf = cross(dfdx(in.v_Pos), dfdy(in.v_Pos)); // before any discard: the whole 2x2 quad takes part
#endif
    float2 texelScreenSize = sqrt(du * du + dv * dv);
    float4 color = sampleNearest(u_BlockTex, u_BlockTexSmplr, uv, pixelSize, du, dv, texelScreenSize);
    if (g.u_UseRGSS != 0u) {
        float minPixelSize = fast::min(pixelSize.x, pixelSize.y);
        float blendFactor = smoothstep(minPixelSize, minPixelSize * 2.0, fast::max(texelScreenSize.x, texelScreenSize.y));
        float duLength = length(du), dvLength = length(dv);
        float mip = fast::max(0.0, log2(sqrt(fast::min(duLength, dvLength) * fast::max(duLength, dvLength)) / minPixelSize));
        float4 rgss = float4(0.0);
        for (int i = 0; i < 4; i++) rgss += u_BlockTex.sample(u_BlockTexSmplr, uv + RGSS[i] * pixelSize, level(mip));
        color = mix(color, rgss * 0.25, float4(blendFactor));
    }
#ifdef MCOPT_SHADE
    float4 texel = color;
#endif
    color *= float4(in.v_Color);
#ifdef ALPHA_CUTOUT
    if (color.w < ALPHA_CUTOUT) discard_fragment();
#endif
#ifdef MCOPT_SHADE
    float3 p = in.v_Pos;
    float fogValue = max(shadeVanillaFog(p, g.u_EnvironmentFog, g.u_RenderFog), 1.0 - float(in.v_Light.z));
    // The face normal from the position's screen derivatives: a 2x2 pixel quad always shades one triangle, so this is that
    // triangle's plane (flat, exact). Its sign is arbitrary: turned toward the camera, which also lights both sides of
    // two-sided cutouts. Axis-aligned faces (nearly all of them) are snapped to their axis.
    nf *= rsqrt(max(dot(nf, nf), 1e-30));
    if (dot(nf, p) > 0.0) nf = -nf;
    half3 n = half3(nf);
    half3 a = abs(n);
    half amax = max(max(a.x, a.y), a.z);
    bool thin = amax < 0.9h; // crossed plants and other slanted cutouts
    if (!thin) n = half3(a.x == amax ? sign(n.x) : 0.0h, a.x != amax && a.y == amax ? sign(n.y) : 0.0h, a.x != amax && a.y != amax ? sign(n.z) : 0.0h);
    main0_out out;
    if (sf.flags.x != SHADE_DIM_OVERWORLD) {
        color.rgb *= float(shadeVanillaFace(n, sf.flags.x)); // the face-shade-off mixins removed it from the vertices
        out.fragColor = float4(mix(color.xyz, g.u_FogColor.xyz, float3(fogValue * g.u_FogColor.w)), color.w);
        return out;
    }
    if (shadeDev(sf, SHADE_DEV_FLAT_LIGHT)) {
        out.fragColor = float4(color.rgb, color.w);
        return out;
    }
    half3 albedo = shadeToLinear(half3(texel.rgb) * in.v_Color.rgb);
    int debug = sf.flags.w;
    if (debug == 3) albedo = half3(0.45h);
#ifdef ALPHA_CUTOUT
    bool foliage = !thin && ALPHA_CUTOUT > 0.25; // the cutout pass (not translucent): leaves, vines and the like
#else
    bool foliage = false;
#endif
    // no map lookups where the direct light is gated off anyway (caves, under overhangs: sky light below ~4)
    half shadow = in.v_Light.y > 0.3h ? shadeShadow(shadowMap, shadowCompare, sf, p, n, thin || foliage) : half(1.0);
    float alpha = color.w;
#ifdef ALPHA_CUTOUT
    // the translucent pass: water (a top face tinted by the biome's water colour; stained glass and ice have white vertex
    // colours) gets its own body colour, a sky reflection and a sun glint, seen from above water
    half3 tint = in.v_Color.rgb;
    bool water = ALPHA_CUTOUT < 0.25 && n.y > 0.5h && max3(tint.r, tint.g, tint.b) - min3(tint.r, tint.g, tint.b) > 0.08h && sf.flags2.y == 0
        && !shadeDev(sf, SHADE_DEV_NO_WATER);
#else
    bool water = false;
#endif
    float3 radiance;
    if (water) {
#ifdef ALPHA_CUTOUT
        float3 lit = shadeLit(half3(1.0h), n, in.v_Light.y, in.v_Light.x, shadow, false, false, sf);
        float4 w = shadeWater(lit, tint, shadeLumaH(half3(texel.rgb)), alpha, p, in.v_Light.y, shadow, skyLut, skyLutSmplr, sf);
        radiance = w.rgb;
        alpha = w.a;
#endif
    } else {
        radiance = shadeLit(albedo, n, in.v_Light.y, in.v_Light.x, shadow, thin, foliage, sf);
        // Light sources until M2's material mask: at block light 14-15, bright warm texels (torch and lantern flames,
        // glowstone, lava, shroomlights) glow instead of being lit like the rest of the block.
        half tl = shadeLumaH(half3(texel.rgb));
        if (in.v_Light.x > 0.92h && texel.r > 0.9 && tl > 0.62h && texel.r > texel.b + 0.12) radiance += float3(albedo) * float3(sf.ambientBlock.xyz) * 2.2;
    }
    radiance = shadeFogAir(radiance, p, fogValue, skyLut, skyLutSmplr, sf);
    float3 rgb = shadeFinish(radiance, fogValue, sf);
    if (debug == 2) rgb = float3(n) * 0.5 + 0.5;
    if (debug == 1) {
        float d = length(p);
        float3 tint = sf.flags.z == 0 ? float3(1.0, 0.3, 0.3) : d < sf.cascadeSplits.x ? float3(0.4, 1.0, 0.4) : d < sf.cascadeSplits.y ? float3(0.4, 0.6, 1.0) : float3(1.0);
        rgb = tint * (0.25 + 0.75 * float(shadow));
    }
    out.fragColor = float4(rgb, alpha);
    return out;
#else
    float2 fog = saturate(float2(in.v_Fog));
    float fogValue = fast::max(fog.x, fog.y);
    main0_out out;
    out.fragColor = float4(mix(color.xyz, g.u_FogColor.xyz, float3(fogValue * g.u_FogColor.w)), color.w);
    return out;
#endif
}
