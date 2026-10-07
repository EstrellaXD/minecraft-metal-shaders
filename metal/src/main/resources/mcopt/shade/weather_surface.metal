// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// mcopt native shading, M3 weather (shader bible E13 + E08's coloured shadows), the receiver side: wet surfaces, puddles
// with reflections, raindrop ripples, snow cover, lightning light, and the tint of light that came through stained glass or
// water (the shadow pass's tint map). Original code; the techniques are public (Schlick 1994, Walter et al. 2007 GGX,
// Lagarde 2011 roughness-aware Fresnel, McGuire & Mara 2014 screen-space marching, value noise).
//
// Assembled by ShadeMsl.terrain after the prelude (shade_look + shade_common + shade_m2) and before water.metal, with the
// SHADE_WEATHER defines (mcopt.metal.shade.weather.ShadeWeather.prelude) in front. Inputs:
//   ShadeLook.wet    x wetness 0..1, y puddles 0..1, z snow cover 0..1, w rain intensity (smoothed rain level, + thunder)
//   ShadeLook.wind   xy wind direction (unit, world xz), z rain slant (horizontal blocks per block of fall), w gust 0..1
//   ShadeLook.flash  xyz unit direction toward the lightning (camera-relative), w its light (radiance on a white surface
//                    facing it, before exposure)
//   ShadeLook.ssr.y  1 when ShadeResources.prevDepth (5) holds the linear view depth of ShadeResources.prevScene (TAA history)
//   ShadeResources   prevScene (1), prevDepth (5, R16F, blocks along last frame's view axis), shadowTint (6, RGBA16F: rgb
//                    the transmittance of the translucent casters, a their reversed-Z light depth, near cascade only)
// UNITS: as shade_common.metal (camera-relative world, blocks; radiance before exposure; reversed-Z).
#ifndef MCOPT_SHADE_WEATHER_SURFACE
#define MCOPT_SHADE_WEATHER_SURFACE

#ifndef SHADE_PUDDLE_STEPS
#define SHADE_PUDDLE_STEPS 10
#endif

constexpr sampler shadeWxLinear(filter::linear, address::clamp_to_edge, coord::normalized);
constexpr sampler shadeWxNearest(filter::nearest, address::clamp_to_edge, coord::normalized);

// ---- noise on the absolute world xz -------------------------------------------------------------------------------------
// The camera block wraps every 4096 blocks; every lattice below has a period that divides 4096, so the wrap is seamless.
static inline float2 shadeWxXZ(float2 rel, constant ShadeFrame &f) {
	return rel + f.cameraFract.xz + fmod(f.cameraBlock.xz, 4096.0);
}

static inline float shadeWxHash(int2 c, uint salt) {
	return shadeHash01(shadeHash(uint(c.x) * 0x8da6b343u ^ shadeHash(uint(c.y) * 0xd8163841u ^ salt)));
}

// Value noise (smooth, 0..1) over cells of size 1 in x, wrapping every mask + 1 cells.
static inline float shadeWxValue(float2 x, int mask, uint salt) {
	float2 i = floor(x), u = x - i;
	u = u * u * (3.0 - 2.0 * u);
	int2 c = int2(i);
	float a = shadeWxHash(c & mask, salt), b = shadeWxHash((c + int2(1, 0)) & mask, salt);
	float d = shadeWxHash((c + int2(0, 1)) & mask, salt), e = shadeWxHash((c + int2(1, 1)) & mask, salt);
	return mix(mix(a, b, u.x), mix(d, e, u.x), u.y);
}

// The puddle field: puddles 2-6 blocks across (8-block cells), lobes (4) and ragged rims (2), on a 32-block swell that makes
// some stretches of ground wetter than others. About 0.5 on average (std 0.14): threshold 0.6 covers ~30 % of open ground.
static inline float shadeWxPuddleField(float2 xa) {
	float big = shadeWxValue(xa * (1.0 / 32.0), 127, 0x01234567u);
	return 0.5 * shadeWxValue(xa * 0.125, 511, 0x51ed270bu) + 0.3 * shadeWxValue(xa * 0.25, 1023, 0x2c1b3c6du)
		+ 0.2 * shadeWxValue(xa * 0.5, 2047, 0x297a2d39u) + 0.25 * (big - 0.5);
}

