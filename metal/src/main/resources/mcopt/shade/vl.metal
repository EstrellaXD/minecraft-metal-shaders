// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Volumetric light ("god rays", shader bible E07), compiled natively by mcvl.m after shade_common.metal. Original code from
// public techniques: single scattering in a participating medium (Hillaire 2015, "Physically based and unified volumetric
// rendering in Frostbite": energy-conserving per-segment integration), Henyey-Greenstein phase, interleaved gradient noise
// (Jimenez 2014) with an R1 temporal offset, neighbourhood-clamped temporal reprojection (Karis 2014), and the screen-space
// radial occlusion march for crepuscular rays (Mitchell, GPU Gems 3 ch. 13). No shader pack code.
//
// Passes, all fragment passes into private RenderTarget|ShaderRead targets (compressible: apple-gpu.md section 0.2):
//   vl_canopy   (compute, 1 threadgroup of 64) how much of the air around the camera is in shadow -> the "canopy factor"
//               (forests, overhangs, cave mouths -> strong shafts; open plains -> subtle), smoothed over seconds, in a
//               16-byte state buffer read by the march.
//   vl_march    (VL resolution, e.g. 1/2 of the target) per pixel: ray-march the local mist from the camera to the scene
//               depth through the sun's shadow map (near cascade where it covers, else far) -> in-scattered light; plus a
//               short screen-space march toward the sun on the depth buffer (crepuscular streaks beyond the shadow range)
//               and, under water, caustic-modulated shafts. rgb = in-scattered radiance (linear, pre-exposure), a = the
//               scene distance this pixel marched to (blocks; 1e4 = sky), used by the temporal and upsample passes.
//   vl_temporal (VL resolution) 3x3 depth-aware filter of the noisy march + reprojected history (ShadeFrame.prevViewProj),
//               clamped to the 3x3 neighbourhood's range and rejected on distance mismatch. Writes the new history.
//   vl_composite (full resolution) depth-aware bilateral upsample of the history; then
//               - vl_composite (M1 / mcopt.shade.hdr=false): adds the light *before* M1's output curve: the target's sRGB value is mapped
//                 back to exposed radiance through the inverse of shadeOutput's curve, the light (x exposure) is added, and
//                 the difference of the two forward curves is added to the pixel (so no light = the exact same pixel);
//                 programmable blending ([[color(0)]]) on the game's RGBA8 main target, blue-noise-ish dither against banding.
//               - vl_composite_hdr (M2 with mcopt.shade.vl.fold=false or a debug view): returns the light x exposure and
//                 the canopy mist's transmittance T for blending (One, SourceAlpha: scene * T + light) into the
//                 pre-exposed HDR world target (RG11B10F / RGBA16F), before bloom and the tone map.
//   M2 default ("fold"): no composite pass; vl_temporal_fold also writes lightT (light + T at VL resolution) and the post
//   chain's final pass (shade_post.metal shadeVlUpsample, ShadePost.setVolumetric) upsamples and blends it. VL_HDR
//   (function constant 0) = built for M2: the canopy kernel also writes ShadeGpu.scene.y. See VL.md section 5.
// Bindings: ShadeFrame at buffer(16), shadow map texture(16) + compare sampler(12) (mc_shade_bind_*), VlParams at buffer(0),
// the canopy state at buffer(1); pass inputs at texture(0..1); M2: ShadeGpu at buffer(18) (HDR composite, canopy kernel).

#ifndef MCOPT_SHADE_VL
#define MCOPT_SHADE_VL

constant bool VL_HDR [[function_constant(0)]];

// Per-frame parameters (mcopt.metal.shade.vl.ShadeVl packs exactly this layout; 16 x float4 = 256 bytes).
struct VlParams {
	float4 size;      //   0 xy VL live size (px: the part of the VL targets this frame covers), zw full-resolution live size (px)
	float4 shape;     //  16 x strength, y max march distance (blocks), z step count, w forward HG g
	float4 medium;    //  32 x haze sigma_s at its base (1/block), y mist sigma_s at its base, z mist base y (camera-relative), w mist scale height
	float4 haze;      //  48 x haze base y (camera-relative), y haze scale height, z isotropic share, w back-lobe share
	float4 light;     //  64 rgb light colour (linear irradiance / pi, art-graded, pre-exposure), w canopy mist sigma_s at canopy 1 (1/block)
	float4 crep;      //  80 x screen-space ray strength (0 = off), y taps, z on-screen weight of the light, w angular falloff
	float4 sunPx;     //  96 xy the light's position in full-res target pixels (Metal rows), z history clamp allowance of a still
	                  //     view (share of the neighbourhood mean; 0 = the plain clamp, M2.1), w bits (M2.2; 0 = M2.1): 1 the clamp
	                  //     box over the 3x3 neighbourhood (else the filter's plus), 2 the history fetched from the texels at
	                  //     this point's distance (else one bilinear sample), 4 the crepuscular taps offset per texel over a
	                  //     bilinear sky mask (else one offset per frame, point reads), 8 the canopy mist's own steps offset
	                  //     per texel (else the march's per-frame offset), 16 the same for the march under water
	float4 water;     // 112 rgb extinction under water (1/block), w scattering (1/block)
	float4 water2;    // 128 x surface y (camera-relative), y underwater (0/1), z max distance under water, w caustic strength
	float4 temporal;  // 144 x history weight, y reset (1 = drop history), z frame index, w time (s, wraps)
	float4 camDelta;  // 160 xyz camera position now - last frame (blocks), w canopy smoothing factor this frame, rising (0..1)
	float4 gate;      // 176 x open-air layers' (haze, mist) strength, y canopy mist strength, z canopy override (< 0: GPU value), w debug view
	float4 extra;     // 192 x canopy reset (1 = snap), y canopy mist scale height (blocks), z canopy smoothing factor, falling,
	                  //     w canopy mist steps (its own linear march over the first 2.5 ranges; 0 = on the main march)
	float4 mist;      // 208 x canopy mist range (blocks: density e^(-t/range) from the camera), y its top above the eye (blocks),
	                  //     z scene extinction by the canopy mist (0/1), w its extinction relative to its scattering (art: < 1
	                  //     keeps the veil light while the shafts stay bright)
	float4 mistAmb;   // 224 rgb ambient (sky) light of the shadowed canopy mist (irradiance / pi, pre-exposure), w share of the
	                  //     open-air haze's unshadowed glow left out (the sky and M1's haze have it already)
	float4 carve;     // 240 x canopy mist carve k: keep max(lit - k unshadowed, 0) / (1 - k) of it (0 = all; M2 > 0: the
	                  //     uniform part goes, rays crossing shadows darken -> visible beams), y the same for the caustic
	                  //     shafts under water (relative to the light without caustics), zw the part of the history texture
	                  //     last frame covered (uv scale; the targets are allocated for the largest render scale, size.xy is
	                  //     this frame's live part)
};

