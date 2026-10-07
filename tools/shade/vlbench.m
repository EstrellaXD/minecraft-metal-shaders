// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
// Dev tool: GPU cost of each volumetric light pass at a given resolution, outside the game (no queue for the harness, no
// other apps' noise per pass). Synthetic but representative inputs: a camera looking slightly up toward a low sun, the
// lower half of the screen terrain at ~25 blocks, the upper half sky; a 2048^2 x 2 shadow map half covered by casters;
// golden-hour forest parameters (canopy 1, every term on). Builds the same pipelines as mcvl.m and times each pass alone
// (GPU start/end of a command buffer that encodes it N times) and the whole frame.
// Usage: vlbench <ShadeMsl.prelude (shade_look + shade_common + shade_m2) + vl.metal> [width height scale steps taps]
//   tools/shade/vlbench.sh assembles the source and runs it.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <math.h>

typedef struct { float m[16]; } M4; // column-major

static M4 mul(M4 a, M4 b) {
	M4 r;
	for (int c = 0; c < 4; c++)
		for (int rr = 0; rr < 4; rr++) {
			float s = 0;
			for (int k = 0; k < 4; k++) s += a.m[k * 4 + rr] * b.m[c * 4 + k];
			r.m[c * 4 + rr] = s;
		}
	return r;
}

static M4 inv(M4 a) { // Gauss-Jordan on a row-major copy
	double m[4][8];
	for (int r = 0; r < 4; r++)
		for (int c = 0; c < 4; c++) {
			m[r][c] = a.m[c * 4 + r];
			m[r][c + 4] = r == c;
		}
	for (int c = 0; c < 4; c++) {
		int p = c;
		for (int r = c + 1; r < 4; r++) if (fabs(m[r][c]) > fabs(m[p][c])) p = r;
		for (int k = 0; k < 8; k++) { double t = m[c][k]; m[c][k] = m[p][k]; m[p][k] = t; }
		double d = m[c][c];
		for (int k = 0; k < 8; k++) m[c][k] /= d;
		for (int r = 0; r < 4; r++) if (r != c) {
			double f = m[r][c];
			for (int k = 0; k < 8; k++) m[r][k] -= f * m[c][k];
		}
	}
	M4 o;
	for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) o.m[c * 4 + r] = (float) m[r][c + 4];
	return o;
}

static void put(float *f, int byteOff, const float *v, int n) { memcpy(f + byteOff / 4, v, n * 4); }

static const char *DEPTH_SRC =
	"#include <metal_stdlib>\nusing namespace metal;\n"
	"struct O { float4 p [[position]]; };\n"
	"vertex O dvs(uint v [[vertex_id]], constant float &z [[buffer(0)]], constant float &top [[buffer(1)]]) {\n"
	"  float2 q[6] = {float2(-1,-1), float2(1,-1), float2(-1,top), float2(1,-1), float2(1,top), float2(-1,top)};\n"
	"  O o; o.p = float4(q[v], z, 1); return o; }\n"
	"fragment void dfs() {}\n"
	"struct D { float d [[depth(any)]]; };\n"
	"fragment D dnoise(O in [[stage_in]]) { uint2 b = uint2(in.p.xy) / 6u; uint h = b.x * 73856093u ^ b.y * 19349663u; h ^= h >> 13; h *= 0x5bd1e995u; h ^= h >> 15;\n"
	"  D o; o.d = (h & 1u) != 0u ? 0.55 + float(h & 255u) / 2550.0 : 0.0; return o; }\n";

