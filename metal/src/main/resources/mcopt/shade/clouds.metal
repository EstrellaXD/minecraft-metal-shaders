// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Portions adapted from Photon Shaders by Benjamin Stott ("sixthsurge"), MIT License, commit 26c2906e
// (include/sky/clouds/common.glsl, cumulus.glsl, cirrus.glsl; program/d2_clouds_upscaling.fsh): the cumulus altitude
// shaping (flat bases, egg-shaped tops), the "powder" brightening of dense cores, the cloud phase function as a mix of a
// strong forward lobe and a backward one, the octave loop of the multiple-scattering approximation, the aerial perspective
// blend toward the clear sky by the clouds' apparent distance, and the checkerboard + reprojected-history upscaling.
// History reprojection with Catmull-Rom sampling after Matt Pettineo (MJP), "Bicubic texture filtering with the Catmull-Rom
// spline in 9 / 5 taps" (gist TheRealMJP/c83b8c0f46b63f3a88a5986f4fa982b1), MIT License (5-tap variant, as taa.metal).
// Everything else (units in blocks, the density model, the slab on a curved world, the weather map, the cirrus layer, the
// cloud shadow map, the reflection dome, the bakes) is original; techniques after Schneider & Vos (Horizon Zero Dawn
// volumetric cloudscapes, SIGGRAPH 2015; Nubis 2017/2022) and Wrenninge et al. (multiple-scattering octaves).
//
// E05 volumetric clouds (mcopt.metal.shade.clouds.ShadeClouds, native/mcclouds.m). Loaded after ShadeMsl.prelude().
// Passes (all fragment passes into private textures; the bakes are compute, once):
//   cloud_bake_noise   (compute, once)  64^3 RGBA8 tileable 3D noise: r Perlin-Worley (billows), g/b/a Worley fBm at 3 scales
//   cloud_bake_weather (compute, once)  512^2 RGBA8 tileable 2D noise: r regional coverage, g cumulus cells, b cirrus, a detail
//   cloud_shadow       (256^2 R16F, every 4th frame) transmittance of the cumulus layer toward the light, on the layer's plane (consumers:
//                                       terrain, water, volumetric light: shadeCloudShadow)
//   cloud_march        (1/N x 1/N of the history: this frame's slot of the N x N checkerboard; N = 3 at potato/balanced,
//                                       2 above) cumulus + cirrus: scattered
//                                       light (rgb, before exposure, after aerial perspective) + transmittance, and the
//                                       clouds' apparent distance (MRT)
//   cloud_resolve      (history size)   reprojection (camera motion + wind) of last frame's history, this frame's slot blended
//                                       in, the other slots from the history (or the march upsampled where it is invalid)
//   cloud_dome         (upper hemisphere, 128x64; every 4th frame) a cheap march by direction, temporally blended: the clouds for
//                                       reflections of directions off screen (water) and future environment lighting
// The sky pass (sky.fs.metal) composites the history behind everything: sky * T + S (shade_clouds.metal).

#ifndef MCOPT_SHADE_CLOUDS_PASSES
#define MCOPT_SHADE_CLOUDS_PASSES

// Per-frame parameters (mcopt.metal.shade.clouds.CloudParams packs exactly this layout: 2 matrices + 20 float4 = 448 bytes).
struct CloudParams {
	float4x4 invViewProj;  //   0 this frame's clip -> camera-relative world, without the TAA jitter
	float4x4 prevViewProj; //  64 last frame's camera-relative world (re-based on this camera) -> clip, without jitter
	float4 size;           // 128 xy history size (px), zw march size (px)
	float4 frame;          // 144 xy this frame's checkerboard slot (0..N-1 each), z 1 = drop the history, w most frames blended (age cap)
	float4 layer;          // 160 x cumulus base y, y cumulus top y (absolute), z camera y (absolute), w curvature 1/(2 R) (1/blocks)
	float4 shape;          // 176 x coverage 0..1, y type (0 flat .. 1 towering), z extinction per block at density 1, w erosion
	float4 origin;         // 192 xy the camera's xz in the weather map's space (blocks, wrapped, wind included), zw the same for
	                       //     the 3D noise (moves a little slower: the clouds boil as they drift)
	float4 wind;           // 208 xy how far the clouds moved since last frame (blocks), z cirrus coverage, w rain/storm 0..1
	float4 light;          // 224 rgb irradiance from the light reaching the cloud layer (sun or moon; before exposure), w 1 sun
	float4 lightDir;       // 240 xyz toward the light (unit), w 0
	float4 ambTop;         // 256 rgb mean sky radiance above the layer (before exposure), w albedo
	float4 ambBottom;      // 272 rgb mean radiance from below (ground and haze), w multiple-scattering boost of the ambient
	float4 march;          // 288 x primary steps, y light steps, z max distance (blocks), w aerial perspective distance (blocks)
	float4 cirrus;         // 304 x altitude (absolute y), y extinction, zw the camera's xz in the cirrus map's space (wrapped)
	float4 shadow;         // 320 x extent (blocks), y reference height (absolute y), zw the map's centre relative to the camera (xz)
	float4 look;           // 336 x powder, y silver lining, z forward g, w overall opacity (fades the clouds in/out)
	float4 dome;           // 352 xy dome size (px), z blend of the new march, w primary steps of the dome march
	float4 misc;           // 368 x frame counter, y first light step (blocks; 0 = 4), z ground height (absolute y: rays stop there), w detail strength
	float4 windDir;        // 384 xy unit axis of the cirrus streaks (fixed in the world; cirrus.zw is in its frame), z checkerboard N (1-3),
	                       //     w radians a march sample covers (history pixel angle x N; the noise mip by distance)
	float4 shadowDir;      // 400 xyz the light the cloud shadow map is cast by (ShadeFrame's: sun, then moon), y >= 0.05, w 0
	float4 art;            // 416 x multiple-scattering octave weight (each octave x this), y octave extinction factor, z sky-light
	                       //     extinction factor, w style (0 volumetric, 1 blocky: vanilla-style 12-block cells, ray-traced)
	float4 shear;          // 432 xy horizontal offset of the 2D shape over the layer's height (blocks; wind shear: tops lean
	                       //     downwind, and columns stop lining up into streaks toward the zenith), z optical depth per step
	                       //     in cloud (smaller = more samples in dense cloud), w the shortest step (fraction of the base step)
};
static_assert(sizeof(CloudParams) == 448, "CloudParams layout (CloudParams.java)");

constexpr sampler cloudRep(filter::linear, address::repeat);
constexpr sampler cloudClamp(filter::linear, address::clamp_to_edge);

