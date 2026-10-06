// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Art-preview tool for mcopt native shading, not part of the mod.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>

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
		NSError *e = nil;
		id<MTLLibrary> lib = [dev newLibraryWithSource:src options:o error:&e];
		if (!lib) { printf("%s\n", e.localizedDescription.UTF8String); return 1; }
		NSData *frame = [NSData dataWithContentsOfFile:@(argv[2])];
		int W = 1280, H = 720;
		MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
		pd.vertexFunction = [lib newFunctionWithName:@"preview_vs"];
		pd.fragmentFunction = [lib newFunctionWithName:@"preview_fs"];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
		id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!pso) { printf("%s\n", e.localizedDescription.UTF8String); return 1; }
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:W height:H mipmapped:NO];
		td.usage = MTLTextureUsageRenderTarget; td.storageMode = MTLStorageModeShared;
		id<MTLTexture> tex = [dev newTextureWithDescriptor:td];
		id<MTLBuffer> fb = [dev newBufferWithBytes:frame.bytes length:frame.length options:MTLResourceStorageModeShared];
		id<MTLCommandQueue> q = [dev newCommandQueue];
		double lutMs = 0; id<MTLTexture> lut = renderLut(dev, lib, q, fb, &lutMs); id<MTLSamplerState> ls = linearSampler(dev);
		id<MTLCommandBuffer> cb = [q commandBuffer];
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = tex; rp.colorAttachments[0].loadAction = MTLLoadActionClear; rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
		[re setRenderPipelineState:pso];
		[re setFragmentBuffer:fb offset:0 atIndex:16]; [re setFragmentTexture:lut atIndex:17]; [re setFragmentSamplerState:ls atIndex:13];
		[re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[re endEncoding];
		[cb commit]; [cb waitUntilCompleted];
		NSMutableData *px = [NSMutableData dataWithLength:W * H * 4];
		[tex getBytes:px.mutableBytes bytesPerRow:W * 4 fromRegion:MTLRegionMake2D(0, 0, W, H) mipmapLevel:0];
		CGColorSpaceRef cs = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
		CGContextRef ctx = CGBitmapContextCreate(px.mutableBytes, W, H, 8, W * 4, cs, kCGImageAlphaNoneSkipLast);
		CGImageRef img = CGBitmapContextCreateImage(ctx);
		CGImageDestinationRef dst = CGImageDestinationCreateWithURL((__bridge CFURLRef) [NSURL fileURLWithPath:@(argv[3])], (__bridge CFStringRef) UTTypePNG.identifier, 1, NULL);
		CGImageDestinationAddImage(dst, img, NULL); CGImageDestinationFinalize(dst);
		printf("wrote %s\n", argv[3]);
	}
	return 0;
}
