// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// M2: the art layer and the GPU-written state every shaded pipeline (and every pipeline drawn into the HDR world target)
// can read, plus the display transform ("Shade Filmic") and its inverse. Self-contained (only metal_stdlib): it is
// prepended to shade_common.metal, and on its own to the game's pipelines when they draw into the HDR target (their
// display-referred colours are converted to scene-linear there, see shadeLdrToHdr).
//
//   buffer(17)  constant ShadeLook&   CPU-written per frame (mcopt.metal.shade.ShadeLook packs this exact layout, 1024 B)
//   buffer(18)  ShadeGpu              GPU-written (auto-exposure); read by the next frame
//
// HDR CONVENTION (mcopt.shade.hdr=true): the world renders into an RGBA16Float target holding PRE-EXPOSED linear Rec.709
// radiance (scene radiance x ShadeGpu.exposure.x). The final pass (shade_post.metal) applies bloom, Purkinje, vignette and
// shadeDisplay(), writing sRGB into the game's RGBA8 main target before the hand and the GUI.
#ifndef MCOPT_SHADE_LOOK
#define MCOPT_SHADE_LOOK
#include <metal_stdlib>
using namespace metal;

struct ShadeLook {
	float4x4 viewProjNoJitter;     //   0  this frame's view-projection without TAA jitter (camera-relative world -> clip)
	float4x4 prevViewProjNoJitter; //  64  last frame's, re-based on this frame's camera (camera-relative world now -> last clip)
	float4x4 celestial;            // 128  rows: world dir -> star space (sun-fixed frame of the tilted sky; stars, Milky Way)
	float4x4 cloudShadowVP;        // 192  reserved (M3 cloud shadows); identity
	float4 sunGeom;    // 256  x sun path tilt (rad), y sun disc angular radius (rad), z moon disc radius (rad), w sun elevation (rad)
	float4 sunArt;     // 272  rgb art-graded direct light (= ShadeFrame.sunColor after the art layer), w sun/moon crossfade 0..1
	float4 ambientArt; // 288  rgb ambient tint applied to the sky light, w ground-bounce strength
	float4 bounce;     // 304  rgb ground albedo for bounce light, w cave minimum light
	float4 grade0;     // 320  x EV bias, y saturation, z vibrance, w scene contrast (unused)
	float4 grade1;     // 336  rgb white-balance gain, w Purkinje strength
	float4 grade2;     // 352  rgb lift (display), w black floor phi
	float4 grade3;     // 368  rgb gain (display), w inset s_in
	float4 tonemap;    // 384  Lottes curve f(x) = x^a / (x^(a d) b + beta): x a, y d, z b, w beta (b, beta solved on the CPU)
	float4 output;     // 400  x peak P (1 = SDR), y paper white, z dither amplitude (LSB), w largest display value the inverse maps (LDR->HDR)
	float4 bloom;      // 416  x mix m, y octave spectrum exponent, z fog-glow k, w lens dirt (unused)
	float4 fogR;       // 432  rgb haze extinction sigma (1/block, at the base height), w scale height (blocks)
	float4 fogM;       // 448  x mist sigma (1/block), y mist scale height, z mist base y (absolute), w mist albedo
	float4 fogMisc;    // 464  x haze base y (absolute), y border fog start fraction, z border fog radius (blocks, 0 = off), w evening glow
	float4 vl;         // 480  reserved for the volumetric light module: x strength, y max distance, z HG g, w steps
	float4 wet;        // 496  (M3 weather) x wetness, y puddles, z snow cover, w rain intensity (smoothed), all 0..1
	float4 wind;       // 512  (M3 weather) xy wind direction (unit, world xz), z rain slant (blocks per block of fall), w snowfall at the camera 0..1
	float4 clouds0;    // 528  reserved
	float4 clouds1;    // 544  reserved
	float4 clouds2;    // 560  reserved
	float4 water;      // 576  reserved for the water module
	float4 waterAbs;   // 592  reserved for the water module
	float4 heldLight;  // 608  reserved
	float4 eye;        // 624  xyz camera-relative feet position, w smoothed sky light at the eye 0..1
	float4 skyGoldH;   // 640  rgb golden-hour gain of the sky toward the horizon (sky LUT pass: sky, reflections, fog behind), w 0
	float4 skyGoldZ;   // 656  rgb the same toward the zenith (rose at sunset), w 0
	float4 night;      // 672  x star brightness, y Milky Way, z aurora (unused), w moon phase angle (rad, 0 = full)
	float4 ssr;        // 688  x 1 = ShadeResources.prevScene holds a finished frame (0: just allocated, undefined), y 1 = ShadeResources.prevDepth is its depth (TAAU), z 1 = ShadeResources.weatherNoise is baked (M3 weather), w 1 = ShadeResources.shadowTint holds casters (M3 weather)
	float4 taa;        // 704  reserved for TAAU
	float4 renderScale;// 720  TAAU dynamic resolution: x render scale (live / output), y texture mip bias (log2), zw live render size (px)
	float4 exposureCfg;// 736  x EV below the reference, y EV above it (bounds), z adapt-up tau (s), w adapt-down tau (s)
	float4 dimTint;    // 752  reserved
	float4 flash;      // 768  (M3 weather) xyz unit direction toward a lightning flash (camera-relative), w its light (radiance on a white surface, before exposure)
	float4 dt;         // 784  x frame dt (s), y time (s, wraps hourly), z day index, w +1 morning / -1 evening
	int4 tier;         // 800  x tier (0 P .. 3 U), y feature bits (SHADE_FEATURE_*), z look preset (0 epic, 1 storybook, 2 natural), w debug view
	float4 gradeIn[3];    // 816  rows of the scene grade + inset matrix (white balance, saturation, inset): c' = (dot(row_i, c))
	float4 gradeOut[3];   // 864  rows of the outset matrix (applied after the curve)
	float4 gradeInInv[3]; // 912  inverse of gradeIn
	float4 gradeOutInv[3];// 960  inverse of gradeOut
	float4 exposureRef;   // 1008 x reference exposure E_ref (CPU, linear), y metering share lambda, z metering key, w 1 = jump (no adaptation)
};

