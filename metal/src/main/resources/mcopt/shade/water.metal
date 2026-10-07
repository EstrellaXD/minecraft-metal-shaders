// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// mcopt native shading: the water and reflections module (shader bible E14, E15 basics, E26 surface). Original code; the
// techniques are public (Schlick 1994, Walter et al. 2007 GGX, Beer-Lambert, McGuire & Mara 2014 screen-space ray
// marching, Tessendorf 2001 deep-water dispersion, caustics as the inverse Jacobian of the refracted light field).
//
// Assembled by ShadeMsl after shade_common.metal (mcopt.metal.shade.water.ShadeWater.prelude puts the SHADE_* defines in
// front) for the translucent terrain fragment; written as a self-contained include so other water renderers (far terrain,
// Distant Horizons' transparent pass) can call the same functions.
//
// UNITS AND SPACES: shade_common.metal's. Positions are camera-relative world (blocks), +y up. Colours are linear radiance
// BEFORE exposure (what shadeLit returns); the caller applies air fog and the output transform (M1: shadeFogAir +
// shadeFinish; M2 HDR: pre-exposure and the post tonemap). Depth is reversed-Z in [0, 1] (1 near, 0 = sky/clear).
//
// INPUTS (bound by mcwater.m on every shaded encoder, fragment stage):
//   texture(18) sceneCopy  the main pass's colour attachment 0, copied at the switch to translucent terrain: the opaque
//                          scene (sky, terrain, entities) without the translucents. M1: tone-mapped sRGB in RGBA8 (read
//                          back to radiance by shadeWaterSceneRadiance); M2 HDR: pre-exposed linear radiance (define
//                          SHADE_HDR).
//   texture(19) depthCopy  the depth attachment at the same moment (Depth32Float, reversed-Z).
//   buffer(23)  ShadeWaterParams (below). state.x == 1 only after this frame's copy: otherwise callers must not read the
//                          copies (they are 1x1 dummies or last frame's).
#ifndef MCOPT_SHADE_WATER
#define MCOPT_SHADE_WATER

#ifndef SHADE_SSR_STEPS
#define SHADE_SSR_STEPS 16
#endif

// Mirrors mcwater.m's WaterParams (64 bytes, written by ShadeWater.frame).
struct ShadeWaterParams {
	float4 still;  // atlas uv rectangle (u0, v0, u1, v1) of minecraft:block/water_still (all 0: unknown)
	float4 flow;   // the same for minecraft:block/water_flow
	float4 state;  // x 1 when the copies hold this frame's opaque scene, y water surface height above the eye (under water),
	               // z wetness 0..1, w 0
	float4 tune;   // x wave strength (mcopt.shade.waterWaves), y SSR max distance (blocks), z reflection strength, w caustics 0/1
};

constexpr sampler shadeWaterLinear(filter::linear, address::clamp_to_edge, coord::normalized);

// 1 still water, 2 flowing water, 0 another translucent sprite, -1 the sprites are unknown (resource reload).
static inline int shadeWaterSprite(float2 uv, constant ShadeWaterParams &wp) {
	if (wp.still.z <= 0.0) return -1;
	if (all(uv >= wp.still.xy) && all(uv <= wp.still.zw)) return 1;
	if (all(uv >= wp.flow.xy) && all(uv <= wp.flow.zw)) return 2;
	return 0;
}

// ---- reading the copies --------------------------------------------------------------------------------------------------
// Linear view depth (the clip w, blocks along the view axis) of a reversed-Z depth value. Renderpearl's projection keeps
// clip w = -z_view, so z_ndc = -A + B / w with A = proj[2][2], B = proj[3][2] (column-major: [column][row]).
static inline float shadeLinearW(float z, constant ShadeFrame &f) {
	return f.proj[3][2] / max(z + f.proj[2][2], 1e-7);
}

