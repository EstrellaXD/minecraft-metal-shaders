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
//               - LDR (M1, VL_HDR = false): adds the light *before* M1's output curve: the target's sRGB value is mapped
//                 back to exposed radiance through the inverse of shadeOutput's curve, the light (x exposure) is added, and
//                 the difference of the two forward curves is added to the pixel (so no light = the exact same pixel);
//                 programmable blending ([[color(0)]]) on the game's RGBA8 main target, blue-noise-ish dither against banding.
//               - HDR (M2, VL_HDR = true): returns the light x exposure for additive blending (One, One) into the
//                 pre-exposed RGBA16F scene target, before the tonemap. See VL.md "Integration".
// Bindings: ShadeFrame at buffer(16), shadow map texture(16) + compare sampler(12) (mc_shade_bind_*), VlParams at buffer(0),
// the canopy state at buffer(1); pass inputs at texture(0..1).

#ifndef MCOPT_SHADE_VL
#define MCOPT_SHADE_VL

constant bool VL_HDR [[function_constant(0)]];

// Per-frame parameters (mcopt.metal.shade.vl.ShadeVl packs exactly this layout; 16 x float4 = 256 bytes).
struct VlParams {
	float4 size;      //   0 xy VL target size (px), zw full-resolution target size (px)
	float4 shape;     //  16 x strength, y max march distance (blocks), z step count, w forward HG g
	float4 medium;    //  32 x haze sigma_s at its base (1/block), y mist sigma_s at its base, z mist base y (camera-relative), w mist scale height
	float4 haze;      //  48 x haze base y (camera-relative), y haze scale height, z isotropic share, w back-lobe share
	float4 light;     //  64 rgb light colour (linear irradiance / pi, art-graded, pre-exposure), w canopy boost of the mist
	float4 crep;      //  80 x screen-space ray strength (0 = off), y taps, z on-screen weight of the light, w angular falloff
	float4 sunPx;     //  96 xy the light's position in full-res target pixels (Metal rows), zw 0
	float4 water;     // 112 rgb extinction under water (1/block), w scattering (1/block)
	float4 water2;    // 128 x surface y (camera-relative), y underwater (0/1), z max distance under water, w caustic strength
	float4 temporal;  // 144 x history weight, y reset (1 = drop history), z frame index, w time (s, wraps)
	float4 camDelta;  // 160 xyz camera position now - last frame (blocks), w canopy smoothing factor this frame (0..1)
	float4 gate;      // 176 x open-air strength (canopy 0), y canopy strength (canopy 1), z canopy override (< 0: GPU value), w debug view
	float4 extra;     // 192 x canopy reset (1 = snap), y extinction scale (LDR composite), z 0, w 0
	float4 pad0;      // 208
	float4 pad1;      // 224
	float4 pad2;      // 240
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

// Procedural caustics on the water surface plane (original): a domain-warped sum of three sines whose zero set forms the
// bright web; sharp ridges, mean about 1. q in blocks, t in seconds.
static inline float vlCaustic(float2 q, float t) {
	float2 p = q * 0.42;
	p += 0.55 * float2(sin(p.y * 1.31 + t * 0.83), sin(p.x * 1.17 - t * 0.71));
	float a = sin(p.x * 1.9 + t * 0.9) + sin(p.y * 2.1 - t * 1.05) + sin((p.x - p.y) * 1.45 + t * 0.6);
	float r = saturate(1.0 - abs(a) * 0.55);
	r *= r;
	return 0.3 + 2.4 * r * r;
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
                      constant VlParams &p [[buffer(0)]], device VlState *state [[buffer(1)]]) {
	threadgroup float partial[2];
	// 64 points: two rings (radius 6 and 14 blocks), alternating 3 and 10 blocks above the eye, in the far cascade.
	float ring = tid < 32u ? 6.0 : 14.0;
	float h = (tid & 1u) != 0u ? 10.0 : 3.0;
	float a = float(tid & 31u) * (6.2831853 / 32.0) + (tid < 32u ? 0.0 : 0.098);
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
		float target = smoothstep(0.15, 0.6, frac);
		float old = state->canopy.x;
		float k = p.extra.x > 0.5 ? 1.0 : p.camDelta.w;
		state->canopy = float4(mix(old, target, k), frac, 0.0, 0.0);
	}
}