// ShadeLook.tier.y feature bits
#define SHADE_FEATURE_HDR 1        // the world draws into the HDR target (shaders output pre-exposed linear radiance)
#define SHADE_FEATURE_BLOOM 2
#define SHADE_FEATURE_AUTO_EXPOSURE 4
#define SHADE_FEATURE_PCSS 8
#define SHADE_FEATURE_STARS 16     // procedural stars, Milky Way and moon (vanilla's stars and moon are dropped)
#define SHADE_FEATURE_HAZE 32      // height haze and mist, border fog
#define SHADE_FEATURE_LIGHT_V2 64  // lighting model v2

struct ShadeGpu {
	float4 sh[9];          //   0  reserved (M2b: irradiance SH)
	float4 sunAtCamera;    // 144  reserved
	float4 moonAtCamera;   // 160  reserved
	float4 exposure;       // 176  x exposure applied this frame (pre-exposure), y metered target, z last frame's, w log2 of x
	float4 scene;          // 192  x mean log2 scene luminance (metered), y canopy factor (reserved), z sun visibility (reserved), w frames
	uint histogram[64];    // 208  log-luminance histogram being accumulated this frame (cleared by the reduce)
};                         // 464 bytes (the buffer is 512)

// buffer(19): an argument buffer (tier 2: each entry is the texture's MTLResourceID) with the textures shade keeps for the
// whole session or the frame (mcshade.m, mc_shade_resource). Entries nobody filled hold a 1x1 dummy. Add new entries at the
// end (ShadeResources.java keeps the indices).
struct ShadeResources {
	texture2d<half> milkyWay;    // 0  RGBA16F equirectangular in galactic longitude/latitude (baked once)
	texture2d<half> prevScene;   // 1  last frame's HDR world (pre-exposed with ShadeGpu.exposure.z), before the tonemap: SSR source
	texture2d<half> scene;       // 2  this frame's HDR world target (read it only outside the world passes: it is their attachment)
	texture2d<half> bloom;       // 3  bloom accumulation from level 1 down (quarter size), after the post chain of the previous frame
	texture2d<half> weatherNoise;// 4  (M3 weather) RG16F 1024^2 tile of 256 blocks: r puddle field, g snow drift (baked once; weather_surface.metal)
	texture2d<half> prevDepth;   // 5  (TAAU) linear view depth of prevScene (R16F, blocks along last frame's view axis, ~65000 = sky); valid when ssr.y == 1
	texture2d<half> shadowTint;  // 6  (M3 weather) coloured shadows: near cascade tint map (RGBA16F, rgb transmittance, a reversed-Z light depth of the nearest translucent caster)
	texture2d<half> reserved7;
};

