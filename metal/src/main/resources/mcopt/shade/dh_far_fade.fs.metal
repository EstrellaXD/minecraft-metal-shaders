// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
//
// FT2: fragment stage for Distant Horizons' far fade (written for mcopt; DH's own stage is replaced, only its uniform block
// layout and resource names are shared). The pass sees DH's LOD image and depth, and the frame's main colour, and its
// result is what DH finally copies into main.
//  - While the shade frame composites DH straight into its HDR world target (ShadeLook.waterShadow.y = 1, ShadeDh.directFrame:
//    no bridge, so main does not hold the frame): the LOD colour with its coverage as alpha, the LODs' own alpha times the
//    fade toward DH's far edge. dh_copy.fs composites that over the HDR target (ShadePost redirects DH's last copy there).
//  - Otherwise (the bridge put the frame into main; the shade setup's vanilla fallback): DH's composite over main, the LODs
//    faded into it toward their far edge.
// Prepended: "#define MCOPT_SHADE", the shade prelude and the MCOPT_SLOT_* defines of the pipeline's uniforms.

struct DhFadeU {       // DH's far fade "fragUniformBlock"
	float startFade;       // blocks from the camera where the LODs start to fade out
	float endFade;         // and where they are gone
	float4x4 invMvmProj;   // DH's clip -> camera-relative view
	uint depthZeroToOne;   // 1: the depth range is [0, 1] (else [-1, 1])
};

struct DhFadeIn {
	float2 texCoord [[user(locn0)]];
};

fragment float4 main0(DhFadeIn in [[stage_in]], constant DhFadeU &u [[buffer(MCOPT_SLOT_fragUniformBlock)]],
                      texture2d<float> mc [[texture(MCOPT_SLOT_uMcColorTexture)]], sampler mcSmplr [[sampler(MCOPT_SLOT_uMcColorTexture)]],
                      texture2d<float> dh [[texture(MCOPT_SLOT_uDhColorTexture)]], sampler dhSmplr [[sampler(MCOPT_SLOT_uDhColorTexture)]],
                      texture2d<float> dhDepth [[texture(MCOPT_SLOT_uDhDepthTexture)]], sampler dhDepthSmplr [[sampler(MCOPT_SLOT_uDhDepthTexture)]],
                      constant ShadeLook &sl [[buffer(17)]]) {
	float4 c = dh.sample(dhSmplr, in.texCoord);
	float z = dhDepth.sample(dhDepthSmplr, in.texCoord).x;
	float4 ndc = u.depthZeroToOne != 0u ? float4(in.texCoord * 2.0 - 1.0, z, 1.0) : float4(float3(in.texCoord, z) * 2.0 - 1.0, 1.0);
	float4 e = u.invMvmProj * ndc;
	float dist = length(e.xyz / e.w);
	// how much of the LOD stays: 1 out to the fade's start, 0 past its end, a smooth step between (either order of the two)
	float span = u.startFade - u.endFade;
	float t = abs(span) > 1e-3 ? saturate((dist - u.endFade) / span) : (dist < u.startFade ? 1.0 : 0.0);
	float keep = t * t * (3.0 - 2.0 * t);
	if (sl.waterShadow.y > 0.5) return float4(c.rgb, saturate(c.a) * keep);
	float4 m = mc.sample(mcSmplr, in.texCoord);
	return float4(mix(m.rgb, c.a > 0.0 ? c.rgb : m.rgb, keep), 1.0);
}
