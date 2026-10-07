// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// M2 post passes (compiled once by mcshade.m after shade_look + shade_common + shade_m2; driven by
// mcopt.metal.shade.ShadePost). All full-screen work is fragment passes into compressible render targets (apple-gpu §0.2).
//   shade_fs_vs              full-screen triangle (uv = (pixel + 0.5) / size, GL row order: no flip)
//   shade_bloom_down0        HDR scene -> bloom level 0 (half size): 13-tap downsample with a Karis average (no fireflies)
//   shade_bloom_down         level i -> i + 1: 13-tap downsample (Jimenez 2014, "Next generation post processing in CoD:AW")
//   shade_bloom_up           U_i = w_i L_i + tent(U_i+1): the octave spectrum accumulated from the smallest level up
//   shade_final              bloom (energy-conserving lerp) + fog glow, Purkinje, vignette, vibrance, Shade Filmic, dither -> RGBA8 sRGB
//   shade_bridge_encode      HDR -> RGBA8 display values (what a mod drawing display-referred colours, e.g. Distant Horizons, sees)
//   shade_bridge_decode      RGBA8 -> HDR where that mod changed a pixel (programmable blending reads the HDR value)
//   shade_histogram          compute: log-luminance histogram of bloom level 2 into ShadeGpu
//   shade_exposure           compute (1 thread): metered exposure, bounded around the reference, adapted -> ShadeGpu.exposure
//   shade_milkyway_bake      the Milky Way texture (once)
// The bloom composite follows the idea of SE Natural Bloom & Dirty Lens (c) 2019 sonicether, MIT License: a lerp toward
// the blurred image instead of an additive threshold, with octave weights i^s and a fog-weighted extra; the code is ours.

struct ShadeFsOut { float4 position [[position]]; float2 uv; };

vertex ShadeFsOut shade_fs_vs(uint vid [[vertex_id]]) {
	ShadeFsOut o;
	float2 p = float2(vid == 1 ? 3.0 : -1.0, vid == 2 ? 3.0 : -1.0);
	o.position = float4(p, 0.0, 1.0);
	o.uv = float2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5); // Metal texture coordinates of the target pixel (same orientation as the source)
	return o;
}

constexpr sampler shadeLinearClamp(filter::linear, address::clamp_to_edge);

// 13 taps: a 4x4 box from 4 bilinear taps in the middle, and 4 overlapping 2x2 boxes at the corners and edges.
static inline half3 shadeDown13(texture2d<half> src, float2 uv, float2 texel, bool karis) {
	half3 a = src.sample(shadeLinearClamp, uv + texel * float2(-2, -2)).rgb;
	half3 b = src.sample(shadeLinearClamp, uv + texel * float2(0, -2)).rgb;
	half3 c = src.sample(shadeLinearClamp, uv + texel * float2(2, -2)).rgb;
	half3 d = src.sample(shadeLinearClamp, uv + texel * float2(-2, 0)).rgb;
	half3 e = src.sample(shadeLinearClamp, uv).rgb;
	half3 f = src.sample(shadeLinearClamp, uv + texel * float2(2, 0)).rgb;
	half3 g = src.sample(shadeLinearClamp, uv + texel * float2(-2, 2)).rgb;
	half3 h = src.sample(shadeLinearClamp, uv + texel * float2(0, 2)).rgb;
	half3 i = src.sample(shadeLinearClamp, uv + texel * float2(2, 2)).rgb;
	half3 j = src.sample(shadeLinearClamp, uv + texel * float2(-1, -1)).rgb;
	half3 k = src.sample(shadeLinearClamp, uv + texel * float2(1, -1)).rgb;
	half3 l = src.sample(shadeLinearClamp, uv + texel * float2(-1, 1)).rgb;
	half3 m = src.sample(shadeLinearClamp, uv + texel * float2(1, 1)).rgb;
	half3 g0 = (j + k + l + m) * 0.25h, g1 = (a + b + d + e) * 0.25h, g2 = (b + c + e + f) * 0.25h, g3 = (d + e + g + h) * 0.25h, g4 = (e + f + h + i) * 0.25h;
	if (!karis) return g0 * 0.5h + (g1 + g2 + g3 + g4) * 0.125h;
	half w0 = 1.0h / (1.0h + shadeLumaH(g0)), w1 = 1.0h / (1.0h + shadeLumaH(g1)), w2 = 1.0h / (1.0h + shadeLumaH(g2)),
	     w3 = 1.0h / (1.0h + shadeLumaH(g3)), w4 = 1.0h / (1.0h + shadeLumaH(g4));
	w0 *= 0.5h; w1 *= 0.125h; w2 *= 0.125h; w3 *= 0.125h; w4 *= 0.125h;
	return (g0 * w0 + g1 * w1 + g2 * w2 + g3 * w3 + g4 * w4) / (w0 + w1 + w2 + w3 + w4);
}

