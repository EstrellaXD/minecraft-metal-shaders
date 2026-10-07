// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// M3 sky, dimensions and camera media (bible E04 aurora, E24 Nether, E25 End, E26 media), loaded after shade_m2.metal in
// ShadeMsl.prelude(). Everything here is original. CPU side: mcopt.metal.shade.dims.ShadeDims.
//
// DATA. Outside the overworld (sunPath.z bit SHADE_FLAG_DIMS_LIT set) ShadeFrame carries the dimension instead of the sun:
//   sunColor / lightDirWorld   Nether: 0 / up; End: the violet key light ("a dead star", fixed direction, casts shadows)
//   ambientSky/Ground/Toward/Away   the dimension ambient (Nether: biome palette over a warm neutral; End: violet)
//   horizon[i] (M1's deprecated horizon table, unused there) = the palette, pre-exposure radiance:
//     SHADE_DP_FOG 0  fog in-scatter (both)          SHADE_DP_LAVA 1  lava up-light at the lava sea (Nether)
//     SHADE_DP_ACCENT 2 Nether biome accent | End sky zenith   3 End sky horizon   4 End beams   5 End filaments
//     6, 7 End nebula hues
// ShadeLook: dimTint = (fog extinction 1/block, lava sea y, border fog radius in blocks (0 off), 1 Nether / 2 End / 3 End
// without a sky pass: vanilla skips the sky during the dragon fight, the final pass paints shadeEndSky on empty pixels);
// flash = (End flash direction, intensity); night.z = aurora strength 0..1.
// NOISE. ShadeResources.milkyWay's alpha holds a tileable fbm (baked with the Milky Way, shade_post.metal): shadeDimsNoise.
#ifndef MCOPT_SHADE_DIMS
#define MCOPT_SHADE_DIMS

#define SHADE_FLAG_DIMS_LIT 4096          // sunPath.z bit: Nether/End terrain is lit per pixel (mcopt.shade.dims)
#define SHADE_FEATURE_DIMS (1 << 20)      // ShadeLook.tier.y: the Nether/End looks
#define SHADE_FEATURE_MEDIA (1 << 21)     // camera inside lava / powder snow
#define SHADE_FEATURE_SHIMMER (1 << 22)   // heat shimmer over distant Nether terrain
#define SHADE_DP_FOG 0
#define SHADE_DP_LAVA 1
#define SHADE_DP_ACCENT 2

static inline bool shadeDimsLit(constant ShadeFrame &f) { return (int(f.sunPath.z) & SHADE_FLAG_DIMS_LIT) != 0; }

// ---- tileable noise ---------------------------------------------------------------------------------------------------------
// Value noise on a lattice wrapped every `period` cells (so the bake tiles), in cell units.
static inline float shadeDimsPeriodicValue(float2 p, int2 period, uint salt) {
	float2 i = floor(p), fr = p - i;
	float2 u = fr * fr * (3.0 - 2.0 * fr);
	int2 c = int2(i);
	int2 c0 = ((c % period) + period) % period, c1 = (((c + 1) % period) + period) % period;
	float a = shadeHash01(shadeHash3(int3(c0.x, c0.y, 0), salt)), b = shadeHash01(shadeHash3(int3(c1.x, c0.y, 0), salt));
	float cc = shadeHash01(shadeHash3(int3(c0.x, c1.y, 0), salt)), d = shadeHash01(shadeHash3(int3(c1.x, c1.y, 0), salt));
	return mix(mix(a, b, u.x), mix(cc, d, u.x), u.y);
}

// The baked noise: 5 octaves over uv in [0, 1)^2, base lattice 16 x 8 cells (square cells on the 1024 x 512 texture).
static inline float shadeDimsBakeNoise(float2 uv) {
	float s = 0.0, a = 0.5, n = 0.0;
	int2 period = int2(16, 8);
	for (int o = 0; o < 5; o++) {
		s += a * shadeDimsPeriodicValue(uv * float2(period), period, 0x2c1b3c6du + uint(o) * 977u);
		n += a;
		a *= 0.5;
		period *= 2;
	}
	return saturate((s / n - 0.5) * 1.9 + 0.5);
}

// The baked noise at p, in units of its base cell (1 = 1/16 of the texture's width). Tiles every 16 x 8 units.
static inline float shadeDimsNoise(texture2d<half> mw, float2 p) {
	constexpr sampler rep(filter::linear, address::repeat);
	return float(mw.sample(rep, p * float2(1.0 / 16.0, 1.0 / 8.0), level(0.0)).a);
}