struct VlState { float4 canopy; }; // x smoothed canopy factor 0..1, y raw shadowed fraction, zw 0

struct VlOut { float4 position [[position]]; };

vertex VlOut vl_vs(uint vid [[vertex_id]]) {
	VlOut o;
	o.position = float4(vid == 1 ? 3.0 : -1.0, vid == 2 ? 3.0 : -1.0, 0.0, 1.0);
	return o;
}

// ---- helpers -----------------------------------------------------------------------------------------------------------
static inline float vlHG(float mu, float g) {
	float g2 = g * g;
	float x = max(1.0 + g2 - 2.0 * g * mu, 1e-4);
	return 0.0795775 * (1.0 - g2) * rsqrt(x * x * x); // (1 - g^2) / (4 pi x^1.5)
}

// Phase: a forward Henyey-Greenstein lobe (strong toward the light), a weak back lobe and an isotropic share (light that
// scattered more than once; keeps shafts visible from the side, not only looking into the sun).
static inline float vlPhase(float mu, constant VlParams &p) {
	float iso = p.haze.z, back = p.haze.w;
	return (1.0 - iso - back) * vlHG(mu, p.shape.w) + back * vlHG(mu, -0.25) + iso * 0.0795775;
}

// World direction (camera-relative, unit) and distance (blocks; 1e4 for the sky) of full-res pixel px with depth d.
static inline float3 vlDirection(float2 ndc, constant ShadeFrame &f) {
	float4 h = f.invViewProj * float4(ndc, 1.0, 1.0); // reversed Z: 1 = near plane
	return normalize(h.xyz / h.w);
}
static inline float vlDistance(float2 ndc, float depth, constant ShadeFrame &f) {
	if (depth <= 0.0) return 1e4;
	float4 h = f.invViewProj * float4(ndc, depth, 1.0);
	return min(length(h.xyz / h.w), 1e4);
}

// Sun visibility at a point given its two cascade clip positions (ortho: linear in the march distance). Near cascade
// (alpha-tested leaves: shafts through canopy gaps) where it covers, else the far one; above everything the map holds =
// lit, below its depth range = shadowed (underground), outside the far square = lit.
static inline float vlShadow(depth2d_array<float> map, sampler cmp, float3 c0, float3 c1) {
	if (max(abs(c0.x), abs(c0.y)) < 0.97 && c0.z > 0.0 && c0.z < 1.0) return map.sample_compare(cmp, shadeShadowUV(c0), 0u, c0.z);
	if (max(abs(c1.x), abs(c1.y)) >= 0.995) return 1.0;
	if (c1.z >= 1.0) return 1.0;
	if (c1.z <= 0.0) return 0.0;
	return map.sample_compare(cmp, shadeShadowUV(c1), 1u, c1.z);
}

// Procedural caustics on the water surface plane (original): a domain-warped sum of three sines whose zero set forms a
// bright web with sharp ridges (r^4: mean about 0.21, 17% of the plane above 0.5). Two scales multiplied: broad cells
// (about 13 blocks) shape the shafts seen from afar, a finer web (about 5 blocks) breaks them into curtains; mean about 1,
// dark gaps about 0.1. q in blocks, t in seconds.
// Half precision (the per-step cost under water): time enters as phases wrapped to [0, 2 pi) in float (tp: x..w = the five
// time terms, see vlCausticPhases), positions stay below a few hundred, so half's resolution is plenty for the sines.
static inline half vlCausticWeb(half2 p, half4 tp, half tp4) {
	p += 0.55h * half2(sin(p.y * 1.31h + tp.x), sin(p.x * 1.17h - tp.y));
	half a = sin(p.x * 1.9h + tp.z) + sin(p.y * 2.1h - tp.w) + sin((p.x - p.y) * 1.45h + tp4);
	half r = saturate(1.0h - abs(a) * 0.55h);
	r *= r;
	return r * r;
}
static inline void vlCausticPhases(float t, thread half4 &a, thread half &a4, thread half4 &b, thread half &b4) {
	// t = the water's clock (ShadeFrame.waterTime: real time, wrapped every 4096 s); every speed is a whole number of turns per
	// 4096 s (about 0.83, 0.71, 0.9, 1.05, 0.6 rad/s and 1.3x those), so the wrap is seamless
	const float TAU = 6.2831853;
	float s = t * (1.0 / 4096.0);
	a = half4(TAU * fract(s * float4(541.0, 463.0, 587.0, 684.0)));
	a4 = half(TAU * fract(s * 391.0));
	b = half4(TAU * fract(s * float4(703.0, 602.0, 763.0, 890.0)));
	b4 = half(TAU * fract(s * 508.0));
}
static inline half vlCaustic(float2 q, half4 ta, half ta4, half4 tb, half tb4) {
	half broad = 0.25h + 3.6h * vlCausticWeb(half2(q * 0.22), ta, ta4);
	half fine = 0.5h + 2.4h * vlCausticWeb(half2(q * 0.55 + 37.0), tb, tb4);
	return broad * fine;
}

