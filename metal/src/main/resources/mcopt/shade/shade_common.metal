// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// mcopt native shading (-Dmcopt.shade=true): what every shaded pipeline shares. The Java side (mcopt.metal.shade.ShadeMsl)
// prepends this file, unchanged, to each shader that uses it, after "#define MCOPT_SHADE" and the MCOPT_SLOT_* defines.
//
// Global bindings, set on every render encoder for both stages (mcshade.m, ShadeBindings):
//   buffer(16)   constant ShadeFrame&        this frame's values (layout below = ShadeFrame.java's packer)
//   texture(16)  depth2d_array<float>        sun/moon shadow map, one slice per cascade (see SHADOW MAP CONVENTION)
//   sampler(12)  comparison sampler          linear (2x2 hardware PCF), clamp to edge, MTLCompareFunctionGreaterEqual
//   texture(17)  texture2d<half>             sky-view LUT (RGBA16Float, SHADE_LUT_W x SHADE_LUT_H): the atmosphere's radiance
//                                            for the upper hemisphere, rendered once per frame (sky_lut.metal) before the sky
//   sampler(13)  linear, clamp to edge       for the LUT
// Slots 16-19 (buffers, textures) and samplers 12-15 are reserved for shade.
//
// SPACES. "World" below means camera-relative world space: world axes (+y up, the sun sets toward -x), origin at the
// camera. Sodium terrain vertices, the sky and the shadow matrices all use it. Absolute = cameraBlock + cameraFract + P.
// Clip space is renderpearl's: reversed Z in [0, 1] (near = 1, far = 0), GL row order (y up); hand-written vertex shaders
// negate gl_Position.y like SPIRV-Cross does for the game's own.
//
// SHADOW MAP CONVENTION (shared with the shadow pass, mcopt.metal.shade.shadow / mcshadow.m):
//   shadowViewProj[c] maps camera-relative world to the shadow clip space of cascade c: orthographic, x and y in [-1, 1],
//   z in [0, 1] REVERSED (1 = nearest to the light, cleared to 0, depth test GreaterEqual). The shadow pass's own vertex
//   shader writes that clip position as is (it does NOT negate y), so texel (u, v) = (x * 0.5 + 0.5, 0.5 - y * 0.5) in
//   Metal texture coordinates (v down) — see shadeShadowUV. A receiver is lit when its (biased) depth >= the stored one.
//   Cascades are chosen by their box (shadeShadow), not by distance: slice 0 = near cascade (redrawn every frame, pushed
//   ahead of the view), slice 1 = far cascade (centered on the camera, redrawn every few frames, world-anchored). The map
//   is cleared to 0 (no caster); casters are biased at raster time (constant + slope), receivers add shadeShadow's offset.
//   The compare sampler compares ref OP texel (GreaterEqual: lit when ref >= stored). Only light-facing faces cast.
//   lightDirWorld is the shadow pass's own (quantized, at least 2 degrees up) light direction, so N.L and shadows agree.
//   Full rationale: SHADOW.md (the shadow contract) and shade_shadow.metal.
#ifndef MCOPT_SHADE_COMMON
#define MCOPT_SHADE_COMMON
#include <metal_stdlib>
using namespace metal;