// ---- bakes ---------------------------------------------------------------------------------------------------------------
// Periodic gradient noise and Worley noise on integer lattices that wrap (the textures tile), hashed with shadeHash3.
static inline float3 cloudGrad3(int3 c, int period, uint salt) {
	c = ((c % period) + period) % period;
	uint h = shadeHash3(c, salt);
	float3 g = float3(shadeHash01(h), shadeHash01(h ^ 0x68e31da4u), shadeHash01(h ^ 0xb5297a4du)) * 2.0 - 1.0;
	return g * rsqrt(max(dot(g, g), 1e-6));
}

static float cloudPerlin3(float3 p, int period, uint salt) {
	int3 i = int3(floor(p));
	float3 f = p - floor(p);
	float3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
	float n000 = dot(cloudGrad3(i + int3(0, 0, 0), period, salt), f - float3(0, 0, 0));
	float n100 = dot(cloudGrad3(i + int3(1, 0, 0), period, salt), f - float3(1, 0, 0));
	float n010 = dot(cloudGrad3(i + int3(0, 1, 0), period, salt), f - float3(0, 1, 0));
	float n110 = dot(cloudGrad3(i + int3(1, 1, 0), period, salt), f - float3(1, 1, 0));
	float n001 = dot(cloudGrad3(i + int3(0, 0, 1), period, salt), f - float3(0, 0, 1));
	float n101 = dot(cloudGrad3(i + int3(1, 0, 1), period, salt), f - float3(1, 0, 1));
	float n011 = dot(cloudGrad3(i + int3(0, 1, 1), period, salt), f - float3(0, 1, 1));
	float n111 = dot(cloudGrad3(i + int3(1, 1, 1), period, salt), f - float3(1, 1, 1));
	float x0 = mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y);
	float x1 = mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y);
	return mix(x0, x1, u.z) * 1.15; // ~[-1, 1]
}

// Distance to the nearest of one jittered point per cell (cells per tile = period), 0 .. ~1 (in cells).
static float cloudWorley3(float3 p, int period, uint salt) {
	int3 i = int3(floor(p));
	float3 f = p - floor(p);
	float md = 9.0;
	for (int z = -1; z <= 1; z++)
		for (int y = -1; y <= 1; y++)
			for (int x = -1; x <= 1; x++) {
				int3 c = i + int3(x, y, z);
				int3 w = ((c % period) + period) % period;
				uint h = shadeHash3(w, salt);
				float3 o = float3(shadeHash01(h), shadeHash01(h ^ 0x1b56c4e9u), shadeHash01(h ^ 0x7f4a7c15u));
				float3 r = float3(x, y, z) + o - f;
				md = min(md, dot(r, r));
			}
	return sqrt(md);
}

static inline float cloudRemap(float v, float lo, float hi, float a, float b) {
	return a + (b - a) * saturate((v - lo) / max(hi - lo, 1e-5));
}

// Spreads a roughly normal value (mean mu, deviation sigma, measured on the baked texture: tools/shade/clouds run.sh stats)
// to a roughly uniform 0..1 (logistic approximation of the normal CDF): coverage c then covers about c of the area.
static inline float cloudUniform(float v, float mu, float sigma) {
	return 1.0 / (1.0 + exp(-1.702 * (v - mu) / sigma));
}

// 64^3: one tile spans 1 in each axis of the sampling coordinate.
kernel void cloud_bake_noise(texture3d<half, access::write> out [[texture(0)]], uint3 id [[thread_position_in_grid]]) {
	const float N = 64.0;
	if (any(float3(id) >= N)) return;
	float3 p = (float3(id) + 0.5) / N;
	// Perlin fBm (4, 8, 16 cells), remapped by an inverted Worley fBm: round billows with soft gaps (Perlin-Worley)
	float pf = cloudPerlin3(p * 4.0, 4, 11u) * 0.5 + cloudPerlin3(p * 8.0, 8, 12u) * 0.3 + cloudPerlin3(p * 16.0, 16, 13u) * 0.2;
	pf = saturate(pf * 0.6 + 0.5);
	float w4 = 1.0 - saturate(cloudWorley3(p * 4.0, 4, 21u));
	float w8 = 1.0 - saturate(cloudWorley3(p * 8.0, 8, 22u));
	float w16 = 1.0 - saturate(cloudWorley3(p * 16.0, 16, 23u));
	float w32 = 1.0 - saturate(cloudWorley3(p * 32.0, 32, 24u));
	float wf0 = w4 * 0.625 + w8 * 0.25 + w16 * 0.125;
	float pw = cloudRemap(pf, wf0 - 1.0, 1.0, 0.0, 1.0);
	float g = w4 * 0.625 + w8 * 0.25 + w16 * 0.125;
	float b = w8 * 0.625 + w16 * 0.25 + w32 * 0.125;
	float a = w16 * 0.75 + w32 * 0.25;
	out.write(half4(half(cloudUniform(pw, 0.67, 0.059)), half(cloudUniform(g, 0.48, 0.118)), half(cloudUniform(b, 0.48, 0.118)),
		half(cloudUniform(a, 0.48, 0.14))), id);
}

static inline float2 cloudGrad2(int2 c, int period, uint salt) {
	c = ((c % period) + period) % period;
	uint h = shadeHash3(int3(c, 0), salt);
	float a = shadeHash01(h) * 6.2831853;
	return float2(cos(a), sin(a));
}

static float cloudPerlin2(float2 p, int period, uint salt) {
	int2 i = int2(floor(p));
	float2 f = p - floor(p);
	float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
	float n00 = dot(cloudGrad2(i, period, salt), f);
	float n10 = dot(cloudGrad2(i + int2(1, 0), period, salt), f - float2(1, 0));
	float n01 = dot(cloudGrad2(i + int2(0, 1), period, salt), f - float2(0, 1));
	float n11 = dot(cloudGrad2(i + int2(1, 1), period, salt), f - float2(1, 1));
	return mix(mix(n00, n10, u.x), mix(n01, n11, u.x), u.y) * 1.4;
}

static float cloudFbm2(float2 p, int period, int octaves, uint salt) {
	float s = 0.0, a = 0.5, n = 0.0;
	for (int i = 0; i < octaves; i++) {
		s += cloudPerlin2(p, period, salt + uint(i)) * a;
		n += a;
		p *= 2.0;
		period *= 2;
		a *= 0.5;
	}
	return s / n;
}

static float cloudWorley2(float2 p, int period, uint salt) {
	int2 i = int2(floor(p));
	float2 f = p - floor(p);
	float md = 9.0;
	for (int y = -1; y <= 1; y++)
		for (int x = -1; x <= 1; x++) {
			int2 c = i + int2(x, y);
			int2 w = ((c % period) + period) % period;
			uint h = shadeHash3(int3(w, 1), salt);
			float2 o = float2(shadeHash01(h), shadeHash01(h ^ 0x1b56c4e9u));
			float2 r = float2(x, y) + o - f;
			md = min(md, dot(r, r));
		}
	return sqrt(md);
}