// ---- raindrop ripples -----------------------------------------------------------------------------------------------------
// Two staggered grids of 0.7-block cells; each cell gets a drop every 0.55-1.05 s (fewer in light rain), at a random point
// of its central half, which spreads a ring at 0.42 blocks/s that fades in about half a second and never leaves the cell.
// Ring profile: a damped sine packet around the radius rho, h = a sin(2 pi e / lambda) exp(-(e / w)^2), e = r - rho; the
// slope comes from its analytic derivative. Returns the height gradient (dh/dx, dh/dz) in blocks per block.
#define SHADE_RIPPLE_CELL 0.7
static float2 shadeWxRipples(float2 xa, float t, float rate) {
	float2 g = float2(0.0);
	const float C = SHADE_RIPPLE_CELL, lambda = 0.075, wid = 0.055, k = 6.2831853 / lambda;
	for (int layer = 0; layer < 2; layer++) {
		float2 q = xa * (1.0 / C) + (layer == 0 ? float2(0.0) : float2(0.5, 0.37));
		float2 ci = floor(q);
		float2 lp = (q - ci) * C;
		uint h = shadeHash3(int3(int2(ci) & 8191, layer), 0x6a09e667u);
		float period = 0.45 + 0.4 * shadeHash01(h);
		float ph = t / period + shadeHash01(h ^ 0xbb67ae85u);
		float n = floor(ph);
		float age = (ph - n) * period;
		uint hk = shadeHash(h ^ (uint(int(n)) * 0x9e3779b9u));
		if (shadeHash01(hk) > rate) continue;
		float2 c0 = (0.3 + 0.4 * float2(shadeHash01(hk ^ 0x3c6ef372u), shadeHash01(hk ^ 0xa54ff53au))) * C;
		float2 d = lp - c0;
		float r = length(d);
		float e = r - age * 0.42;
		float env = exp(-age * 5.0) * (1.0 - smoothstep(0.2 * C, 0.29 * C, r)) * exp(-(e * e) / (wid * wid));
		float s;
		float c = sincos(k * e, s);
		float dh = env * (k * c - 2.0 * e / (wid * wid) * s);
		g += dh * d / max(r, 1e-4);
	}
	return g * 0.004;
}

// ---- wet surfaces and puddles -----------------------------------------------------------------------------------------------
struct ShadeWet {
	half omega;   // wetness of this surface 0..1 (darkening, gloss)
	half puddle;  // standing water 0..1
	half snow;    // snow cover 0..1
	float2 slope; // ripple height gradient on puddles (world xz)
};