// ---- per-frame values -------------------------------------------------------------------------------------------------
// Byte offsets are fixed: mcopt.metal.shade.ShadeFrame (Java) writes exactly this layout, 1008 bytes. Change both together.
struct ShadeFrame {
	float4x4 view;              //   0  world -> view (rotation only)
	float4x4 proj;              //  64  view -> clip as the world is drawn (view bobbing, hurt tilt and nausea included);
	                            //      MetalFX's sub-pixel jitter is in it only with -Dmcopt.fx.scale < 1 (jitter.xy)
	float4x4 viewProj;          // 128  proj * view
	float4x4 invViewProj;       // 192  inverse(viewProj): clip -> world
	float4x4 prevViewProj;      // 256  last frame's viewProj, re-based on this frame's camera: world (now) -> last frame's clip
	float4x4 shadowViewProj[2]; // 320  world -> shadow clip per cascade (identity while shadows are off)
	float4 cameraBlock;         // 448  xyz floor(camera position) (exact below 2^24), w 0
	float4 cameraFract;         // 464  xyz camera position - cameraBlock, w camera height above sea level (y - 63) in blocks
	float4 sunDirWorld;         // 480  xyz unit vector toward the sun, w = its y (sine of the sun's elevation)
	float4 sunDirView;          // 496  xyz the same in view space, w 0
	float4 moonDirWorld;        // 512  xyz unit vector toward the moon, w moon phase brightness (0 new .. 1 full)
	float4 lightDirWorld;       // 528  xyz the light that shines and casts shadows: the sun by day, the moon by night; w 1 sun, 0 moon
	float4 sunColor;            // 544  rgb light from lightDirWorld (sun or moon) reaching the ground: linear, pre-exposure, divided by
	                            //      pi (a white Lambert surface facing it reflects exactly this), after the atmosphere and weather; w luminance
	float4 ambientSky;          // 560  rgb sky light on an upward surface (irradiance / pi) at sky light 15, w 0
	float4 ambientGround;       // 576  rgb light bounced up from the ground (irradiance / pi) on a downward surface at sky light 15, w 0
	float4 ambientBlock;        // 592  rgb block light (torches) at block light 15, linear; w minimum ambient (caves; brightness option, night vision)
	float4 fogColor;            // 608  rgb the game's fog colour (sRGB, as vanilla uses it); a how much of it to use instead of the
	                            //      atmosphere (1 under water / in lava / powder snow, with blindness or darkness, and outside the overworld)
	float4 fogRange;            // 624  x environmental start, y environmental end, z render distance start, w render distance end (blocks)
	float4 fogSky;              // 640  x vanilla sky fog end, y cloud fog end, z aerial haze density (1/blocks), w haze strength 0..1
	float4 skyColor;            // 656  rgb the game's sky colour (sRGB), a 1 - rain
	float4 zenithColor;         // 672  rgb atmosphere radiance straight up (linear, pre-exposure), w 0
	float4 horizon[8];          // 688  rgb atmosphere radiance on the horizon (linear, pre-exposure) for directions with x = -1 .. 1
	                            //      (index i <-> x = i / 3.5 - 1; the sun moves in the x-y plane, so x alone sets the angle to it)
	float4 cascadeSplits;       // 816  x, y distance (blocks) within which cascade 0 / 1 covers every direction; z, w blocks per texel of cascade 0 / 1
	float4 shadowParams;        // 832  x 1 / map size, y map size (texels), z depth bias (clip z), w normal offset (texels)
	float4 screen;              // 848  xy render target size (pixels), zw 1 / size
	float4 time;                // 864  x day time: 0 noon, 0.25 sunset, 0.5 midnight, 0.75 sunrise; y game time in seconds (wraps every hour);
	                            //      z exposure (pre-tonemap scale, eye-adapted); w partial tick
	float4 weather;             // 880  x rain 0..1, y thunder 0..1, z night vision 0..1, w star brightness
	float4 jitter;              // 896  xy NDC jitter in proj this frame, zw last frame's (0 without MetalFX)
	float4 atmosphere;          // 912  x sun intensity, y moon intensity, z Mie amount (haze, rain), w sky brightness multiplier
	float4 ambientToward;       // 928  rgb sky (+ half the ground's) light on a vertical face turned toward the sun's azimuth (shadeSkyAxis), w 0
	float4 ambientAway;         // 944  rgb the same for a vertical face turned away from it, w 0
	int4 flags;                 // 960  x dimension (0 overworld, 1 nether / no sky, 2 end), y fog type (0 air, 1 water, 2 lava, 3 powder snow),
	                            //      z shadows enabled (0/1), w debug mode (0 off, 1 shadow map, 2 normals, 3 lighting only)
	int4 flags2;                // 976  x frame index (wraps), y underwater (0/1), z cascade count, w sky light at the camera (0..15)
	float4 sunPath;             // 992  x sin, y cos of the sun path tilt (mcopt.shade.sunTilt: the sun and moon orbit rotated about
	                            //      world x, toward +z at noon), zw 0
};

