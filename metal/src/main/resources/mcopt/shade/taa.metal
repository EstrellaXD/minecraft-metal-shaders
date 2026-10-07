// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Catmull-Rom history sampling (taaCatmullRom) after Matt Pettineo (MJP), "Bicubic texture filtering with the Catmull-Rom
// spline in 9 / 5 taps" (https://gist.github.com/TheRealMJP/c83b8c0f46b63f3a88a5986f4fa982b1), MIT License; the 5-tap
// variant (corners dropped and renormalised) follows Jimenez, "Filmic SMAA", SIGGRAPH 2016. Everything else here is
// original, built from public techniques: Karis 2014 (YCoCg neighbourhood rectification, luma-weighted resolve), Salvi 2016
// (variance clipping), Yang et al. 2020 (confidence-weighted temporal upsampling).
//
// mcopt native shading: temporal anti-aliasing and upscaling (TAAU, shader bible E18), compiled natively by mctaa.m.
//
// taau_fs runs once per OUTPUT pixel (native resolution) as a fragment pass into two compressible targets:
//   color(0) RGBA16Float: the resolved colour (rgb, in the input's encoding) and the pixel's history age in frames (a);
//   color(1) R16Float:    linear view depth (clip w, blocks) of the nearest input sample, for next frame's disocclusion test.
// Both are ping-ponged by mctaa.m: next frame reads them as `hist` / `histDepth`.
// taa_output_fs then copies the resolve into the game's main target with a contrast-limited sharpen.
//
// Conventions (same as every mcopt target): rows are in GL order (texture row 0 = bottom of the picture), so a pixel
// centre (x + 0.5, row + 0.5) of a W x H target is NDC ((x + 0.5) / W * 2 - 1, (row + 0.5) / H * 2 - 1) and Metal
// texture coordinates need no flip. Depth is reversed Z in [0, 1] (0 = far plane / sky).
//
// The INPUT is the jittered world render: a viewport sub-rect (0, 0, input.xy) of textures that may be larger (dynamic
// resolution renders into fixed-size targets). The scene is drawn shifted by `jitter.xy` input pixels (the projection's
// sub-pixel offset, Halton 2,3), so the input pixel centred at c + 0.5 shows the unjittered scene point c + 0.5 - jitter,
// and output pixel centre q maps to unjittered input position q * ratio.
#include <metal_stdlib>
using namespace metal;

struct TaaParams {
	float4x4 invCur;  //   0  this frame's unjittered clip -> camera-relative world
	float4x4 prevVP;  //  64  camera-relative world (this frame's camera) -> last frame's unjittered clip
	float4 curRowW;   // 128  4th row of this frame's unjittered viewProj (clip w = linear view depth, blocks)
	float4 input;     // 144  xy active input size (px), zw unused
	float4 output;    // 160  xy output size (px), zw 1 / output size
	float4 jitter;    // 176  xy this frame's jitter (input px), zw input px per output px (input.xy / output.xy)
	float4 blend;     // 192  x minimum blend weight of the current frame, y clip box size (sigmas), z history age cap, w 1 = no history
	float4 misc;      // 208  x history rescale (exposure now / then), y unused (HDR input: function constant TAA_HDR), z reactive strength, w debug view (taau_debug_fs)
	float4 sharpen;   // 224  x strength (0 off), y current-frame weight floor in motion (> 1.5 px/frame), zw unused
};

struct TaaVOut { float4 position [[position]]; };

// One triangle covering the target.
vertex TaaVOut taa_vs(uint id [[vertex_id]]) {
	float2 p = float2((id << 1) & 2, id & 2);
	TaaVOut o;
	o.position = float4(p * 2.0 - 1.0, 0.0, 1.0);
	return o;
}

// ---- colour space ---------------------------------------------------------------------------------------------------
// TAA_HDR (function constant 0, set by mctaa.m): the input is linear HDR (M2's pre-exposed scene), so samples blend in a
// range-compressed space (c / (1 + luma), Karis 2014) where one very bright sample can't dominate a neighbourhood and
// flicker; reversible. Off (M1's tonemapped RGBA8 input), colours are already bounded and blend as they are.
constant bool TAA_HDR [[function_constant(0)]];
constant bool taaHdr = is_function_constant_defined(TAA_HDR) && TAA_HDR;
static inline half3 taaCompress(half3 c) {
	return taaHdr ? c / (1.0h + dot(c, half3(0.2126h, 0.7152h, 0.0722h))) : c;
}
static inline float3 taaExpand(float3 c) {
	return taaHdr ? c / max(1.0 - dot(c, float3(0.2126, 0.7152, 0.0722)), 1e-4) : c;
}