static inline float vlNoise(float2 px, float frame) {
	// IGN, decorrelated per frame by the R1 sequence (golden ratio)
	float n = fract(52.9829189 * fract(dot(px, float2(0.06711056, 0.00583715))));
	return fract(n + frame * 0.6180339887);
}

// ---- canopy factor --------------------------------------------------------------------------------------------------------
kernel void vl_canopy(uint tid [[thread_index_in_threadgroup]], uint lane [[thread_index_in_simdgroup]],
                      uint sg [[simdgroup_index_in_threadgroup]],
                      constant ShadeFrame &f [[buffer(16)]], depth2d_array<float> map [[texture(16)]],
                      constant VlParams &p [[buffer(0)]], device VlState *state [[buffer(1)]],
                      device ShadeGpu *gpu [[buffer(18), function_constant(VL_HDR)]]) {
	threadgroup float partial[2];
	// 64 points: four rings of 16 (radius 3, 7, 11 and 15 blocks), alternating 1.5 and 5 blocks above the eye (below a
	// tree canopy, in the air the shafts would cross), in the far cascade.
	uint ringIdx = tid >> 4u;
	float ring = 3.0 + 4.0 * float(ringIdx);
	float h = (tid & 1u) != 0u ? 5.0 : 1.5;
	float a = float(tid & 15u) * (6.2831853 / 16.0) + float(ringIdx) * 0.39;
	float3 pt = float3(cos(a) * ring, h, sin(a) * ring);
	float shadowed = 0.0;
	if (f.flags.z != 0) {
		float4 c = f.shadowViewProj[1] * float4(pt, 1.0);
		if (max(abs(c.x), abs(c.y)) < 0.995 && c.z < 1.0) {
			if (c.z <= 0.0) {
				shadowed = 1.0;
			} else {
				float2 uv = shadeShadowUV(c.xyz);
				uint2 texel = uint2(clamp(uv * f.shadowParams.y, 0.0, f.shadowParams.y - 1.0));
				shadowed = map.read(texel, 1u) > c.z + 1e-5 ? 1.0 : 0.0; // stored nearer the light than the point: shadowed
			}
		}
	}
	float s = simd_sum(shadowed);
	if (lane == 0u) partial[sg] = s;
	threadgroup_barrier(mem_flags::mem_threadgroup);
	if (tid == 0u) {
		float frac = (partial[0] + partial[1]) / 64.0;
		float target = smoothstep(0.12, 0.5, frac);
		float old = state->canopy.x;
		// rise quickly (stepping under trees), fade slowly (leaving them)
		float k = p.extra.x > 0.5 ? 1.0 : (target > old ? p.camDelta.w : p.extra.z);
		float canopy = mix(old, target, k);
		state->canopy = float4(canopy, frac, 0.0, 0.0);
		if (VL_HDR) gpu->scene.y = canopy; // M2: ShadeGpu.scene.y for other passes (E06 mist, fog glow)
	}
}

// Canopy mist: the humid air under trees and overhangs, around the camera, scaled by the canopy factor. Density
// sigma(t) = s * e^(-t / R) * e^(-max(y - top, 0) / H) along a ray (y = dir.y * t, camera-relative): it fades with the
// distance from the camera (the mist is where the trees are) and above the canopy. Optical depth over [0, D], closed form
// (the march samples the same density, the composite takes the scene's extinction from this, at full resolution).
static inline float vlMistDensity(float t, float y, constant VlParams &p) {
	return exp(-t / p.mist.x - max(y - p.mist.y, 0.0) / p.extra.y);
}
static inline float vlMistDepth(float3 dir, float D, constant VlParams &p) {
	float R = p.mist.x, H = p.extra.y;
	float t1 = dir.y > 1e-4 ? min(p.mist.y / dir.y, D) : D; // below the top of the layer up to t1
	float od = R * (1.0 - exp(-t1 / R));
	if (D > t1) {
		float k = 1.0 / R + dir.y / H;
		od += exp(-t1 / R) * (1.0 - exp(-(D - t1) * k)) / k;
	}
	return od;
}

// ---- march ----------------------------------------------------------------------------------------------------------------
struct VlMarchOut {
	half4 light [[color(0)]]; // rgb in-scattered radiance, a marched distance
	half sky [[color(1)]];    // 1 where the pixel is sky (R8Unorm): next frame's crepuscular taps read this
};