// The first level reads the full-resolution scene: 4 bilinear taps (a 4x4 texel box), each Karis-weighted 1 / (1 + luma) so
// a few very bright texels (the sun, specular sparkles) can't flicker the whole bloom; the 13-tap filter takes over below.
// p.xy: the part of src's uv range that holds the image (TAAU: the live sub-rect of the internal-resolution HDR world,
// whose stale border must not bleed in; 1, 1 otherwise). Level 0 itself is half the output size.
fragment half4 shade_bloom_down0(ShadeFsOut in [[stage_in]], texture2d<half> src [[texture(0)]], constant float4 &p [[buffer(0)]]) {
	float2 texel = 1.0 / float2(src.get_width(), src.get_height());
	float2 uv = in.uv * p.xy, lo = texel, hi = p.xy - texel;
	half3 a = min(src.sample(shadeLinearClamp, clamp(uv + texel * float2(-1, -1), lo, hi)).rgb, half3(30000.0h));
	half3 b = min(src.sample(shadeLinearClamp, clamp(uv + texel * float2(1, -1), lo, hi)).rgb, half3(30000.0h));
	half3 c = min(src.sample(shadeLinearClamp, clamp(uv + texel * float2(-1, 1), lo, hi)).rgb, half3(30000.0h));
	half3 d = min(src.sample(shadeLinearClamp, clamp(uv + texel * float2(1, 1), lo, hi)).rgb, half3(30000.0h));
	half wa = 1.0h / (1.0h + shadeLumaH(a)), wb = 1.0h / (1.0h + shadeLumaH(b)), wc = 1.0h / (1.0h + shadeLumaH(c)), wd = 1.0h / (1.0h + shadeLumaH(d));
	return half4((a * wa + b * wb + c * wc + d * wd) / (wa + wb + wc + wd), 1.0h);
}

fragment half4 shade_bloom_down(ShadeFsOut in [[stage_in]], texture2d<half> src [[texture(0)]]) {
	float2 texel = 1.0 / float2(src.get_width(), src.get_height());
	return half4(shadeDown13(src, in.uv, texel, false), 1.0h);
}

// x: weight of this level, y: weight of the coarser accumulation (1, or 0 for the coarsest level)
struct ShadeBloomUp { float4 w; };

fragment half4 shade_bloom_up(ShadeFsOut in [[stage_in]], texture2d<half> level [[texture(0)]], texture2d<half> coarser [[texture(1)]],
                              constant ShadeBloomUp &p [[buffer(0)]]) {
	half3 l = level.sample(shadeLinearClamp, in.uv).rgb;
	float2 t = 1.0 / float2(coarser.get_width(), coarser.get_height());
	// 3x3 tent upsample of the coarser accumulation
	half3 u = coarser.sample(shadeLinearClamp, in.uv).rgb * 4.0h;
	u += (coarser.sample(shadeLinearClamp, in.uv + float2(t.x, 0)).rgb + coarser.sample(shadeLinearClamp, in.uv - float2(t.x, 0)).rgb
	    + coarser.sample(shadeLinearClamp, in.uv + float2(0, t.y)).rgb + coarser.sample(shadeLinearClamp, in.uv - float2(0, t.y)).rgb) * 2.0h;
	u += coarser.sample(shadeLinearClamp, in.uv + t).rgb + coarser.sample(shadeLinearClamp, in.uv - t).rgb
	    + coarser.sample(shadeLinearClamp, in.uv + float2(t.x, -t.y)).rgb + coarser.sample(shadeLinearClamp, in.uv + float2(-t.x, t.y)).rgb;
	return half4(l * half(p.w.x) + u * half(p.w.y / 16.0), 1.0h);
}