// 512^2, one tile = CLOUD_WEATHER_TILE blocks (4096): r regional coverage (features ~1500 blocks), g cumulus cells
// (~200-300 blocks: Perlin fBm shaped by an inverted Worley, clustered), b cirrus fBm, a fine detail fBm.
kernel void cloud_bake_weather(texture2d<half, access::write> out [[texture(0)]], uint2 id [[thread_position_in_grid]]) {
	const float N = 512.0;
	if (any(float2(id) >= N)) return;
	float2 p = (float2(id) + 0.5) / N;
	float r = cloudFbm2(p * 3.0, 3, 4, 101u);
	float pf = cloudFbm2(p * 12.0, 12, 4, 201u);
	float w = 1.0 - saturate(cloudWorley2(p * 16.0, 16, 301u));
	float w2 = 1.0 - saturate(cloudWorley2(p * 32.0, 32, 302u));
	float cells = pf + (w * 0.75 + w2 * 0.25) * 0.45;
	float b = cloudFbm2(p * 6.0, 6, 5, 401u);
	float a = cloudFbm2(p * 24.0, 24, 3, 501u);
	out.write(half4(half(cloudUniform(r * 0.5 + 0.5, 0.49, 0.104)), half(cloudUniform(cells * 0.5 + 0.25, 0.38, 0.1035)),
		half(cloudUniform(b * 0.5 + 0.5, 0.50, 0.089)), half(cloudUniform(a * 0.5 + 0.5, 0.50, 0.104))), id);
}

// ---- the layer -----------------------------------------------------------------------------------------------------------
#define CLOUD_WEATHER_TILE 4096.0
#define CLOUD_NOISE_SCALE (1.0 / 120.0)
#define CLOUD_DETAIL_SCALE (1.0 / 60.0)
#define CLOUD_NOISE_TEXEL (120.0 / 64.0) // blocks per texel of the billow noise

struct CloudRay {
	float a, b, y0; // altitude above the curved ground along the ray: y0 + b t + a t^2
};

static inline CloudRay cloudRay(float3 dir, constant CloudParams &c) {
	CloudRay r;
	r.a = max(c.layer.w * (1.0 - dir.y * dir.y), 1e-9);
	r.b = dir.y;
	r.y0 = c.layer.z;
	return r;
}

static inline float cloudAltitude(CloudRay r, float t) { return r.y0 + t * (r.b + t * r.a); }

// Real roots of a t^2 + b t + cc = 0 (a > 0), r1 <= r2; false when none. Stable form (no cancellation for tiny a).
static inline bool cloudRoots(float a, float b, float cc, thread float &r1, thread float &r2) {
	float disc = b * b - 4.0 * a * cc;
	if (disc < 0.0) return false;
	float q = -0.5 * (b + (b >= 0.0 ? 1.0 : -1.0) * sqrt(disc));
	float x1 = q / a, x2 = abs(q) > 1e-20 ? cc / q : x1;
	r1 = min(x1, x2);
	r2 = max(x1, x2);
	return true;
}

// The first stretch [t0, t1] of the ray inside the slab yb..yt (curved with the world), clipped at maxT and at the ground;
// false when there is none.
static bool cloudSegment(CloudRay r, float yb, float yt, float ground, float maxT, thread float &t0, thread float &t1) {
	float t1a, t2a;
	if (!cloudRoots(r.a, r.b, r.y0 - yt, t1a, t2a)) return false; // always above the top
	float lo = max(t1a, 0.0), hi = t2a;
	float r1, r2;
	if (cloudRoots(r.a, r.b, r.y0 - yb, r1, r2)) {
		// below the base between r1 and r2
		float s0 = lo, s1 = min(hi, r1);
		if (s1 <= s0) {
			s0 = max(lo, r2);
			s1 = hi;
		}
		lo = s0;
		hi = s1;
	}
	float g1, g2;
	if (r.y0 > ground && cloudRoots(r.a, r.b, r.y0 - ground, g1, g2) && g1 > 0.0) hi = min(hi, g1); // the planet in the way
	hi = min(hi, maxT);
	t0 = lo;
	t1 = hi;
	return hi > lo;
}

// Cumulus density (0..1) at camera-relative p with absolute altitude y. q: 0 = 2D shape only (far light samples, shadow
// map), 1 = + billows (3D), 2 = + fine detail.
#define CLOUD_BLOCK 12.0

// Blocky style: whether the 12-block cell at wrapped world xz holds a cloud (the weather map's cells at the cell's centre,
// with the day's coverage), like vanilla's cloud texture but following our weather.
static inline bool cloudBlockCell(float2 xz, constant CloudParams &c, texture2d<half> weather) {
	float2 cell = floor(xz / CLOUD_BLOCK);
	half4 w = weather.sample(cloudRep, (cell + 0.5) * (CLOUD_BLOCK / CLOUD_WEATHER_TILE), level(0.0));
	float cov = saturate(c.shape.x + (float(w.r) - 0.5) * 0.55);
	return float(w.g) > 1.0 - cov;
}