#define SHADE_DIM_OVERWORLD 0
#define SHADE_DIM_NETHER 1
#define SHADE_DIM_END 2

// ---- colour -------------------------------------------------------------------------------------------------------------
static inline half3 shadeToLinear(half3 c) { return c * c * (c * 0.305306011h + 0.682171111h) + c * 0.012522878h; } // close fit of the sRGB curve
static inline half3 shadeToSrgb(half3 c) {
	half3 s1 = sqrt(c), s2 = sqrt(s1), s3 = sqrt(s2);
	return saturate(half(0.585122381) * s1 + half(0.783140355) * s2 - half(0.368262736) * s3); // close fit of the inverse sRGB curve
}
static inline float shadeLuma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }
static inline half shadeLumaH(half3 c) { return dot(c, half3(0.2126h, 0.7152h, 0.0722h)); }

// Tone curve, straight to the display value: a rational fit (within 2/255 above black) of sRGB(1 - e^-1.05x), i.e. an
// exponential shoulder that stays linear in the shadows so dark sides keep their detail, in a handful of half-precision ops
// on t = sqrt(x). Before it: a slight saturation lift and a dim-light shift (rods: colours fade toward a cool grey at night)
// that leaves torch-lit and daylit pixels alone. LDR output (M1); M2 moves this to an HDR target and a post pass.
static inline half3 shadeTonemap(half3 x) {
	half3 t = sqrt(max(x, half(0.0)));
	return saturate(t * (half(1.08978453) * t + half(0.9984842)) / (t * (half(1.19790536) * t + half(0.32032119)) + half(1.0)));
}

// Linear radiance (pre-exposure) -> the sRGB value the RGBA8 target stores.
static inline float3 shadeOutput(float3 radiance, constant ShadeFrame &f) {
	half3 c = half3(min(radiance * f.time.z, 60000.0));
	half l = shadeLumaH(c);
	c = max(mix(half3(l), c, half(1.12)), half(0.0));
	half rods = half(smoothstep(1.4, 2.6, f.time.z)) * (half(1.0) - smoothstep(half(0.03), half(0.25), l));
	c = mix(c, half3(l) * half3(0.78h, 0.9h, 1.22h), rods * half(0.65));
	return float3(shadeTonemap(c));
}

// ---- atmosphere ---------------------------------------------------------------------------------------------------------
// Single scattering in a Rayleigh + Mie + ozone atmosphere (units: km), integrated along the view ray with 8 samples; the
// light's optical depth at each sample is analytic (Chapman function approximation for an exponential atmosphere, with the
// tangent-point form below the local horizon and the planet's shadow). ShadeSky.java mirrors this exactly for the sun,
// ambient and horizon colours, so the sky, the terrain's light and its fog agree.
#define SHADE_R_GROUND 6360.0f
#define SHADE_R_TOP 6420.0f
#define SHADE_ALTITUDE 0.5f
#define SHADE_H_R 8.0f
#define SHADE_H_M 1.2f
#define SHADE_MIE_G 0.78f
#define SHADE_SAMPLES 8
constant float3 SHADE_BETA_R = float3(5.802e-3, 13.558e-3, 33.1e-3);
constant float SHADE_BETA_M = 3.996e-3;
constant float SHADE_BETA_M_EXT = 4.440e-3;
constant float3 SHADE_BETA_O = float3(0.650e-3, 1.881e-3, 0.085e-3) * 2.2; // ozone, riding on the Rayleigh density profile