// p: x 1 / (sum of the bloom weights), y bloom on (0/1), z frame index, w debug view; q: x weight of bloom level 0;
// v: x volumetric light folded in (0/1, ShadePost.setVolumetric), y its max march distance (blocks), zw the live part of the
// VL textures (uv scale);
// r: xy the live render size in depth (TAAU dynamic resolution: the sub-rect of the fixed-size world depth this frame drew;
// output uv maps into it), z sharpen strength (TAAU: scene is the resolved history, sharpened here; 0 off), w 1 = Distant
// Horizons drew this frame (M3: the Nether fog leaves the pixels where depth is empty);
// x (M3 XDR, shade_final_xdr): x on, y highlight peak (x SDR white), z knee (exposed scene luminance), w octaves to the peak
struct ShadeFinalParams { float4 p; float4 q; float4 v; float4 r; float4 x; };

// Contrast-limited sharpen of the resolved TAAU history (original; was the TAAU module's output pass, folded in here: one
// full-resolution pass less): an unsharp mask from the 4-neighbour cross, weaker where the local contrast is already high,
// clamped to the neighbourhood's range so it never overshoots into halos. Restores the crispness of pixel-art textures the
// temporal resolve softens. Linear pre-exposed HDR: the contrast measure is relative, so it behaves the same at any level.
static inline half3 shadeSharpen(texture2d<half> img, uint2 px, half3 m, half amount) {
	int2 c = int2(px), last = int2(img.get_width(), img.get_height()) - 1;
	half3 n = img.read(uint2(min(c + int2(0, 1), last))).rgb;
	half3 s = img.read(uint2(max(c - int2(0, 1), int2(0)))).rgb;
	half3 e = img.read(uint2(min(c + int2(1, 0), last))).rgb;
	half3 w = img.read(uint2(max(c - int2(1, 0), int2(0)))).rgb;
	half3 lo = min(m, min(min(n, s), min(e, w)));
	half3 hi = max(m, max(max(n, s), max(e, w)));
	half range = dot(hi - lo, half3(0.2126h, 0.7152h, 0.0722h)) / max(dot(hi, half3(0.2126h, 0.7152h, 0.0722h)), 0.02h);
	half k = amount * 2.0h * (1.0h - smoothstep(0.15h, 0.9h, range));
	return clamp(m + (m - (n + s + e + w) * 0.25h) * k, lo, hi);
}