// ---- march ----------------------------------------------------------------------------------------------------------------
fragment half4 vl_march(VlOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant VlParams &p [[buffer(0)]],
                        constant VlState &st [[buffer(1)]], depth2d<float> depth [[texture(0)]],
                        depth2d_array<float> map [[texture(16)]], sampler cmp [[sampler(12)]]) {
	float2 scale = p.size.zw / p.size.xy;
	// the full-res pixel this VL pixel stands for (the same one the upsample assumes)
	uint2 fpx = uint2(min(floor(in.position.xy * scale), p.size.zw - 1.0));
	float2 fpos = float2(fpx) + 0.5;
	float2 ndc = fpos / p.size.zw * 2.0 - 1.0;
	float d = depth.read(fpx);
	float3 dir = vlDirection(ndc, f);
	float dist = vlDistance(ndc, d, f);
	float xi = vlNoise(in.position.xy, p.temporal.z);
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
		float mist = p.medium.y * (1.0 + p.light.w * canopy);
		float sum = 0.0, od = 0.0, prevB = 0.0;
		float invN = 1.0 / float(n);
		for (int i = 0; i < n; i++) {
			float u1 = float(i + 1) * invN, u = (float(i) + xi) * invN;
			float b1 = D * u1 * u1, t = D * u * u, dt = b1 - prevB;
			prevB = b1;
			float y = dir.y * t;
			float sigma = p.medium.x * exp(-max(y - p.haze.x, 0.0) / p.haze.y) + mist * exp(-max(y - p.medium.z, 0.0) / p.medium.w);
			float v = shadows ? vlShadow(map, cmp, c0o + c0d * t, c1o + c1d * t) : 1.0;
			float sdt = sigma * dt;
			// energy-conserving segment integral: (1 - e^-sigma dt) / sigma, times the transmittance so far
			sum += v * exp(-od) * (1.0 - exp(-sdt));
			od += sdt;
		}
		float strength = mix(p.gate.x, p.gate.y, canopy) * p.shape.x;
		scatter = p.light.rgb * (3.14159265 * vlPhase(mu, p) * sum * strength);
	} else {
		// Under water: the medium is the water; sunlight reaches each point through the water above it (extinction along the
		// refracted-ish path to the surface) and arrives patterned by the surface's caustics, which stay constant along the
		// light direction: curtains of light (shafts) that sway with the waves.
		float D = min(dist, p.water2.z);
		float3 ext = p.water.rgb;
		float3 sum = float3(0.0);
		float prevB = 0.0, invN = 1.0 / float(n);
		float ly = max(L.y, 0.12);
		for (int i = 0; i < n; i++) {
			float u1 = float(i + 1) * invN, u = (float(i) + xi) * invN;
			float b1 = D * u1 * u1, t = D * u * u, dt = b1 - prevB;
			prevB = b1;
			float3 pt = dir * t;
			float below = max(p.water2.x - pt.y, 0.0);
			float path = below / ly;
			float2 q = pt.xz + L.xz * path + f.cameraFract.xz + fmod(f.cameraBlock.xz, 256.0);
			float c = mix(1.0, vlCaustic(q, p.temporal.w), p.water2.w * exp(-below * 0.06));
			float v = shadows ? vlShadow(map, cmp, c0o + c0d * t, c1o + c1d * t) : 1.0;
			sum += exp(-ext * (path + t)) * (v * c * dt);
		}
		float phase = 0.75 * vlHG(mu, 0.65) + 0.25 * 0.0795775;
		scatter = p.light.rgb * sum * (p.water.w * 3.14159265 * phase * p.shape.x) * float(L.y > 0.0);
	}

	// Crepuscular streaks: march toward the light on the depth buffer; sky seen along the way lets light through.
	if (p.crep.x > 0.0 && p.crep.z > 0.0 && !underwater) {
		float2 to = p.sunPx.xy - fpos;
		int taps = int(p.crep.y);
		float open = 0.0, wsum = 0.0, w = 1.0;
		for (int k = 0; k < taps; k++) {
			float s = (float(k) + xi) / float(taps);
			float2 q = fpos + to * (s * s);
			if (all(q >= 0.0) && all(q < p.size.zw)) open += (depth.read(uint2(q)) <= 0.0 ? 1.0 : 0.0) * w;
			wsum += w;
			w *= 0.92;
		}
		open /= max(wsum, 1e-4);
		float ang = saturate(mu);
		float fall = pow(ang, p.crep.w);
		scatter += p.light.rgb * (open * fall * p.crep.x * p.crep.z * min(dist, p.shape.y) / p.shape.y);
	}
	return half4(half3(min(scatter, 30000.0)), half(dist));
}

