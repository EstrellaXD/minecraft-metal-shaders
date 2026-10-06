// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Art-preview tool for mcopt native shading, not part of the mod.
// Preview scene for art tuning (not shipped): analytic boxes on a grass plane, lit with shade_common's own functions.
struct VOut { float4 pos [[position]]; };
vertex VOut preview_vs(uint vid [[vertex_id]]) {
	float2 p = float2(vid == 1 ? 3.0 : -1.0, vid == 2 ? 3.0 : -1.0);
	VOut o; o.pos = float4(p, 0.0, 1.0); return o;
}
struct Box { float3 lo, hi; int mat; };
static Box sceneBox(int k) {
	Box b;
	if (k < 8) {
		float a = float(k) * 0.785398 + 0.3;
		float3 c = float3(sin(a), 0.0, -cos(a)) * (11.0 + 3.0 * float(k % 3));
		float hgt = 2.0 + float((k * 7) % 5);
		float w = 1.5 + float((k * 3) % 3);
		b.lo = float3(c.x - w, -1.62, c.z - w); b.hi = float3(c.x + w, -1.62 + hgt, c.z + w); b.mat = k % 4;
	} else {
		int j = k - 8;
		float a = float(j) * 0.392699 + 0.1;
		float d = 70.0 + 55.0 * float(j % 3);
		float3 c = float3(sin(a), 0.0, -cos(a)) * d;
		float hgt = 12.0 + float((j * 11) % 4) * 9.0;
		b.lo = float3(c.x - 14.0, -1.62, c.z - 10.0); b.hi = float3(c.x + 14.0, -1.62 + hgt, c.z + 10.0); b.mat = 4 + j % 2;
	}
	return b;
}
static bool hitBox(float3 o, float3 d, Box b, thread float &t, thread float3 &n) {
	float3 inv = 1.0 / d;
	float3 t0 = (b.lo - o) * inv, t1 = (b.hi - o) * inv;
	float3 tmin = min(t0, t1), tmax = max(t0, t1);
	float tn = max(max(tmin.x, tmin.y), tmin.z), tf = min(min(tmax.x, tmax.y), tmax.z);
	if (tn > tf || tf < 0.0 || tn < 1e-3) return false;
	t = tn;
	n = tn == tmin.x ? float3(-sign(d.x), 0, 0) : tn == tmin.y ? float3(0, -sign(d.y), 0) : float3(0, 0, -sign(d.z));
	return true;
}
constant float3 ALBEDO[6] = { float3(0.52, 0.52, 0.52), float3(0.58, 0.43, 0.25), float3(0.27, 0.47, 0.18), float3(0.86, 0.80, 0.60), float3(0.45, 0.47, 0.45), float3(0.40, 0.55, 0.30) };
fragment float4 preview_fs(VOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], texture2d<half> lut [[texture(17)]], sampler ls [[sampler(13)]]) {
	float2 ndc = float2(in.pos.x * f.screen.z * 2.0 - 1.0, 1.0 - in.pos.y * f.screen.w * 2.0);
	float4 wp = f.invViewProj * float4(ndc, 0.5, 1.0);
	float3 dir = normalize(wp.xyz / wp.w);
	float best = 1e9; float3 n = 0; int mat = -1;
	for (int k = 0; k < 24; k++) { float t; float3 nn; if (hitBox(0, dir, sceneBox(k), t, nn) && t < best) { best = t; n = nn; mat = sceneBox(k).mat; } }
	if (dir.y < 0.0) { float t = -1.62 / dir.y; if (t < best) { best = t; n = float3(0, 1, 0); mat = 6; } }
	if (mat < 0) return float4(shadeSkyColor(dir, lut, ls, f), 1.0);
	float3 p = dir * best;
	float3 alb = mat == 6 ? float3(0.36, 0.56, 0.24) : ALBEDO[mat];
	if (mat == 6) { float2 g = floor(p.xz); alb *= 0.9 + 0.1 * fract(sin(dot(g, float2(12.9898, 78.233))) * 43758.5453); }
	// analytic shadow toward the light
	half shadow = 1.0;
	float3 L = f.lightDirWorld.xyz;
	for (int k = 0; k < 24; k++) { float t; float3 nn; if (hitBox(p + n * 0.01, L, sceneBox(k), t, nn)) { shadow = 0.0; break; } }
	if (L.y <= 0.0) shadow = 0.0;
	// a torch on the near box
	float3 torch = float3(sin(0.3) * 11.0 - 2.6, 0.5, -cos(0.3) * 11.0 + 2.6);
	half block = half(saturate(1.0 - length(p - torch) / 13.0));
	half sky = f.flags2.w >= 15 ? half(1.0) : half(0.0);
	float3 rad = shadeLit(shadeToLinear(half3(alb)), half3(n), sky, block, shadow, false, f);
	float vf = shadeVanillaFog(p, f.fogRange.xy, f.fogRange.zw);
	rad = shadeFogAir(rad, p, vf, lut, ls, f);
	return float4(shadeFinish(rad, vf, f), 1.0);
}

fragment float4 sky_only_fs(VOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], texture2d<half> lut [[texture(17)]], sampler ls [[sampler(13)]]) {
	float2 ndc = float2(in.pos.x * f.screen.z * 2.0 - 1.0, 1.0 - in.pos.y * f.screen.w * 2.0);
	float4 wp = f.invViewProj * float4(ndc, 0.5, 1.0);
	return float4(shadeSkyColor(normalize(wp.xyz / wp.w), lut, ls, f), 1.0);
}