// How wet the surface at camera-relative p is. n: snapped face normal (toward the camera); sky: sky light 0..1 (rain only
// reaches sky light 15: open sky; 14 under an overhang's edge gets a little); thin: crossed plants; foliage: leaves and the
// like (no puddles; they get wet and snow-covered); footprint: blocks per pixel (ripples fade where they would alias).
static ShadeWet shadeWetSurface(float3 p, half3 n, half sky, bool thin, bool foliage, float footprint, constant ShadeFrame &f,
                                constant ShadeLook &L) {
	ShadeWet w;
	w.omega = 0.0h;
	w.puddle = 0.0h;
	w.snow = 0.0h;
	w.slope = float2(0.0);
	half open = smoothstep(0.90h, 0.995h, sky);
	if (open <= 0.0h) return w;
	half face = n.y > 0.5h ? 1.0h : n.y < -0.5h ? 0.0h : 0.55h; // walls get wet streaks, undersides stay dry
	w.omega = half(L.wet.x) * open * face * (thin ? 0.45h : foliage ? 0.7h : 1.0h);
	float2 xa = shadeWxXZ(p.xz, f);
#ifdef SHADE_SNOW_COVER
	if (L.wet.z > 0.001 && n.y > 0.5h) {
		float drift = shadeWxValue(xa * 0.5, 2047, 0x1f83d9abu) * 0.6 + shadeWxPuddleField(xa + 37.0) * 0.4;
		w.snow = half(smoothstep(1.0 - float(L.wet.z) * 1.15, 1.15 - float(L.wet.z) * 1.15, drift + 0.15)) * open;
		w.omega *= 1.0h - w.snow;
	}
#endif
#ifdef SHADE_PUDDLES
	if (L.wet.y > 0.001 && n.y > 0.5h && !thin && !foliage && sky > 0.95h) {
		float field = shadeWxPuddleField(xa);
		float t = mix(0.80, 0.60, float(L.wet.y));
		half P = half(smoothstep(t, t + 0.035, field)) * smoothstep(0.95h, 0.995h, sky) * (1.0h - w.snow);
		// a darker wet rim around each puddle, and the ground between them stays a little wetter
		w.omega = max(w.omega, half(smoothstep(t - 0.07, t, field)) * half(L.wet.y) * open);
		w.puddle = P;
#ifdef SHADE_RIPPLES
		float fade = 1.0 - smoothstep(0.012, 0.035, footprint);
		if (P > 0.0h && L.wet.w > 0.01 && fade > 0.0) w.slope = shadeWxRipples(xa, f.sunPath.w, min(L.wet.w * 1.1, 1.0)) * fade;
#endif
	}
#endif
	return w;
}

// Wet albedo: porous surfaces darken (light scatters longer under the water film and is absorbed), standing water dims what
// is under it a little more; snow covers it.
static inline half3 shadeWetAlbedo(half3 albedo, ShadeWet w) {
	half3 a = albedo;
	if (w.omega > 0.0h) a = pow(max(a, half3(1e-4h)), half3(1.0h + 0.65h * w.omega)) * (1.0h - 0.08h * w.omega);
	if (w.puddle > 0.0h) a *= 1.0h - 0.3h * w.puddle;
	if (w.snow > 0.0h) a = mix(a, half3(0.86h, 0.88h, 0.92h), w.snow);
	return a;
}

// Fresnel of a water film (f0 0.02), roughness-aware (Lagarde 2011): rough films don't reach a full mirror at grazing angles.
static inline float shadeWxFresnel(float cosT, float rough) {
	float m = 1.0 - saturate(cosT);
	float m2 = m * m;
	return 0.02 + (max(1.0 - rough, 0.02) - 0.02) * m2 * m2 * m;
}

// GGX specular of the sun or moon (radiance per unit sunColor), Smith-correlated visibility, times n.l.
static inline float shadeWxGgx(float3 n, float3 v, float3 l, float alpha) {
	float3 h = normalize(l - v);
	float nh = saturate(dot(n, h)), nl = saturate(dot(n, l)), nv = max(dot(n, -v), 1e-3);
	float a2 = alpha * alpha;
	float dd = nh * nh * (a2 - 1.0) + 1.0;
	float D = a2 / (3.14159265 * dd * dd);
	float gv = nl * sqrt(nv * nv * (1.0 - a2) + a2), gl = nv * sqrt(nl * nl * (1.0 - a2) + a2);
	return 3.14159265 * D * (0.5 / max(gv + gl, 1e-5)) * nl;
}