// ---- lighting in the Nether and the End (E24, E25) ---------------------------------------------------------------------------
// shadeLit2 with no sky light gives the block light, the cave floor (and anything later modules add there); on top: the
// dimension ambient (up / down / sides), the Nether's lava up-light (warm light from below, strongest near the lava sea, a
// slow flicker), the End's key light (shadowed) and its flash. p: camera-relative position.
static float3 shadeLitDims(half3 albedo, half3 n, half block, half shadow, bool thin, bool foliage, float3 p, constant ShadeFrame &f,
                           constant ShadeLook &L) {
	float3 base = shadeLit2(albedo, n, half(0.0), block, half(0.0), thin, foliage, f, L);
	float3 nn = float3(n);
	float3 side = 0.5 * (f.ambientToward.rgb + f.ambientAway.rgb);
	float3 light = nn.y >= 0.0 ? mix(side, f.ambientSky.rgb, nn.y) : mix(side, f.ambientGround.rgb, -nn.y);
	if (foliage || thin) light *= 1.15;
	float yAbs = f.cameraBlock.y + f.cameraFract.y + p.y;
	if (L.dimTint.w < 1.5) {
		// Nether: lava below lights ceilings and undersides
		float up = thin ? 0.5 : saturate(0.55 - 0.45 * nn.y);
		float h = exp(-max(yAbs - L.dimTint.y, 0.0) / 30.0);
		float t = f.time.y;
		float flick = 0.88 + 0.12 * sin(t * 1.3 + (f.cameraBlock.x + p.x) * 0.07) * sin(t * 0.7 + (f.cameraBlock.z + p.z) * 0.05);
		light += f.horizon[SHADE_DP_LAVA].rgb * (up * h * flick);
	} else {
		float3 Ld = f.lightDirWorld.xyz;
		float nl = dot(nn, Ld);
		float ndl = thin ? 0.3 + 0.5 * abs(nl) : foliage ? 0.25 + 0.75 * saturate(nl) + 0.5 * saturate(-nl) : (nl > 0.0 ? (nl + 0.08) / 1.08 : 0.0);
		light += f.sunColor.rgb * (ndl * float(shadow));
		if (L.flash.w > 0.0) light += float3(0.55, 0.75, 1.0) * (L.flash.w * 1.4 * saturate(dot(nn, L.flash.xyz) * 0.6 + 0.4));
	}
	float face = 1.0 - 0.06 * abs(nn.x) * (1.0 - abs(nn.y));
	return base + float3(albedo) * light * face;
}

// ---- the End sky (E25) ---------------------------------------------------------------------------------------------------------
// Deep indigo gradient, a slowly turning nebula (two hues, domain-warped noise in an equirectangular map: the longitude
// multipliers are whole numbers so it wraps), sharp stars, and luminous curtains ("beams") rising from the void around the
// island, brightening with the End flash. Pre-exposure radiance.
static float3 shadeEndSky(float3 dir, constant ShadeFrame &f, constant ShadeLook &L, texture2d<half> mw, float pixelAngle) {
	float y = clamp(dir.y, -1.0, 1.0);
	float e = asin(y);
	float3 sky = mix(f.horizon[2].rgb, f.horizon[3].rgb, pow(saturate(1.0 - abs(y)), 3.0));
	sky *= y < 0.0 ? mix(1.0, 0.3, saturate(-y * 1.6)) : 1.0; // the void below is darker
	float t = f.sunPath.w; // the real-time clock (smooth)
	float lon = atan2(dir.z, dir.x) * 0.15915494 + 0.5; // 0..1
	float lat = e * 0.31830989 + 0.5;
	float2 q = float2(lon + t * 0.0004, lat);
	float w = shadeDimsNoise(mw, q * float2(32.0, 16.0) + float2(3.1, 0.7));
	float nb = shadeDimsNoise(mw, q * float2(16.0, 12.0) + float2(w * 1.6, w * 0.9));
	float nb2 = shadeDimsNoise(mw, q * float2(48.0, 30.0) + float2(w * 2.2, 0.0));
	float band = exp(-abs(e - 0.35) / 0.55);
	float neb = smoothstep(0.55, 1.05, nb * 0.8 + nb2 * 0.35) * band;
	sky += mix(f.horizon[6].rgb, f.horizon[7].rgb, smoothstep(0.3, 0.75, w)) * (neb * 0.9);
	// stars (the overworld's field, brighter and denser looking: no atmosphere)
	float sigma = clamp(0.7 * pixelAngle / (1.5707963 / 420.0), 0.25, 0.8);
	sky += shadeStars(dir, 1.0, f.time.y, sigma) * (0.5 * (1.0 - 0.6 * neb));
	sky += shadeStars(float3(dir.z, dir.x, dir.y), 1.0, f.time.y + 17.0, sigma) * 0.25;
	// beams: ridged noise around the horizon, rising from below it
	float flash = L.flash.w;
	float az = lon;
	float r1 = shadeDimsNoise(mw, float2(az * 64.0, t * 0.02));
	float r2 = shadeDimsNoise(mw, float2(az * 160.0, t * 0.035 + 3.0));
	float beam = pow(saturate(1.0 - abs(2.0 * r1 - 1.0)), 12.0);
	float fil = pow(saturate(1.0 - abs(2.0 * r2 - 1.0)), 24.0);
	float rise = smoothstep(-0.3, -0.04, e) * exp(-max(e + 0.04, 0.0) / (0.10 + 0.12 * r1));
	float calm = 1.0 + 1.5 * flash;
	sky += (f.horizon[4].rgb * beam * 0.55 + f.horizon[5].rgb * fil * 0.4) * (rise * calm);
	if (flash > 0.0) {
		float c = saturate(dot(dir, L.flash.xyz));
		sky += float3(0.55, 0.7, 1.0) * (flash * (pow(c, 96.0) * 14.0 + pow(c, 6.0) * 0.5)) * length(f.horizon[4].rgb);
	}
	return sky;
}