// Column (km of sea-level-equivalent air) from radius r toward a direction at cosine mu to the vertical, density scale h.
static inline float shadeColumn(float r, float mu, float h) {
	float c = sqrt(r / h * 1.5707963);
	if (mu >= 0.0) return h * exp((SHADE_R_GROUND - r) / h) * c / ((c - 1.0) * mu + 1.0);
	float rt = r * sqrt(max(1.0 - mu * mu, 0.0)); // the ray's lowest point
	// Through the planet (its shadow): instead of no light at all, the grazing column plus extinction growing with depth
	// into the shadow, a cheap stand-in for the multiple scattering that keeps twilight blue rather than black.
	float deep = max(SHADE_R_GROUND - rt, 0.0);
	rt = max(rt, SHADE_R_GROUND);
	float ct = sqrt(rt / h * 1.5707963);
	return h * (2.0 * ct * exp((SHADE_R_GROUND - rt) / h) - exp((SHADE_R_GROUND - r) / h) * c / ((c - 1.0) * -mu + 1.0)) + deep * 1.5;
}

static inline float shadePhaseR(float mu) { return 0.0596831 * (1.0 + mu * mu); }
static inline float shadePhaseM(float mu, float g) { // Cornette-Shanks
	float g2 = g * g;
	return 0.1193662 * (1.0 - g2) * (1.0 + mu * mu) / ((2.0 + g2) * pow(max(1.0 + g2 - 2.0 * g * mu, 1e-4), 1.5));
}

// Radiance scattered toward the camera along dir by a light of the given intensity from lightDir. mie scales the Mie
// coefficients (haze, rain). transmittance: of the view ray, through the whole atmosphere (0 toward the ground).
static float3 shadeScatter(float3 dir, float3 lightDir, float intensity, float mie, thread float3 &transmittance) {
	float r0 = SHADE_R_GROUND + SHADE_ALTITUDE;
	float b = r0 * dir.y;
	float tEnd = -b + sqrt(max(b * b - r0 * r0 + SHADE_R_TOP * SHADE_R_TOP, 0.0));
	float dg = b * b - r0 * r0 + SHADE_R_GROUND * SHADE_R_GROUND;
	bool ground = dir.y < 0.0 && dg > 0.0;
	if (ground) tEnd = -b - sqrt(dg);
	float mu = dot(dir, lightDir);
	float pr = shadePhaseR(mu), pm = shadePhaseM(mu, SHADE_MIE_G);
	float betaM = SHADE_BETA_M * mie, betaMExt = SHADE_BETA_M_EXT * mie;
	float odR = 0.0, odM = 0.0, tPrev = 0.0;
	float3 sumR = 0.0, sumM = 0.0;
	for (int i = 0; i < SHADE_SAMPLES; i++) {
		float u = float(i + 1) / float(SHADE_SAMPLES);
		float t = tEnd * u * u, dt = t - tPrev, tm = 0.5 * (t + tPrev);
		tPrev = t;
		float3 p = float3(0.0, r0, 0.0) + dir * tm;
		float r = length(p);
		float dR = exp((SHADE_R_GROUND - r) / SHADE_H_R) * dt, dM = exp((SHADE_R_GROUND - r) / SHADE_H_M) * dt;
		float muL = dot(p, lightDir) / r;
		float cR = odR + 0.5 * dR + shadeColumn(r, muL, SHADE_H_R), cM = odM + 0.5 * dM + shadeColumn(r, muL, SHADE_H_M);
		float3 t3 = exp(-(SHADE_BETA_R + SHADE_BETA_O) * cR - betaMExt * cM);
		sumR += t3 * dR;
		sumM += t3 * dM;
		odR += dR;
		odM += dM;
	}
	transmittance = ground ? float3(0.0) : exp(-(SHADE_BETA_R + SHADE_BETA_O) * odR - betaMExt * odM);
	return intensity * (SHADE_BETA_R * pr * sumR + betaM * pm * sumM);
}