fragment VlMarchOut vl_march(VlOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant VlParams &p [[buffer(0)]],
                        constant VlState &st [[buffer(1)]], depth2d<float> depth [[texture(0)]], texture2d<half> prevSky [[texture(1)]],
                        depth2d_array<float> map [[texture(16)]], sampler cmp [[sampler(12)]]) {
	float2 scale = p.size.zw / p.size.xy;
	// the full-res pixel this VL pixel stands for (the same one the upsample assumes)
	uint2 fpx = uint2(min(floor(in.position.xy * scale), p.size.zw - 1.0));
	float2 fpos = float2(fpx) + 0.5;
	float2 ndc = fpos / p.size.zw * 2.0 - 1.0;
	float d = depth.read(fpx);
	float3 dir = vlDirection(ndc, f);
	float dist = vlDistance(ndc, d, f);
	// Step jitter: a per-frame offset (R1 sequence, integrated by the temporal pass) plus a quarter-step IGN spread across
	// pixels. Neighbouring rays then sample nearly the same depths, so their shadow-map fetches stay in cache: full
	// per-pixel jitter made the march 2-3x slower on a real (blocky, 16 MB) shadow map (tools/shade/vlbench.sh).
	float xi = fract(p.temporal.z * 0.6180339887 + 0.25 * vlNoise(in.position.xy, 0.0));
	float3 L = f.lightDirWorld.xyz;
	float mu = dot(dir, L);
	float canopy = p.gate.z >= 0.0 ? p.gate.z : st.canopy.x;
	float3 scatter = float3(0.0);
	bool underwater = p.water2.y > 0.5;
	int n = int(p.shape.z);
	float3 c0o = (f.shadowViewProj[0] * float4(0.0, 0.0, 0.0, 1.0)).xyz, c0d = (f.shadowViewProj[0] * float4(dir, 0.0)).xyz;
	float3 c1o = (f.shadowViewProj[1] * float4(0.0, 0.0, 0.0, 1.0)).xyz, c1d = (f.shadowViewProj[1] * float4(dir, 0.0)).xyz;
	bool shadows = f.flags.z != 0;

	if (!underwater) {
		float D = min(dist, p.shape.y);
		float mist = p.medium.y;
		// canopy mist: the humid air under trees and overhangs, around the camera's height (not the sea's)
		float local = p.light.w * canopy;
		int nNear = int(p.extra.w);
		// the canopy mist (density e^(-t/R) from the camera) gets its own linear march over its first 2.5 R (~92 % of it):
		// the main march's quadratic spacing put only ~5 of its samples there, too few to resolve the leaf gaps into beams
		float localMain = nNear > 0 ? 0.0 : local;
		float sumOpen = 0.0, sumOpenLit = 0.0, sumLocal = 0.0, sumLocalAll = 0.0, trans = 1.0, prevB = 0.0;
		float invN = 1.0 / float(n);
		for (int i = 0; i < n; i++) {
			float u1 = float(i + 1) * invN, u = (float(i) + xi) * invN;
			float b1 = D * u1 * u1, t = D * u * u, dt = b1 - prevB;
			prevB = b1;
			float y = dir.y * t;
			float sOpen = p.medium.x * exp(-max(y - p.haze.x, 0.0) / p.haze.y) + mist * exp(-max(y - p.medium.z, 0.0) / p.medium.w);
			float sLocal = localMain * vlMistDensity(t, y, p);
			float sigma = sOpen + sLocal;
			float v = shadows ? vlShadow(map, cmp, c0o + c0d * t, c1o + c1d * t) : 1.0;
			// energy-conserving segment integral: (1 - e^-sigma dt) / sigma, times the transmittance so far, split by layer
			float seg = trans * (1.0 - exp(-sigma * dt)) / max(sigma, 1e-9);
			sumOpen += sOpen * seg;
			sumOpenLit += v * sOpen * seg;
			sumLocal += v * sLocal * seg;
			sumLocalAll += sLocal * seg;
			trans *= exp(-(sOpen + sLocal * p.mist.w) * dt);
		}
		if (nNear > 0 && local > 1e-7) {
			float Dn = min(D, 2.5 * p.mist.x), dtn = Dn / float(nNear), tl = 1.0;
			// M2.2 (sunPx.w bit 8): each texel's own offset (IGN + R1). With the march's shared per-frame offset every ray of a
			// neighbourhood crossed the leaf shadows at the same depths, so the light of the whole neighbourhood rose and fell
			// together: the temporal clamp, built from the neighbours, followed that noise and the beams and the veil over the sky
			// flickered (the carve's 1 / (1 - k) gain made it worse). Its few steps stay within the near cascade around the camera.
			float xn = (int(p.sunPx.w) & 8) != 0 ? vlNoise(in.position.xy + 7.0, p.temporal.z) : fract(xi + 0.5);
			for (int j = 0; j < nNear; j++) {
				float t = (float(j) + xn) * dtn;
				float sLocal = local * vlMistDensity(t, dir.y * t, p);
				float v = shadows ? vlShadow(map, cmp, c0o + c0d * t, c1o + c1d * t) : 1.0;
				float seg = tl * (1.0 - exp(-sLocal * dtn)) / max(sLocal, 1e-9);
				sumLocal += v * sLocal * seg;
				sumLocalAll += sLocal * seg;
				tl *= exp(-sLocal * p.mist.w * dtn);
			}
		}
		// The open-air haze's sun glow is already in the sky and M1's aerial haze: keep only what the shadows carve out of
		// it (the lit part above a share of its unshadowed value) so shafts read as structure, not as a milky veil. The
		// canopy mist (ours alone) counts fully. Each layer has its art gate.
		float openPart = max(sumOpenLit - p.mistAmb.w * sumOpen, 0.0);
		float k = p.carve.x;
		sumLocal = max(sumLocal - k * sumLocalAll, 0.0) / (1.0 - k);
		scatter = p.light.rgb * (3.14159265 * vlPhase(mu, p) * (openPart * p.gate.x + sumLocal * p.gate.y) * p.shape.x);
	} else {
		// Under water: the medium is the water; sunlight reaches each point through the water above it (extinction along the
		// refracted-ish path to the surface) and arrives patterned by the surface's caustics, which stay constant along the
		// light direction: curtains of light (shafts) that sway with the waves.
		float D = min(dist, p.water2.z);
		float3 ext = p.water.rgb;
		float3 sum = float3(0.0), sumAll = float3(0.0);
		int nw = n + 2;
		float prevB = 0.0, invN = 1.0 / float(nw);
		half4 ta, tb;
		half ta4, tb4;
		vlCausticPhases(p.temporal.w, ta, ta4, tb, tb4);
		float ly = max(L.y, 0.12);
		// M2.2 (sunPx.w bit 16): each texel's own offset, like the canopy mist's steps (the curtains flickered under the
		// shared per-frame offset)
		float xw = (int(p.sunPx.w) & 16) != 0 ? vlNoise(in.position.xy + 13.0, p.temporal.z) : xi;
		for (int i = 0; i < nw; i++) {
			float u1 = float(i + 1) * invN, u = (float(i) + xw) * invN;
			float b1 = D * u1 * u1, t = D * u * u, dt = b1 - prevB;
			prevB = b1;
			float3 pt = dir * t;
			float below = max(p.water2.x - pt.y, 0.0);
			float path = below / ly;
			float2 q = pt.xz + L.xz * path + f.cameraFract.xz + fmod(f.cameraBlock.xz, 256.0);
			// the waves focus the light over a few blocks (no pattern right under the surface), then it spreads out again
			half hb = half(min(below, 60.0));
			half c = mix(1.0h, vlCaustic(q, ta, ta4, tb, tb4), half(p.water2.w) * (1.0h - exp2(-0.5771h * hb)) * exp2(-0.0721h * hb));
			float v = shadows ? vlShadow(map, cmp, c0o + c0d * t, c1o + c1d * t) : 1.0;
			float3 tr = float3(exp2(half3(-1.442695 * ext * min(path + t, 200.0)))) * (v * dt);
			sum += tr * float(c);
			sumAll += tr;
		}
		// carve (M2): drop part of the uniform glow so the caustic curtains read as shafts
		sum = max(sum - p.carve.y * sumAll, 0.0) / (1.0 - p.carve.y);
		float phase = 0.75 * vlHG(mu, 0.65) + 0.25 * 0.0795775;
		scatter = p.light.rgb * sum * (p.water.w * 3.14159265 * phase * p.shape.x) * float(L.y > 0.0);
	}

	// Crepuscular streaks: march toward the light over last frame's sky mask (VL resolution, one byte per texel: a sixteenth
	// of the bytes of the full-res depth, cache-friendly); sky seen along the way lets light through. Skipped on a reset.
	if (p.crep.x > 0.0 && p.crep.z > 0.0 && !underwater && p.temporal.y < 0.5) {
		float2 toV = (p.sunPx.xy - fpos) / scale, fv = in.position.xy;
		int taps = int(p.crep.y);
		float open = 0.0, wsum = 0.0, w = 1.0;
		// M2.2 (sunPx.w bit 4): each texel's own tap offset (IGN + R1) and a bilinear mask: the shared per-frame offset moved
		// every tap of a neighbourhood across the leaf holes together, so the neighbourhood clamp could not see that noise and
		// the dark sky rays and the streaks changed every frame; the mask is small (R8 at VL resolution), so the scattered
		// taps stay in cache
		bool smoothTaps = (int(p.sunPx.w) & 4) != 0;
		float xc = smoothTaps ? vlNoise(in.position.xy, p.temporal.z) : xi;
		constexpr sampler maskLin(filter::linear, address::clamp_to_edge);
		float2 maskTexel = 1.0 / float2(prevSky.get_width(), prevSky.get_height());
		for (int k = 0; k < taps; k++) {
			float s = (float(k) + xc) / float(taps);
			float2 q = fv + toV * (s * s);
			if (all(q >= 0.0) && all(q < p.size.xy)) {
				open += (smoothTaps ? float(prevSky.sample(maskLin, clamp(q, 0.5, p.size.xy - 0.5) * maskTexel).r)
				                    : float(prevSky.read(uint2(q)).r)) * w;
			}
			wsum += w;
			w *= 0.92;
		}
		open /= max(wsum, 1e-4);
		float ang = saturate(mu);
		float fall = pow(ang, p.crep.w);
		if (d <= 0.0) {
			// the sky: the sky model already has the sun's glow; the streaks there are the shadows of the clouds and hills
			// between the sun and the air (Photon's dark rays across the aureole). Negative light: the final pass subtracts it
			// (floored at a share of the pixel), so open sky keeps its colour
			if (VL_HDR) scatter -= p.light.rgb * ((1.0 - open) * fall * p.crep.x * p.crep.z * 1.5);
			else scatter += p.light.rgb * (open * fall * p.crep.x * p.crep.z * 0.8);
		} else {
			// in front of near geometry (a trunk against the low sun gets streaks across it), fading in over the first blocks:
			// only light coming through gaps (above the typical openness), not a uniform glow
			float reach = saturate((dist + 6.0) / 48.0);
			float gaps = VL_HDR ? saturate((open - 0.25) / 0.75) : open;
			scatter += p.light.rgb * (gaps * fall * p.crep.x * p.crep.z * reach);
		}
	}
	VlMarchOut o;
	o.light = half4(half3(clamp(scatter, -30000.0, 30000.0)), half(dist));
	o.sky = d <= 0.0 ? 1.0h : 0.0h;
	return o;
}