// ---- history fetch: Catmull-Rom in 5 bilinear taps (MJP, MIT; see the header) ------------------------------------------
// Positions in float (they span the whole output), weights and colours in half (this pass is bound by ALU and latency, not
// bandwidth: tools/shade/taatest perf). centreA: the alpha of the centre tap, i.e. the bilinear history age, so the age
// needs no fetch of its own.
static inline half3 taaCatmullRom(texture2d<half> tex, sampler s, float2 uv, float2 size, float2 invSize, thread half &centreA) {
	float2 pos = uv * size;
	float2 t1 = floor(pos - 0.5) + 0.5;     // centre of the texel at grid index 1
	half2 f = half2(pos - t1);
	half2 w0 = f * (-0.5h + f * (1.0h - 0.5h * f));
	half2 w1 = 1.0h + f * f * (-2.5h + 1.5h * f);
	half2 w2 = f * (0.5h + f * (2.0h - 1.5h * f));
	half2 w3 = f * f * (-0.5h + 0.5h * f);
	half2 w12 = w1 + w2;
	float2 t0 = (t1 - 1.0) * invSize, t3 = (t1 + 2.0) * invSize, t12 = (t1 + float2(w2 / w12)) * invSize;
	half4 cc = tex.sample(s, t12);
	centreA = cc.a;
	half3 c = tex.sample(s, float2(t12.x, t0.y)).rgb * (w12.x * w0.y)
		+ tex.sample(s, float2(t0.x, t12.y)).rgb * (w0.x * w12.y)
		+ cc.rgb * (w12.x * w12.y)
		+ tex.sample(s, float2(t3.x, t12.y)).rgb * (w3.x * w12.y)
		+ tex.sample(s, float2(t12.x, t3.y)).rgb * (w12.x * w3.y);
	half wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
	return max(c / wsum, 0.0h); // the spline's negative lobes can ring below 0 at hard edges
}

struct TaaOut {
	half4 color [[color(0)]];
	half depth [[color(1)]];
};