// The scene copy's value back to linear radiance before exposure. M1 stores tone-mapped sRGB: undo the sRGB curve, then
// the hue-preserving tone curve on the brightest channel (shadeOutput keeps the channel ratios below its white blend),
// then the exposure. Clipped highlights come back as ~5.6 / exposure.
static inline float3 shadeWaterSceneRadiance(half3 stored, constant ShadeFrame &f) {
#ifdef SHADE_HDR
	return float3(stored) / max(f.time.z, 1e-6); // HDR target: pre-exposed linear radiance (M2: use ShadeGpu's exposure)
#else
	float3 o = float3(shadeToLinear(stored));
	float mo = max(max(o.r, o.g), max(o.b, 1e-5));
	float tm = min(mo, 0.9985);
	float y = (tm + sqrt(tm * tm + 0.024144 * tm)) * (1.0 / 2.012);    // undo the toe: 1.006 y^2 - tm y - 0.006 tm = 0
	float m = -log2(max(1.0 - y, 1e-4)) * (0.69314718 / 1.1);          // undo 1 - e^-1.1x
	return o * (m / mo) / max(f.time.z, 1e-6);
#endif
}

// ---- waves ------------------------------------------------------------------------------------------------------------------
// The surface is a sum of directional waves with sharpened crests, h_i = a_i * e^(sin(theta_i) - 1), travelling with deep-water
// dispersion (omega = sqrt(g k), slowed for a calm, Minecraft-scale look), each one pulling the next ones' sample point
// toward its crests (a little choppiness, no visible tiling). Slopes and the Laplacian (for caustics) are analytic; waves
// shorter than a few pixels' footprint fade out instead of aliasing (no TAA in M1). Directions, wavelengths, amplitudes:
// our own set, roughly along the wind toward +x+z.
#define SHADE_WAVE_COUNT 7
constant float4 SHADE_WAVES[SHADE_WAVE_COUNT] = {
	//   dir x,  dir z, wavelength, amplitude (blocks)
	float4( 0.920,  0.392, 8.70, 0.060),
	float4( 0.616,  0.788, 5.90, 0.040),
	float4( 0.993, -0.118, 3.70, 0.024),
	float4( 0.309,  0.951, 2.45, 0.015),
	float4( 0.809, -0.588, 1.55, 0.009),
	float4(-0.182,  0.983, 1.00, 0.0055),
	float4( 0.707,  0.707, 0.62, 0.0032),
};
constant float SHADE_WAVE_PHASE[SHADE_WAVE_COUNT] = {0.0, 1.7, 4.1, 2.3, 5.2, 0.6, 3.4};

// Absolute world xz for camera-relative xz, wrapped every 2048 blocks (float precision; the pattern jumps once per 2048
// blocks travelled, at a wrap of the camera's block position).
static inline float2 shadeWaterXZ(float2 rel, constant ShadeFrame &f) {
	return rel + f.cameraFract.xz + fmod(f.cameraBlock.xz, 2048.0);
}

// Slope (dh/dx, dh/dz) of the surface at absolute xz; lap = the height's Laplacian, height = h. strength scales the
// amplitudes; footprint: blocks per pixel (wave LOD), 0 for none.
static float2 shadeWaves(float2 x, float t, float strength, float footprint, thread float &lap, thread float &height) {
	float2 g = 0.0;
	lap = 0.0;
	height = 0.0;
	for (int i = 0; i < SHADE_WAVE_COUNT; i++) {
		float4 w = SHADE_WAVES[i];
		float lod = saturate(w.z / max(footprint * 3.0, 1e-4) - 1.0);
		float a = w.w * strength * lod;
		if (a <= 0.0) continue;
		float k = 6.2831853 / w.z;
		float omega = sqrt(9.81 * k) * 0.62;
		float th = k * dot(w.xy, x) + omega * t + SHADE_WAVE_PHASE[i];
		float c;
		float s = sincos(th, c);
		float e = exp(s - 1.0);
		float dh = a * k * c * e;            // slope along the wave's direction
		g += w.xy * dh;
		lap += a * k * k * (c * c - s) * e;
		height += a * e;
		x -= w.xy * (c * e * a * 1.6);       // pull the next waves toward this one's crests
	}
	return g;
}