// ---- temporal -------------------------------------------------------------------------------------------------------------
static inline half4 vlTemporal(VlOut in, constant ShadeFrame &f, constant VlParams &p, texture2d<half> raw, texture2d<half> history) {
	constexpr sampler lin(filter::linear, address::clamp_to_edge);
	int2 px = int2(in.position.xy);
	int2 hi = int2(p.size.xy) - 1;
	half4 c = raw.read(uint2(px));
	float d = float(c.a);
	float3 sum = float3(c.rgb) * 2.0;
	float wsum = 2.0;
	float3 mn = float3(c.rgb), mx = mn;
	// a plus of 5 taps with a tight depth weight (a 3x3 smoothed the beams away; the history does the denoising)
	const int2 taps[4] = {int2(1, 0), int2(-1, 0), int2(0, 1), int2(0, -1)};
	for (int k = 0; k < 4; k++) {
		half4 s = raw.read(uint2(clamp(px + taps[k], int2(0), hi)));
		float w = exp(-abs(float(s.a) - d) / (0.03 * d + 0.3));
		sum += float3(s.rgb) * w;
		wsum += w;
		if (w > 0.35) {
			mn = min(mn, float3(s.rgb));
			mx = max(mx, float3(s.rgb));
		}
	}
	// M2.2: the clamp box over the full 3x3 again (as before M2's review), only the filter stays a plus: the plus's box
	// was so tight that the history snapped to each frame's noise (shafts flickering at leaf edges)
	if ((int(p.sunPx.w) & 1) != 0) {
		const int2 diag[4] = {int2(1, 1), int2(-1, 1), int2(1, -1), int2(-1, -1)};
		for (int k = 0; k < 4; k++) {
			half4 s = raw.read(uint2(clamp(px + diag[k], int2(0), hi)));
			if (abs(float(s.a) - d) < 1.05 * (0.03 * d + 0.3)) { // the plus's depth tolerance (its w > 0.35)
				mn = min(mn, float3(s.rgb));
				mx = max(mx, float3(s.rgb));
			}
		}
	}
	float3 cur = sum / wsum;
	if (p.temporal.y > 0.5) return half4(half3(cur), half(d));
	// reproject: this pixel's point in last frame's clip space
	float2 scale = p.size.zw / p.size.xy;
	float2 fpos = floor(in.position.xy * scale) + 0.5;
	float2 ndc = fpos / p.size.zw * 2.0 - 1.0;
	float3 dir = vlDirection(ndc, f);
	bool sky = d >= 9000.0;
	float4 pc = sky ? f.prevViewProj * float4(dir, 0.0) : f.prevViewProj * float4(dir * d, 1.0);
	if (pc.w <= 1e-4) return half4(half3(cur), half(d));
	float2 puv = (pc.xy / pc.w) * 0.5 + 0.5;
	if (any(puv < 0.0) || any(puv > 1.0)) return half4(half3(cur), half(d));
	// the history covers the live part of its target that last frame used (TAAU dynamic resolution: carve.zw)
	float2 hs = p.carve.z > 0.0 ? p.carve.zw : float2(1.0);
	float2 hdim = float2(history.get_width(), history.get_height());
	float2 hTexel = 0.5 / hdim;
	float dPrev = sky ? 1e4 : length(dir * d + p.camDelta.xyz);
	float tol = 0.08 * dPrev + 0.6;
	half4 h;
	bool valid;
	if ((int(p.sunPx.w) & 2) != 0) {
		// M2.2: the bilinear footprint's texels at this point's distance only. A plain bilinear fetch blended the distances of a
		// leaf and the sky behind it into one that matched neither, so every texel near a leaf edge lost its history and showed
		// this frame's raw march; under TAAU the jitter moves the leaf edges by a sub-pixel every frame, so the texels along
		// them alternated leaf/sky and their light jumped between the two raw values (shafts flickering at the leaf edges).
		float2 hp = clamp(puv * hs, hTexel, hs - hTexel) * hdim - 0.5;
		float2 hfl = floor(hp), hfr = hp - hfl;
		int2 hb = int2(hfl), hhi = int2(hs * hdim + 0.5) - 1;
		float4 acc = 0.0;
		float wacc = 0.0;
		for (int j = 0; j <= 1; j++) {
			for (int i = 0; i <= 1; i++) {
				half4 s = history.read(uint2(clamp(hb + int2(i, j), int2(0), hhi)));
				float bw = (i == 0 ? 1.0 - hfr.x : hfr.x) * (j == 0 ? 1.0 - hfr.y : hfr.y) + 1e-4;
				if (abs(float(s.a) - dPrev) < tol) {
					acc += float4(s) * bw;
					wacc += bw;
				}
			}
		}
		valid = wacc > 0.0;
		h = half4(acc / max(wacc, 1e-6));
	} else {
		h = history.sample(lin, clamp(puv * hs, hTexel, hs - hTexel));
		valid = abs(float(h.a) - dPrev) < tol;
	}
	if (!valid) return half4(half3(cur), half(d));
	// widen the box a little (the march is noisy; a tight box would flicker)
	float3 mid = 0.5 * (mn + mx), ext = 0.5 * (mx - mn) * 1.35 + 0.002 * (abs(mid) + 0.001);
	// Still view (M2.2): the march's jitter is coherent (one step offset for the whole frame, so neighbouring rays sample the same
	// depths and the shadow fetches stay in cache): its error is the same for a texel and its neighbours, so the box above,
	// built from this frame's neighbours, moves with it and clamps the history onto this frame's noise. The shafts through
	// leaf gaps, the dark sky rays and the screen-space streaks then changed every frame (flicker, wobble). Where the history
	// lands on this texel (the reprojection moved it less than ~1 VL pixel) the box widens by a share of its mean, so the
	// history can average the jitter out; under motion the plain box (no ghosting).
	if (p.sunPx.z > 0.0) {
		float2 moved = puv * p.size.zw / scale - in.position.xy;
		float still = 1.0 - smoothstep(0.75, 2.0, length(moved));
		ext += still * p.sunPx.z * (abs(mid) + 0.5 * (mx - mn));
	}
	float3 hc = clamp(float3(h.rgb), mid - ext, mid + ext);
	return half4(half3(mix(cur, hc, p.temporal.x)), half(d));
}