// The sky's radiance along dir (no sun disc): sun and moon scattering, faint airglow, overcast in rain. Linear, pre-exposure.
static float3 shadeSkyRadiance(float3 dir, constant ShadeFrame &f, thread float3 &transmittance) {
	float3 sky = float3(0.0);
	float3 tr = float3(1.0);
	float mie = f.atmosphere.z;
	if (f.sunDirWorld.w > -0.22) sky += shadeScatter(dir, f.sunDirWorld.xyz, f.atmosphere.x, mie, tr);
	if (f.atmosphere.y > 0.0) sky += shadeScatter(dir, f.moonDirWorld.xyz, f.atmosphere.y, mie, tr) * float3(0.85, 0.95, 1.25);
	sky += float3(0.0006, 0.0009, 0.0018) * (1.0 - 0.5 * f.weather.x);  // airglow: the night sky is never black
	float rain = f.weather.x;
	float l = shadeLuma(sky);
	sky = mix(sky, float3(l) * float3(0.92, 0.96, 1.0) * (1.0 - 0.45 * f.weather.y), rain * 0.75) * f.atmosphere.w;
	transmittance = tr;
	return sky;
}

// The atmosphere on the horizon toward dir, from the per-frame table (what the terrain fogs into).
static inline float3 shadeHorizon(float3 dir, constant ShadeFrame &f) {
	float2 d = normalize(dir.xz + float2(1e-5, 0.0));
	float x = clamp((d.x + 1.0) * 3.5, 0.0, 6.999);
	int i = int(x);
	return mix(f.horizon[i].xyz, f.horizon[i + 1].xyz, x - float(i));
}

// Unit horizontal vector toward the sun's azimuth (+x when the sun is straight up; the sky is round then anyway).
static inline float2 shadeSkyAxis(constant ShadeFrame &f) {
	float2 a = f.sunDirWorld.xz;
	float l = length(a);
	return l > 1e-3 ? a / l : float2(1.0, 0.0);
}

// ---- lighting -----------------------------------------------------------------------------------------------------------
// Light levels arrive as Sodium's lightmap coordinates (0..240 for levels 0..15), normalized to 0..1.
static inline half shadeSkyCurve(half s) { return s / (half(4.0) - half(3.0) * s); }    // vanilla's brightness curve
static inline half shadeBlockCurve(half b) {                                               // vanilla's, with a softer tail
	half v = b / (half(4.0) - half(3.0) * b);
	return v * v * half(0.65) + v * half(0.35);
}

// Vanilla's directional face shade for a face normal (what the face-shade-off mixin removed from the vertex colours),
// for the dimensions that keep vanilla-style lighting: down, up, north/south, west/east.
static inline half shadeVanillaFace(half3 n, int dimension) {
	half3 a = abs(n);
	half up = dimension == SHADE_DIM_NETHER ? half(0.9) : half(1.0), down = dimension == SHADE_DIM_NETHER ? half(0.9) : half(0.5);
	half y = n.y > half(0.0) ? up : down;
	return (a.x * half(0.6) + a.z * half(0.8) + a.y * y) / max(a.x + a.y + a.z, half(1e-3));
}

// Interleaved gradient noise (Jimenez 2014): a per-pixel rotation that TAA (M2) will average out.
static inline float shadeNoise(float2 pixel, int frame) {
	pixel += float(frame & 63) * 5.588238;
	return fract(52.9829189 * fract(dot(pixel, float2(0.06711056, 0.00583715))));
}

// Overworld light on a surface (linear radiance, pre-exposure). albedo linear; n unit face normal; sky, block light 0..1;
// shadow the light's visibility; thin: crossed plants and other slanted two-sided cutouts (light passes through, no hard
// terminator); foliage: axis-aligned cutout faces (leaves, vines): wrapped light, so canopies glow a little where the light
// passes through them instead of going flat on the sides and undersides (the map still shadows what's inside).
static float3 shadeLit(half3 albedo, half3 n, half sky, half block, half shadow, bool thin, bool foliage, constant ShadeFrame &f) {
	half ndl = half(dot(float3(n), f.lightDirWorld.xyz));
	ndl = thin ? half(0.3) + half(0.5) * abs(ndl) : foliage ? half(0.22) + half(0.78) * saturate(ndl) : saturate(ndl);
	// Direct light needs open sky: with a shadow map the map does the work and this only keeps sunlight out of caves; without
	// one, sky light is the only hint of what's overhead.
	half gate = f.flags.z != 0 ? smoothstep(half(0.3), half(0.8), sky) : sky * sky * sky * sky;
	half3 direct = half3(f.sunColor.xyz) * (ndl * shadow * gate);
	float2 axis = shadeSkyAxis(f);
	half towardSun = half(dot(float2(n.xz), axis)) * half(0.5) + half(0.5);
	half3 side = mix(half3(f.ambientAway.xyz), half3(f.ambientToward.xyz), towardSun);
	half3 ambient = n.y >= half(0.0) ? mix(side, half3(f.ambientSky.xyz), n.y) : mix(side, half3(f.ambientGround.xyz), -n.y);
	ambient *= shadeSkyCurve(sky);
	half3 torch = half3(f.ambientBlock.xyz) * shadeBlockCurve(block);
	return float3(albedo) * float3(direct + ambient + torch + half(f.ambientBlock.w));
}