// lod: mip level of the 3D noise (log2 of the sample's footprint in billow texels): far samples read blurred noise
// instead of aliasing it into grain.
static float cloudDensity(float3 p, float y, int q, constant CloudParams &c, texture2d<half> weather, texture3d<half> noise,
                          float detail = 1.0, float lod = 0.0) {
	if (c.art.w > 0.5) return y >= c.layer.x && y <= c.layer.y && cloudBlockCell(c.origin.xy + p.xz, c, weather) ? 1.0 : 0.0;
	float h = (y - c.layer.x) / (c.layer.y - c.layer.x);
	if (h <= 0.0 || h >= 1.0) return 0.0;
	float2 xz = c.origin.xy + p.xz;
	half4 w = weather.sample(cloudRep, (xz - c.shear.xy * h) * (1.0 / CLOUD_WEATHER_TILE), level(0.0));
	// coverage varies by region (the weather map's r), the cells (g) say where the cumulus stand
	float cov = saturate(c.shape.x + (float(w.r) - 0.5) * 0.55);
	float cell = float(w.g);
	float core = saturate((cell - (1.0 - cov)) / max(cov, 0.08)); // 0 at a cloud's footprint edge, 1 in its core
	// vertical profile (after Photon's cumulus shaping): a flat base, the footprint shrinking toward an egg-shaped top; the
	// column's height grows with its core and the day's cloud type (flat fair-weather cumulus .. towering)
	float topH = mix(0.38, 1.0, c.shape.y) * (0.45 + 0.55 * core);
	float x = h / max(topH, 0.05);
	float d = core - x * x * mix(0.9, 0.6, c.shape.y);
	d *= smoothstep(0.0, 0.04, h);
	if (d <= 0.0) return 0.0;
	if (q == 0) return saturate(d * 0.85);
	float3 n3 = float3(xz.x + c.origin.z - c.origin.x, y, xz.y + c.origin.w - c.origin.y);
	half4 n = noise.sample(cloudRep, n3 * CLOUD_NOISE_SCALE, level(lod));
	// billows (after Photon's detail erosion): Worley distance squared eats into the shape between the cells, more where
	// the cloud is thin (dampen(1 - d)), so the edges and the tops break into round lobes; the Perlin-Worley adds variety
	float wd = 1.0 - (float(n.g) * 0.75 + float(n.r) * 0.25);
	float thin = saturate(1.0 - d);
	thin = thin * (2.0 - thin);
	d -= c.shape.w * 0.55 * wd * wd * thin;
	if (q >= 2 && d > 0.0) {
		half4 m = noise.sample(cloudRep, n3 * CLOUD_DETAIL_SCALE, level(lod + 1.0));
		float wd2 = 1.0 - (float(m.g) * 0.6 + float(m.b) * 0.4);
		// fine detail: strongest on the sides and lower half, little on the domes' crowns
		float fade = 0.6 + 0.2 * smoothstep(0.85, 1.0, 1.0 - h) - 0.35 * smoothstep(0.05, 0.5, h);
		d -= 0.55 * c.misc.w * detail * wd2 * wd2 * thin * fade;
	}
	// inner texture: the billow noise varies the density inside the cloud too (mottled bases and bellies seen from below,
	// uneven light through the body), not only its edges
	d *= 0.6 + 0.8 * float(n.r);
	d = saturate(d);
	// edges sharpen with height: crisp cauliflower tops, softer ragged bases
	d = 1.0 - pow(1.0 - d, mix(3.0, 7.0, h));
	return d * (0.5 + 0.5 * smoothstep(0.0, 0.2, h));
}

// Optical depth toward the light from p (blocks x density x extinction): steps double from 6 blocks, jittered.
static float cloudLightDepth(float3 p, float y, float jitter, int steps, constant CloudParams &c, texture2d<half> weather, texture3d<half> noise,
                             float lod) {
	float3 L = c.lightDir.xyz;
	// steps of 4, 8, 16, 32... blocks (faster growth with fewer steps): the first two see the billows (3D noise: shadowed creases between the lobes), the
	// rest only the cloud's large shape
	float tau = 0.0, s = 0.0, step = c.misc.y > 0.0 ? c.misc.y : 4.0;
	// fewer steps grow faster, so the march still reaches ~90-110 blocks (deep shadow inside big clouds)
	float grow = steps <= 3 ? 3.5 : steps == 4 ? 2.6 : 2.0;
	for (int i = 0; i < steps; i++) {
		float m = s + step * (0.5 + 0.5 * (jitter - 0.5));
		float3 q = p + L * m;
		tau += cloudDensity(q, y + L.y * m, i < 2 ? 1 : 0, c, weather, noise, 1.0, lod + float(i)) * step;
		s += step;
		step *= grow;
	}
	return tau * c.shape.z;
}

static inline float cloudHG(float mu, float g) {
	float g2 = g * g;
	return 0.0795775 * (1.0 - g2) / pow(max(1.0 + g2 - 2.0 * g * mu, 1e-4), 1.5);
}

// Phase: a strong forward lobe, a narrow silver-lining peak and a backward lobe (Photon's mix, our weights); k narrows
// the lobes for the multiple-scattering octaves.
static inline float cloudPhase(float mu, float k, constant CloudParams &c) {
	float g = c.look.z * k;
	return 0.7 * cloudHG(mu, g) + c.look.y * k * cloudHG(mu, 0.97) + 0.3 * cloudHG(mu, -0.25 * k);
}

struct CloudResult {
	float3 S;    // scattered light toward the camera (before exposure)
	float T;     // transmittance
	float dist;  // apparent distance (blocks): transmittance-weighted mean depth of what scattered
};

