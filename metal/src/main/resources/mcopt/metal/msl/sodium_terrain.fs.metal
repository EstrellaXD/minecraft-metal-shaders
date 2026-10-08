// This file is copied or adapted from Sodium (https://github.com/CaffeineMC/sodium), Copyright JellySquid
// (jellysquid3) and contributors. Unlike the rest of mcopt, it is licensed under the PolyForm Shield License 1.0.0
// (LICENSES/PolyForm-Shield-1.0.0.md, https://polyformproject.org/licenses/shield/1.0.0), not Apache-2.0.
// See NOTICE.
//
// Sodium's block_layer_opaque.fsh reading the lean outputs of sodium_terrain.vs.metal. The loader prepends
// "#define ALPHA_CUTOUT x" for the cutout and translucent passes, like Sodium's own shader defines.
//
// With MCOPT_SHADE defined (mcopt native shading; the loader prepends the define and mcopt/shade/shade_look.metal,
// shade_common.metal, shade_m2.metal) the texel is lit per pixel in the overworld: sun or moon light with the shadow map
// (PCSS-lite), directional sky light by sky level, warm block light, vanilla's baked AO kept in the vertex colour, height
// haze and fog into the sky behind it; then exposure (pre-exposed radiance into the HDR world target with MCOPT_HDR, M1's
// tone curve into RGBA8 without). Outside the overworld it is vanilla's lightmapped colour and fog (converted to
// scene-linear in the HDR target). Debug views (mcopt.shade.debug): 1 shadow/cascades, 2 normals, 3 lighting only.
// With SHADE_WATER also defined (the translucent pass; mcopt.shade.water, the loader prepends mcopt/shade/water.metal)
// water is recognized by its atlas sprites and shaded by shadeWaterSurface: waves, reflections (screen space over the
// sky), refraction, absorption, caustics, foam, the surface from below; it composites what is behind it from the scene
// copy taken at the switch to this pass (pre-exposed HDR radiance with MCOPT_HDR) and is drawn opaque.
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
                         , constant ShadeFrame& sf [[buffer(16)]], constant ShadeLook& sl [[buffer(17)]], constant ShadeGpu& sg [[buffer(18)]],
                         depth2d_array<float> shadowMap [[texture(16)]], sampler shadowCompare [[sampler(12)]],
                         texture2d<half> skyLut [[texture(17)]], sampler skyLutSmplr [[sampler(13)]], float4 fragCoord [[position]]
#ifdef SHADE_WATER
                         , texture2d<half> sceneCopy [[texture(18)]], depth2d<float> depthCopy [[texture(19)]], constant ShadeWaterParams& wp [[buffer(23)]]
                         , texture2d<half> waterWaves [[texture(20)]], texture2d<float> waterCoarse [[texture(21)]]
                         , texture2d<half> waterDetail [[texture(22)]]
#endif
#if defined(MCOPT_HDR) && (defined(SHADE_WATER) || defined(SHADE_WEATHER))
                         , constant ShadeResources& sr [[buffer(19)]]
#endif
#endif
                         )
{
    float2 uv = in.v_TexCoord;
    float2 pixelSize = g.u_TexelSize;
    float2 du = dfdx(uv);
    float2 dv = dfdy(uv);
#ifdef MCOPT_SHADE
    float3 dpx = dfdx(in.v_Pos), dpy = dfdy(in.v_Pos);
    float3 nf = cross(dpx, dpy); // before any discard: the whole 2x2 quad takes part
#ifdef SHADE_WATER
    float waterFootprint = max(length(dpx), length(dpy));
    float2 waterFlow = shadeWaterFlowDir(dpx, dpy, du, dv);
#endif
    // TAA render scale (cameraBlock.w, 1 at native): gradients as at native resolution, i.e. mip bias log2(scale), so
    // textures keep their native sharpness; the jitter and the temporal resolve take care of the extra texture aliasing
    float mipScale = sf.cameraBlock.w > 0.0 ? sf.cameraBlock.w : 1.0;
    du *= mipScale;
    dv *= mipScale;
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
        out.fragColor = float4(shadeVanillaOut(mix(color.xyz, g.u_FogColor.xyz, float3(fogValue * g.u_FogColor.w)), sl), color.w);
        return out;
    }
    if (shadeDev(sf, SHADE_DEV_FLAT_LIGHT)) {
        out.fragColor = float4(shadeVanillaOut(color.rgb, sl), color.w);
        return out;
    }
    float ex = shadeExposure(sf, sg);
    half3 albedo = shadeToLinear(half3(texel.rgb) * in.v_Color.rgb);
    int debug = sf.flags.w;
    if (debug == 3) albedo = half3(0.45h);
#ifdef ALPHA_CUTOUT
    bool foliage = !thin && ALPHA_CUTOUT > 0.25; // the cutout pass (not translucent): leaves, vines and the like
#else
    bool foliage = false;