// ---- aurora (E04) -----------------------------------------------------------------------------------------------------------
// Curtains in a thin shell high above (a flat "virtual" layer: no terrain occlusion beyond the sky test): the ray crosses the
// shell in N steps (blue-noise dithered); at each the curtain field is a domain-warped noise whose 0.5 contour is the
// curtain line, streaked by noise that depends only on the horizontal position (vertical rays), coloured by height in the
// shell (violet lower fringe, green body, crimson top). Pure emission. Pre-exposure radiance x ShadeLook.night.z.
static float3 shadeAurora(float3 dir, constant ShadeFrame &f, constant ShadeLook &L, texture2d<half> mw, float2 pixel) {
	float s = L.night.z;
	if (s <= 0.002 || dir.y < 0.02) return float3(0.0);
	int steps = L.tier.x <= 0 ? 10 : L.tier.x == 1 ? 12 : L.tier.x == 2 ? 16 : 24;
	const float h0 = 1.0, h1 = 1.9;
	float dy = max(dir.y, 0.05);
	float t0 = h0 / dy, t1 = h1 / dy;
	float t = f.sunPath.w;
	float jitter = shadeNoise(pixel, f.flags2.x);
	float2 drift = float2(t * 0.0021, t * 0.0009);
	float3 sum = float3(0.0);
	for (int i = 0; i < steps; i++) {
		float h = (float(i) + jitter) / float(steps);
		float2 xz = dir.xz * (mix(t0, t1, h) * 0.55) + drift;
		float warp = shadeDimsNoise(mw, xz * 0.6 + float2(t * 0.0031, 0.0));
		float c = shadeDimsNoise(mw, xz * 0.9 + warp * 0.9);
		float line = exp(-((c - 0.5) * (c - 0.5)) * 380.0);
		float rays = 0.35 + 0.65 * shadeDimsNoise(mw, xz * float2(9.0, 9.0) + float2(0.0, t * 0.012));
		float g = exp(-((h - 0.22) * (h - 0.22)) / 0.04);
		float v = exp(-(h * h) / 0.006);
		float r = smoothstep(0.45, 0.95, h) * 0.5;
		float3 col = float3(0.20, 1.0, 0.45) * g + float3(0.42, 0.30, 1.0) * (v * 0.8) + float3(1.0, 0.22, 0.35) * r;
		float env = smoothstep(0.0, 0.05, h) * (1.0 - 0.85 * smoothstep(0.55, 1.0, h));
		sum += col * (line * rays * env);
	}
	float horizon = smoothstep(0.02, 0.18, dir.y);
	return sum * (s * horizon * 0.06 / float(steps) * 12.0);
}