// ---- screen-space reflections -----------------------------------------------------------------------------------------------
// March a world-space ray (from p0 along unit r, at most maxDist blocks) in screen space against the depth copy: clip-space
// end points (the far one pulled in front of the camera), the segment clipped to the screen, SHADE_SSR_STEPS steps spaced
// denser near the start (contact reflections matter most), then 4 bisection steps on the first crossing behind the depth
// buffer and a thickness test in linear depth, so thin things in front of a far background don't smear into long streaks.
// A crossing that fails the thickness test keeps marching (the ray passed behind something). jitter in [0, 1) offsets the
// steps per pixel. Returns radiance (before exposure) and a weight: 0 miss, fading to 0 toward the screen edges and the
// ray's far end, so callers blend it over their sky reflection.
static float4 shadeSsr(float3 p0, float3 r, float jitter, float maxDist, constant ShadeFrame &f, texture2d<half> scene, depth2d<float> depth) {
	float4 c0 = f.viewProj * float4(p0, 1.0);
	float4 dc = f.viewProj * float4(r, 0.0);
	float len = maxDist;
	if (dc.w < 0.0) len = min(len, (c0.w - 0.1) / -dc.w * 0.95);
	if (len <= 0.05 || c0.w <= 0.05) return 0.0;
	float4 c1 = c0 + dc * len;
	float3 s0 = c0.xyz / c0.w, s1 = c1.xyz / c1.w;
	float2 d = s1.xy - s0.xy;
	float tx = d.x > 1e-6 ? (1.0 - s0.x) / d.x : d.x < -1e-6 ? (-1.0 - s0.x) / d.x : 1e6;
	float ty = d.y > 1e-6 ? (1.0 - s0.y) / d.y : d.y < -1e-6 ? (-1.0 - s0.y) / d.y : 1e6;
	float tEnd = min(1.0, min(tx, ty));
	if (tEnd <= 0.0) return 0.0;
	bool screenEnd = tEnd < 1.0;
	float3 ds = (s1 - s0) * tEnd;
	float2 size = float2(depth.get_width(), depth.get_height());
	float A = f.proj[2][2], B = f.proj[3][2];
	float tPrev = 0.0;
	for (int i = 1; i <= SHADE_SSR_STEPS; i++) {
		float u = (float(i) - jitter) / float(SHADE_SSR_STEPS);
		float t = u * (0.35 + 0.65 * u);
		float3 s = s0 + ds * t;
		float zs = depth.read(uint2(clamp((s.xy * 0.5 + 0.5) * size, float2(0.0), size - 1.0)));
		if (s.z <= zs) { // the ray went behind what the depth buffer holds there
			float lo = tPrev, hi = t;
			for (int j = 0; j < 4; j++) {
				float m = 0.5 * (lo + hi);
				float3 sm = s0 + ds * m;
				float zm = depth.read(uint2(clamp((sm.xy * 0.5 + 0.5) * size, float2(0.0), size - 1.0)));
				if (sm.z <= zm) hi = m; else lo = m;
			}
			float3 sh = s0 + ds * hi;
			float2 uv = sh.xy * 0.5 + 0.5;
			float zh = depth.read(uint2(clamp(uv * size, float2(0.0), size - 1.0)));
			float wr = B / max(sh.z + A, 1e-7), ws = B / max(zh + A, 1e-7);
			float wp = B / max(s0.z + ds.z * tPrev + A, 1e-7);
			float thick = 0.3 + 0.02 * ws + abs(wr - wp);
			if (zh > 0.0 && wr - ws < thick) {
				float2 edge = smoothstep(0.0, 0.07, min(uv, 1.0 - uv));
				float fade = edge.x * edge.y;
				if (!screenEnd) fade *= 1.0 - smoothstep(0.75, 1.0, hi);
				return float4(shadeWaterSceneRadiance(scene.sample(shadeWaterLinear, uv).rgb, f), fade);
			}
		}
		tPrev = t;
	}
	return 0.0;
}

// ---- optics -------------------------------------------------------------------------------------------------------------------
// Schlick's Fresnel for water (n = 1.333, f0 = 0.0204), cosine on the air side.
static inline float shadeWaterFresnel(float cosT) {
	float m = 1.0 - saturate(cosT);
	float m2 = m * m;
	return 0.0204 + 0.9796 * m2 * m2 * m;
}

