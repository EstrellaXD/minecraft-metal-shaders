// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading, not part of the mod.
// GPU time of shade's final pass at 3024x1964 for one or more post libraries (prelude + shade_post.metal), overworld noon-like
// inputs (every pixel has depth: the haze path), plus the XDR compose (RGBA8 + RG11B10F -> RGB9E5) vs the plain staging copy.
// finaltime <libA.metal> [libB.metal ...]   (see run.sh). Interleaves the libraries over several rounds; prints the best times.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

static id<MTLTexture> tex(id<MTLDevice> d, MTLPixelFormat f, int w, int h) {
	MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:f width:w height:h mipmapped:NO];
	td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
	td.storageMode = MTLStorageModePrivate;
	return [d newTextureWithDescriptor:td];
}

static void clearTo(id<MTLCommandQueue> q, id<MTLTexture> t, double v) {
	id<MTLCommandBuffer> cb = [q commandBuffer];
	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	if (t.pixelFormat == MTLPixelFormatDepth32Float) {
		rp.depthAttachment.texture = t;
		rp.depthAttachment.loadAction = MTLLoadActionClear;
		rp.depthAttachment.clearDepth = v;
		rp.depthAttachment.storeAction = MTLStoreActionStore;
	} else {
		rp.colorAttachments[0].texture = t;
		rp.colorAttachments[0].loadAction = MTLLoadActionClear;
		rp.colorAttachments[0].clearColor = MTLClearColorMake(v, v * 0.8, v * 0.6, 1.0);
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	}
	[[cb renderCommandEncoderWithDescriptor:rp] endEncoding];
	[cb commit];
	[cb waitUntilCompleted];
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
		id<MTLCommandQueue> q = [dev newCommandQueue];
		int W = 3024, H = 1964;
		id<MTLTexture> scene = tex(dev, MTLPixelFormatRGBA16Float, W, H), bloom = tex(dev, MTLPixelFormatRGBA16Float, W / 4, H / 4),
			bloom0 = tex(dev, MTLPixelFormatRGBA16Float, W / 2, H / 2), vlT = tex(dev, MTLPixelFormatRGBA16Float, W / 2, H / 2),
			vlD = tex(dev, MTLPixelFormatRGBA16Float, W / 2, H / 2), depth = tex(dev, MTLPixelFormatDepth32Float, W, H),
			out8 = tex(dev, MTLPixelFormatRGBA8Unorm, W, H), excess = tex(dev, MTLPixelFormatRG11B10Float, W, H),
			stage9 = tex(dev, MTLPixelFormatRGB9E5Float, W, H), stage8 = tex(dev, MTLPixelFormatRGBA8Unorm, W, H);
		clearTo(q, scene, 0.6); clearTo(q, bloom, 0.3); clearTo(q, bloom0, 0.3); clearTo(q, vlT, 0.05); clearTo(q, vlD, 40.0);
		clearTo(q, depth, 0.5); clearTo(q, excess, 0.2); clearTo(q, out8, 0.5);
		id<MTLBuffer> P = [dev newBufferWithLength:256 options:MTLResourceStorageModeShared];
		id<MTLBuffer> F = [dev newBufferWithLength:2048 options:MTLResourceStorageModeShared];
		id<MTLBuffer> L = [dev newBufferWithLength:2048 options:MTLResourceStorageModeShared];
		id<MTLBuffer> G = [dev newBufferWithLength:1024 options:MTLResourceStorageModeShared];
		id<MTLBuffer> R = [dev newBufferWithLength:1024 options:MTLResourceStorageModeShared];
		float *p = P.contents, *f = F.contents, *l = L.contents, *g = G.contents;
		// P: p (bloom 0.04, on, frame, debug 0), q, v (VL on, 64 blocks, uv scale 1), r (no DRS, sharpen 0.3, no DH), x (XDR on, peak 4, knee 3, 4 octaves)
		float pv[] = {0.04f, 1, 7, 0, 0.3f, 0, 0, 0, 1, 64, 1, 1, 0, 0, 0.3f, 0, 1, 4, 3, 4};
		memcpy(p, pv, sizeof pv);
		for (int i = 0; i < 4; i++) { f[i * 5] = 1; f[16 + i * 5] = 1; f[32 + i * 5] = 1; f[48 + i * 5] = 1; } // view, proj, viewProj, invViewProj ~ identity
		f[48 + 11] = -0.1f; f[48 + 14] = 0.1f; f[48 + 15] = 0.0f; // invViewProj: a perspective-ish w (depth -> distance)
		for (int i = 0; i < 8; i++) { f[688 / 4 + i * 4] = 0.8f; f[688 / 4 + i * 4 + 1] = 0.9f; f[688 / 4 + i * 4 + 2] = 1.2f; }
		f[480 / 4 + 1] = 0.9f; f[480 / 4 + 3] = 0.9f; f[544 / 4] = 3; f[544 / 4 + 1] = 3; f[544 / 4 + 2] = 3; f[640 / 4 + 2] = 0.004f; f[640 / 4 + 3] = 1;
		f[864 / 4 + 2] = 0.25f; f[912 / 4] = 1;
		((int *) l)[800 / 4] = 1; ((int *) l)[800 / 4 + 1] = 0xffff; // tier balanced; every M2 feature bit (not the M3 high bits)
		for (int i = 0; i < 400; i++) if (l[i] == 0) l[i] = 0.5f;
		((int *) l)[800 / 4] = 1; ((int *) l)[800 / 4 + 1] = 0xffff;
		g[0] = 0.25f; // exposure
		struct Pso { const char *name; id<MTLRenderPipelineState> pso; int mrt; double best; };
		NSMutableArray *names = [NSMutableArray array];
		struct Pso psos[16]; int n = 0;
		for (int a = 1; a < argc; a++) {
			NSError *e = nil;
			NSString *src = [NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[a]] encoding:NSUTF8StringEncoding error:&e];
			MTLCompileOptions *o = [MTLCompileOptions new];
			o.languageVersion = MTLLanguageVersion3_0;
			o.mathMode = MTLMathModeFast;
			id<MTLLibrary> lib = [dev newLibraryWithSource:src options:o error:&e];
			if (!lib) { printf("compile %s: %s\n", argv[a], e.localizedDescription.UTF8String); return 1; }
			for (int x = 0; x < 2; x++) {
				const char *fn = x ? "shade_final_xdr" : "shade_final";
				id<MTLFunction> ff = [lib newFunctionWithName:[NSString stringWithUTF8String:fn]];
				if (!ff) continue;
				MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
				pd.vertexFunction = [lib newFunctionWithName:@"shade_fs_vs"];
				pd.fragmentFunction = ff;
				pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
				if (x) pd.colorAttachments[1].pixelFormat = MTLPixelFormatRG11B10Float;
				id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:pd error:&e];
				if (!pso) { printf("pso %s: %s\n", fn, e.localizedDescription.UTF8String); return 1; }
				char *label = malloc(256);
				snprintf(label, 256, "%s:%s", [[[NSString stringWithUTF8String:argv[a]] lastPathComponent] UTF8String], fn);
				psos[n++] = (struct Pso) {label, pso, x, 1e9};
			}
		}
		// the XDR compose (as in mcxdr.m) and the plain copy it replaces
		NSString *cs = @"#include <metal_stdlib>\nusing namespace metal;\nstruct V { float4 pos [[position]]; };\n"
			"vertex V xvs(uint id [[vertex_id]]) { float2 p = float2((id << 1) & 2, id & 2); V v; v.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); return v; }\n"
			"static float3 s2l(float3 c) { return select(pow((c + 0.055) / 1.055, 2.4), c / 12.92, c <= 0.04045); }\n"
			"constant float3x3 M = float3x3(float3(0.822462, 0.033194, 0.017083), float3(0.177538, 0.966806, 0.072397), float3(0.0, 0.0, 0.910520));\n"
			"fragment float4 xfs(V v [[stage_in]], texture2d<float> src [[texture(0)]], texture2d<half> ex [[texture(1)]]) {\n"
			"  uint2 px = uint2(v.pos.xy); float4 m = src.read(px); float3 lin = s2l(saturate(m.rgb)) + float3(ex.read(px).rgb) * saturate(1.0 - m.a); return float4(M * lin, 1.0); }\n";
		NSError *e = nil;
		id<MTLLibrary> clib = [dev newLibraryWithSource:cs options:nil error:&e];
		MTLRenderPipelineDescriptor *cd = [MTLRenderPipelineDescriptor new];
		cd.vertexFunction = [clib newFunctionWithName:@"xvs"];
		cd.fragmentFunction = [clib newFunctionWithName:@"xfs"];
		cd.colorAttachments[0].pixelFormat = MTLPixelFormatRGB9E5Float;
		id<MTLRenderPipelineState> compose = [dev newRenderPipelineStateWithDescriptor:cd error:&e];
		double bestCompose = 1e9, bestCopy = 1e9;
		for (int round = 0; round < 8; round++) {
			for (int k = 0; k < n; k++) {
				for (int it = 0; it < 8; it++) {
					id<MTLCommandBuffer> cb = [q commandBuffer];
					MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
					rp.colorAttachments[0].texture = out8;
					rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
					rp.colorAttachments[0].storeAction = MTLStoreActionStore;
					if (psos[k].mrt) {
						rp.colorAttachments[1].texture = excess;
						rp.colorAttachments[1].loadAction = MTLLoadActionDontCare;
						rp.colorAttachments[1].storeAction = MTLStoreActionStore;
					}
					id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
					[r setRenderPipelineState:psos[k].pso];
					id<MTLTexture> ts[] = {scene, bloom, depth, bloom0, vlT, vlD};
					for (int t = 0; t < 6; t++) [r setFragmentTexture:ts[t] atIndex:t];
					[r setFragmentBuffer:P offset:0 atIndex:0];
					[r setFragmentBuffer:F offset:0 atIndex:16];
					[r setFragmentBuffer:L offset:0 atIndex:17];
					[r setFragmentBuffer:G offset:0 atIndex:18];
					[r setFragmentBuffer:R offset:0 atIndex:19];
					[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
					[r endEncoding];
					[cb commit];
					[cb waitUntilCompleted];
					double ms = (cb.GPUEndTime - cb.GPUStartTime) * 1e3;
					if (it > 1 && ms < psos[k].best) psos[k].best = ms;
				}
			}
			for (int it = 0; it < 8; it++) {
				id<MTLCommandBuffer> cb = [q commandBuffer];
				MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
				rp.colorAttachments[0].texture = stage9;
				rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
				rp.colorAttachments[0].storeAction = MTLStoreActionStore;
				id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
				[r setRenderPipelineState:compose];
				[r setFragmentTexture:out8 atIndex:0];
				[r setFragmentTexture:excess atIndex:1];
				[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
				[r endEncoding];
				[cb commit];
				[cb waitUntilCompleted];
				double ms = (cb.GPUEndTime - cb.GPUStartTime) * 1e3;
				if (it > 1 && ms < bestCompose) bestCompose = ms;
				cb = [q commandBuffer];
				id<MTLBlitCommandEncoder> b = [cb blitCommandEncoder];
				[b copyFromTexture:out8 toTexture:stage8];
				[b endEncoding];
				[cb commit];
				[cb waitUntilCompleted];
				ms = (cb.GPUEndTime - cb.GPUStartTime) * 1e3;
				if (it > 1 && ms < bestCopy) bestCopy = ms;
			}
		}
		for (int k = 0; k < n; k++) printf("%-40s %.3f ms\n", psos[k].name, psos[k].best);
		printf("%-40s %.3f ms\n", "xdr compose -> RGB9E5 staging", bestCompose);
		printf("%-40s %.3f ms\n", "plain staging copy (RGBA8 blit)", bestCopy);
	}
	return 0;
}