// Vanilla's fog amount for terrain at camera-relative p: the larger of environmental (spherical) and render-distance
// (cylindrical) linear fogs, the same as the game's shaders.
static inline float shadeVanillaFog(float3 p, float2 env, float2 renderDistance) {
	float spherical = length(p), cylindrical = max(length(p.xz), abs(p.y));
	float a = saturate((spherical - env.x) / max(env.y - env.x, 1e-3));
	float b = saturate((cylindrical - renderDistance.x) / max(renderDistance.y - renderDistance.x, 1e-3));
	return max(a, b);
}

// ---- sky-view LUT -------------------------------------------------------------------------------------------------------
// Texel (u, v) holds shadeSkyRadiance toward: azimuth phi = 2 asin(u) away from the sun's (u = sin(phi / 2): linear near
// the sun, where the Mie glow needs texels, and a sqrt instead of an acos to look up; the sky is mirror-symmetric about the
// vertical plane through the sun, and the moon sits in that plane too), elevation v^2 * pi / 2 (dense near the horizon).
#define SHADE_LUT_W 128
#define SHADE_LUT_H 80

static inline float3 shadeLutDirection(float2 uv, constant ShadeFrame &f) {
	float2 axis = shadeSkyAxis(f);
	float phi = 2.0 * asin(saturate(uv.x)), e = uv.y * uv.y * 1.5707963;
	float2 h = axis * cos(phi) + float2(-axis.y, axis.x) * sin(phi);
	return float3(h.x * cos(e), sin(e), h.y * cos(e));
}

// The atmosphere's radiance toward dir (linear, pre-exposure) from the LUT; directions below the horizon read the horizon
// row, darkened with depth (the ground's haze). The sky and the terrain's fog both come from here, so they meet exactly.
static inline float3 shadeSkyLookup(float3 dir, texture2d<half> lut, sampler s, constant ShadeFrame &f) {
	float2 axis = shadeSkyAxis(f);
	float2 hz = dir.xz;
	float hl2 = dot(hz, hz);
	float c = hl2 > 1e-8 ? dot(hz, axis) * rsqrt(hl2) : 1.0;
	float u = sqrt(saturate(0.5 - 0.5 * c));
	float v = sqrt(saturate(fast::asin(saturate(dir.y)) * 0.63661977));
	float2 uv = (float2(u, v) * float2(SHADE_LUT_W - 1, SHADE_LUT_H - 1) + 0.5) / float2(SHADE_LUT_W, SHADE_LUT_H);
	float3 sky = float3(lut.sample(s, uv, level(0.0)).rgb);
	return dir.y < 0.0 ? sky * mix(1.0, 0.55, saturate(-dir.y * 3.0)) : sky;
}

// Air fog for lit terrain (pre-exposure): fade into the sky behind it (the LUT toward p), with vanilla's distances
// (vanillaFog) plus a light aerial haze, so far terrain melts into the sky exactly where the sky meets the horizon.
static inline float3 shadeFogAir(float3 radiance, float3 p, float vanillaFog, texture2d<half> lut, sampler s, constant ShadeFrame &f) {
	float haze = (1.0 - exp(-length(p) * f.fogSky.z)) * f.fogSky.w;
	float a = max(vanillaFog, haze) * (1.0 - f.fogColor.a);
	return a > 0.0 ? mix(radiance, shadeSkyLookup(normalize(p), lut, s, f), a) : radiance;
}