// ---- display transform ("Shade Filmic") ---------------------------------------------------------------------------------
// Structure: desaturate (white balance + saturation + inset, one matrix) -> per-channel Lottes 2016 curve anchored at
// mid-grey and white -> resaturate (outset) -> display gain/lift. The inset/outset pair gives saturated highlights a path
// to white without hue skews, the curve gives contrast, and the same parameters invert exactly (shadeLdrToHdr), so
// display-referred content drawn into the HDR target survives the round trip.
static inline float3 shadeRows(const constant float4 *m, float3 c) {
	return float3(dot(m[0].xyz, c), dot(m[1].xyz, c), dot(m[2].xyz, c));
}

static inline float3 shadeLottes(float3 x, float4 t) {
	float3 xa = pow(max(x, 1e-7), float3(t.x));
	float3 xad = pow(max(x, 1e-7), float3(t.x * t.y));
	return xa / (xad * t.z + t.w);
}

// Inverse of shadeLottes per channel: with u = x^a, y = u / (b u^d + beta), i.e. g(u) = u - y (b u^d + beta) = 0. g is
// convex and increasing past its root, so Newton from an upper bound converges monotonically; the bound comes from the
// tangent of u^d at 1 (u^d <= 1 + d (u - 1)) and needs y b d < 1, which the clamp (output.w, at most 0.95 / (b d)) keeps.
static inline float3 shadeLottesInverse(float3 y, float4 t) {
	float a = t.x, d = t.y, b = t.z, beta = t.w;
	y = clamp(y, 0.0, 0.95 / (b * d));
	float3 u = (y * b * (1.0 - d) + y * beta) / (1.0 - y * b * d);
	for (int i = 0; i < 4; i++) {
		float3 ud = pow(max(u, 1e-9), float3(d));
		float3 g = u - y * (b * ud + beta);
		float3 dg = 1.0 - y * b * d * ud / max(u, 1e-9);
		u = max(u - g / max(dg, 0.05), 0.0);
	}
	return pow(u, float3(1.0 / a));
}

// Scene-linear, pre-exposed Rec.709 -> linear display value (0..output.x), without the dither and the OETF.
static inline float3 shadeDisplay(float3 x, constant ShadeLook &L) {
	float3 c = max(shadeRows(L.gradeIn, max(x, 0.0)), 0.0);
	c = min(shadeLottes(c, L.tonemap), L.output.x);
	float3 y = max(shadeRows(L.gradeOut, c), 0.0);
	return max(y * L.grade3.rgb + L.grade2.rgb, 0.0);
}

static inline float3 shadeSrgbToLinearExact(float3 c) {
	return select(pow((c + 0.055) / 1.055, 2.4), c / 12.92, c <= 0.04045);
}
static inline float3 shadeLinearToSrgbExact(float3 c) {
	return select(1.055 * pow(max(c, 0.0), 1.0 / 2.4) - 0.055, c * 12.92, c <= 0.0031308);
}

// A display-referred colour (what an unshaded pipeline wrote for an RGBA8 target: sRGB-encoded, vanilla-lit) -> the
// pre-exposed scene-linear value that shadeDisplay maps back to it (up to output.w, so white texels don't become lights).
static inline float3 shadeLdrToHdr(float3 srgb, constant ShadeLook &L) {
	float3 y = shadeSrgbToLinearExact(saturate(srgb));
	y = (y - L.grade2.rgb) / max(L.grade3.rgb, 1e-3);
	y = clamp(shadeRows(L.gradeOutInv, max(y, 0.0)), 0.0, L.output.w);
	float3 c = shadeLottesInverse(y, L.tonemap);
	return max(shadeRows(L.gradeInInv, c), 0.0);
}
static inline half4 shadeLdrToHdr(half4 c, constant ShadeLook &L) { return half4(half3(min(shadeLdrToHdr(float3(c.rgb), L), 60000.0)), c.a); }
static inline float4 shadeLdrToHdr(float4 c, constant ShadeLook &L) { return float4(shadeLdrToHdr(c.rgb, L), c.a); }

#endif