// GGX (Trowbridge-Reitz) specular of the sun or moon off a surface with normal n, viewed along v (camera to point),
// light from unit l; alpha = roughness^2. Radiance per unit sunColor (irradiance / pi), Smith-correlated visibility.
static inline float shadeGgxSpec(float3 n, float3 v, float3 l, float alpha) {
	float3 h = normalize(l - v);
	float nh = saturate(dot(n, h)), nl = saturate(dot(n, l)), nv = max(dot(n, -v), 1e-3);
	float a2 = alpha * alpha;
	float dd = nh * nh * (a2 - 1.0) + 1.0;
	float D = a2 / (3.14159265 * dd * dd);
	float gv = nl * sqrt(nv * nv * (1.0 - a2) + a2), gl = nv * sqrt(nl * nl * (1.0 - a2) + a2);
	float vis = 0.5 / max(gv + gl, 1e-5);
	return 3.14159265 * D * vis * nl * shadeWaterFresnel(dot(h, -v));
}

// Absorption (1/block) from the biome's water colour (the vertex tint, linear): pure water's red-first absorption plus the
// tint's own darkness per channel (vanilla's default blue gives clear teal shallows and deep blue; swamp water turns green
// and murky; warm oceans stay clear cyan).
static inline float3 shadeWaterSigmaA(half3 tint) {
	float3 t = max(float3(shadeToLinear(tint)), float3(0.004));
	return float3(0.26, 0.07, 0.035) + 0.09 * -log(t);
}
#define SHADE_WATER_SIGMA_S 0.022 // scattering (1/block), grey: the blue of deep water comes from absorbing red and green

// ---- the surface ------------------------------------------------------------------------------------------------------------
struct ShadeWaterIn {
	float3 p;        // camera-relative position of the surface fragment
	float3 nFace;    // geometric face normal, turned toward the camera
	float footprint; // blocks per pixel on the surface (wave LOD)
	float2 flowDir;  // world xz direction of the current (flowing water's top faces), 0 for still water
	float2 pixel;    // fragment position (pixels, = memory row order of the copies)
	float depth;     // the fragment's depth (reversed-Z)
	half3 tint;      // vertex colour: biome water colour x vanilla AO
	half sky;        // sky light 0..1
	half shadow;     // sun/moon visibility at the surface (shadow map)
	float3 lit;      // light on an upward white surface (shadeLit(1, up, ...)): sun, sky and block light, radiance
};

// The wave normal at camera-relative p (+y up), and the height there.
static float3 shadeWaterNormal(ShadeWaterIn w, constant ShadeFrame &f, constant ShadeWaterParams &wp, thread float &height) {
	float t = f.time.y;
	float2 x = shadeWaterXZ(w.p.xz, f) - w.flowDir * (t * 1.4); // flowing water carries its waves downstream
	float strength = wp.tune.x * (1.0 + 1.6 * wp.state.z) * (1.0 + 0.6 * length(w.flowDir)) * exp(-length(w.p) * (1.0 / 90.0));
	float lap;
	float2 g = shadeWaves(x, t, strength, w.footprint, lap, height);
	return normalize(float3(-g.x, 1.0, -g.y));
}

// Caustic focusing (1 = none) on a bottom at camera-relative pf, depth dy under the surface. The sun's rays that reach pf
// crossed the surface up-sun of it; there the waves' curvature bends them together or apart, and the light per area is the
// inverse Jacobian of the refraction mapping x -> x + dy (1 - 1/n) grad h: 1 / |det(I + k Hessian(h))|. Where the
// determinant crosses zero (focal curves) the light bunches into the bright cell network of real caustics. Evaluated on
// a set of short waves of their own (well spread directions, so cells and not a lattice), each warping the next.
#define SHADE_CAUSTIC_COUNT 6
constant float4 SHADE_CAUSTIC_WAVES[SHADE_CAUSTIC_COUNT] = {
	//   dir x,  dir z, wavelength, amplitude
	float4( 0.940,  0.342, 2.60, 0.018),
	float4(-0.276,  0.961, 2.10, 0.015),
	float4( 0.454, -0.891, 1.70, 0.012),
	float4(-0.866, -0.500, 1.37, 0.0095),
	float4( 0.643,  0.766, 1.11, 0.0075),
	float4(-0.985,  0.174, 0.89, 0.006),
};