#endif
#ifdef SHADE_WATER
    // the water module: water by its sprites (every face; vertex colour guess while the sprites are unknown), shaded in
    // full when this frame's scene copy exists, else M1's stand-in for top faces seen from above
    // quad-uniform (one triangle per 2x2 quad; helper lanes' extrapolated uv may leave the sprite): SSR's lanes work together
    int waterSprite = int(shadeQuadMax(float(shadeWaterSprite(uv, wp))));
    bool waterFull = waterSprite > 0 && wp.state.x > 0.5 && !shadeDev(sf, SHADE_DEV_NO_WATER);
#else
    bool waterFull = false;
#endif
    // no map lookups where the direct light is gated off anyway (caves, under overhangs: sky light below ~4); the water
    // module only gates its glint and caustics with it: M1's 4-tap tent there instead of PCSS
    half shadow = in.v_Light.y <= 0.3h ? half(1.0)
        : waterFull ? shadeShadow(shadowMap, shadowCompare, sf, p, n, false)
        : shadeShadow2(shadowMap, shadowCompare, sf, sl, p, n, thin || foliage, foliage, fragCoord.xy, in.v_Light.y);
    float alpha = color.w;
#ifdef ALPHA_CUTOUT
    // the translucent pass: water (a top face tinted by the biome's water colour; stained glass and ice have white vertex
    // colours) gets its own body colour, a sky reflection and a sun glint, seen from above water
    half3 tint = in.v_Color.rgb;
    bool water = ALPHA_CUTOUT < 0.25 && n.y > 0.5h && max3(tint.r, tint.g, tint.b) - min3(tint.r, tint.g, tint.b) > 0.08h && sf.flags2.y == 0
        && !shadeDev(sf, SHADE_DEV_NO_WATER);
#ifdef SHADE_WATER
    if (waterSprite == 0) water = false;
#endif
#else
    bool water = false;
#endif
    float3 radiance;
#ifdef SHADE_WATER
    if (waterFull) {
        half3 up = half3(0.0h, 1.0h, 0.0h);
        ShadeWaterIn wi;
        wi.p = p;
        wi.nFace = float3(n);
        wi.footprint = waterFootprint;
        wi.flowDir = waterSprite == 2 && n.y > 0.5h ? waterFlow : float2(0.0);
        wi.pixel = fragCoord.xy;
        wi.depth = fragCoord.z;
        wi.tint = tint;
        wi.sky = in.v_Light.y;
        wi.shadow = shadow;
        wi.dxz = float4(dpx.xz, dpy.xz);
        wi.detail = float4(0.0);
#ifdef SHADE_WATER_DETAIL
        // M2.1 fine ripples (baked waves): top faces, and the surface seen from below with the camera under water
        if ((n.y > 0.5h || (n.y < -0.5h && sf.flags2.y != 0)) && wp.state.w > 0.5) wi.detail = shadeWaterDetailIn(wi, sf, waterDetail);
#endif
        wi.lit = shadeLit2(half3(1.0h), up, in.v_Light.y, in.v_Light.x, shadow, false, false, sf, sl);
        wi.exposure = ex;
#ifdef MCOPT_HDR
#ifdef SHADE_SSR_PREV
        wi.prevExposure = sl.ssr.x > 0.5 ? sg.exposure.z : 0.0; // last frame's finished HDR world (ShadeLook.ssr.x: it was written): SSR colour, rendered sky in the reflections
        wi.look = &sl;
#else
        wi.prevExposure = 0.0;
        wi.look = nullptr;
#endif
        wi.fog = sf.flags2.y != 0 ? shadeLdrToHdr(sf.fogColor.rgb, sl) / max(ex, 1e-6) : float3(0.0); // only read from below
        texture2d<half> prevScene = sr.prevScene;
#else
        wi.prevExposure = 0.0;
        wi.look = nullptr;
        wi.fog = sf.flags2.y != 0 ? shadeWaterDisplayRadiance(half3(sf.fogColor.rgb), ex) : float3(0.0);
        texture2d<half> prevScene = sceneCopy;
#endif
        radiance = shadeWaterSurface(wi, sf, wp, skyLut, skyLutSmplr, sceneCopy, depthCopy, prevScene, waterWaves, waterCoarse);
        alpha = 1.0; // the water composited what is behind it from the scene copy
        if (debug == 7) radiance = float3(saturate(wi.footprint * 4.0), float(waterSprite == 2), saturate(length(p) / 128.0)) / max(ex, 1e-3);
    } else