int main(int argc, const char **argv) {
	@autoreleasepool {
		if (argc < 2) { fprintf(stderr, "usage: vlbench <vl source> [w h scale steps taps]\n"); return 2; }
		int W = argc > 3 ? atoi(argv[2]) : 3024, H = argc > 3 ? atoi(argv[3]) : 1964;
		float scale = argc > 4 ? atof(argv[4]) : 0.5f;
		int steps = argc > 5 ? atoi(argv[5]) : 14, taps = argc > 6 ? atoi(argv[6]) : 8;
		int vw = (int) lroundf(W * scale), vh = (int) lroundf(H * scale);
		id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
		id<MTLCommandQueue> q = [dev newCommandQueue];
		NSError *e = nil;
		NSString *src = [NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&e];
		MTLCompileOptions *o = [MTLCompileOptions new];
		o.languageVersion = MTLLanguageVersion3_0;
		o.mathMode = MTLMathModeFast;
		id<MTLLibrary> lib = [dev newLibraryWithSource:src options:o error:&e];
		if (!lib) { printf("compile: %s\n", e.localizedDescription.UTF8String); return 1; }
		id<MTLLibrary> dlib = [dev newLibraryWithSource:[NSString stringWithUTF8String:DEPTH_SRC] options:nil error:&e];

		// pipelines (as mcvl.m)
		MTLFunctionConstantValues *fc = [MTLFunctionConstantValues new];
		bool hdr = false;
		[fc setConstantValue:&hdr type:MTLDataTypeBool atIndex:0];
		id<MTLRenderPipelineState> (^pso)(NSString *, MTLPixelFormat) = ^id<MTLRenderPipelineState>(NSString *fs, MTLPixelFormat fmt) {
			NSError *err = nil;
			MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
			pd.vertexFunction = [lib newFunctionWithName:@"vl_vs"];
			pd.fragmentFunction = [lib newFunctionWithName:fs constantValues:fc error:&err];
			pd.colorAttachments[0].pixelFormat = fmt;
			if ([fs isEqualToString:@"vl_march"]) pd.colorAttachments[1].pixelFormat = MTLPixelFormatR8Unorm;
			id<MTLRenderPipelineState> p = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
			if (!p) printf("pso %s: %s\n", fs.UTF8String, err.localizedDescription.UTF8String);
			return p;
		};
		id<MTLRenderPipelineState> march = pso(@"vl_march", MTLPixelFormatRGBA16Float), temporal = pso(@"vl_temporal", MTLPixelFormatRGBA16Float),
			composite = pso(@"vl_composite", MTLPixelFormatRGBA8Unorm);
		// the M2 variant: vl_composite_hdr, RG11B10F scene target, scene * T + light
		MTLFunctionConstantValues *fch = [MTLFunctionConstantValues new];
		bool yes = true;
		[fch setConstantValue:&yes type:MTLDataTypeBool atIndex:0];
		MTLRenderPipelineDescriptor *hpd = [MTLRenderPipelineDescriptor new];
		hpd.vertexFunction = [lib newFunctionWithName:@"vl_vs"];
		hpd.fragmentFunction = [lib newFunctionWithName:@"vl_composite_hdr" constantValues:fch error:&e];
		hpd.colorAttachments[0].pixelFormat = MTLPixelFormatRG11B10Float; // M2's default HDR world format (performance/balanced)
		hpd.colorAttachments[0].blendingEnabled = YES;
		hpd.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorOne;
		hpd.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorSourceAlpha;
		hpd.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorZero;
		hpd.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOne;
		id<MTLRenderPipelineState> compositeHdr = [dev newRenderPipelineStateWithDescriptor:hpd error:&e];
		if (!compositeHdr) printf("hdr pso: %s\n", e.localizedDescription.UTF8String);
		id<MTLComputePipelineState> canopy = [dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"vl_canopy"] error:&e];
		MTLRenderPipelineDescriptor *dpd = [MTLRenderPipelineDescriptor new];
		dpd.vertexFunction = [dlib newFunctionWithName:@"dvs"];
		dpd.fragmentFunction = [dlib newFunctionWithName:@"dfs"];
		dpd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
		id<MTLRenderPipelineState> dpso = [dev newRenderPipelineStateWithDescriptor:dpd error:&e];
		dpd.fragmentFunction = [dlib newFunctionWithName:@"dnoise"];
		id<MTLRenderPipelineState> npso = [dev newRenderPipelineStateWithDescriptor:dpd error:&e]; // shadow map: blocky casters (6-texel cells, half covered)
		MTLDepthStencilDescriptor *dsd = [MTLDepthStencilDescriptor new];
		dsd.depthCompareFunction = MTLCompareFunctionAlways;
		dsd.depthWriteEnabled = YES;
		id<MTLDepthStencilState> dss = [dev newDepthStencilStateWithDescriptor:dsd];

		// textures
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:W height:H mipmapped:NO];
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
		id<MTLTexture> depth = [dev newTextureWithDescriptor:td];
		td.pixelFormat = MTLPixelFormatRGBA8Unorm;
		id<MTLTexture> color = [dev newTextureWithDescriptor:td];
		td.pixelFormat = MTLPixelFormatRG11B10Float;
		id<MTLTexture> scene = [dev newTextureWithDescriptor:td];
		td.pixelFormat = MTLPixelFormatRGBA16Float;
		td.width = vw;
		td.height = vh;
		id<MTLTexture> raw = [dev newTextureWithDescriptor:td], hist0 = [dev newTextureWithDescriptor:td], hist1 = [dev newTextureWithDescriptor:td];
		td.pixelFormat = MTLPixelFormatR8Unorm;
		id<MTLTexture> sky0 = [dev newTextureWithDescriptor:td], sky1 = [dev newTextureWithDescriptor:td];
		MTLTextureDescriptor *sd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:2048 height:2048 mipmapped:NO];
		sd.textureType = MTLTextureType2DArray;
		sd.arrayLength = 2;
		sd.storageMode = MTLStorageModePrivate;
		sd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
		id<MTLTexture> shadow = [dev newTextureWithDescriptor:sd];
		MTLSamplerDescriptor *smp = [MTLSamplerDescriptor new];
		smp.minFilter = smp.magFilter = MTLSamplerMinMagFilterLinear;
		smp.compareFunction = MTLCompareFunctionGreaterEqual;
		smp.sAddressMode = smp.tAddressMode = MTLSamplerAddressModeClampToEdge;
		id<MTLSamplerState> cmp = [dev newSamplerStateWithDescriptor:smp];

		// fill: depth = lower 55% terrain (~25 blocks), upper sky; shadow map: half covered (casters at 0.6, the rest 0)
		id<MTLCommandBuffer> cb = [q commandBuffer];
		{
			MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
			rp.depthAttachment.texture = depth;
			rp.depthAttachment.loadAction = MTLLoadActionClear;
			rp.depthAttachment.clearDepth = 0.0;
			rp.depthAttachment.storeAction = MTLStoreActionStore;
			id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
			[r setRenderPipelineState:dpso];
			[r setDepthStencilState:dss];
			float z = 0.002f, top = 0.1f;
			[r setVertexBytes:&z length:4 atIndex:0];
			[r setVertexBytes:&top length:4 atIndex:1];
			[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];
			[r endEncoding];
			for (int s = 0; s < 2; s++) {
				MTLRenderPassDescriptor *sp = [MTLRenderPassDescriptor renderPassDescriptor];
				sp.depthAttachment.texture = shadow;
				sp.depthAttachment.slice = s;
				sp.depthAttachment.loadAction = MTLLoadActionClear;
				sp.depthAttachment.clearDepth = 0.0;
				sp.depthAttachment.storeAction = MTLStoreActionStore;
				id<MTLRenderCommandEncoder> r2 = [cb renderCommandEncoderWithDescriptor:sp];
				[r2 setRenderPipelineState:npso];
				[r2 setDepthStencilState:dss];
				float z2 = 0.6f, top2 = 1.0f;
				[r2 setVertexBytes:&z2 length:4 atIndex:0];
				[r2 setVertexBytes:&top2 length:4 atIndex:1];
				[r2 drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];
				[r2 endEncoding];
			}
		}
		[cb commit];
		[cb waitUntilCompleted];

		// ShadeFrame
		float F[252] = {0};
		float fovy = 70.0f * M_PI / 180.0f, a = (float) W / H, fy = 1.0f / tanf(fovy / 2), nearZ = 0.05f;
		M4 proj = {{fy / a, 0, 0, 0, 0, fy, 0, 0, 0, 0, 0, -1, 0, 0, nearZ, 0}};
		float pitch = 10.0f * M_PI / 180.0f, yaw = 0; // looking toward +x (east), 10 degrees up
		float fx = cosf(pitch) * cosf(yaw), fyv = sinf(pitch), fz = cosf(pitch) * sinf(yaw);
		float f3[3] = {fx, fyv, fz}, up[3] = {0, 1, 0}, rt[3] = {f3[1] * up[2] - f3[2] * up[1], f3[2] * up[0] - f3[0] * up[2], f3[0] * up[1] - f3[1] * up[0]};
		float rl = sqrtf(rt[0] * rt[0] + rt[1] * rt[1] + rt[2] * rt[2]);
		for (int i = 0; i < 3; i++) rt[i] /= rl;
		float u2[3] = {rt[1] * f3[2] - rt[2] * f3[1], rt[2] * f3[0] - rt[0] * f3[2], rt[0] * f3[1] - rt[1] * f3[0]};
		M4 view = {{rt[0], u2[0], -f3[0], 0, rt[1], u2[1], -f3[1], 0, rt[2], u2[2], -f3[2], 0, 0, 0, 0, 1}};
		M4 vp = mul(proj, view), ivp = inv(vp);
		M4 sh0 = {{1 / 32.f, 0, 0, 0, 0, 0, 1 / 96.f, 0, 0, 1 / 32.f, 0, 0, 0, 0, 0.5f, 1}};
		M4 sh1 = {{1 / 128.f, 0, 0, 0, 0, 0, 1 / 256.f, 0, 0, 1 / 128.f, 0, 0, 0, 0, 0.5f, 1}};
		put(F, 0, view.m, 16);
		put(F, 64, proj.m, 16);
		put(F, 128, vp.m, 16);
		put(F, 192, ivp.m, 16);
		put(F, 256, vp.m, 16);
		put(F, 320, sh0.m, 16);
		put(F, 384, sh1.m, 16);
		float L[4] = {0.96f, 0.27f, 0, 1};
		put(F, 528, L, 4);
		float t[4] = {0.8f, 100, 1.0f, 0};
		put(F, 864, t, 4);
		float sc[4] = {32, 128, 1 / 64.f, 1 / 16.f};
		put(F, 816, sc, 4);
		float sp[4] = {1 / 2048.f, 2048, 0.00015f, 1.5f};
		put(F, 832, sp, 4);
		int flags[4] = {0, 0, 1, 0};
		memcpy((char *) F + 960, flags, 16);
		id<MTLBuffer> frame = [dev newBufferWithBytes:F length:1008 options:MTLResourceStorageModeShared];

		// VlParams: golden-hour forest, canopy 1
		float Pv[64] = {
			(float) vw, (float) vh, (float) W, (float) H, // size
			1.0f, 160, (float) steps, 0.68f,              // shape
			0.0012f, 0.005f, -10, 12,                     // medium
			6, 48, 0.35f, 0.05f,                          // haze
			1.2f, 0.8f, 0.5f, 0.024f,                     // light
			0.3f, (float) taps, 1.0f, 6,                  // crep
			W * 0.85f, H * 0.7f, 0, 0,                    // sunPx
			0.4f, 0.11f, 0.06f, 0.022f,                   // water
			4, 0, 48, 1,                                  // water2
			0.9f, 0, 5, 100,                              // temporal
			0.01f, 0, 0, 0.01f,                           // camDelta
			0.7f, 1.6f, 1.0f, 0,                          // gate (canopy forced 1)
			0, 8, 0.004f, 0,                              // extra
			16, 4, 1, 0.5f,                               // mist
			0.05f, 0.06f, 0.08f, 0.5f,                    // mistAmb
			0, 0, 0, 0};
		const float *P = Pv;
		float G[128] = {0};
		G[44] = 1.0f; // ShadeGpu.exposure.x (byte 176)
		id<MTLBuffer> gpuBuf = [dev newBufferWithBytes:G length:512 options:MTLResourceStorageModeShared];
		id<MTLBuffer> state = [dev newBufferWithLength:16 options:MTLResourceStorageModeShared];
		memset(state.contents, 0, 16);

		typedef void (^Enc)(id<MTLCommandBuffer>);
		void (^pass)(id<MTLCommandBuffer>, id<MTLTexture>, id<MTLTexture>, MTLLoadAction, id<MTLRenderPipelineState>, id<MTLBuffer>, id<MTLTexture>, id<MTLTexture>) =
			^(id<MTLCommandBuffer> c, id<MTLTexture> out, id<MTLTexture> out1, MTLLoadAction load, id<MTLRenderPipelineState> p, id<MTLBuffer> st, id<MTLTexture> t0, id<MTLTexture> t1) {
				MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
				rp.colorAttachments[0].texture = out;
				rp.colorAttachments[0].loadAction = load;
				rp.colorAttachments[0].storeAction = MTLStoreActionStore;
				if (out1) {
					rp.colorAttachments[1].texture = out1;
					rp.colorAttachments[1].loadAction = MTLLoadActionDontCare;
					rp.colorAttachments[1].storeAction = MTLStoreActionStore;
				}
				id<MTLRenderCommandEncoder> r = [c renderCommandEncoderWithDescriptor:rp];
				[r setRenderPipelineState:p];
				[r setFragmentBuffer:frame offset:0 atIndex:16];
				[r setFragmentTexture:shadow atIndex:16];
				[r setFragmentSamplerState:cmp atIndex:12];
				[r setFragmentBytes:P length:256 atIndex:0];
				[r setFragmentBuffer:gpuBuf offset:0 atIndex:18];
				if (st) [r setFragmentBuffer:st offset:0 atIndex:1];
				if (t0) [r setFragmentTexture:t0 atIndex:0];
				if (t1) [r setFragmentTexture:t1 atIndex:1];
				[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
				[r endEncoding];
			};
		Enc eCanopy = ^(id<MTLCommandBuffer> c) {
			id<MTLComputeCommandEncoder> k = [c computeCommandEncoder];
			[k setComputePipelineState:canopy];
			[k setBuffer:frame offset:0 atIndex:16];
			[k setTexture:shadow atIndex:16];
			[k setBytes:P length:256 atIndex:0];
			[k setBuffer:state offset:0 atIndex:1];
			[k dispatchThreadgroups:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[k endEncoding];
		};
		Enc eMarch = ^(id<MTLCommandBuffer> c) { pass(c, raw, sky1, MTLLoadActionDontCare, march, state, depth, sky0); };
		Enc eTemporal = ^(id<MTLCommandBuffer> c) { pass(c, hist1, nil, MTLLoadActionDontCare, temporal, nil, raw, hist0); };
		Enc eComposite = ^(id<MTLCommandBuffer> c) { pass(c, color, nil, MTLLoadActionLoad, composite, state, hist1, depth); };
		Enc eCompositeHdr = ^(id<MTLCommandBuffer> c) { pass(c, scene, nil, MTLLoadActionLoad, compositeHdr, state, hist1, depth); };
		Enc eAll = ^(id<MTLCommandBuffer> c) { eCanopy(c); eMarch(c); eTemporal(c); eComposite(c); };
		double (^time)(Enc) = ^double(Enc en) {
			const int N = 30, R = 5;
			double best = 1e9;
			for (int rep = 0; rep < R; rep++) {
				id<MTLCommandBuffer> c = [q commandBuffer];
				for (int i = 0; i < N; i++) en(c);
				[c commit];
				[c waitUntilCompleted];
				double ms = (c.GPUEndTime - c.GPUStartTime) * 1000.0 / N;
				if (rep > 0 && ms < best) best = ms; // first one warms up
			}
			return best;
		};
		eAll(cb = [q commandBuffer]);
		[cb commit];
		[cb waitUntilCompleted];
		printf("vlbench %dx%d, VL %dx%d (scale %.3f), %d steps + %d taps\n", W, H, vw, vh, scale, steps, taps);
		printf("  canopy    %.3f ms\n", time(eCanopy));
		printf("  march     %.3f ms\n", time(eMarch));
		printf("  temporal  %.3f ms\n", time(eTemporal));
		printf("  composite %.3f ms\n", time(eComposite));
		printf("  frame     %.3f ms\n", time(eAll));
		printf("  composite (M2 HDR, RGBA16F) %.3f ms\n", time(eCompositeHdr));
		return 0;
	}
}
