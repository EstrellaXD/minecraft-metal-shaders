// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Art-preview tool for mcopt native shading, not part of the mod.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
// skytime SRC FRAME.bin: GPU ms of a full-screen 3024x1964 pass of sky_only_fs (shadeSkyColor for every pixel), median of 30.

static id<MTLTexture> renderLut(id<MTLDevice> dev, id<MTLLibrary> lib, id<MTLCommandQueue> q, id<MTLBuffer> fb, double *ms) {
	MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
	pd.vertexFunction = [lib newFunctionWithName:@"shade_lut_vs"]; pd.fragmentFunction = [lib newFunctionWithName:@"shade_lut_fs"];
	pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
	NSError *e = nil;
	id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:pd error:&e];
	if (!pso) { printf("lut: %s\n", e.localizedDescription.UTF8String); exit(1); }
	MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:128 height:80 mipmapped:NO];
	td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead; td.storageMode = MTLStorageModePrivate;
	id<MTLTexture> lut = [dev newTextureWithDescriptor:td];
	id<MTLCommandBuffer> cb = [q commandBuffer];
	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = lut; rp.colorAttachments[0].loadAction = MTLLoadActionDontCare; rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
	[re setRenderPipelineState:pso]; [re setFragmentBuffer:fb offset:0 atIndex:16];
	[re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3]; [re endEncoding];
	[cb commit]; [cb waitUntilCompleted];
	if (ms) *ms = (cb.GPUEndTime - cb.GPUStartTime) * 1000.0;
	return lut;
}
static id<MTLSamplerState> linearSampler(id<MTLDevice> dev) {
	MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
	sd.minFilter = MTLSamplerMinMagFilterLinear; sd.magFilter = MTLSamplerMinMagFilterLinear;
	sd.sAddressMode = MTLSamplerAddressModeClampToEdge; sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
	return [dev newSamplerStateWithDescriptor:sd];
}
int main(int argc, const char **argv) {
	@autoreleasepool {
		id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
		NSString *src = [NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil];
		MTLCompileOptions *o = [MTLCompileOptions new]; o.languageVersion = MTLLanguageVersion3_0; o.mathMode = MTLMathModeFast;
		NSError *e = nil; id<MTLLibrary> lib = [dev newLibraryWithSource:src options:o error:&e];
		if (!lib) { printf("%s\n", e.localizedDescription.UTF8String); return 1; }
		NSData *frame = [NSData dataWithContentsOfFile:@(argv[2])];
		int W = 3024, H = 1964;
		MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
		pd.vertexFunction = [lib newFunctionWithName:@"preview_vs"]; pd.fragmentFunction = [lib newFunctionWithName:@(argc > 3 ? argv[3] : "sky_only_fs")];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
		id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:pd error:&e];
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:W height:H mipmapped:NO];
		td.usage = MTLTextureUsageRenderTarget; td.storageMode = MTLStorageModePrivate;
		id<MTLTexture> tex = [dev newTextureWithDescriptor:td];
		id<MTLBuffer> fb = [dev newBufferWithBytes:frame.bytes length:frame.length options:MTLResourceStorageModeShared];
		id<MTLCommandQueue> q = [dev newCommandQueue];
		double lutMs = 0; id<MTLTexture> lut = renderLut(dev, lib, q, fb, &lutMs); id<MTLSamplerState> ls = linearSampler(dev);
		double t[30];
		for (int i = 0; i < 30; i++) {
			id<MTLCommandBuffer> cb = [q commandBuffer];
			MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
			rp.colorAttachments[0].texture = tex; rp.colorAttachments[0].loadAction = MTLLoadActionDontCare; rp.colorAttachments[0].storeAction = MTLStoreActionStore;
			id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
			[re setRenderPipelineState:pso]; [re setFragmentBuffer:fb offset:0 atIndex:16]; [re setFragmentTexture:lut atIndex:17]; [re setFragmentSamplerState:ls atIndex:13];
			[re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3]; [re endEncoding];
			[cb commit]; [cb waitUntilCompleted];
			t[i] = (cb.GPUEndTime - cb.GPUStartTime) * 1000.0;
		}
		for (int i = 0; i < 30; i++) for (int j = i + 1; j < 30; j++) if (t[j] < t[i]) { double x = t[i]; t[i] = t[j]; t[j] = x; }
		printf("%s %s: median %.3f ms, min %.3f ms (full 3024x1964); LUT pass %.3f ms\n", argv[2], argc > 3 ? argv[3] : "sky", t[15], t[0], lutMs);
	}
	return 0;
}