// Final colour of a shaded surface: exposure, tone curve, sRGB, then the game's own fog colour where it applies (water,
// lava, blindness...; f.fogColor.a), mixed after the tone curve like vanilla mixes it into its LDR colours.
static inline float3 shadeFinish(float3 radiance, float vanillaFog, constant ShadeFrame &f) {
	float3 c = shadeOutput(radiance, f);
	return mix(c, f.fogColor.rgb, vanillaFog * f.fogColor.a);
}

// ---- water (M1: a still-water stand-in until M2's real water) ------------------------------------------------------------
// Gentle wave normal for a water surface at camera-relative p: a few long sines over absolute world xz (whole cycles per 64
// blocks, so the camera's block position can wrap at 64 exactly), fading flat with distance so nothing aliases without TAA.
static float3 shadeWaterNormal(float3 p, constant ShadeFrame &f) {
	float2 w = p.xz + f.cameraFract.xz + fmod(f.cameraBlock.xz, 64.0);
	float t = f.time.y;
	const float k = 6.2831853 / 64.0;
	float2 d1 = float2(7.0, 3.0) * k, d2 = float2(-4.0, 9.0) * k, d3 = float2(13.0, -11.0) * k, d4 = float2(-19.0, -6.0) * k;
	float2 g = d1 * cos(dot(d1, w) + t * 1.1) * 0.050 + d2 * cos(dot(d2, w) + t * 1.4) * 0.040
	         + d3 * cos(dot(d3, w) + t * 1.9) * 0.022 + d4 * cos(dot(d4, w) + t * 2.3) * 0.016;
	float fade = 1.0 / (1.0 + length(p) * 0.03);
	return normalize(float3(-g.x * fade, 1.0, -g.y * fade));
}

// A water surface seen from above: the lit water colour (radiance, alpha as drawn) under a Fresnel reflection of the
// atmosphere (the sky-view LUT) and a sun glint, both only where the sky is open (sky light). Returns radiance and the
// alpha to draw with: the reflection is opaque in proportion to its Fresnel weight. No terrain in the reflection (M2: SSR).
static float4 shadeWater(float3 radiance, float alpha, float3 p, half sky, half shadow, texture2d<half> lut, sampler s, constant ShadeFrame &f) {
	float3 v = normalize(p);
	float3 n = shadeWaterNormal(p, f);
	float cosT = saturate(dot(-v, n));
	float fr = 0.02 + 0.98 * pow(1.0 - cosT, 5.0);
	float3 r = reflect(v, n);
	r.y = abs(r.y);
	float open = float(shadeSkyCurve(sky));
	float3 refl = shadeSkyLookup(r, lut, s, f) * open;
	float glint = pow(saturate(dot(r, f.lightDirWorld.xyz)), 900.0) * 900.0 + pow(saturate(dot(r, f.lightDirWorld.xyz)), 60.0) * 6.0;
	refl += f.sunColor.rgb * (glint * float(shadow) * open);
	fr *= 0.35 + 0.65 * open;
	float a = fr + (1.0 - fr) * alpha;
	return float4((refl * fr + radiance * alpha * (1.0 - fr)) / max(a, 1e-3), a);
}

// ---- sky ------------------------------------------------------------------------------------------------------------------
#define SHADE_SUN_RADIUS 0.0262f // radians (1.5 degrees: bigger than the real sun, smaller than vanilla's square)

