// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
//
// FT1: vertex stage for Distant Horizons' LOD terrain (pipelines distanthorizons:opaque_terrain / transparent_terrain),
// written for mcopt's shade model; it replaces DH's own stage (ShadeDh registers it). Only DH's data interface is shared
// (vertex attributes and uniform blocks, read from mcopt's translation of its pipeline): the decoding below is ours. DH's
// lightmap lookup is gone: the light levels go to the fragment stage raw (FT2.8: packed with the face and tile in one flat
// word) and the shade model lights the LOD.
//
// Vertex format (DH 3.3.x): position in blocks relative to the buffer's model offset (uint3), meta (low byte: the two
// light nibbles; second byte: micro-offset sign bits, which move LOD faces a hair outward on x / z so neighbouring LOD
// quads do not leave cracks), the block colour (RGBA, unorm), face index 0..5 (down, up, north, south, west, east) and the
// texture tile index (0 = none).
// Prepended: "#define MCOPT_SHADE", the shade prelude and the MCOPT_SLOT_* defines of the pipeline's uniforms.

struct DhShared {      // DH's "vertSharedUniformBlock"
	uint whiteWorld;
	float worldYOffset;
	float microOffset;
	float earthRadius;
	float frameMod8;
	float viewWidth;
	float viewHeight;
	float3 cameraPos;
	float4x4 combined;
};

struct DhUnique {      // DH's "vertUniqueUniformBlock"
	float3 modelOffset;
};

struct DhIn {
	uint3 position [[attribute(0)]];
	uint meta [[attribute(1)]];
	float4 color [[attribute(2)]];
	uint face [[attribute(4)]];
	uint tile [[attribute(5)]];
};

struct DhOut {
	float4 position [[position]];
	float3 p [[user(locn0)]];          // camera-relative world position (blocks)
	float3 cell [[user(locn1)]];       // position in the buffer's block grid: the texture tile's coordinates
	half4 color [[user(locn2)]];       // block colour (sRGB)
	// (FT2.8, audit idx 111) one flat word instead of 7 flat components (light, lightmap, face, tile: ~70 % of the LOD
	// fragment time went to the vertex stage, its outputs per vertex on a very dense mesh): bits 0-7 the light nibbles (DH's
	// meta low byte), 8-10 the face, 11-31 the texture tile (0 = none). dh_terrain.fs unpacks it, and reads the game's
	// lightmap itself where the shade model doesn't light
	uint pk [[user(locn3), flat]];
};

vertex DhOut main0(DhIn in [[stage_in]], constant DhShared &u [[buffer(MCOPT_SLOT_vertSharedUniformBlock)]],
                   constant DhUnique &m [[buffer(MCOPT_SLOT_vertUniqueUniformBlock)]], constant ShadeFrame &sf [[buffer(16)]],
                   texture2d<float> lightMap [[texture(MCOPT_SLOT_uLightMap)]], sampler lightMapSmplr [[sampler(MCOPT_SLOT_uLightMap)]]) {
	DhOut o;
	float3 cell = float3(in.position);
	float3 p = cell + (m.modelOffset - u.cameraPos);
	// micro offset: bit 0 / 1 = push along +x / -x, bit 4 / 5 = along +z / -z
	uint micro = (in.meta >> 8) & 0xFFu;
	float dx = (micro & 1u) != 0u ? u.microOffset : 0.0;
	float dz = (micro & 16u) != 0u ? u.microOffset : 0.0;
	p.x += (micro & 2u) != 0u ? -dx : dx;
	p.z += (micro & 32u) != 0u ? -dz : dz;
	// earth curvature (DH's option; off unless |radius| > 1): bend the LOD down along the sphere of that radius
	if (abs(u.earthRadius) > 1.0) {
		float r = u.earthRadius + cell.y + u.worldYOffset;
		float h = length(p.xz);
		float a = h / r;
		p.y += (cos(a) - 1.0) * r;
		if (a > 1e-6) p.xz *= sin(a) / a;
	}
	float4 clip = u.combined * float4(p, 1.0);
	// Metal's y (as mcopt's translation of DH's stage flips it); with SHADE_DH_JITTER (mcopt.shade.dh.jitter) the shade
	// frame's TAA jitter too (ShadeFrame.jitter.xy, NDC, added before the flip; 0 without TAA), so the LODs sit on the same
	// jittered raster as the rest of the world
	o.position = float4(clip.x, -clip.y, clip.z, clip.w);
#ifdef SHADE_DH_JITTER
	o.position.xy += float2(sf.jitter.x, -sf.jitter.y) * clip.w;
#endif
	o.p = p;
	o.cell = cell;
	o.color = u.whiteWorld != 0u ? half4(1.0h) : half4(in.color);
	o.pk = (in.tile << 11) | ((in.face & 7u) << 8) | (in.meta & 0xFFu);
	return o;
}