static float shadeWaterCaustics(float3 pf, float dy, constant ShadeFrame &f, constant ShadeWaterParams &wp) {
	float3 l = f.lightDirWorld.xyz;
	float2 x = shadeWaterXZ(pf.xz + l.xz / max(l.y, 0.25) * dy, f);
	float t = f.time.y;
	float3 hs = 0.0; // Hessian: xx, zz, xz
	for (int i = 0; i < SHADE_CAUSTIC_COUNT; i++) {
		float4 w = SHADE_CAUSTIC_WAVES[i];
		float k = 6.2831853 / w.z;
		float th = k * dot(w.xy, x) + sqrt(9.81 * k) * 0.55 * t + float(i) * 1.9;
		float c;
		float s = sincos(th, c);
		float e = exp(s - 1.0);
		float q = w.w * k * k * (c * c - s) * e;
		hs += q * float3(w.x * w.x, w.y * w.y, w.x * w.y);
		x -= w.xy * (c * e * w.w * 14.0); // strong mutual warping: irregular cells
	}
	float kk = min(dy, 5.0) * 0.25 * 3.2 * wp.tune.x * (1.0 + 0.8 * wp.state.z);
	float det = (1.0 + kk * hs.x) * (1.0 + kk * hs.y) - kk * kk * hs.z * hs.z;
	return min(1.0 / max(abs(det), 0.18), 4.0);
}