// The resolve of output pixel centre q; debug (mcopt.shade.taaDebug views) gets the colour to show instead, so the
// history itself is never polluted by a debug view.
static inline TaaOut taauResolve(float2 q, constant TaaParams &p, texture2d<half> cur, depth2d<float> curDepth, texture2d<half> hist,
                                 texture2d<half> histDepth, texture2d<half> reactive, thread half3 &debug)
{
	constexpr sampler linear(filter::linear, address::clamp_to_edge);
	float2 pin = q * p.jitter.zw + p.jitter.xy;        // that scene point in the jittered input raster (pixel units)
	int2 base = int2(floor(pin));
	int2 last = int2(p.input.xy) - 1;

	// The 3x3 input neighbourhood: a reconstruction of this frame's colour at q (Gaussian fit of Blackman-Harris, radius
	// ~1 input pixel, separable so 6 exponentials instead of 9), and its moments for rectifying the history (in RGB: YCoCg
	// tested no better on tools/shade/taatest and costs ALU, which this pass is bound by; a 5-tap cross instead of 3x3 was
	// 0.06 ms cheaper but flickered 20-25% more in motion).
	half2 off = half2(float2(base) + 0.5 - pin);        // offset of the base texel's centre from pin, in (-1, 0]
	half3 ox = half3(off.x - 1.0h, off.x, off.x + 1.0h), oy = half3(off.y - 1.0h, off.y, off.y + 1.0h);
	half3 wx = exp(-2.29h * ox * ox), wy = exp(-2.29h * oy * oy);
	half3 sum = 0.0h, m1 = 0.0h, m2 = 0.0h;
	for (int dy = 0; dy < 3; dy++) {
		int y = clamp(base.y + dy - 1, 0, last.y);
		for (int dx = 0; dx < 3; dx++) {
			half3 c = taaCompress(cur.read(uint2(clamp(base.x + dx - 1, 0, last.x), y)).rgb);
			sum += c * (wx[dx] * wy[dy]);
			m1 += c;
			m2 += c * c;
		}
	}
	// nearest of the 2x2 input depths around pin (one gather; reversed Z: the largest value is the nearest surface)
	constexpr sampler point(filter::nearest, address::clamp_to_edge);
	float4 z4 = curDepth.gather(point, pin / float2(curDepth.get_width(), curDepth.get_height()));
	float zmax = max(max(z4.x, z4.y), max(z4.z, z4.w));
	half wsum = (wx.x + wx.y + wx.z) * (wy.x + wy.y + wy.z);
	half wnear = max3(wx.x, wx.y, wx.z) * max3(wy.x, wy.y, wy.z);
	half3 current = sum / wsum;
	half3 mean = m1 * (1.0h / 9.0h);
	half3 sigma = sqrt(max(m2 * (1.0h / 9.0h) - mean * mean, 0.0h));

	// Camera motion from depth: this pixel's (nearest) surface in last frame's clip space. Static world only; moving
	// entities fall back on the rectification (and later per-object motion, see TAAU.md).
	float2 ndc = q * p.output.zw * 2.0 - 1.0;
	float4 h = p.invCur * float4(ndc, zmax, 1.0);
	float3 world = h.xyz / h.w;
	float4 prev = p.prevVP * float4(world, 1.0);
	float viewDepth = dot(p.curRowW, float4(world, 1.0));
	float2 uvPrev = prev.xy / prev.w * 0.5 + 0.5;

	TaaOut o;
	o.depth = half(clamp(viewDepth, 0.0, 65000.0));
	bool valid = p.blend.w < 0.5 && prev.w > 0.0 && all(uvPrev > 0.0) && all(uvPrev < 1.0);
	half age = 0.0h;
	half3 history = current;
	half disocclusion = 0.0h;
	if (valid) {
		uint2 hp = uint2(min(uvPrev * p.output.xy, p.output.xy - 1.0));
		// Disocclusion: the surface last frame saw there is not the one we reprojected (it was hidden, or it moved).
		float stored = float(histDepth.read(hp).r);
		float tolerance = 0.03 * prev.w + 0.1;
		disocclusion = half(saturate((abs(stored - prev.w) - tolerance) / tolerance));
		history = taaCompress(taaCatmullRom(hist, linear, uvPrev, p.output.xy, p.output.zw, age) * half(p.misc.x));
	}
	// Rectify: the history may only take colours this frame's neighbourhood could produce (variance box). It is clipped
	// toward the mean along the line between them, which (unlike a per-channel clamp) keeps the history's hue; outside = how
	// far outside the box it was, in box units. A still camera allows a wider box (p.blend.y is set wider by the CPU), which
	// keeps thin detail stable.
	half3 extent = half(p.blend.y) * sigma + 0.002h;
	half3 v = history - mean;
	half3 a = abs(v) / extent;
	half outside = max3(a.x, a.y, a.z);
	half3 clipped = outside > 1.0h ? mean + v / outside : history;
	age *= 1.0h - disocclusion;
	// Blend weight of this frame: running average over the pixel's age, never below the minimum (responsiveness), scaled by
	// the confidence that an input sample actually landed near this output pixel (upscaling: output pixels between input
	// samples rely on the history), raised where the history had to be clipped hard (something changed: anti-ghosting)
	// and where the reactive mask says the input is not reprojectable (particles, animated translucents).
	half alpha = age < 0.5h ? 1.0h : max(1.0h / (age + 1.0h), half(p.blend.x)) * mix(0.35h, 1.0h, wnear);
	alpha = max(alpha, saturate((outside - 1.0h) * 0.25h) * 0.5h);
	// Motion: every frame in motion resamples the history at a new sub-pixel offset, which slowly low-passes fine texture
	// (pixel-art detail smeared while strafing). A floor on the current frame's weight once the pixel moves more than ~1.5
	// px per frame trades a little stability for detail (taatest moving 0.67x: error vs truth 4.55 -> ~4.2).
	float motionPx = length(uvPrev * p.output.xy - q);
	alpha = max(alpha, half(p.sharpen.y) * half(saturate(motionPx * (1.0 / 1.5))));
	if (p.misc.z > 0.0) alpha = max(alpha, reactive.read(uint2(clamp(base, int2(0), int2(reactive.get_width(), reactive.get_height()) - 1))).r * half(p.misc.z));
	half3 resolved = mix(clipped, current, saturate(alpha));
	float3 rgb = taaExpand(float3(resolved));
	half newAge = min(age + 1.0h, half(p.blend.z));
	o.color = half4(half3(max(rgb, 0.0)), newAge);
	if (!all(isfinite(o.color))) o.color = half4(current, 1.0h);
	int view = int(p.misc.w);
	if (view == 1) debug = half3(mix(float3(1.0, 0.1, 0.0), float3(0.1, 1.0, 0.2), saturate(float(newAge) / p.blend.z)));   // history age
	else if (view == 2) debug = half3(saturate(float(outside) - 1.0), float(disocclusion), valid ? 0.0 : 1.0);           // rejection
	else if (view == 3) debug = half3(taaExpand(float3(current)));                                                       // no history
	else debug = o.color.rgb;
	return o;
}