// The cumulus layer along dir (camera-relative world, unit). steps/lsteps: primary and light samples; q: best density
// quality; jitter in [0, 1).
static CloudResult cloudMarch(float3 dir, int steps, int lsteps, int q, float jitter, constant CloudParams &c, texture2d<half> weather,
                              texture3d<half> noise) {
	CloudResult o;
	o.S = 0.0;
	o.T = 1.0;
	o.dist = -1.0;
	CloudRay r = cloudRay(dir, c);
	float t0, t1;
	if (!cloudSegment(r, c.layer.x, c.layer.y, c.misc.z, c.march.z, t0, t1)) return o;
	// inside the layer: the fog around the camera matters, the far stretch is hidden by it (and by aerial perspective)
	bool inside = t0 < 1.0;
	if (inside) t1 = min(t1, 1500.0);
	float len = t1 - t0;
	// fewer, longer steps where the stretch is short (straight up) and the far horizon (thin on screen)
	int n = clamp(int(float(steps) * saturate(len / 600.0) + 0.999), max(steps / 2, 4), steps);
	float dt = len / float(n);
	// the fine detail (a ~30-block tile) fades out where the steps get too coarse to resolve it (it aliases into grain)
	float detail = saturate((26.0 - dt) / 14.0);
	if (detail <= 0.0) q = min(q, 1);
	float sigma = c.shape.z;
	float albedo = c.ambTop.w;
	float3 L = c.lightDir.xyz;
	float mu = dot(dir, L);
	float pw = c.look.x;
	// the phase depends on the ray only (mu): the octaves' lobes once per ray, not per sample (12 pow per sample before)
	float ph0 = cloudPhase(mu, 1.0, c), ph1 = cloudPhase(mu, 0.5, c), ph2 = cloudPhase(mu, 0.25, c), ph3 = cloudPhase(mu, 0.125, c);
	float3 Sacc = 0.0;
	float T = 1.0, dsum = 0.0, wsum = 0.0;
	float thick = c.layer.y - c.layer.x;
	// empty air is crossed with double steps on the 2D footprint alone (a superset of the cloud: erosion only removes);
	// a hit steps back and walks on with single full-quality steps until 4 misses in a row
	bool fine = false;
	int miss = 0;
	// inside the layer the steps start short and grow with distance (the cloud around the camera is close: uniform 60-block
	// steps made it a noisy guess that showed the checkerboard)
	float dtl = inside ? dt * 0.08 : dt;
	float t = t0 + jitter * dtl;
	for (int i = 0; i < 3 * n + 4 + (inside ? 24 : 0) && t < t1; i++) {
		if (inside) dtl = dt * clamp((t + 24.0) / 400.0, 0.08, 1.0);
		float3 p = dir * t;
		float y = cloudAltitude(r, t);
		if (!fine) {
			if (cloudDensity(p, y, 0, c, weather, noise) <= 0.0) {
				t += 2.0 * dtl;
				continue;
			}
			fine = true;
			miss = 0;
			t = max(t - dtl, t0 + jitter * dtl);
			continue;
		}
		// mip of the noise for this sample's footprint (pixel angle x distance)
		float lod = max(log2(max(t * c.windDir.w, 1e-3) / CLOUD_NOISE_TEXEL), 0.0);
		float d = cloudDensity(p, y, T > 0.25 ? q : 1, c, weather, noise, detail, lod);
		if (d <= 0.002) {
			if (++miss >= 4) fine = false;
			t += dtl;
			continue;
		}
		miss = 0;
		float st = d * sigma;
		// in dense cloud a whole step would jump from lit to opaque: at most shear.z optical depths per sample (down to
		// shear.w of a step), so where the ray starts in the cloud (the jitter) matters less
		float ds = clamp(c.shear.z / max(st, 1e-4), dtl * c.shear.w, dtl);
		float stepT = exp(-st * ds);
		float h = saturate((y - c.layer.x) / thick);
		float tauL = cloudLightDepth(p, y, fract(jitter + float(i) * 0.618034), lsteps, c, weather, noise, lod);
		// skylight from above through what is left above this point (one cheap sample), bounce from below
		float above = c.layer.y - y;
		float dUp = cloudDensity(p + float3(0.0, above * 0.4, 0.0), y + above * 0.4, 0, c, weather, noise);
		// (diffuse light: much less extinct than the direct beam)
		float tauUp = (dUp * 0.7 + d * 0.3) * sigma * above * c.art.z;
		float tauDown = d * sigma * (y - c.layer.x) * c.art.z * 0.75;
		// multiple scattering: 4 octaves (Wrenninge): each dimmer, far less extinct and more isotropic (the light that
		// diffuses through a thick cloud: lit sides near white, bases grey, not black)
		float a1 = c.art.x, b1 = c.art.y;
		float ms = exp(-tauL) * ph0 + a1 * exp(-b1 * tauL) * ph1 + a1 * a1 * exp(-b1 * b1 * tauL) * ph2
			+ a1 * a1 * a1 * exp(-b1 * b1 * b1 * tauL) * ph3;
		// powder: the thin skin of a cloud scatters less light toward the eye than its depths (dark creases on the sunlit
		// side), gone when looking toward the light (Schneider's "beer-powder", shaped after Photon's)
		float powder = 1.0 - pw * exp(-st * 28.0);
		ms *= mix(powder, 1.0, saturate(mu * 1.5));
		float3 direct = c.light.rgb * ms;
		float3 amb = (c.ambTop.rgb * exp(-tauUp) * (0.55 + 0.45 * h) + c.ambBottom.rgb * exp(-tauDown)) * c.ambBottom.w;
		float3 Sstep = albedo * st * (direct + amb);
		float wseg = T * (1.0 - stepT);
		Sacc += Sstep * (wseg / max(st, 1e-6));
		dsum += t * wseg;
		wsum += wseg;
		T *= stepT;
		if (T < 0.02) break;
		t += ds;
	}
	o.S = Sacc;
	o.T = saturate((T - 0.02) / 0.98);
	o.dist = wsum > 1e-5 ? dsum / wsum : t0;
	return o;
}

// Thin cirrus on a curved plane high above the cumulus: streaks along the wind, lit with a broad forward lobe.
static CloudResult cloudCirrus(float3 dir, constant CloudParams &c, texture2d<half> weather) {
	CloudResult o;
	o.S = 0.0;
	o.T = 1.0;
	o.dist = -1.0;
	if (c.wind.z <= 0.001) return o;
	CloudRay r = cloudRay(dir, c);
	float r1, r2;
	if (!cloudRoots(r.a, r.b, r.y0 - c.cirrus.x, r1, r2)) return o;
	float t = r1 > 0.0 ? r1 : r2;
	if (t <= 0.0 || t > c.march.z * 1.6) return o;
	// in the streaks' frame (axis fixed in the world; cirrus.zw: the camera there, wrapped), stretched 4:1 along the axis
	float2 rel = dir.xz * t;
	float2 wd = c.windDir.xy, wn = float2(-wd.y, wd.x);
	float2 s = c.cirrus.zw + float2(dot(rel, wd), dot(rel, wn));
	half4 w0 = weather.sample(cloudRep, s * float2(1.0 / 16384.0, 1.0 / 4096.0), level(0.0));
	float2 warp = (float2(w0.a, w0.r) - 0.5) * 0.06;
	half4 w1 = weather.sample(cloudRep, s * float2(1.0 / 8192.0, 1.0 / 2048.0) + warp, level(0.0));
	half4 w2 = weather.sample(cloudRep, s * float2(1.0 / 4096.0, 1.0 / 1024.0) + warp * 1.5, level(0.0));
	// broad sheets (w0, w1) combed into soft streaks (w2)
	float n = float(w0.b) * 0.45 + float(w1.b) * 0.4 + float(w2.a) * 0.15;
	float d = cloudRemap(n, 1.0 - c.wind.z * 0.6, 1.0, 0.0, 1.0);
	d *= 0.55 + 0.45 * float(w2.b);
	d = d * d * (3.0 - 2.0 * d);
	if (d <= 0.0) return o;
	float tau = d * c.cirrus.y;
	float T = exp(-tau);
	float mu = dot(dir, c.lightDir.xyz);
	float phase = 0.6 * cloudHG(mu, 0.75) + 0.4 * cloudHG(mu, 0.2);
	float3 S = (c.light.rgb * phase * 3.0 + c.ambTop.rgb * 0.9) * (1.0 - T) * 0.9;
	o.S = S;
	o.T = T;
	o.dist = t;
	return o;
}