// Water seen from above (or a side face): reflection (SSR over the sky, sun glint), refraction of the bottom, absorption and
// in-scattering along the path through the water, caustics on the bottom, shoreline foam. Composites what is behind the
// water itself (draw with alpha 1). Radiance before exposure, without air fog between the camera and the surface.
static float3 shadeWaterAbove(ShadeWaterIn w, constant ShadeFrame &f, constant ShadeWaterParams &wp, texture2d<half> lut, sampler lutS,
                              texture2d<half> scene, depth2d<float> depth) {
	float dist = length(w.p);
	float3 v = w.p / max(dist, 1e-4);
	bool top = w.nFace.y > 0.5;
	float height = 0.0;
	float3 n = w.nFace;
	if (top) {
		n = shadeWaterNormal(w, f, wp, height);
		// grazing angles: normals average out across a pixel and the far water calms into a mirror
		float graze = 1.0 - saturate(-v.y);
		n = normalize(mix(n, float3(0.0, 1.0, 0.0), 0.8 * graze * graze * graze * graze));
	}
	float cosV = max(dot(-v, n), 0.02);
	float fres = shadeWaterFresnel(cosV) * wp.tune.z;

	// --- what is behind the surface: refraction, absorption, in-scattering, caustics
	float2 size = float2(scene.get_width(), scene.get_height());
	float2 uv = w.pixel / size;
	float wSurf = shadeLinearW(w.depth, f);
	float z0 = depth.read(uint2(min(w.pixel, size - 1.0)));
	float dw0 = z0 > 0.0 ? shadeLinearW(z0, f) - wSurf : 64.0;
	// only the waves' tilt shifts the bottom (a flat surface doesn't), more for deeper water, in view space
	float3 tilt = (f.view * float4(n - w.nFace, 0.0)).xyz;
	float2 shift = tilt.xy * (0.4 * min(max(dw0, 0.0), 6.0));
	float2 uvR = uv + float2(f.proj[0][0] * shift.x, f.proj[1][1] * shift.y) * (0.5 / max(wSurf, 0.5));
	uvR = clamp(uvR, 0.5 / size, 1.0 - 0.5 / size);
	float zR = depth.read(uint2(uvR * size));
	if (zR > w.depth) { // the shifted sample is in front of the water: no refraction there
		uvR = uv;
		zR = z0;
	}
	float lView = zR > 0.0 ? max(shadeLinearW(zR, f) - wSurf, 0.0) * dist / max(wSurf, 1e-3) : 1e4;
	float dy = top ? lView * saturate(-v.y) : lView * 0.5;
	float3 sigmaA = shadeWaterSigmaA(w.tint);
	float3 sigmaT = sigmaA + SHADE_WATER_SIGMA_S;
	float3 bottom = shadeWaterSceneRadiance(scene.sample(shadeWaterLinear, uvR).rgb, f);
#ifdef SHADE_CAUSTICS
	if (top && wp.tune.w > 0.0 && dy > 0.05 && dy < 24.0 && f.lightDirWorld.w > 0.5) {
		float3 pf = w.p + v * lView;
		float light = smoothstep(0.1, 0.8, float(w.shadow)) * saturate(f.lightDirWorld.y * 4.0) * smoothstep(0.55, 0.9, float(w.sky));
		float c = shadeWaterCaustics(pf, dy, f, wp);
		float fade = light * exp(-dy * 0.2) * saturate(dy * 3.0) * (1.0 - smoothstep(0.04, 0.12, w.footprint)); // fine lines alias far away
		bottom *= mix(1.0, c * 0.8, 0.75 * fade); // ~energy neutral: the focus averages a bit over 1
	}
#endif
	float3 tBottom = exp(-sigmaT * (lView + dy)); // down to the bottom along the view, and the light's way down to it
	float3 tView = exp(-sigmaT * min(lView, 512.0));
	// light scattered toward the eye inside the water: what reaches a mean depth of ~2 blocks, single-scattering albedo
	float3 inScatter = w.lit * exp(-sigmaT * 2.0) * (SHADE_WATER_SIGMA_S / sigmaT) * 0.8;
	float3 body = bottom * tBottom + inScatter * (1.0 - tView);

	// --- reflection: the sky (only as open as the sky light says), SSR over it, the sun's glint
	float open = float(shadeSkyCurve(w.sky));
	float3 r = reflect(v, n);
	float3 rs = float3(r.x, abs(r.y), r.z);
	float3 refl = shadeSkyLookup(rs, lut, lutS, f) * open;
	float hitWeight = 0.0;
#ifdef SHADE_SSR
	{
		float jitter = shadeNoise(w.pixel, 0);
		// reflected images stay readable: the ray follows a calmer normal than the shading (ripples wobble, don't shred)
		float3 rr = reflect(v, normalize(mix(n, w.nFace, 0.55)));
		rr = normalize(float3(rr.x, max(rr.y, 0.01), rr.z));
		float4 hit = shadeSsr(w.p + n * 0.02, rr, jitter, wp.tune.y, f, scene, depth);
		hitWeight = hit.a;
		refl = mix(refl, hit.rgb, hit.a);
	}
#endif
	// the sun's (or moon's) glint: rougher with distance (normals average out: long glitter roads) and in rain; it carries
	// its own Fresnel, hidden where SSR found something in front of the sky
	float alpha = 0.035 + 0.10 * saturate(dist / 110.0) + 0.06 * wp.state.z;
	float spec = shadeGgxSpec(n, v, f.lightDirWorld.xyz, alpha);
	float soft = smoothstep(0.15, 0.85, float(w.shadow));
	float3 glint = f.sunColor.rgb * min(spec, 60.0) * soft * open * (1.0 - hitWeight);

	float3 color = mix(body, refl, fres) + glint * wp.tune.z;
	if (f.flags.w == 5) return float3(hitWeight, fres, 0.0) / max(f.time.z, 1e-3);                               // debug: SSR hits, Fresnel
	if (f.flags.w == 6) return float3(saturate(lView / 16.0), saturate(dy / 8.0), zR > 0.0 ? 0.0 : 1.0) / max(f.time.z, 1e-3); // debug: path, depth

#ifdef SHADE_FOAM
	if (top && zR > 0.0) {
		// where the bottom comes up to the surface; broken up by the wave height so it doesn't draw contour lines
		float shore = min(dy, lView * 0.7);
		float edge = 0.12 + 0.22 * saturate(height * 9.0);
		float foam = (1.0 - smoothstep(0.0, edge, shore)) * 0.55 * smoothstep(0.0, 0.03, shore + 0.02);
		color = mix(color, w.lit * 0.85, foam);
	}
#endif
	return color;
}