// ---- reflections from last frame's image ------------------------------------------------------------------------------------
// The opaque pass has no copy of this frame yet, so puddles reflect LAST frame's finished world (ShadeResources.prevScene:
// the TAA history, pre-exposed with ShadeGpu.exposure.z) and march its depth (prevDepth: the linear view depth TAA keeps for
// the same pixels). The ray (camera-relative, this frame) goes through last frame's unjittered clip space
// (ShadeLook.prevViewProjNoJitter, re-based on this frame's camera); 1/w is affine along the projected segment, so the ray's
// depth at each step is exact. Hits get the base's haze added (prevScene is taken before the final pass adds it). Returns
// (radiance before exposure, weight); weight 0 = no hit (callers keep their sky reflection).
static float4 shadeWxHistSsr(float3 p, float3 r, float2 pixel, float maxDist, float prevExposure, constant ShadeFrame &f, constant ShadeLook &L,
                             texture2d<half> hist, texture2d<half> histDepth) {
	float4 c0 = L.prevViewProjNoJitter * float4(p, 1.0);
	float4 dc = L.prevViewProjNoJitter * float4(r, 0.0);
	float len = maxDist;
	if (dc.w < 0.0) len = min(len, (c0.w - 0.2) / -dc.w * 0.9);
	if (len <= 0.2 || c0.w <= 0.1) return float4(0.0);
	float4 c1 = c0 + dc * len;
	float2 s0 = c0.xy / c0.w, s1 = c1.xy / c1.w;
	float iw0 = 1.0 / c0.w, iw1 = 1.0 / c1.w;
	float2 d = s1 - s0;
	float tx = d.x > 1e-6 ? (1.0 - s0.x) / d.x : d.x < -1e-6 ? (-1.0 - s0.x) / d.x : 1e6;
	float ty = d.y > 1e-6 ? (1.0 - s0.y) / d.y : d.y < -1e-6 ? (-1.0 - s0.y) / d.y : 1e6;
	float tEnd = min(1.0, min(tx, ty));
	if (tEnd <= 0.0) return float4(0.0);
	bool screenEnd = tEnd < 1.0;
	const int N = SHADE_PUDDLE_STEPS;
	float jitter = shadeNoise(pixel, f.flags2.x);
	float tPrev = 0.0;
	for (int i = 1; i <= N; i++) {
		float u = (float(i) - jitter) / float(N);
		float t = u * (0.3 + 0.7 * u) * tEnd;
		float2 s = s0 + d * t;
		float wr = 1.0 / mix(iw0, iw1, t);
		float ws = float(histDepth.sample(shadeWxNearest, s * 0.5 + 0.5, level(0.0)).r);
		if (wr <= ws + 0.05 + 0.004 * ws) {
			tPrev = t;
			continue;
		}
		// behind something: refine between the last step in front and this one
		float lo = tPrev, hi = t;
		for (int j = 0; j < 3; j++) {
			float m = 0.5 * (lo + hi);
			float2 sm = s0 + d * m;
			float wm = 1.0 / mix(iw0, iw1, m);
			if (wm > float(histDepth.sample(shadeWxNearest, sm * 0.5 + 0.5, level(0.0)).r)) hi = m; else lo = m;
		}
		float2 sh = s0 + d * hi;
		float2 uv = sh * 0.5 + 0.5;
		float wh = float(histDepth.sample(shadeWxNearest, uv, level(0.0)).r);
		float wrh = 1.0 / mix(iw0, iw1, hi);
		float wp = 1.0 / mix(iw0, iw1, tPrev);
		float thick = 0.35 + 0.025 * wh + abs(wrh - wp);
		if (wh > 30000.0 || wrh - wh > thick) { // passed behind a thin thing, or the sky: keep marching
			tPrev = t;
			continue;
		}
		float2 edge = smoothstep(0.0, 0.06, min(uv, 1.0 - uv));
		float fade = edge.x * edge.y * (1.0 - smoothstep(0.6, 1.0, (wrh - wh) / thick));
		if (!screenEnd) fade *= 1.0 - smoothstep(0.75, 1.0, hi / tEnd);
		float3 c = float3(hist.sample(shadeWxLinear, uv, level(0.0)).rgb) / prevExposure;
		// the hit's camera-relative point (screen parameter -> ray parameter: lambda = t / w1 / lerp(1 / w0, 1 / w1, t)), for
		// the haze the final pass adds on screen
		float3 ph = p + r * (len * hi * iw1 / mix(iw0, iw1, hi));
		if (shadeFeature(L, SHADE_FEATURE_HAZE) && f.fogColor.a < 0.5) {
			float3 T;
			float3 S = shadeHazeInscatter(ph, f, L, 1.0, T);
			c = c * T + S;
		}
		return float4(c, fade);
	}
	return float4(0.0);
}