// ---- fog in the Nether and the End (final pass, from depth) ----------------------------------------------------------------
// Nether: a layered haze, denser toward the lava sea and broken up by drifting noise, glowing warm low down; distance
// dissolves into coloured smoke (bible E24). End: a thin violet haze that meets the sky's horizon. Both end in a 4th-power
// fog toward the render distance. c: exposed scene colour; geo: the pixel has depth (wp valid); keepSky: Distant Horizons
// drew its LODs where the depth is empty (leave those pixels). Returns the exposed colour.
static float3 shadeDimsFog(float3 c, float3 wp, bool geo, constant ShadeFrame &f, constant ShadeLook &L, float ex, bool keepSky,
                           texture2d<half> mw) {
	float sigma = L.dimTint.x;
	bool nether = L.dimTint.w < 1.5;
	float yc = f.cameraBlock.y + f.cameraFract.y;
	float3 S = f.horizon[SHADE_DP_FOG].rgb;
	float3 lava = f.horizon[SHADE_DP_LAVA].rgb;
	if (!geo) {
		if (!nether || keepSky) return c;
		float g = exp(-max(yc - L.dimTint.y, 0.0) / 40.0);
		return (S + lava * (0.06 * g)) * ex; // the Nether's void: smoke all the way
	}
	float d = length(wp);
	float3 v = wp / max(d, 1e-4);
	float tau = sigma * d;
	if (nether) tau += sigma * 1.0 * shadeLayerDepth(yc, v.y, d, L.dimTint.y + 6.0, 16.0);
	float3 mid = float3(f.cameraBlock.xyz) + f.cameraFract.xyz + wp * 0.5;
	float n = shadeDimsNoise(mw, mid.xz * (1.0 / 40.0) + float2(mid.y * (1.0 / 70.0), 0.0) + f.sunPath.w * float2(0.004, 0.0017));
	tau *= 0.6 + 0.8 * n;
	float T = exp(-tau);
	float3 Sc = S;
	if (nether) {
		float ym = yc + wp.y * 0.5;
		float g = exp(-max(ym - L.dimTint.y, 0.0) / 30.0);
		Sc = S * (0.8 + 0.4 * n) + lava * (0.09 * g);
	}
	float3 o = c * T + Sc * ex * (1.0 - T);
	if (L.dimTint.z > 0.0) {
		float r = max(length(wp.xz), abs(wp.y)) / L.dimTint.z;
		float b = saturate(r * r * r * r);
		float3 edge = nether ? Sc : f.horizon[3].rgb;
		o = mix(o, edge * ex, b);
	}
	return o;
}

// Heat shimmer over distant Nether terrain: a pixel offset (output pixels), growing with distance.
static inline float2 shadeShimmer(float2 uv, float dist, constant ShadeFrame &f, texture2d<half> mw) {
	float k = saturate((dist - 24.0) / 64.0);
	if (k <= 0.0) return float2(0.0);
	float t = f.sunPath.w;
	float a = shadeDimsNoise(mw, uv * float2(48.0, 30.0) + float2(0.0, t * 0.9));
	float b = shadeDimsNoise(mw, uv * float2(48.0, 30.0) + float2(7.3, t * 0.7));
	return (float2(a, b) - 0.5) * (1.4 * k);
}

// ---- camera inside a medium (E26) -------------------------------------------------------------------------------------------
// Lava: a glowing, slowly churning orange (brighter than paper white: it blooms, and in XDR it really glows) with darker
// crimson edges; powder snow: a bright cold frost with soft crystalline variation. dist: blocks to the surface (1e4 sky).
// Water keeps the water module's own treatment. Returns the exposed colour.
static float3 shadeMedia(float3 c, float2 uv, float dist, constant ShadeFrame &f, texture2d<half> mw) {
	int type = f.flags.y;
	float t = f.sunPath.w;
	float2 r = (uv - 0.5) * float2(1.5, 1.0);
	float edge = smoothstep(0.25, 0.85, length(r));
	if (type == 2) {
		float n1 = shadeDimsNoise(mw, uv * float2(5.0, 3.0) + float2(t * 0.03, -t * 0.12));
		float n2 = shadeDimsNoise(mw, uv * float2(12.0, 8.0) + float2(-t * 0.05, -t * 0.22) + n1 * 1.3);
		float glow = 0.9 + 1.8 * n1 * n2 + 0.5 * smoothstep(0.55, 0.9, n2);
		float3 lava = float3(1.0, 0.26, 0.03) * (0.3 * glow) * mix(1.0, 0.45, edge) + float3(0.18, 0.015, 0.0) * edge;
		float T = exp(-2.5 * dist);
		return mix(lava, c, T);
	}
	if (type == 3) {
		float n1 = shadeDimsNoise(mw, uv * float2(24.0, 16.0));
		float n2 = shadeDimsNoise(mw, uv * float2(70.0, 44.0) + n1);
		float frost = 0.78 + 0.12 * n1 + 0.18 * smoothstep(0.7, 0.95, n2) + 0.15 * edge;
		float3 snow = float3(0.84, 0.9, 1.0) * frost;
		float T = exp(-1.2 * dist) * (1.0 - 0.6 * edge);
		return mix(snow, c, T);
	}
	return c;
}

#endif