// ---- temporal -------------------------------------------------------------------------------------------------------------
fragment half4 vl_temporal(VlOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant VlParams &p [[buffer(0)]],
                           texture2d<half> raw [[texture(0)]], texture2d<half> history [[texture(1)]]) {
	constexpr sampler lin(filter::linear, address::clamp_to_edge);
	int2 px = int2(in.position.xy);
	int2 hi = int2(p.size.xy) - 1;
	half4 c = raw.read(uint2(px));
	float d = float(c.a);
	float3 sum = float3(c.rgb) * 2.0;
	float wsum = 2.0;
	float3 mn = float3(c.rgb), mx = mn;
	for (int j = -1; j <= 1; j++) {
		for (int i = -1; i <= 1; i++) {
			if (i == 0 && j == 0) continue;
			half4 s = raw.read(uint2(clamp(px + int2(i, j), int2(0), hi)));
			float w = exp(-abs(float(s.a) - d) / (0.06 * d + 0.4)) * ((i == 0 || j == 0) ? 1.0 : 0.6);
			sum += float3(s.rgb) * w;
			wsum += w;
			if (w > 0.35) {
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
	half4 h = history.sample(lin, puv);
	float dPrev = sky ? 1e4 : length(dir * d + p.camDelta.xyz);
	bool valid = abs(float(h.a) - dPrev) < 0.08 * dPrev + 0.6;
	if (!valid) return half4(half3(cur), half(d));
	// widen the box a little (the march is noisy; a tight box would flicker)
	float3 mid = 0.5 * (mn + mx), ext = 0.5 * (mx - mn) * 1.25 + 0.002 * (mid + 0.001);
	float3 hc = clamp(float3(h.rgb), mid - ext, mid + ext);
	return half4(half3(mix(cur, hc, p.temporal.x)), half(d));
}

// ---- composite ------------------------------------------------------------------------------------------------------------
// Exposed radiance -> sRGB, M1's output curve without the night shift (shade_common.metal shadeOutput).
static inline float3 vlForward(float3 c) {
	float m = max(max(c.r, c.g), max(c.b, 1e-6));
	float tm = shadeCurve(m);
	float3 o = c * (tm / m);
	if (tm > 0.6) o = mix(o, shadeCurve3(c), smoothstep(0.6, 1.0, tm) * 0.6);
	return float3(shadeToSrgb(half3(saturate(o))));
}

// sRGB -> exposed radiance: the inverse of the hue-preserving part of vlForward (the brightest channel through the inverse
// curve, the others keep their ratio). Approximate near white, where the per-channel blend starts.
static inline float3 vlInverse(float3 s) {
	float3 lin = float3(shadeToLinear(half3(s)));
	float m = max(max(lin.r, lin.g), max(lin.b, 1e-6));
	// y^2 * 1.006 / (y + 0.006) = m  ->  y = (m + sqrt(m^2 + 4 * 1.006 * 0.006 m)) / (2 * 1.006)
	float y = min((m + sqrt(m * m + 0.024144 * m)) / 2.012, 0.996);
	float x = -log2(1.0 - y) / 1.5869637;
	return lin * (x / m);
}

// Depth-aware upsample of the VL history to full-res pixel px (distance dist).
static inline float3 vlUpsample(texture2d<half> vl, float2 pos, float dist, constant VlParams &p) {
	float2 lp = pos * (p.size.xy / p.size.zw) - 0.5;
	float2 fl = floor(lp), fr = lp - fl;
	int2 base = int2(fl), hi = int2(p.size.xy) - 1;
	float3 sum = float3(0.0);
	float wsum = 0.0, best = 1e9;
	float3 bestC = float3(0.0);
	float bestD = 1.0;
	for (int j = 0; j <= 1; j++) {
		for (int i = 0; i <= 1; i++) {
			half4 s = vl.read(uint2(clamp(base + int2(i, j), int2(0), hi)));
			float sd = float(s.a);
			float bw = (i == 0 ? 1.0 - fr.x : fr.x) * (j == 0 ? 1.0 - fr.y : fr.y) + 1e-3;
			float diff = abs(sd - dist);
			float w = bw * exp(-diff / (0.04 * dist + 0.15));
			sum += float3(s.rgb) * w;
			wsum += w;
			if (diff < best) {
				best = diff;
				bestC = float3(s.rgb);
				bestD = sd;
			}
		}
	}
	if (wsum > 0.02) return sum / wsum;
	// no low-res sample at this depth (a thin leaf against the sky): the closest one, scaled by how much air this pixel's
	// ray actually crosses (in-scattering grows with the marched length) so foliage edges get no halo
	return bestC * saturate((min(dist, p.shape.y) + 0.5) / (min(bestD, p.shape.y) + 0.5));
}

fragment float4 vl_composite(VlOut in [[stage_in]], float4 dst [[color(0)]], constant ShadeFrame &f [[buffer(16)]],
                             constant VlParams &p [[buffer(0)]], texture2d<half> vl [[texture(0)]], depth2d<float> depth [[texture(1)]]) {
	uint2 px = uint2(in.position.xy);
	float2 ndc = in.position.xy / p.size.zw * 2.0 - 1.0;
	float dist = vlDistance(ndc, depth.read(px), f);
	float3 light = vlUpsample(vl, in.position.xy, dist, p) * f.time.z;
	if (p.gate.w == 1.0) return float4(vlForward(light), 1.0);           // debug: the light alone
	if (p.gate.w == 2.0) return float4(float3(saturate(dist / p.shape.y)), 1.0); // debug: marched distance
	if (VL_HDR) return float4(light, 0.0);                                  // M2: additive into the pre-exposed HDR scene
	if (max(light.r, max(light.g, light.b)) < 1.0 / 2048.0) return dst;
	float3 e = vlInverse(dst.rgb);
	float3 o = dst.rgb + (vlForward(e + light) - vlForward(e));
	// dither (triangular, +-1 LSB) so smooth shafts don't band in 8 bits
	float n0 = vlNoise(in.position.xy, p.temporal.z), n1 = vlNoise(in.position.xy + 17.0, p.temporal.z + 3.0);
	o += (n0 + n1 - 1.0) / 255.0;
	return float4(saturate(o), dst.a);
}

#endif