// Volumetric light folded into the final pass (the VL module's low-resolution result, ShadePost.setVolumetric): vlT holds
// rgb = in-scattered light (radiance before exposure), a = transmittance of its medium; vlD the matching VL history, a =
// the distance each texel marched (blocks, 1e4 = sky). Depth-aware bilinear upsample to the pixel at uv with scene distance
// dist: bilinear x distance similarity; where no low-resolution texel is at this depth (a thin leaf against the sky), the
// closest one, its light scaled by how much air this pixel's ray crosses (no halos around foliage).
static inline half4 shadeVlUpsample(texture2d<half> vlT, texture2d<half> vlD, float2 uv, float dist, float maxD, float2 uvScale) {
	// the live part of the VL targets (TAAU dynamic resolution: allocated for the largest scale; uvScale = live / allocated)
	float2 size = float2(vlT.get_width(), vlT.get_height()) * uvScale;
	uv = min(uv * uvScale, uvScale - 0.5 / float2(vlT.get_width(), vlT.get_height()));
	float2 lp = uv / uvScale * size - 0.5;
	float2 fl = floor(lp);
	half2 fr = half2(lp - fl);
	int2 base = int2(fl), hi = int2(size) - 1;
	half invTol = half(1.0 / (0.04 * dist + 0.15));
	half hd = half(min(dist, 3e4));
	// the four distances of the bilinear footprint in one fetch (gather order: (0,1) (1,1) (1,0) (0,0)); when all of them
	// are at this pixel's depth (most of the screen) one hardware-filtered sample does it
	half4 dd = vlD.gather(shadeLinearClamp, uv, int2(0), component::w);
	if (all(abs(dd - hd) * invTol < 0.5h)) return vlT.sample(shadeLinearClamp, uv);
	half4 sum = 0.0h, bestC = half4(0.0h, 0.0h, 0.0h, 1.0h);
	half wsum = 0.0h, best = 6e4h, bestD = 1.0h;
	for (int j = 0; j <= 1; j++) {
		for (int i = 0; i <= 1; i++) {
			uint2 q = uint2(clamp(base + int2(i, j), int2(0), hi));
			half4 s = vlT.read(q);
			half sd = j == 0 ? (i == 0 ? dd.w : dd.z) : (i == 0 ? dd.x : dd.y);
			half bw = (i == 0 ? 1.0h - fr.x : fr.x) * (j == 0 ? 1.0h - fr.y : fr.y) + 1e-3h;
			half diff = abs(sd - hd);
			half x = diff * invTol;
			half w = bw / (1.0h + x * x * (1.0h + x)); // ~ e^-x near 0, falls faster beyond
			sum += s * w;
			wsum += w;
			if (diff < best) {
				best = diff;
				bestC = s;
				bestD = sd;
			}
		}
	}
	if (wsum > 0.02h) return sum / wsum;
	half mD = half(maxD);
	return half4(bestC.rgb * saturate((min(hd, mD) + 0.5h) / (min(bestD, mD) + 0.5h)), bestC.a);
}

// Camera-relative world position of the pixel at uv = (pixel + 0.5) / size with reversed-Z depth d. Targets hold GL row
// order (row 0 = the bottom of the view), so the NDC is uv * 2 - 1 with no flip.
static inline float3 shadeWorldFromDepth(float2 uv, float d, constant ShadeFrame &f) {
	float2 ndc = uv * 2.0 - 1.0;
	float4 w = f.invViewProj * float4(ndc, d, 1.0);
	return w.xyz / w.w;
}

