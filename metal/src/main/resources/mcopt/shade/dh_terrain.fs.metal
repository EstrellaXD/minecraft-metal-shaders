// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
//
// FT1: fragment stage for Distant Horizons' LOD terrain, lit by the shade model (written for mcopt; DH's own stage is
// replaced, only its uniform block layout is shared). The LOD gets what Sodium's terrain gets, minus what a LOD has no
// data for: sun / moon N.L and sky / ground ambient by its sky light (shadeLit2), warm block light by its block light, the
// volumetric clouds' shadow, the aerial perspective and haze toward the sky behind (shadeFogAir2, which in this RGBA8
// compile also applies the height haze the final pass gives the world: the final pass skips DH's pixels, not in its
// depth), the game's fog under water / in lava. No terrain shadow lookup (the cascades end long before the LODs start).
//
// Output: DH composites its LODs into the frame's display-referred main colour, which ShadePost's bridge decodes back
// into the HDR world target (shade_bridge_decode: shadeLdrToHdr). So the LOD writes its pre-exposed radiance through the
// same display transform the bridge's encode uses: it comes back as that radiance (8-bit display steps).
// Prepended: "#define MCOPT_SHADE", the shade prelude and the MCOPT_SLOT_* defines of the pipeline's uniforms.

struct DhFrag {        // DH's "fragUniformBlock"
	float clipDistance;
	float noiseIntensity;
	int noiseSteps;
	int noiseDropoff;
	uint dither;
	uint noiseEnabled;
};

struct DhIn {
	float3 p [[user(locn0)]];
	float3 cell [[user(locn1)]];
	half4 color [[user(locn2)]];
	half2 light [[user(locn3), flat]];
	half3 lightmap [[user(locn6), flat]];
	uint face [[user(locn4), flat]];
	uint tile [[user(locn5), flat]];
};

// face 0..5: down, up, north (-z), south (+z), west (-x), east (+x)
static inline half3 dhNormal(uint face) {
	switch (face) {
		case 0u: return half3(0.0h, -1.0h, 0.0h);
		case 1u: return half3(0.0h, 1.0h, 0.0h);
		case 2u: return half3(0.0h, 0.0h, -1.0h);
		case 3u: return half3(0.0h, 0.0h, 1.0h);
		case 4u: return half3(-1.0h, 0.0h, 0.0h);
		default: return half3(1.0h, 0.0h, 0.0h);
	}
}

// where in its 16x16 texture tile a face point falls (upright on the sides, as the block's own texture is drawn)
static inline float2 dhTileUv(float3 cell, uint face) {
	float3 q = fract(cell);
	float2 uv = face <= 1u ? q.xz : face <= 3u ? float2(q.x, 1.0 - q.y) : float2(q.z, 1.0 - q.y);
	if (face == 0u) uv.y = 1.0 - uv.y;
	if (face == 2u || face == 5u) uv.x = 1.0 - uv.x;
	return uv;
}

// 4x4 ordered threshold (0..15 / 16), for the dithered hand-over to the game's own chunks
static inline float dhBayer(float2 px) {
	uint2 i = uint2(px) & 3u;
	uint b = ((i.x ^ i.y) & 1u) << 3 | (i.y & 1u) << 2 | ((i.x ^ i.y) & 2u) | (i.y & 2u) >> 1;
	return float(b) / 16.0;
}