// The sky's final colour toward unit dir (sRGB, what the target stores): the atmosphere (LUT), the sun disc with limb
// darkening in the colour of the light reaching the ground, and the game's fog colour under water and the like.
static float3 shadeSkyColor(float3 dir, texture2d<half> lut, sampler s, constant ShadeFrame &f) {
	float3 sky = shadeSkyLookup(dir, lut, s, f);
	float cosA = dot(dir, f.sunDirWorld.xyz);
	float x = (1.0 - cosA) / (1.0 - cos(SHADE_SUN_RADIUS)); // ~ (angle / radius)^2
	if (x < 1.3 && f.lightDirWorld.w > 0.5 && dir.y > -0.02) {
		float limb = 0.4 + 0.6 * sqrt(saturate(1.0 - x));
		float disc = (1.0 - smoothstep(0.85, 1.3, x)) * limb;
		sky += f.sunColor.rgb * (40.0 * disc);
	}
	float vanillaSky = dir.y <= 0.0 ? 1.0 : saturate(min(16.0 / max(dir.y, 1e-3), 512.0) / max(f.fogSky.x, 1.0));
	return shadeFinish(sky, vanillaSky, f);
}

// ---- shadows ------------------------------------------------------------------------------------------------------------
// Receiver side of the shadow pass (mcopt.metal.shade.shadow.ShadowPass, mcshadow.m). The same maths as shade_shadow.metal
// (which the headless shadow test uses), on ShadeFrame fields: box-based cascade choice (the near cascade is pushed ahead
// of the view, so a distance test would waste it), a blend band between the cascades, a depth-range fallback to the far
// cascade, a receiver offset along the normal plus toward the light growing with tan(theta), and a 3x3 tent of hardware
// 2x2 PCF taps per cascade (deterministic: no noise to shimmer while M1 has no TAA).
static inline float2 shadeShadowUV(float3 clip) { return float2(clip.x * 0.5 + 0.5, 0.5 - clip.y * 0.5); }

// One cascade: visibility, and m = how far out in its square the point is (>= 1: outside it or its depth range).
static inline float shadeShadowCascade(depth2d_array<float> map, sampler cmp, uint layer, float4x4 vp, float3 p, float3 offset,
                                       float texelUv, float spread, thread float &m) {
	float4 c = vp * float4(p + offset, 1.0);
	m = max(abs(c.x), abs(c.y));
	if (c.z <= 0.0 || c.z >= 1.0) m = 2.0;
	if (m >= 1.0) return 1.0;
	float2 uv = shadeShadowUV(c.xyz);
	float h = spread * texelUv;
	float s = map.sample_compare(cmp, uv + float2(-h, -h), layer, c.z);
	s += map.sample_compare(cmp, uv + float2(h, -h), layer, c.z);
	s += map.sample_compare(cmp, uv + float2(-h, h), layer, c.z);
	s += map.sample_compare(cmp, uv + float2(h, h), layer, c.z);
	return s * 0.25;
}

// Visibility (0 shadowed .. 1 lit) of camera-relative world point p with unit face normal n (facing the camera) from the
// light (f.lightDirWorld). thin: two-sided foliage, offset toward the light only. 1 when shadows are off, past the far
// cascade (fading out over its outer 15%), and for faces turned away from the light (N.L is 0 there anyway).
static half shadeShadow(depth2d_array<float> map, sampler cmp, constant ShadeFrame &f, float3 p, half3 n, bool thin) {
	if (f.flags.z == 0) return half(1.0);
	float3 L = f.lightDirWorld.xyz;
	float nl = thin ? 1.0 : dot(float3(n), L);
	if (nl <= 0.0) return half(1.0);
	float tanT = clamp(sqrt(max(1.0 - nl * nl, 0.0)) / max(nl, 0.02), 0.0, 12.0);
	float3 dir = thin ? L * 1.5 : float3(n) * 0.75 + L * (0.5 + 0.75 * tanT); // offset per texel of world size
	float texelUv = f.shadowParams.x;
	float m0, m1;
	float near = shadeShadowCascade(map, cmp, 0u, f.shadowViewProj[0], p, dir * f.cascadeSplits.z, texelUv, 0.75, m0);
	if (m0 < 0.85) return half(near);
	float far = shadeShadowCascade(map, cmp, 1u, f.shadowViewProj[1], p, dir * f.cascadeSplits.w, texelUv, 0.5, m1);
	far = mix(far, 1.0, smoothstep(0.85, 1.0, m1));
	return half(m0 >= 1.0 ? far : mix(near, far, smoothstep(0.85, 1.0, m0)));
}

#endif