// Everything of the final pass before the display transform: the exposed scene colour (haze, the dimension fogs, camera
// media, VL, bloom, Purkinje, vignette, vibrance). lum: the scene luminance before exposure (debug view 4).
static float3 shadeFinalScene(ShadeFsOut in, texture2d<half> scene, texture2d<half> bloom, depth2d<float> depth, texture2d<half> bloom0,
                              texture2d<half> vlT, texture2d<half> vlD, constant ShadeFinalParams &P, constant ShadeFrame &f,
                              constant ShadeLook &L, constant ShadeGpu &g, constant ShadeResources &sr, thread float &lum) {
	uint2 px = uint2(in.position.xy);
	float ex = max(g.exposure.x, 1e-6);
	// height haze and mist (E06) from depth, once per pixel (the shaded pipelines skip it in the HDR target): surfaces in the
	// air only (under water / in lava the game's fog owns it), not the sky (the atmosphere has its own) nor Distant Horizons'
	// LODs (not in this depth; DH fogs them). Depth may be smaller than the image (a resolver's output over the internal world).
	float2 dsize = P.r.x > 0.0 ? P.r.xy : float2(depth.get_width(), depth.get_height());
	uint2 dpx = min(uint2(in.uv * dsize), uint2(dsize) - 1u);
	float d = depth.read(dpx);
	bool dims = (L.tier.y & SHADE_FEATURE_DIMS) != 0 && L.dimTint.x > 0.0 && f.flags.x != SHADE_DIM_OVERWORLD;
	half3 c0;
	if (dims && (L.tier.y & SHADE_FEATURE_SHIMMER) != 0 && L.dimTint.w < 1.5 && d > 0.0) {
		// M3 heat shimmer over distant Nether terrain (shade_dims.metal): this pixel shows a slightly displaced one
		float dist = length(shadeWorldFromDepth(in.uv, d, f));
		int2 q = clamp(int2(px) + int2(round(shadeShimmer(in.uv, dist, f, sr.milkyWay))), int2(0), int2(scene.get_width(), scene.get_height()) - 1);
		c0 = scene.read(uint2(q)).rgb;
	} else {
		c0 = scene.read(px).rgb;
		if (P.r.z > 0.0) c0 = shadeSharpen(scene, px, c0, half(P.r.z));
	}
	float3 c = float3(c0);
	float3 T = float3(1.0);
	bool hazeOn = (L.tier.y & SHADE_FEATURE_HAZE) != 0 && f.flags.x == SHADE_DIM_OVERWORLD && f.fogColor.a < 0.5;
	float3 wp = d > 0.0 ? shadeWorldFromDepth(in.uv, d, f) : float3(0.0);
	if (d > 0.0 && hazeOn) {
		if (dot(wp, wp) > 4.0) {
			float3 S = shadeHazeInscatter(wp, f, L, 1.0, T);
			c = c * T + S * ex;
		}
	}
	// M3: the Nether's layered glowing haze / the End's violet haze (shade_dims.metal), in open air
	if (dims && f.flags.y == 0) {
		c = shadeDimsFog(c, wp, d > 0.0, f, L, ex, P.r.w > 0.5, sr.milkyWay);
		T = float3(d > 0.0 ? exp(-L.dimTint.x * length(wp)) : 0.0);
	}
	// M3: the camera inside lava or powder snow (E26)
	if ((L.tier.y & SHADE_FEATURE_MEDIA) != 0 && (f.flags.y == 2 || f.flags.y == 3)) c = shadeMedia(c, in.uv, d > 0.0 ? length(wp) : 1e4, f, sr.milkyWay);
	// volumetric light (god rays, canopy mist; VL module): its medium is the air near the camera, in front of the haze
	float tv = 1.0;
	if (P.v.x > 0.5) {
		half4 v = shadeVlUpsample(vlT, vlD, in.uv, d > 0.0 ? length(wp) : 1e4, P.v.y, P.v.z > 0.0 ? P.v.zw : float2(1.0));
		tv = float(v.a);
		// v.rgb < 0 on the sky: the crepuscular shadows (dark rays), floored at 55 % of the pixel
		float3 lit = c * tv;
		c = max(lit + clamp(float3(v.rgb) * ex, -30000.0, 30000.0), lit * 0.55);
	}
	if (P.p.y > 0.5) {
		float3 b = float3(bloom.sample(shadeLinearClamp, in.uv).rgb + bloom0.sample(shadeLinearClamp, in.uv).rgb * half(P.q.x)) * P.p.x;
		// fog glow: the haze and the canopy mist both soften what is behind them
		float m = L.bloom.x + (d > 0.0 ? L.bloom.z * (1.0 - shadeLuma(T) * tv) : L.bloom.z * (0.25 + 0.75 * (1.0 - tv)));
		c = mix(c, b, saturate(m));
	}
	// Purkinje: dim light (scene luminance, not exposed) shifts toward the rods' cool grey
	lum = shadeLuma(c) / ex;
	float rods = (1.0 - smoothstep(0.004, 0.04, lum)) * L.grade1.w;
	c = mix(c, float3(shadeLuma(c)) * float3(0.72, 0.88, 1.3), rods);
	// vignette (sparing what is bright)
	float2 r = (in.uv - 0.5) * float2(float(scene.get_width()) / float(scene.get_height()), 1.0);
	float vr = pow(saturate(length(r) * 1.25), 2.4);
	c *= 1.0 - 0.12 * vr * (1.0 - 0.5 * saturate(shadeLuma(c) - 1.0));
	// vibrance: saturate the less saturated colours more
	float Y = shadeLuma(c);
	float mx = max(max(c.r, c.g), c.b), mn = min(min(c.r, c.g), c.b);
	float chroma = (mx - mn) / max(mx, 1e-4);
	c = max(Y + (c - Y) * (1.0 + L.grade0.z * (1.0 - saturate(chroma / 0.6))), 0.0);
	return c;
}