#endif
    if (water) {
#ifdef ALPHA_CUTOUT
        float3 lit = shadeLit2(half3(1.0h), n, in.v_Light.y, in.v_Light.x, shadow, false, false, sf, sl);
        float4 w = shadeWater(lit, tint, shadeLumaH(half3(texel.rgb)), alpha, p, in.v_Light.y, shadow, skyLut, skyLutSmplr, sf);
        radiance = w.rgb;
        alpha = w.a;
#endif
    } else {
#ifdef SHADE_WEATHER
        // M3 weather (weather_surface.metal): rain-soaked, darker albedo, puddles on open top faces, snow cover
#ifdef MCOPT_HDR
        ShadeWet wet = shadeWetSurface(p, n, in.v_Light.y, thin, foliage, max(length(dpx), length(dpy)), sf, sl, sr.weatherNoise, sl.ssr.z > 0.5);
#else
        ShadeWet wet = shadeWetSurface(p, n, in.v_Light.y, thin, foliage, max(length(dpx), length(dpy)), sf, sl, skyLut, false);
#endif
        half3 wetAlbedo = debug == 3 ? albedo : shadeWetAlbedo(albedo, wet);
        radiance = shadeLit2(wetAlbedo, n, in.v_Light.y, in.v_Light.x, shadow, thin, foliage, sf, sl);
#else
        radiance = shadeLit2(albedo, n, in.v_Light.y, in.v_Light.x, shadow, thin, foliage, sf, sl);
#endif
        // Light sources until M2's material mask: at block light 14-15, bright warm texels (torch and lantern flames,
        // glowstone, lava, shroomlights) glow instead of being lit like the rest of the block.
        half tl = shadeLumaH(half3(texel.rgb));
        if (in.v_Light.x > 0.92h && texel.r > 0.9 && tl > 0.62h && texel.r > texel.b + 0.12) radiance += float3(albedo) * float3(sf.ambientBlock.xyz) * 2.2;
#ifdef SHADE_WEATHER
#if defined(SHADE_COLOR_SHADOW) && defined(MCOPT_HDR)
        // light that came through stained glass, ice or water takes their colour (the shadow pass's tint map, near cascade)
        if (shadow > 0.0h && in.v_Light.y > 0.3h && sl.ssr.w > 0.5 && !shadeDev(sf, SHADE_DEV_WX_NO_TINT)) radiance += shadeShadowTintDelta(wetAlbedo, n, in.v_Light.y, shadow, shadeShadowTint(p, n, sf, sl, sr.shadowTint), thin, foliage, sf);
#endif
#ifdef SHADE_LIGHTNING
        radiance += shadeWxFlash(wetAlbedo, n, in.v_Light.y, sl);
#endif
        {
            ShadeWetLight wl;
            wl.p = p;
            wl.n = n;
            wl.sky = in.v_Light.y;
            wl.shadow = shadow;
            wl.pixel = fragCoord.xy;
#ifdef MCOPT_HDR
            wl.prevExposure = sl.ssr.x > 0.5 ? sg.exposure.z : 0.0;
            wl.histOk = sl.ssr.x > 0.5 && sl.ssr.y > 0.5;
            radiance = shadeWetShade(radiance, wetAlbedo, wet, wl, sf, sl, skyLut, skyLutSmplr, sr.prevScene, sr.prevDepth);
#else
            wl.prevExposure = 0.0;
            wl.histOk = false;
            radiance = shadeWetShade(radiance, wetAlbedo, wet, wl, sf, sl, skyLut, skyLutSmplr, skyLut, skyLut);
#endif
        }
        if (debug == 8) radiance = float3(float(wet.omega), float(wet.puddle), float(wet.snow)) * 0.25 / max(ex, 1e-3);
#endif
#if defined(SHADE_WATER) && defined(SHADE_GLOSSY)
        // the water module's glossy translucents (stained glass, ice, slime, honey): mirror reflection over the lit colour
        if (!thin && !shadeDev(sf, SHADE_DEV_NO_WATER)) {
#ifdef MCOPT_HDR
#ifdef SHADE_SSR_PREV
            float prevEx = sl.ssr.x > 0.5 ? sg.exposure.z : 0.0;
#else
            float prevEx = 0.0;
#endif
            float4 gl = shadeGlossy(radiance, alpha, p, float3(n), fragCoord.xy, in.v_Light.y, shadow, ex, prevEx, sf, &sl, wp, skyLut,
                                    skyLutSmplr, sceneCopy, depthCopy, sr.prevScene, waterCoarse);
#else
            float4 gl = shadeGlossy(radiance, alpha, p, float3(n), fragCoord.xy, in.v_Light.y, shadow, ex, 0.0, sf, nullptr, wp, skyLut, skyLutSmplr,
                                    sceneCopy, depthCopy, sceneCopy, waterCoarse);
#endif
            radiance = gl.rgb;
            alpha = gl.a;
        }
#endif
    }
    radiance = shadeFogAir2(radiance, p, fogValue, 1.0 - float(in.v_Light.z), skyLut, skyLutSmplr, sf, sl);
    float3 rgb = shadeFinish2(radiance, fogValue, sf, sl, ex);
    if (debug == 2) rgb = shadeVanillaOut(float3(n) * 0.5 + 0.5, sl);
    if (debug == 1) {
        float d = length(p);
        float3 tint = sf.flags.z == 0 ? float3(1.0, 0.3, 0.3) : d < sf.cascadeSplits.x ? float3(0.4, 1.0, 0.4) : d < sf.cascadeSplits.y ? float3(0.4, 0.6, 1.0) : float3(1.0);
        rgb = shadeVanillaOut(tint * (0.25 + 0.75 * float(shadow)), sl);
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