// Blocky style (Storybook): the slab of 12-block cells ray-traced exactly (a 2D DDA through the cells between the slab's
// planes, flat like vanilla's), the first filled cell's face lit: wrapped sun with a self-shadow from the cells toward the
// light, sky light from above and the ground's from below, a forward rim toward the sun. Nearly opaque, crisp edges.
static CloudResult cloudBlocky(float3 dir, constant CloudParams &c, texture2d<half> weather) {
	CloudResult o;
	o.S = 0.0;
	o.T = 1.0;
	o.dist = -1.0;
	float y0 = c.layer.z, yb = c.layer.x, yt = c.layer.y;
	float maxT = min(c.march.z, 4000.0);
	float t0, t1;
	if (abs(dir.y) < 1e-5) {
		if (y0 < yb || y0 > yt) return o;
		t0 = 0.0;
		t1 = maxT;
	} else {
		float ta = (yb - y0) / dir.y, tb = (yt - y0) / dir.y;
		t0 = max(min(ta, tb), 0.0);
		t1 = min(max(ta, tb), maxT);
	}
	if (t1 <= t0) return o;
	float2 q0 = c.origin.xy + dir.xz * t0;
	float2 d = dir.xz;
	float2 cell = floor(q0 / CLOUD_BLOCK);
	float2 st = float2(d.x >= 0.0 ? 1.0 : -1.0, d.y >= 0.0 ? 1.0 : -1.0);
	float2 ad = max(abs(d), float2(1e-6));
	float2 tDelta = CLOUD_BLOCK / ad;
	float2 next = ((cell + max(st, 0.0)) * CLOUD_BLOCK - q0) / (st * ad);
	float t = t0;
	int axis = -1;
	bool hit = false;
	for (int i = 0; i < 160; i++) {
		half4 w = weather.sample(cloudRep, (cell + 0.5) * (CLOUD_BLOCK / CLOUD_WEATHER_TILE), level(0.0));
		float cov = saturate(c.shape.x + (float(w.r) - 0.5) * 0.55);
		if (float(w.g) > 1.0 - cov) {
			hit = true;
			break;
		}
		if (next.x < next.y) {
			t = t0 + next.x;
			next.x += tDelta.x;
			cell.x += st.x;
			axis = 0;
		} else {
			t = t0 + next.y;
			next.y += tDelta.y;
			cell.y += st.y;
			axis = 1;
		}
		if (t >= t1) break;
	}
	if (!hit) return o;
	float3 n = axis == 0 ? float3(-st.x, 0.0, 0.0) : axis == 1 ? float3(0.0, 0.0, -st.y)
		: (t0 > 0.0 ? float3(0.0, dir.y > 0.0 ? -1.0 : 1.0, 0.0) : -dir);
	float3 L = c.lightDir.xyz;
	float3 ph = dir * t;
	float yh = y0 + dir.y * t;
	float vis = 1.0;
	for (int k = 1; k <= 3; k++) {
		float3 pk = ph + n * 0.5 + L * (float(k) * 7.0);
		float yk = yh + n.y * 0.5 + L.y * float(k) * 7.0;
		if (yk >= yb && yk <= yt && cloudBlockCell(c.origin.xy + pk.xz, c, weather)) vis *= 0.45;
	}
	float ndl = dot(n, L);
	float wrap = saturate((ndl + 0.35) / 1.35);
	float mu = dot(dir, L);
	float rim = (1.0 - abs(dot(n, dir))) * cloudHG(mu, 0.75) * 0.9;
	// sky light by the face's direction, plus light diffused through the thin slab (keeps undersides from going flat grey)
	float3 amb = mix(c.ambBottom.rgb * 1.4 + c.ambTop.rgb * 0.5, c.ambTop.rgb * 1.25, n.y * 0.5 + 0.5) + c.light.rgb * 0.035;
	float albedo = c.ambTop.w;
	o.S = albedo * (c.light.rgb * (wrap * vis * 0.3183 + rim * vis) + amb) * 0.92;
	o.T = 0.08;
	o.dist = t;
	return o;
}

// Aerial perspective (Photon's blend): the clouds' light fades toward the clear sky behind by the air between. fade (0..1)
// multiplies the air's transmittance: the clouds melt into the horizon's haze instead of ending on a line.
static inline CloudResult cloudAerial(CloudResult r, float3 sky, float fade, constant CloudParams &c) {
	if (r.dist < 0.0) return r;
	float Tair = exp(-r.dist / c.march.w) * fade * (1.0 - smoothstep(0.55 * c.march.z, c.march.z, r.dist));
	r.S = mix((1.0 - r.T) * sky, r.S, Tair);
	return r;
}

// Rays below the horizontal skim the curved world before they rise into the layer (the clouds seen there are the farthest,
// squeezed into the horizon): 1 for rays level or up, falling to 0 toward the ray that grazes the ground (misc.z), so the
// cloud deck fades into the horizon haze below it instead of stopping on a hard edge over the haze.
static inline float cloudHorizonFade(float3 dir, constant CloudParams &c) {
	float clear = c.layer.z - c.misc.z;
	if (dir.y >= 0.0 || clear <= 1.0) return 1.0;
	CloudRay r = cloudRay(dir, c);
	float k = saturate(1.0 - r.b * r.b / (4.0 * r.a * clear)); // the ray's closest approach to the ground / the camera's height
	// only for a camera below the layer: from inside or above it, rays looking down meet the clouds before the ground (the
	// fade turned the whole deck seen from above into flat sky-coloured haze)
	return mix(smoothstep(0.0, 0.9, k), 1.0, smoothstep(c.layer.x - 24.0, c.layer.x, c.layer.z));
}

// Cumulus + cirrus toward dir with aerial perspective: (S, T) and the apparent distance (-1: no cloud).
static CloudResult cloudSky(float3 dir, int steps, int lsteps, int q, float jitter, constant ShadeFrame &f, constant CloudParams &c,
                            texture2d<half> weather, texture3d<half> noise, texture2d<half> lut, sampler lutS) {
	float3 sky = shadeSkyLookup(dir, lut, lutS, f);
	float hf = cloudHorizonFade(dir, c);
	CloudResult cu = cloudAerial(c.art.w > 0.5 ? cloudBlocky(dir, c, weather) : cloudMarch(dir, steps, lsteps, q, jitter, c, weather, noise), sky, hf, c);
	CloudResult ci = cloudAerial(cloudCirrus(dir, c, weather), sky, hf, c);
	CloudResult o;
	o.S = cu.S + cu.T * ci.S; // the cirrus is always behind (far above) the cumulus
	o.T = cu.T * ci.T;
	o.dist = cu.dist >= 0.0 ? cu.dist : ci.dist;
	float fade = c.look.w;
	o.S *= fade;
	o.T = mix(1.0, o.T, fade);
	return o;
}

static inline float3 cloudDirection(float2 uv, constant CloudParams &c) {
	float4 w = c.invViewProj * float4(uv * 2.0 - 1.0, 0.5, 1.0);
	return normalize(w.xyz / w.w);
}

// ---- passes --------------------------------------------------------------------------------------------------------------
struct CloudVsOut { float4 position [[position]]; };

vertex CloudVsOut cloud_vs(uint vid [[vertex_id]]) {
	CloudVsOut o;
	o.position = float4(vid == 1 ? 3.0 : -1.0, vid == 2 ? 3.0 : -1.0, 0.0, 1.0);
	return o;
}