// The SDR display value -> what main stores: black floor, sRGB, triangular dither (+-1 LSB), debug view 4.
static inline float3 shadeFinalEncode(float3 y, float4 position, constant ShadeFinalParams &P, constant ShadeLook &L, float lum) {
	float phi = L.grade2.w;
	y += phi * exp(-3.0 * y / max(phi, 1e-6));
	float3 srgb = shadeLinearToSrgbExact(saturate(y));
	// triangular dither, +-1 LSB
	float n0 = shadeNoise(position.xy, int(P.p.z)), n1 = shadeNoise(position.xy + 17.0, int(P.p.z) + 7);
	srgb += (n0 + n1 - 1.0) * (L.output.z / 255.0);
	if (P.p.w == 4.0) srgb = float3(saturate(log2(max(lum, 1e-6)) / 16.0 + 0.6)); // debug: luminance (log)
	return saturate(srgb);
}

fragment float4 shade_final(ShadeFsOut in [[stage_in]], texture2d<half> scene [[texture(0)]], texture2d<half> bloom [[texture(1)]],
                            depth2d<float> depth [[texture(2)]], texture2d<half> bloom0 [[texture(3)]],
                            texture2d<half> vlT [[texture(4)]], texture2d<half> vlD [[texture(5)]], constant ShadeFinalParams &P [[buffer(0)]],
                            constant ShadeFrame &f [[buffer(16)]], constant ShadeLook &L [[buffer(17)]], constant ShadeGpu &g [[buffer(18)]],
                            constant ShadeResources &sr [[buffer(19)]]) {
	float lum;
	float3 c = shadeFinalScene(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, lum);
	return float4(shadeFinalEncode(shadeDisplay(c, L), in.position, P, L, lum), 1.0);
}

// M3 XDR (E23; mcxdr.m composes at present): main gets the same SDR image with alpha 0 (what later draws -- hand, GUI --
// cover gets alpha > 0), the second target the highlights' brightness above it, in linear Rec.709 (>= 0): the SDR curve
// is kept up to a knee (white surfaces at noon, the sky: full-screen brightness stays where it is), and above it the luminance
// rises toward the peak over P.x.w octaves (the sun, glints, lava, torches, bloom cores), in the SDR pixel's own hue.
struct ShadeFinalXdrOut { float4 sdr [[color(0)]]; half4 excess [[color(1)]]; };

fragment ShadeFinalXdrOut shade_final_xdr(ShadeFsOut in [[stage_in]], texture2d<half> scene [[texture(0)]], texture2d<half> bloom [[texture(1)]],
                                          depth2d<float> depth [[texture(2)]], texture2d<half> bloom0 [[texture(3)]],
                                          texture2d<half> vlT [[texture(4)]], texture2d<half> vlD [[texture(5)]], constant ShadeFinalParams &P [[buffer(0)]],
                                          constant ShadeFrame &f [[buffer(16)]], constant ShadeLook &L [[buffer(17)]], constant ShadeGpu &g [[buffer(18)]],
                                          constant ShadeResources &sr [[buffer(19)]]) {
	float lum;
	float3 c = shadeFinalScene(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, lum);
	float3 y = shadeDisplay(c, L);
	float h = saturate(log2(max(shadeLuma(c), 1e-6) / P.x.z) / P.x.w);
	float boost = (P.x.y - 1.0) * h * h * (3.0 - 2.0 * h);
	float3 excess = y / max(shadeLuma(y), 0.05) * boost;
	ShadeFinalXdrOut o;
	o.sdr = float4(shadeFinalEncode(y, in.position, P, L, lum), 0.0);
	if (P.p.w == 8.0) o.sdr = float4(saturate(float3(boost / max(P.x.y - 1.0, 1e-3), h, 0.0)), 0.0); // debug: XDR boost, knee
	o.excess = half4(half3(min(max(excess, 0.0), 60000.0)), 1.0h);
	return o;
}

