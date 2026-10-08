// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0. Dev tool (see dimstime.m).
// Full-screen fragments calling the M3 functions with every pixel paying (worst case).
fragment half4 t_empty(ShadeFsOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]]) { return half4(half2(in.uv), 0.0h, 1.0h); }
fragment half4 t_aurora(ShadeFsOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant ShadeLook &L [[buffer(17)]], texture2d<half> mw [[texture(0)]]) {
	float3 dir = normalize(float3(in.uv.x - 0.5, 0.4 + in.uv.y, 0.7));
	return half4(half3(shadeAurora(dir, f, L, mw, in.position.xy)), 1.0h);
}
// M3.3: the aurora before the quad sharing (M3.2's every-pixel version)
fragment half4 t_aurora_old(ShadeFsOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant ShadeLook &L [[buffer(17)]], texture2d<half> mw [[texture(0)]]) {
	float3 dir = normalize(float3(in.uv.x - 0.5, 0.4 + in.uv.y, 0.7));
	return half4(half3(shadeAuroraFull(dir, f, L, mw, in.position.xy)), 1.0h);
}
fragment half4 t_endsky(ShadeFsOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant ShadeLook &L [[buffer(17)]], texture2d<half> mw [[texture(0)]]) {
	float3 dir = normalize(float3(in.uv.x - 0.5, in.uv.y - 0.5, 0.7));
	return half4(half3(shadeEndSky(dir, f, L, mw, 0.0004)), 1.0h);
}
fragment half4 t_netherfog(ShadeFsOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant ShadeLook &L [[buffer(17)]], texture2d<half> mw [[texture(0)]]) {
	float3 wp = float3(in.uv.x - 0.5, in.uv.y - 0.6, 1.0) * 80.0;
	return half4(half3(shadeDimsFog(float3(0.2), wp, true, f, L, 1.0, false, mw)), 1.0h);
}
fragment half4 t_media(ShadeFsOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], texture2d<half> mw [[texture(0)]]) {
	return half4(half3(shadeMedia(float3(0.2), in.uv, 3.0, f, mw)), 1.0h);
}