struct CloudMarchOut {
	half4 color [[color(0)]]; // rgb scattered light (before exposure), a transmittance
	float dist [[color(1)]];  // apparent distance (blocks; the slab's mid distance where the ray holds no cloud)
};

fragment CloudMarchOut cloud_march(CloudVsOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant CloudParams &c [[buffer(0)]],
                                   texture2d<half> weather [[texture(0)]], texture3d<half> noise [[texture(1)]],
                                   texture2d<half> lut [[texture(17)]], sampler lutS [[sampler(13)]]) {
	// the history pixel this march pixel stands for this frame (N x N checkerboard, N = windDir.z)
	float n = c.windDir.z;
	float2 hp = min(floor(in.position.xy) * n + c.frame.xy, c.size.xy - 1.0);
	float2 uv = (hp + 0.5) / c.size.xy;
	float3 dir = cloudDirection(uv, c);
	// a history pixel is marched every N^2 frames: its start offsets step by the golden ratio per visit (stepping per frame
	// made N^2 x 0.618 = 0.56 per visit with N = 3, nearly periodic: a biased mean that showed the checkerboard as a grid)
	float visit = floor(c.misc.x / (n * n));
	float jitter = fract(shadeNoise(hp, 0) + visit * 0.6180339887);
	CloudResult r = cloudSky(dir, int(c.march.x), int(c.march.y), 2, jitter, f, c, weather, noise, lut, lutS);
	CloudMarchOut o;
	o.color = half4(half3(min(r.S, 60000.0)), half(r.T));
	float dist = r.dist;
	if (dist < 0.0) {
		// no cloud: reproject by the slab's distance in that direction (clouds that appear there move like that)
		CloudRay ray = cloudRay(dir, c);
		float t0, t1;
		dist = cloudSegment(ray, c.layer.x, c.layer.y, c.misc.z, c.march.z, t0, t1) ? 0.5 * (t0 + t1) : c.march.z;
	}
	o.dist = dist;
	return o;
}

// 5-tap Catmull-Rom of the history (rgb and transmittance): the wind moves the clouds a fraction of a pixel every frame, and
// bilinear reprojection would blur the history a little more each frame.
static inline half4 cloudCatmullRom(texture2d<half> tex, float2 uv, float2 size) {
	float2 pos = uv * size;
	float2 t1 = floor(pos - 0.5) + 0.5;
	float2 f = pos - t1;
	float2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
	float2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
	float2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
	float2 w3 = f * f * (-0.5 + 0.5 * f);
	float2 w12 = w1 + w2;
	float2 inv = 1.0 / size;
	float2 t0 = (t1 - 1.0) * inv, t3 = (t1 + 2.0) * inv, t12 = (t1 + w2 / w12) * inv;
	float4 c = float4(tex.sample(cloudClamp, float2(t12.x, t0.y))) * (w12.x * w0.y)
		+ float4(tex.sample(cloudClamp, float2(t0.x, t12.y))) * (w0.x * w12.y)
		+ float4(tex.sample(cloudClamp, t12)) * (w12.x * w12.y)
		+ float4(tex.sample(cloudClamp, float2(t3.x, t12.y))) * (w3.x * w12.y)
		+ float4(tex.sample(cloudClamp, float2(t12.x, t3.y))) * (w12.x * w3.y);
	float wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
	c /= wsum;
	return half4(half3(max(c.rgb, 0.0)), half(saturate(c.a)));
}

struct CloudResolveOut {
	half4 color [[color(0)]]; // rgb scattered light (before exposure), a transmittance
	half age [[color(1)]];    // R8Unorm: frames this pixel was marched and blended / 64
};

// The history pixel's new value: this frame's march where it is this frame's checkerboard slot (blended into the
// reprojected history: 1 / age, capped), else the reprojected history (Photon's checkerboard upscaling, our weights), or the
// march upsampled where nothing reprojects (a turn uncovering the screen edge, first frames).
fragment CloudResolveOut cloud_resolve(CloudVsOut in [[stage_in]], constant CloudParams &c [[buffer(0)]],
                                       texture2d<half> cur [[texture(0)]], texture2d<float> curDist [[texture(1)]],
                                       texture2d<half> hist [[texture(2)]], texture2d<half> histAge [[texture(3)]]) {
	uint n = uint(c.windDir.z);
	uint2 px = uint2(in.position.xy);
	uint2 slot = uint2(c.frame.xy);
	uint2 mp = min(px / n, uint2(c.size.zw) - 1u);
	bool mine = all(px % n == slot);
	float2 uv = (float2(px) + 0.5) / c.size.xy;
	float3 dir = cloudDirection(uv, c);
	half4 now = cur.read(mp);
	float dNow = curDist.read(mp).r;
	// reproject: where this direction's cloud (at the current march's distance) was last frame. The density is read at
	// origin + p with origin = camera + the accumulated wind, so a cloud drifts by -wind each frame: last frame it was at
	// p + wind (the sign matters: the other way round the history smeared 2 x wind per frame, blurring and patterning the
	// clouds)
	float3 wp = dir * dNow + float3(c.wind.x, 0.0, c.wind.y);
	float4 pc = c.prevViewProj * float4(wp, 1.0);
	float2 puv = pc.xy / max(pc.w, 1e-6) * 0.5 + 0.5;
	bool valid = c.frame.z < 0.5 && pc.w > 1e-4 && all(puv >= 0.0) && all(puv <= 1.0);
	half4 h = half4(0.0h, 0.0h, 0.0h, 1.0h);
	float age = 0.0;
	if (valid) {
		h = cloudCatmullRom(hist, puv, c.size.xy);
		age = float(histAge.sample(cloudClamp, puv).r) * 64.0;
		// the block's one march distance reprojects all N x N of its pixels: where the distances jump (a near cloud's edge
		// against far sky or cloud: wind and camera parallax differ there) the history can come from the wrong surface, so
		// keep it inside the range of this frame's march around it (fringes of stray pixels on near clouds otherwise)
		int2 mi = int2(mp), ms = int2(c.size.zw) - 1;
		int2 ax = int2(min(mi.x + 1, ms.x), mi.y), bx = int2(max(mi.x - 1, 0), mi.y);
		int2 ay = int2(mi.x, min(mi.y + 1, ms.y)), by = int2(mi.x, max(mi.y - 1, 0));
		float d1 = curDist.read(uint2(ax)).r, d2 = curDist.read(uint2(bx)).r, d3 = curDist.read(uint2(ay)).r, d4 = curDist.read(uint2(by)).r;
		float dMin = min(min(min(d1, d2), min(d3, d4)), dNow), dMax = max(max(max(d1, d2), max(d3, d4)), dNow);
		if (dMax > dMin * 1.3 + 4.0) {
			half4 c1 = cur.read(uint2(ax)), c2 = cur.read(uint2(bx)), c3 = cur.read(uint2(ay)), c4 = cur.read(uint2(by));
			half4 lo = min(min(min(c1, c2), min(c3, c4)), now), hi = max(max(max(c1, c2), max(c3, c4)), now);
			half4 pad = (hi - lo) * 0.15h;
			h = clamp(h, lo - pad, hi + pad);
		}
	}
	CloudResolveOut o;
	if (mine) {
		float w = valid ? 1.0 / min(age + 1.0, c.frame.w) : 1.0;
		o.color = valid ? mix(h, now, half(w)) : now;
		o.age = half(min(age + 1.0, c.frame.w) / 64.0);
	} else if (valid && age > 0.5) {
		o.color = h;
		o.age = half(age / 64.0);
	} else {
		float2 mpos = (float2(px) + 0.5 - float2(slot) - 0.5) / float(n) + 0.5;
		o.color = cur.sample(cloudClamp, mpos / c.size.zw);
		o.age = 0.0h;
	}
	return o;
}

