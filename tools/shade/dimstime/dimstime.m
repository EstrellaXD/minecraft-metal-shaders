// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading, not part of the mod.
// GPU cost of the M3 sky/dimension shader functions over a full 3024x1964 target (worst case: every pixel pays):
// dimstime <library.metal>  (the library: prelude + shade_post.metal + dimstime.metal, see run.sh)
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
int main(int argc, const char **argv) {
	@autoreleasepool {
		id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
		NSError *e = nil;
		NSString *src = [NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&e];
		MTLCompileOptions *o = [MTLCompileOptions new];
		o.languageVersion = MTLLanguageVersion3_0;
		o.mathMode = MTLMathModeFast;
		id<MTLLibrary> lib = [dev newLibraryWithSource:src options:o error:&e];
		if (!lib) { printf("compile: %s\n", e.localizedDescription.UTF8String); return 1; }
		int W = 3024, H = 1964;
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:W height:H mipmapped:NO];
		td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
		td.storageMode = MTLStorageModePrivate;
		id<MTLTexture> target = [dev newTextureWithDescriptor:td];
		// the noise texture (milkyWay.a stand-in): random halves
		MTLTextureDescriptor *nd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:1024 height:512 mipmapped:NO];
		id<MTLTexture> noise = [dev newTextureWithDescriptor:nd];
		uint16_t *buf = malloc(1024 * 512 * 8);
		for (int i = 0; i < 1024 * 512 * 4; i++) buf[i] = 0x3800 + (arc4random() & 0x3ff); // 0.5 .. 1
		[noise replaceRegion:MTLRegionMake2D(0, 0, 1024, 512) mipmapLevel:0 withBytes:buf bytesPerRow:1024 * 8];
		if (argc < 3) { // the real bake (shade_post.metal shade_milkyway_bake: Milky Way + the tileable fbm in alpha)
			nd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
			nd.storageMode = MTLStorageModePrivate;
			noise = [dev newTextureWithDescriptor:nd];
			MTLRenderPipelineDescriptor *bp = [MTLRenderPipelineDescriptor new];
			bp.vertexFunction = [lib newFunctionWithName:@"shade_fs_vs"];
			bp.fragmentFunction = [lib newFunctionWithName:@"shade_milkyway_bake"];
			bp.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
			id<MTLRenderPipelineState> bake = [dev newRenderPipelineStateWithDescriptor:bp error:&e];
			id<MTLCommandBuffer> cb = [[dev newCommandQueue] commandBuffer];
			MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
			rp.colorAttachments[0].texture = noise;
			rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
			rp.colorAttachments[0].storeAction = MTLStoreActionStore;
			id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
			[r setRenderPipelineState:bake];
			[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
			[r endEncoding];
			[cb commit];
			[cb waitUntilCompleted];
		}
		id<MTLBuffer> frame = [dev newBufferWithLength:2048 options:MTLResourceStorageModeShared];
		id<MTLBuffer> look = [dev newBufferWithLength:1024 options:MTLResourceStorageModeShared];
		float *F = frame.contents, *L = look.contents;
		// ShadeFrame: invViewProj identity-ish, horizon palette, sunPath.w time, flags (dimension in flags.x set per test)
		for (int i = 0; i < 4; i++) { F[192 / 4 + i * 5] = 1; F[128 / 4 + i * 5] = 1; F[64 / 4 + i * 5] = 1; F[i * 5] = 1; }
		for (int i = 0; i < 8; i++) { F[688 / 4 + i * 4] = 0.1; F[688 / 4 + i * 4 + 1] = 0.05; F[688 / 4 + i * 4 + 2] = 0.2; }
		F[992 / 4 + 3] = 100.0; F[464 / 4 + 1] = 0.5; F[448 / 4 + 1] = 64;
		L[672 / 4 + 2] = 1.0;           // aurora strength
		L[752 / 4] = 0.02; L[752 / 4 + 1] = 31; L[752 / 4 + 2] = 256; L[752 / 4 + 3] = 1 + 4; // dimTint (Nether; + 4 x bit 1: motes)
		((int *) L)[800 / 4] = 1;        // tier balanced
		const char *names[] = {"t_empty", "t_aurora", "t_endsky", "t_netherfog", "t_media"};
		for (int k = 0; k < 5; k++) {
			MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
			pd.vertexFunction = [lib newFunctionWithName:@"shade_fs_vs"];
			pd.fragmentFunction = [lib newFunctionWithName:[NSString stringWithUTF8String:names[k]]];
			pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
			id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:pd error:&e];
			if (!pso) { printf("pso %s: %s\n", names[k], e.localizedDescription.UTF8String); return 1; }
			id<MTLCommandQueue> q = [dev newCommandQueue];
			double best = 1e9;
			for (int it = 0; it < 30; it++) {
				id<MTLCommandBuffer> cb = [q commandBuffer];
				MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
				rp.colorAttachments[0].texture = target;
				rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
				rp.colorAttachments[0].storeAction = MTLStoreActionStore;
				id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
				[r setRenderPipelineState:pso];
				[r setFragmentBuffer:frame offset:0 atIndex:16];
				[r setFragmentBuffer:look offset:0 atIndex:17];
				[r setFragmentTexture:noise atIndex:0];
				[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
				[r endEncoding];
				[cb commit];
				[cb waitUntilCompleted];
				double ms = (cb.GPUEndTime - cb.GPUStartTime) * 1e3;
				if (it > 3 && ms < best) best = ms;
			}
			printf("%-12s %.3f ms (3024x1964, every pixel)\n", names[k], best);
		}
	}
	return 0;
}