// The surface seen from below (the camera under water): inside Snell's window (48.6 degrees around the normal) the world
// above, refracted (the scene copy, or the sky where the copy has none); outside it total internal reflection mirrors the
// water below (SSR into the underwater scene, else the water's own colour). The vanilla underwater fog then applies
// between the camera and the surface (shadeFinish).
static float3 shadeWaterBelow(ShadeWaterIn w, constant ShadeFrame &f, constant ShadeWaterParams &wp, texture2d<half> lut, sampler lutS,
                              texture2d<half> scene, depth2d<float> depth) {
	float dist = length(w.p);
	float3 v = w.p / max(dist, 1e-4);
	float height;
	float3 up = shadeWaterNormal(w, f, wp, height);
	up = normalize(float3(up.x * 1.8, up.y, up.z * 1.8)); // from below the ripples bend the light more visibly
	float3 n = -up; // toward the camera
	float3 t = refract(v, n, 1.333);
	bool tir = dot(t, t) < 1e-6;
	float fres = tir ? 1.0 : shadeWaterFresnel(dot(t, up));
	float2 size = float2(scene.get_width(), scene.get_height());
	float2 uv = w.pixel / size;
	// the world above through the window: the copy behind the surface, shifted by the waves' tilt
	float3 above = 0.0;
	if (!tir) {
		float3 tilt = (f.view * float4(up - float3(0.0, 1.0, 0.0), 0.0)).xyz;
		float wSurf = shadeLinearW(w.depth, f);
		float2 uvR = clamp(uv + float2(f.proj[0][0] * tilt.x, f.proj[1][1] * tilt.y) * (1.2 / max(wSurf, 0.5)), 0.5 / size, 1.0 - 0.5 / size);
		float zR = depth.read(uint2(uvR * size));
		float3 sky = shadeSkyLookup(t, lut, lutS, f);
		if (f.lightDirWorld.w > 0.5) sky += f.sunColor.rgb * min(shadeGgxSpec(up, -t, f.lightDirWorld.xyz, 0.06) * 3.0, 40.0) * float(w.shadow);
		above = zR > 0.0 ? shadeWaterSceneRadiance(scene.sample(shadeWaterLinear, uvR).rgb, f) : sky;
		above *= float(shadeSkyCurve(w.sky)) * 0.6 + 0.4;
	}
	// the water below, mirrored
	float3 below = shadeWaterSceneRadiance(half3(f.fogColor.rgb), f) * (0.65 + 0.5 * saturate(height * 7.0 + 0.3)); // the mirror ripples
#ifdef SHADE_SSR
	if (fres > 0.05) {
		float4 hit = shadeSsr(w.p + n * 0.02, reflect(v, n), shadeNoise(w.pixel, 0), min(wp.tune.y, 48.0), f, scene, depth);
		below = mix(below, hit.rgb, hit.a);
	}
#endif
	return mix(above, below, fres);
}

// Entry point for the translucent terrain fragment: picks the side the camera is on.
static float3 shadeWaterSurface(ShadeWaterIn w, constant ShadeFrame &f, constant ShadeWaterParams &wp, texture2d<half> lut, sampler lutS,
                                texture2d<half> scene, depth2d<float> depth) {
	if (f.flags2.y != 0 && w.nFace.y < -0.5) return shadeWaterBelow(w, f, wp, lut, lutS, scene, depth);
	return shadeWaterAbove(w, f, wp, lut, lutS, scene, depth);
}

// World xz direction in which the atlas v coordinate grows across this face (the flowing water sprite is laid along the
// current), from the screen derivatives of position and uv; 0 where it isn't defined.
static inline float2 shadeWaterFlowDir(float3 dpx, float3 dpy, float2 duvx, float2 duvy) {
	float2 a = dpx.xz, b = dpy.xz;
	float det = a.x * b.y - a.y * b.x;
	if (abs(det) < 1e-12) return 0.0;
	float2 g = float2(duvx.y * b.y - duvy.y * a.y, -duvx.y * b.x + duvy.y * a.x) / det;
	float l = length(g);
	return l > 1e-6 ? g / l : float2(0.0);
}

#endif