// Clouds in front of the world (the camera up at or above the cloud layer: flying, mountain tops): composited over the
// HDR scene before the tone map where the terrain lies behind the clouds' apparent distance (the sky pass already put
// them behind everything else). Blend: One, OneMinusSourceAlpha (scene x T + light x exposure).
fragment half4 cloud_over(CloudVsOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant ShadeLook &L [[buffer(17)]],
                          constant ShadeGpu &g [[buffer(18)]], depth2d<float> depth [[texture(0)]], texture2d<float> cdist [[texture(1)]],
                          texture2d<half> hist [[texture(2)]], constant uint &mode [[buffer(0)]]) {
	float d = depth.read(uint2(in.position.xy));
	// mode 1 (Distant Horizons drew this frame): its LODs are not in the world depth, so an empty pixel below the horizon is
	// taken as far terrain behind the clouds (true sky there gets the clouds a second time: rare with the LODs to the horizon)
	if (d <= 0.0 && mode == 0u) discard_fragment();
	float2 ndc = in.position.xy * f.screen.zw * 2.0 - 1.0;
	float4 w = f.invViewProj * float4(ndc, d > 0.0 ? d : 0.5, 1.0);
	float3 p = w.xyz / w.w;
	float dist = length(p);
	float3 dir = p / max(dist, 1e-4);
	if (d <= 0.0) {
		if (dir.y >= 0.0) discard_fragment();
		dist = 1e9;
	}
	float4 cp = L.viewProjNoJitter * float4(dir, 0.0);
	if (cp.w <= 1e-6) discard_fragment();
	float2 uv = cp.xy / cp.w * 0.5 + 0.5;
	// the march's distances (1/N of the history): bilinear by hand (R32F), the nearest texel drew stair steps along the
	// clouds' edges in front of the world
	float2 dsz = float2(cdist.get_width(), cdist.get_height());
	float2 dp = clamp(uv * dsz - 0.5, float2(0.0), dsz - 1.0);
	uint2 d0 = uint2(dp), d1 = min(d0 + 1u, uint2(dsz) - 1u);
	float2 fw = dp - float2(d0);
	float cd = mix(mix(cdist.read(d0).r, cdist.read(uint2(d1.x, d0.y)).r, fw.x), mix(cdist.read(uint2(d0.x, d1.y)).r, cdist.read(d1).r, fw.x), fw.y);
	float k = smoothstep(cd * 0.8, cd * 1.05, dist);
	if (k <= 0.001) discard_fragment();
	half4 cl = hist.sample(cloudClamp, uv);
	float T = mix(1.0, float(cl.a), k);
	return half4(half3(min(float3(cl.rgb) * k * g.exposure.x, 60000.0)), half(1.0 - T));
}

// The reflection dome: texel (u, v) <-> azimuth u * 2 pi (atan2(z, x)), height dir.y = v^2 (dense near the horizon).
static inline float3 cloudDomeDirection(float2 uv) {
	float az = uv.x * 6.2831853, y = uv.y * uv.y, c = sqrt(max(1.0 - y * y, 0.0));
	return float3(cos(az) * c, y, sin(az) * c);
}

fragment half4 cloud_dome(CloudVsOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant CloudParams &c [[buffer(0)]],
                          texture2d<half> weather [[texture(0)]], texture3d<half> noise [[texture(1)]], texture2d<half> prev [[texture(2)]],
                          texture2d<half> lut [[texture(17)]], sampler lutS [[sampler(13)]]) {
	float2 uv = in.position.xy / c.dome.xy;
	float3 dir = cloudDomeDirection(uv);
	// updated every 4th frame: golden-ratio steps per update (per frame, 4 x 0.618 = 0.47 per update, close to periodic)
	float jitter = fract(shadeNoise(in.position.xy, 0) + floor(c.misc.x * 0.25) * 0.6180339887);
	CloudResult r = cloudSky(dir, int(c.dome.w), 2, 1, jitter, f, c, weather, noise, lut, lutS);
	half4 now = half4(half3(min(r.S, 60000.0)), half(r.T));
	if (c.frame.z > 0.5) return now;
	return mix(prev.read(uint2(in.position.xy)), now, half(c.dome.z));
}

// Transmittance of the cumulus toward the light, on the plane y = shadow.y: texel -> the plane point, then a few samples
// along the light direction through the slab (2D shape + billows).
fragment half cloud_shadow(CloudVsOut in [[stage_in]], constant CloudParams &c [[buffer(0)]], texture2d<half> weather [[texture(0)]],
                           texture3d<half> noise [[texture(1)]]) {
	float2 uv = in.position.xy / 256.0;
	float2 rel = c.shadow.zw + (uv - 0.5) * c.shadow.x;
	float3 L = c.shadowDir.xyz;
	float ly = L.y;
	float yRef = c.shadow.y;
	float sa = (c.layer.x - yRef) / ly, sb = (c.layer.y - yRef) / ly;
	const int N = 6;
	float ds = (sb - sa) / float(N);
	float tau = 0.0;
	float3 base = float3(rel.x, yRef - c.layer.z, rel.y);
	for (int i = 0; i < N; i++) {
		float s = sa + (float(i) + 0.5) * ds;
		float3 p = base + float3(L.x, ly, L.z) * s;
		tau += cloudDensity(p, yRef + ly * s, 1, c, weather, noise, 1.0, 1.1) * ds;
	}
	return half(exp(-tau * c.shape.z * 0.8));
}

#endif