// ---- bridge for mods that draw display-referred colours over the HDR world (Distant Horizons) ---------------------------
fragment float4 shade_bridge_encode(ShadeFsOut in [[stage_in]], texture2d<half> scene [[texture(0)]], constant ShadeLook &L [[buffer(17)]]) {
	float3 c = float3(scene.read(uint2(in.position.xy)).rgb);
	return float4(shadeLinearToSrgbExact(saturate(shadeDisplay(c, L))), 1.0);
}

// The HDR value stays where the mod left the bridge's value untouched (within 1.5 LSB of what encode wrote); elsewhere the
// mod's colour comes in through the inverse display transform, like any unshaded pipeline's.
fragment float4 shade_bridge_decode(ShadeFsOut in [[stage_in]], float4 hdr [[color(0)]], texture2d<half> ldr [[texture(0)]],
                                    constant ShadeLook &L [[buffer(17)]]) {
	float3 now = float3(ldr.read(uint2(in.position.xy)).rgb);
	float3 was = round(shadeLinearToSrgbExact(saturate(shadeDisplay(hdr.rgb, L))) * 255.0) / 255.0;
	if (all(abs(now - was) < 1.5 / 255.0)) return hdr;
	return float4(shadeLdrToHdr(now, L), hdr.a);
}

// ---- auto-exposure (E20) -------------------------------------------------------------------------------------------------
#define SHADE_HIST_MIN -14.0
#define SHADE_HIST_MAX 8.0

kernel void shade_histogram(texture2d<half> src [[texture(0)]], device ShadeGpu &g [[buffer(18)]], uint2 id [[thread_position_in_grid]],
                            uint lid [[thread_index_in_threadgroup]]) {
	threadgroup atomic_uint bins[64];
	if (lid < 64) atomic_store_explicit(&bins[lid], 0u, memory_order_relaxed);
	threadgroup_barrier(mem_flags::mem_threadgroup);
	uint w = src.get_width(), h = src.get_height();
	if (id.x < w && id.y < h) {
		float ex = max(g.exposure.x, 1e-6);
		float lum = float(shadeLumaH(src.read(id).rgb)) / ex;
		float t = (log2(max(lum, 1e-7)) - SHADE_HIST_MIN) / (SHADE_HIST_MAX - SHADE_HIST_MIN);
		uint bin = uint(clamp(t * 64.0, 0.0, 63.0));
		float2 r = (float2(id) + 0.5) / float2(w, h) - 0.5;
		float weight = exp(-dot(r, r) / 0.3);
		if (r.x > 0.2 && r.y > 0.2) weight *= 0.4; // the hand's corner
		atomic_fetch_add_explicit(&bins[bin], uint(weight * 16.0 + 0.5), memory_order_relaxed);
	}
	threadgroup_barrier(mem_flags::mem_threadgroup);
	if (lid < 64) {
		uint v = atomic_load_explicit(&bins[lid], memory_order_relaxed);
		if (v != 0) atomic_fetch_add_explicit((device atomic_uint *)&g.histogram[lid], v, memory_order_relaxed);
	}
}