// The sky in reflected direction r: the atmosphere LUT, or last frame's rendered sky where r lands on screen and the history
// depth says sky or clouds there (clouds, sun aureole, stars, rain over the sky).
static float3 shadeWxSkyRefl(float3 r, float prevExposure, bool histOk, constant ShadeFrame &f, constant ShadeLook &L, texture2d<half> lut, sampler lutS,
                             texture2d<half> hist, texture2d<half> histDepth) {
	float3 sky = shadeSkyLookup(float3(r.x, max(r.y, 0.0), r.z), lut, lutS, f);
	if (histOk) {
		float4 c = L.prevViewProjNoJitter * float4(r, 0.0);
		if (c.w > 1e-4) {
			float2 uv = c.xy / c.w * 0.5 + 0.5;
			float2 edge = saturate(min(uv, 1.0 - uv) * 12.0);
			float wgt = edge.x * edge.y;
			if (wgt > 0.0 && float(histDepth.sample(shadeWxNearest, uv, level(0.0)).r) > 1000.0) {
				sky = mix(sky, float3(hist.sample(shadeWxLinear, uv, level(0.0)).rgb) / prevExposure, wgt);
			}
		}
	}
	return sky;
}

// ---- shading the wet layer ------------------------------------------------------------------------------------------------
struct ShadeWetLight {
	float3 p;          // camera-relative position
	half3 n;           // snapped face normal (toward the camera)
	half sky;          // sky light 0..1
	half shadow;       // sun/moon visibility
	float2 pixel;      // fragment position
	float prevExposure;// ShadeGpu.exposure.z when prevScene is valid, else 0
	bool histOk;       // prevDepth matches prevScene (ShadeLook.ssr.y)
};

// The water film's specular on top of the diffuse radiance: sky (or SSR on puddles) reflection and the sun's highlight, with
// the diffuse part dimmed by what the film reflects. Radiance before exposure.
static float3 shadeWetShade(float3 radiance, half3 albedo, ShadeWet w, ShadeWetLight in, constant ShadeFrame &f, constant ShadeLook &L, texture2d<half> lut,
                            sampler lutS, texture2d<half> hist, texture2d<half> histDepth) {
	half wet = max(w.omega, w.puddle);
	if (wet <= 0.004h) return radiance;
	float dist = length(in.p);
	float3 v = in.p / max(dist, 1e-4);
	float3 nf = float3(in.n);
	float3 n = nf;
	float P = float(w.puddle);
	if (P > 0.0) n = normalize(float3(-w.slope.x * P, 1.0, -w.slope.y * P));
	// roughness: damp ground 0.8, soaked 0.38, standing water 0.03 (the ripples add their own detail); vegetation (grass tops,
	// leaves: green albedo) holds less of a film than stone, wood or paths
	float veg = saturate((float(albedo.g) - max(float(albedo.r), float(albedo.b))) * 8.0);
	float rough = mix(min(mix(0.8, 0.38, float(w.omega)) + 0.25 * veg, 0.95), 0.03, P);
	float cosV = max(dot(-v, n), 0.02);
	float F = shadeWxFresnel(cosV, rough);
	float3 r = reflect(v, n);
	if (dot(r, nf) < 0.02) r = normalize(r + nf * (0.02 - dot(r, nf))); // ripples must not send the mirror ray into the ground
	float open = float(shadeSkyCurve(in.sky));
	float3 refl = shadeWxSkyRefl(r, in.prevExposure, in.histOk && P > 0.0, f, L, lut, lutS, hist, histDepth);
	// rough films see a blurred sky: toward the sky's mean light (ambientSky ~ irradiance / pi)
	refl = mix(refl, float3(f.ambientSky.rgb) * 1.1, saturate(rough * 1.6)) * open;
#ifdef SHADE_PUDDLE_SSR
	if (P > 0.02 && in.histOk && in.prevExposure > 0.0) {
		float4 hit = shadeWxHistSsr(in.p, r, in.pixel, SHADE_PUDDLE_DIST, in.prevExposure, f, L, hist, histDepth);
		refl = mix(refl, hit.rgb, hit.w);
	}
#endif
	// the sun's (or moon's) highlight: a mirror-sharp glint on puddles, a broad sheen on wet ground
	float a = max(rough * rough, 0.0025);
	float3 sun = float3(f.sunColor.rgb) * min(shadeWxGgx(n, v, f.lightDirWorld.xyz, a), 60.0) * float(in.shadow)
		* float(smoothstep(0.3h, 0.8h, in.sky));
	float k = mix(float(w.omega) * 0.65 * (1.0 - 0.5 * veg), 1.0, P);
	return radiance * (1.0 - F * k) + (refl * F + sun * F) * k;
}