fragment half4 vl_temporal(VlOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant VlParams &p [[buffer(0)]],
                           texture2d<half> raw [[texture(0)]], texture2d<half> history [[texture(1)]]) {
	return vlTemporal(in, f, p, raw, history);
}

// M2 "fold" (ShadePost.setVolumetric): the temporal pass also writes what the final pass blends in, at VL resolution:
// rgb = in-scattered light incl. the canopy mist's sky-lit veil (radiance before exposure), a = the canopy mist's
// transmittance T (analytic, per VL texel; the final pass upsamples it depth-aware with the history's distances).
struct VlFoldOut {
	half4 history [[color(0)]];
	half4 lightT [[color(1)]];
};

fragment VlFoldOut vl_temporal_fold(VlOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant VlParams &p [[buffer(0)]],
                                    constant VlState &st [[buffer(1)]], texture2d<half> raw [[texture(0)]], texture2d<half> history [[texture(1)]]) {
	VlFoldOut o;
	o.history = vlTemporal(in, f, p, raw, history);
	float3 light = float3(o.history.rgb);
	float T = 1.0;
	float canopy = p.gate.z >= 0.0 ? p.gate.z : st.canopy.x;
	float mistK = p.light.w * canopy * p.mist.w * p.mist.z;
	if (mistK > 1e-5 && p.water2.y < 0.5) {
		float2 fpos = floor(in.position.xy * (p.size.zw / p.size.xy)) + 0.5;
		float2 ndc = fpos / p.size.zw * 2.0 - 1.0;
		T = exp2(-1.442695 * mistK * vlMistDepth(vlDirection(ndc, f), min(float(o.history.a), p.shape.y), p));
		light += p.mistAmb.rgb * (1.0 - T);
	}
	o.lightT = half4(half3(min(light, 30000.0)), half(T));
	return o;
}