fragment float4 main0(DhIn in [[stage_in]], constant DhFrag &u [[buffer(MCOPT_SLOT_fragUniformBlock)]],
                      texture2d<float> atlas [[texture(MCOPT_SLOT_uBlockAtlas)]], sampler atlasSmplr [[sampler(MCOPT_SLOT_uBlockAtlas)]],
                      constant ShadeFrame &sf [[buffer(16)]], constant ShadeLook &sl [[buffer(17)]], constant ShadeGpu &sg [[buffer(18)]],
                      constant ShadeResources &sr [[buffer(19)]], texture2d<half> skyLut [[texture(17)]], sampler skyLutSmplr [[sampler(13)]],
                      float4 fragCoord [[position]]) {
	float3 p = in.p;
	float d = length(p);
	// the game's chunks own everything nearer than DH's clip distance: a hard cut, or a dithered one over 1-1.5x of it
	// (SHADE_DH_NOCLIP, dev: none, to price the discard: a pipeline that may discard loses the GPU's hidden-surface removal)
#ifndef SHADE_DH_NOCLIP
	if (u.dither != 0u) {
		if (smoothstep(u.clipDistance, u.clipDistance * 1.5, d) <= dhBayer(fragCoord.xy) + 0.001) discard_fragment();
	} else if (u.clipDistance > 0.0 && d < u.clipDistance) {
		discard_fragment();
	}
#endif
	// albedo: the block's colour, with the tile's detail around it (the tile stores the texture relative to its mean,
	// 0.5 = the mean: x2), weighed by the tile's alpha
	half3 srgb = in.color.rgb;
	if (in.tile != 0u) {
		float2 cellOrigin = float2(float(in.tile & 255u), float(in.tile >> 8)) * 16.0;
		float4 t = atlas.sample(atlasSmplr, (cellOrigin + dhTileUv(in.cell, in.face) * 16.0) / float2(atlas.get_width(), atlas.get_height()));
		srgb = mix(srgb, saturate(srgb * half3(t.rgb) * 2.0h), half(t.a));
	}
	// not lit by the shade model (Nether / End without the dims module's light, the setup fallback's vanilla frames): the
	// game's lightmap, as DH draws it, and the game's fog colour over the environmental range
	if (sf.flags.x > SHADE_DIM_END || (sf.flags.x != SHADE_DIM_OVERWORLD && !shadeDimsLit(sf))) {
		float3 v = float3(srgb * in.lightmap);
		float fa = saturate((d - sf.fogRange.x) / max(sf.fogRange.y - sf.fogRange.x, 1.0)) * sf.fogColor.a;
		return float4(mix(v, sf.fogColor.rgb, fa), in.color.a);
	}
	half3 n = dhNormal(in.face);
	half block = in.light.x, sky = in.light.y;
	half3 albedo = shadeToLinear(srgb);
	bool overworld = sf.flags.x == SHADE_DIM_OVERWORLD;
	float ex = max(sg.exposure.x, 1e-6);
	float3 radiance;
	half outAlpha = in.color.a;
	if (!overworld) {
		// Nether / End with the dims module: the dimension's own light (shade_dims.metal), as Sodium's terrain without sky light
		radiance = shadeLit2(albedo, n, half(0.0), block, half(0.0), false, false, sf, sl);
		radiance += shadeLitDimsExtra(albedo, n, half(1.0), false, false, p, sf, sl);
	} else {
		// no terrain shadow map out here: direct light where the sky is open (shadeLit2 gates it by sky light), dimmed by
		// the volumetric clouds' shadow
		half shadow = sky > 0.3h ? half(shadeCloudShadow(p, sl, sr.cloudShadow)) : half(1.0);
		radiance = shadeLit2(albedo, n, sky, block, shadow, false, false, sf, sl);
		// (FT2.7, audit idx 74) a lightning flash lights the LODs like the terrain (shadeWxFlash): no dark seam at the chunks' edge
		if (sl.flash.w > 0.0) {
			half gk = shadeSkyCurve(sky);
			radiance += float3(albedo) * float3(0.78, 0.86, 1.0) * (sl.flash.w * (saturate(dot(float3(n), sl.flash.xyz)) * 0.65 + 0.35) * float(gk * gk));
		}
#ifdef SHADE_DH_TRANSLUCENT
		// FT2.5: the LODs' water (DH's translucent top faces) mirrors the sky like the near water (water.metal): Schlick's
		// Fresnel for water, the atmosphere and the volumetric clouds (their reflection dome) in the mirrored direction, the
		// sun's glint with the near water's far roughness (its glitter road goes on past the chunks). A flat surface out
		// here: waves average out over a LOD pixel. The reflection draws opaque in proportion to its Fresnel weight, as the
		// near water's does; DH's water colour (lit) shows through the rest. No terrain in it (no depth for SSR out here).
		if (in.face == 1u && in.color.a < 0.99h && !shadeDev(sf, SHADE_DEV_FLAT_LIGHT)) {
			float3 v = p / max(d, 1e-4);
			// the waves a LOD pixel averages over (slope ~0.2): at grazing views the facets that face the eye reflect at a
			// steeper angle (less: Fresnel ~0.34, not 1) and higher into the sky (bluer than the horizon). As a flat mirror the far
			// sea took the whole white horizon haze and read as milk (w1, Gael 2026-10-09: "se ve rara")
			const float slope2 = 0.2 * 0.2;
			float cosV = sqrt(v.y * v.y + slope2);
			float3 r = float3(v.xz * (sqrt(max(1.0 - cosV * cosV, 0.0)) / max(length(v.xz), 1e-4)), cosV).xzy;
			float open = float(shadeSkyCurve(sky));
			float m = 1.0 - saturate(cosV);
			float fr = (0.0204 + 0.9796 * m * m * m * m * m) * (0.35 + 0.65 * open);
			float3 refl = shadeSkyLookup(r, skyLut, skyLutSmplr, sf);
			float4 cd = shadeCloudsDome(r, sl, sr.cloudDome);
			refl = refl * cd.a + cd.rgb;
			// w3 (Gael: the far sea's reflections looked low quality, a flat colour): where the flat mirror direction is on
			// screen, the rendered sky there (last frame's HDR image, as the near water's shadeWaterSkyRefl does): the clouds at
			// full resolution, the sunset's colours, the far islands (LODs are not in the depth: they count as sky). Anything the
			// depth holds nearer than ~500 blocks (the game's own chunks) is not what the far sea mirrors: the dome / LUT there.
			// Faded out over the last 1/8 of the screen. Needs TAAU's depth of that image (ShadeLook.ssr.y).
			if (sl.ssr.x > 0.5 && sl.ssr.y > 0.5) {
				float3 rm = float3(v.x, -v.y, v.z);
				float4 c = sf.viewProj * float4(rm, 0.0), pc = sf.prevViewProj * float4(rm, 0.0);
				if (c.w > 1e-4 && pc.w > 1e-4) {
					float2 uv = c.xy / c.w * 0.5 + 0.5;
					float wgt = saturate(min(uv.x, 1.0 - uv.x) * 8.0) * saturate(min(uv.y, 1.0 - uv.y) * 8.0);
					float2 uvp = saturate(pc.xy / pc.w * 0.5 + 0.5);
					constexpr sampler lin(filter::linear, address::clamp_to_edge, coord::normalized);
					if (wgt > 0.0) wgt *= smoothstep(400.0, 600.0, float(sr.prevDepth.sample(lin, uvp).r));
					if (wgt > 0.0) refl = mix(refl, float3(sr.prevScene.sample(lin, uvp).rgb) / max(sg.exposure.z, 1e-6), wgt);
				}
			}
			refl *= open;
			// GGX glint (normal up), alpha 0.135: the near water's at 110+ blocks
			float3 l = sf.lightDirWorld.xyz, h = normalize(l - v);
			float nh = saturate(h.y), nl = saturate(l.y), nv = max(-v.y, 1e-3);
			const float a2 = 0.135 * 0.135 * 0.135 * 0.135;
			float dd = nh * nh * (a2 - 1.0) + 1.0;
			float vis = 0.5 / max(nl * sqrt(nv * nv * (1.0 - a2) + a2) + nv * sqrt(nl * nl * (1.0 - a2) + a2), 1e-5);
			float mh = 1.0 - saturate(dot(h, -v));
			float spec = a2 / (dd * dd) * vis * nl * (0.0204 + 0.9796 * mh * mh * mh * mh * mh);
			float3 glint = sf.sunColor.rgb * min(spec, 60.0) * float(smoothstep(0.15h, 0.85h, shadow)) * open;
			float ba = float(in.color.a);
			float aw = fr + (1.0 - fr) * ba;
			radiance = (refl * fr + radiance * ba * (1.0 - fr) + glint) / max(aw, 1e-3);
			outAlpha = half(aw);
		}
#endif
		radiance = shadeFogAir2(radiance, p, 0.0, 0.0, skyLut, skyLutSmplr, sf, sl, &sr);
	}
	if (shadeDev(sf, SHADE_DEV_FLAT_LIGHT)) radiance = float3(albedo) * sf.ambientSky.xyz;
	if (sf.flags.w == 2) radiance = (float3(n) * 0.5 + 0.5) / ex * 0.18; // debug view 2: normals
	float3 c = min(max(radiance, 0.0) * ex, 60000.0);
	// (FT2.8, audit idx 101) the Nether's / End's medium on the LODs too (the final pass gives it to the near terrain from the main
	// depth, which holds no LODs): sharp unhazed LOD terrain stood behind a wall of smoke at the render distance
	if (!overworld) c = shadeDimsFog(c, p, true, sf, sl, ex, false, sr.milkyWay, false);
	// the game's own fog (under water, in lava / powder snow, blindness; outside the overworld) over the environmental range
	float a = sf.fogColor.a * saturate((d - sf.fogRange.x) / max(sf.fogRange.y - sf.fogRange.x, 1.0));
	if (a > 0.0) {
		float3 t = sf.flags.y == 1 ? pow(1.0 - a, float3(2.6, 1.25, 1.0)) : float3(1.0 - a);
		c = c * t + shadeLdrToHdr(sf.fogColor.rgb, sl) * (1.0 - t);
	}
	return float4(shadeLinearToSrgbExact(saturate(shadeDisplay(c, sl))), outAlpha);
}