fragment TaaOut taau_fs(TaaVOut in [[stage_in]], constant TaaParams &p [[buffer(0)]],
                        texture2d<half> cur [[texture(0)]], depth2d<float> curDepth [[texture(1)]],
                        texture2d<half> hist [[texture(2)]], texture2d<half> histDepth [[texture(3)]],
                        texture2d<half> reactive [[texture(4)]])
{
	half3 debug;
	return taauResolve(in.position.xy, p, cur, curDepth, hist, histDepth, reactive, debug);
}

// mcopt.shade.taaDebug: the same resolve, plus the debug view straight into the destination (no output pass).
struct TaaDebugOut {
	half4 color [[color(0)]];
	half depth [[color(1)]];
	half4 view [[color(2)]];
};

fragment TaaDebugOut taau_debug_fs(TaaVOut in [[stage_in]], constant TaaParams &p [[buffer(0)]],
                                   texture2d<half> cur [[texture(0)]], depth2d<float> curDepth [[texture(1)]],
                                   texture2d<half> hist [[texture(2)]], texture2d<half> histDepth [[texture(3)]],
                                   texture2d<half> reactive [[texture(4)]])
{
	half3 debug;
	TaaOut r = taauResolve(in.position.xy, p, cur, curDepth, hist, histDepth, reactive, debug);
	TaaDebugOut o;
	o.color = r.color;
	o.depth = r.depth;
	o.view = half4(debug, 1.0h);
	return o;
}

// The resolved image -> the game's main target, with a contrast-limited sharpen (original): an unsharp mask from the
// 4-neighbour cross, weaker where the local contrast is already high, and clamped to the neighbourhood's range so it never
// overshoots into halos. Restores the crispness of pixel-art textures the temporal resolve softens.
fragment half4 taa_output_fs(TaaVOut in [[stage_in]], constant TaaParams &p [[buffer(0)]], texture2d<half> resolved [[texture(0)]])
{
	int2 c = int2(in.position.xy);
	int2 last = int2(p.output.xy) - 1;
	half3 m = resolved.read(uint2(c)).rgb;
	half amount = half(p.sharpen.x);
	if (amount <= 0.0h) return half4(m, 1.0h);
	half3 n = resolved.read(uint2(clamp(c + int2(0, 1), int2(0), last))).rgb;
	half3 s = resolved.read(uint2(clamp(c - int2(0, 1), int2(0), last))).rgb;
	half3 e = resolved.read(uint2(clamp(c + int2(1, 0), int2(0), last))).rgb;
	half3 w = resolved.read(uint2(clamp(c - int2(1, 0), int2(0), last))).rgb;
	half3 lo = min(m, min(min(n, s), min(e, w)));
	half3 hi = max(m, max(max(n, s), max(e, w)));
	half range = dot(hi - lo, half3(0.2126h, 0.7152h, 0.0722h)) / max(dot(hi, half3(0.2126h, 0.7152h, 0.0722h)), 0.05h);
	half k = amount * 2.0h * (1.0h - smoothstep(0.15h, 0.9h, range));
	half3 o = clamp(m + (m - (n + s + e + w) * 0.25h) * k, lo, hi);
	return half4(o, 1.0h);
}
