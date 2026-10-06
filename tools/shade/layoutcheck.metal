// Echoes every ShadeFrame field (float4 by float4, in declaration order) for layoutcheck.m. Appended to shade_common.metal.
kernel void echo(constant ShadeFrame &f [[buffer(16)]], device float4 *o [[buffer(0)]], uint i [[thread_position_in_grid]]) {
	if (i != 0) return;
	int k = 0;
	for (int c = 0; c < 4; c++) o[k++] = f.view[c];
	for (int c = 0; c < 4; c++) o[k++] = f.proj[c];
	for (int c = 0; c < 4; c++) o[k++] = f.viewProj[c];
	for (int c = 0; c < 4; c++) o[k++] = f.invViewProj[c];
	for (int c = 0; c < 4; c++) o[k++] = f.prevViewProj[c];
	for (int c = 0; c < 4; c++) o[k++] = f.shadowViewProj[0][c];
	for (int c = 0; c < 4; c++) o[k++] = f.shadowViewProj[1][c];
	o[k++] = f.cameraBlock; o[k++] = f.cameraFract; o[k++] = f.sunDirWorld; o[k++] = f.sunDirView; o[k++] = f.moonDirWorld; o[k++] = f.lightDirWorld;
	o[k++] = f.sunColor; o[k++] = f.ambientSky; o[k++] = f.ambientGround; o[k++] = f.ambientBlock; o[k++] = f.fogColor; o[k++] = f.fogRange; o[k++] = f.fogSky;
	o[k++] = f.skyColor; o[k++] = f.zenithColor; for (int h = 0; h < 8; h++) o[k++] = f.horizon[h];
	o[k++] = f.cascadeSplits; o[k++] = f.shadowParams; o[k++] = f.screen; o[k++] = f.time; o[k++] = f.weather; o[k++] = f.jitter; o[k++] = f.atmosphere;
	o[k++] = f.ambientToward; o[k++] = f.ambientAway; o[k++] = float4(f.flags); o[k++] = float4(f.flags2); o[k++] = f.sunPath;
	o[k++] = float4(float(sizeof(ShadeFrame)));
}
