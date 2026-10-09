// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
//
// FT2: fragment stage for Distant Horizons' copy pipeline (written for mcopt; DH's own stage is replaced, only its resource
// name is shared). DH copies its LOD image between its own targets and, last, into the frame's main colour.
//  - Into its own (RGBA8) targets, and into main when the shade frame does not redirect it: the plain copy.
//  - Into the HDR world target (MCOPT_HDR, prepended by the format adapter: ShadePost redirects DH's last copy there while
//    ShadeDh.directFrame): the composite that replaces the bridge. The LOD colour (display-referred, as dh_terrain.fs wrote it)
//    goes back to pre-exposed radiance through the inverse display transform, over what the target holds (the sky, the
//    clouds), by the coverage dh_far_fade.fs left in alpha; programmable blending, nothing written where no LOD is.
// Prepended: "#define MCOPT_SHADE", the shade prelude and the MCOPT_SLOT_* defines of the pipeline's uniforms.

struct DhCopyIn {
	float2 texCoord [[user(locn0)]];
};

#ifdef MCOPT_HDR
fragment float4 main0(DhCopyIn in [[stage_in]], float4 dst [[color(0)]], texture2d<float> src [[texture(MCOPT_SLOT_uCopyTexture)]],
                      sampler srcSmplr [[sampler(MCOPT_SLOT_uCopyTexture)]], constant ShadeLook &sl [[buffer(17)]]) {
	float4 c = src.sample(srcSmplr, in.texCoord);
	if (c.a <= 0.0) discard_fragment();
	return float4(mix(dst.rgb, shadeLdrToHdr(c.rgb, sl), saturate(c.a)), dst.a);
}
#else
fragment float4 main0(DhCopyIn in [[stage_in]], texture2d<float> src [[texture(MCOPT_SLOT_uCopyTexture)]],
                      sampler srcSmplr [[sampler(MCOPT_SLOT_uCopyTexture)]]) {
	return src.sample(srcSmplr, in.texCoord);
}
#endif