// ---- composite ------------------------------------------------------------------------------------------------------------
// Full resolution, so everything here is half precision (twice the ALU rate on Apple GPUs) and transcendental-light.
// Exposed radiance -> sRGB: the hue-preserving part of M1's output curve (shade_common.metal shadeOutput, without the night
// shift and without its per-channel blend toward white above 0.6: the composite only adds the *difference* of two of these,
// so the pixel keeps M1's exact value and only the added light is shaped by this simpler curve; 0.2 ms less at native).
static inline half3 vlForward(half3 c) {
	half m = max(max(c.r, c.g), max(c.b, 1e-4h));
	half y = 1.0h - exp2(-1.5869637h * m);
	half tm = y * y * 1.006h / (y + 0.006h);
	return shadeToSrgb(saturate(c * (tm / m)));
}

// sRGB -> exposed radiance: the inverse of the hue-preserving part of vlForward (the brightest channel through the inverse
// curve, the others keep their ratio). Approximate near white, where the per-channel blend starts; the composite only uses
// it through a difference of two forward curves, which cancels the error where little light is added.
static inline half3 vlInverse(half3 s) {
	half3 lin = shadeToLinear(s);
	half m = max(max(lin.r, lin.g), max(lin.b, 1e-4h));
	// y^2 * 1.006 / (y + 0.006) = m  ->  y = (m + sqrt(m^2 + 4 * 1.006 * 0.006 m)) / (2 * 1.006)
	half y = min((m + sqrt(m * m + 0.024144h * m)) * 0.4970179h, 0.996h);
	half x = -log2(1.0h - y) * 0.6301338h;
	return lin * (x / m);
}