kernel void shade_exposure(device ShadeGpu &g [[buffer(18)]], constant ShadeLook &L [[buffer(17)]], uint id [[thread_position_in_grid]]) {
	if (id != 0) return;
	float total = 0.0;
	for (int i = 0; i < 64; i++) total += float(g.histogram[i]);
	float logRef = log2(max(L.exposureRef.x, 1e-6)) + L.grade0.x;
	float target = logRef;
	float mean = g.scene.x;
	if (total > 0.0) {
		// weighted mean of log2 luminance between the 40th and 92nd percentiles (ignores the sun and black pockets)
		float lo = total * 0.40, hi = total * 0.92, acc = 0.0, sum = 0.0, wsum = 0.0;
		for (int i = 0; i < 64; i++) {
			float n = float(g.histogram[i]);
			float a = max(lo - acc, 0.0), b = min(hi - acc, n);
			float take = max(b - a, 0.0);
			float l = SHADE_HIST_MIN + (float(i) + 0.5) / 64.0 * (SHADE_HIST_MAX - SHADE_HIST_MIN);
			sum += take * l;
			wsum += take;
			acc += n;
		}
		if (wsum > 0.0) mean = sum / wsum;
		float metered = log2(max(L.exposureRef.z, 1e-4)) - mean;
		float lambda = L.exposureRef.y;
		target = lambda * metered + (1.0 - lambda) * logRef;
		target = clamp(target, logRef - L.exposureCfg.x, logRef + L.exposureCfg.y);
	}
	float now = log2(max(g.exposure.x, 1e-6));
	float next;
	if (L.exposureRef.w > 0.5 || !(now > -30.0 && now < 30.0)) {
		next = target;
	} else {
		float tau = target > now ? L.exposureCfg.z : L.exposureCfg.w;
		next = now + (target - now) * (1.0 - exp(-L.dt.x / max(tau, 1e-3)));
	}
	g.exposure = float4(exp2(next), exp2(target), g.exposure.x, next);
	g.scene.x = mean;
	g.scene.w += 1.0;
	for (int i = 0; i < 64; i++) g.histogram[i] = 0u;
}

// ---- the Milky Way (E04), baked once into an equirectangular texture in galactic longitude/latitude ---------------------
static float shadeValueNoise(float3 x, uint salt) {
	float3 i = floor(x), fr = x - i;
	float3 u = fr * fr * (3.0 - 2.0 * fr);
	int3 c = int3(i);
	float n000 = shadeHash01(shadeHash3(c, salt)), n100 = shadeHash01(shadeHash3(c + int3(1, 0, 0), salt));
	float n010 = shadeHash01(shadeHash3(c + int3(0, 1, 0), salt)), n110 = shadeHash01(shadeHash3(c + int3(1, 1, 0), salt));
	float n001 = shadeHash01(shadeHash3(c + int3(0, 0, 1), salt)), n101 = shadeHash01(shadeHash3(c + int3(1, 0, 1), salt));
	float n011 = shadeHash01(shadeHash3(c + int3(0, 1, 1), salt)), n111 = shadeHash01(shadeHash3(c + int3(1, 1, 1), salt));
	return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y), mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);
}
static float shadeFbm(float3 x, int octaves, uint salt) {
	float s = 0.0, a = 0.5, n = 0.0;
	for (int i = 0; i < octaves; i++) {
		s += a * shadeValueNoise(x, salt + uint(i) * 101u);
		n += a;
		x = x * 2.03 + 17.1;
		a *= 0.5;
	}
	return s / n;
}

fragment half4 shade_milkyway_bake(ShadeFsOut in [[stage_in]]) {
	float lon = (in.uv.x - 0.5) * 6.2831853, lat = (in.uv.y - 0.5) * 3.1415927;
	float3 dir = float3(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat));
	float lc = abs(lon);                       // angle to the galactic centre (at lon 0)
	float b = abs(lat) * 57.29578, l = lc * 57.29578;
	float disc = exp(-b / (3.0 + 3.0 * pow(cos(lon * 0.5), 2.0))) * (0.6 + 0.4 * shadeFbm(dir * 6.0, 4, 11u));
	float bulge = 2.2 * exp(-(l * l / 144.0 + b * b / 36.0));
	float dust = 1.0 - 0.75 * (1.0 - abs(2.0 * shadeFbm(dir * 9.0, 3, 23u) - 1.0)) * exp(-b / 2.0);
	float clouds = 0.3 * pow(shadeFbm(dir * 40.0, 2, 37u), 4.0) * exp(-b / 8.0);
	float v = (disc + bulge) * dust + clouds;
	float3 col = mix(float3(1.0, 0.86, 0.66), float3(0.74, 0.82, 1.0), saturate(l / 70.0));
	// alpha (M3): a tileable fbm over the texture for the sky effects (shade_dims.metal shadeDimsNoise: aurora, End nebula
	// and beams, Nether fog, media), so they need no texture of their own
	return half4(half3(col * v * 0.035), half(shadeDimsBakeNoise(in.uv)));
}