// ---- lightning ------------------------------------------------------------------------------------------------------------
// A flash lights everything under open sky from the bolt's direction (no shadows: it's brief and the clouds spread it).
static inline float3 shadeWxFlash(half3 albedo, half3 n, half sky, constant ShadeLook &L) {
	if (L.flash.w <= 0.0) return float3(0.0);
	float ndl = saturate(dot(float3(n), L.flash.xyz)) * 0.65 + 0.35;
	half g = shadeSkyCurve(sky);
	return float3(albedo) * float3(0.78, 0.86, 1.0) * (L.flash.w * ndl * float(g * g));
}

// ---- coloured shadows (E08) --------------------------------------------------------------------------------------------------
// The light that reached p through stained glass, ice or water: the near cascade's tint map (ShadowPass, the translucent
// casters' transmittance with the light depth of the one nearest the light in alpha). Only below a caster: the receiver must
// be farther from the light than it by a quarter block (and the map fades out with the near cascade's edge). 1 = untinted.
static half3 shadeShadowTint(float3 p, half3 n, constant ShadeFrame &f, texture2d<half> tint) {
	float4 c = f.shadowViewProj[0] * float4(p + float3(n) * (0.75 * f.cascadeSplits.z), 1.0);
	float m = max(abs(c.x), abs(c.y));
	if (m >= 1.0 || c.z <= 0.0 || c.z >= 1.0) return half3(1.0h);
	half4 t = tint.sample(shadeWxLinear, shadeShadowUV(c.xyz), level(0.0));
	float zBlocks = 1.0 / max(length(float3(f.shadowViewProj[0][0][2], f.shadowViewProj[0][1][2], f.shadowViewProj[0][2][2])), 1e-6);
	float gate = saturate(((float(t.a) - c.z) * zBlocks - 0.25) * 4.0) * (1.0 - smoothstep(0.85, 1.0, m));
	return mix(half3(1.0h), t.rgb, half(gate));
}

// The direct light's share of shadeLit2 that the tint changes (the same sun term: wrap, gate, shadow), times (tint - 1):
// added to shadeLit2's result, it turns the light that came through glass into the glass's colour.
static inline float3 shadeShadowTintDelta(half3 albedo, half3 n, half sky, half shadow, half3 tint, bool thin, bool foliage, constant ShadeFrame &f) {
	half nl = half(dot(float3(n), f.lightDirWorld.xyz));
	half ndl = thin ? half(0.3) + half(0.5) * abs(nl)
		: foliage ? half(0.25) + half(0.75) * saturate(nl) + half(0.5) * saturate(-nl)
		: (nl > half(0.0) ? (nl + half(0.08)) / half(1.08) : half(0.0));
	half gate = smoothstep(half(0.3), half(0.8), sky);
	if (f.flags2.y != 0) gate *= 0.5h;
	return float3(albedo * half3(f.sunColor.rgb) * (ndl * shadow * gate) * (tint - 1.0h));
}

#endif