// Depth-aware upsample of the VL history to full-res pixel pos (distance dist): bilinear x depth similarity (a cheap
// rational falloff instead of exp).
static inline half3 vlUpsample(texture2d<half> vl, float2 pos, float dist, constant VlParams &p) {
	float2 lp = pos * (p.size.xy / p.size.zw) - 0.5;
	float2 fl = floor(lp);
	half2 fr = half2(lp - fl);
	int2 base = int2(fl), hi = int2(p.size.xy) - 1;
	half invTol = half(1.0 / (0.04 * dist + 0.15));
	half hd = half(min(dist, 3e4));
	half3 sum = 0.0h, bestC = 0.0h;
	half wsum = 0.0h, best = 6e4h, bestD = 1.0h;
	for (int j = 0; j <= 1; j++) {
		for (int i = 0; i <= 1; i++) {
			half4 s = vl.read(uint2(clamp(base + int2(i, j), int2(0), hi)));
			half bw = (i == 0 ? 1.0h - fr.x : fr.x) * (j == 0 ? 1.0h - fr.y : fr.y) + 1e-3h;
			half diff = abs(s.a - hd);
			half x = diff * invTol;
			half w = bw / (1.0h + x * x * (1.0h + x)); // ~ e^-x near 0, falls faster beyond
			sum += s.rgb * w;
			wsum += w;
			if (diff < best) {
				best = diff;
				bestC = s.rgb;
				bestD = s.a;
			}
		}
	}
	if (wsum > 0.02h) return sum / wsum;
	// no low-res sample at this depth (a thin leaf against the sky): the closest one, scaled by how much air this pixel's
	// ray actually crosses (in-scattering grows with the marched length) so foliage edges get no halo
	half maxD = half(p.shape.y);
	return bestC * saturate((min(hd, maxD) + 0.5h) / (min(bestD, maxD) + 0.5h));
}

// The light added at full-resolution pixel px (pre-exposed: x exposure) and the canopy mist's transmittance T.
static inline half3 vlCompositeLight(float2 pos, float dist, float2 ndc, float exposure, constant ShadeFrame &f, constant VlParams &p,
                                     constant VlState &st, texture2d<half> vl, thread half &T) {
	half3 light = max(vlUpsample(vl, pos, dist, p), 0.0h) * half(exposure); // (the sky's negative streaks are for the fold only)
	// the canopy mist dims what is behind it (full resolution, analytic: crisp at every edge) toward its sky-lit colour;
	// the sun-lit part of it is in the march
	T = 1.0h;
	float canopy = p.gate.z >= 0.0 ? p.gate.z : st.canopy.x;
	float mistK = p.light.w * canopy * p.mist.w * p.mist.z;
	if (mistK > 1e-5 && p.water2.y < 0.5) {
		T = half(exp2(-1.442695 * mistK * vlMistDepth(vlDirection(ndc, f), min(dist, p.shape.y), p)));
		light += half3(p.mistAmb.rgb * exposure) * (1.0h - T);
	}
	return light;
}

// M1 (LDR main target, programmable blending): the light added before M1's output curve, exposure ShadeFrame.time.z.
fragment half4 vl_composite(VlOut in [[stage_in]], half4 dst [[color(0)]], constant ShadeFrame &f [[buffer(16)]],
                             constant VlParams &p [[buffer(0)]], constant VlState &st [[buffer(1)]], texture2d<half> vl [[texture(0)]],
                             depth2d<float> depth [[texture(1)]]) {
	uint2 px = uint2(in.position.xy);
	float2 ndc = in.position.xy / p.size.zw * 2.0 - 1.0;
	float dist = vlDistance(ndc, depth.read(px), f);
	half T;
	half3 light = vlCompositeLight(in.position.xy, dist, ndc, f.time.z, f, p, st, vl, T);
	if (p.gate.w == 1.0) return half4(vlForward(light), 1.0h);   // debug: the light alone
	if (p.gate.w == 2.0) return half4(half3(saturate(dist / p.shape.y)), 1.0h); // debug: marched distance
	if (p.gate.w == 3.0) return half4(half3(T), 1.0h);                 // debug: canopy mist transmittance
	// below ~half an 8-bit step anywhere on the curve: leave the pixel alone (open plains at noon: most of the screen)
	if (max(light.r, max(light.g, light.b)) < half(1.0 / 1024.0) && T > 0.999h) return dst;
	half3 e = vlInverse(dst.rgb);
	half3 o = dst.rgb + (vlForward(e * T + light) - vlForward(e));
	// dither (triangular, +-1 LSB) so smooth shafts don't band in 8 bits
	float n0 = vlNoise(in.position.xy, p.temporal.z), n1 = vlNoise(in.position.xy + 17.0, p.temporal.z + 3.0);
	o += half(n0 + n1 - 1.0) * (1.0h / 255.0h);
	return half4(saturate(o), dst.a);
}

// M2 (HDR world target, before bloom and the tone map): returns (light, T) for the fixed-function blend
// src * One + dst * SourceAlpha = scene * T + light, in pre-exposed radiance (ShadeGpu.exposure.x). No read of the target
// (RG11B10F or RGBA16F), so no programmable-blending dependency: a plain full-resolution blend.
fragment half4 vl_composite_hdr(VlOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant ShadeGpu &g [[buffer(18)]],
                                 constant VlParams &p [[buffer(0)]], constant VlState &st [[buffer(1)]], texture2d<half> vl [[texture(0)]],
                                 depth2d<float> depth [[texture(1)]]) {
	uint2 px = uint2(in.position.xy);
	float2 ndc = in.position.xy / p.size.zw * 2.0 - 1.0;
	float dist = vlDistance(ndc, depth.read(px), f);
	half T;
	half3 light = vlCompositeLight(in.position.xy, dist, ndc, g.exposure.x, f, p, st, vl, T);
	if (p.gate.w == 1.0) return half4(light, 0.0h);                                    // debug: the light alone
	if (p.gate.w == 2.0) return half4(half3(saturate(dist / p.shape.y) * 0.5), 0.0h);  // debug: marched distance
	if (p.gate.w == 3.0) return half4(half3(T * 0.5h), 0.0h);                          // debug: canopy mist transmittance
	return half4(min(light, half3(30000.0h)), T);
}

#endif
