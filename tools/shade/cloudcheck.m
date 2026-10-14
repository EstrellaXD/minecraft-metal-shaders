// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
// Dev tool (v12-opt): compiles the volumetric clouds' passes library (CloudConfig.source(): ShadeMsl.prelude + clouds.metal) like
// mcclouds.m (MSL 3.0, fast math) and builds every pipeline it builds: the generic march, resolves, dome, shadow map and
// clouds-over pass (no function constant set) and the bake kernels, then the lean march and resolves (CLOUD_FC_DEV / MODE /
// STYLE set) for the default mode bits with each style. Usage: cloudcheck <clouds_passes.metal>
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

static int pso(id<MTLDevice> dev, id<MTLLibrary> lib, NSString *fs, MTLPixelFormat f0, MTLPixelFormat f1, int lean, int devBits, int mode, float style) {
	NSError *e = nil;
	MTLFunctionConstantValues *fc = [MTLFunctionConstantValues new];
	if (lean) {
		[fc setConstantValue:&devBits type:MTLDataTypeInt atIndex:0];
		[fc setConstantValue:&mode type:MTLDataTypeInt atIndex:1];
		[fc setConstantValue:&style type:MTLDataTypeFloat atIndex:2];
	}
	MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
	pd.vertexFunction = [lib newFunctionWithName:@"cloud_vs"];
	pd.fragmentFunction = [lib newFunctionWithName:fs constantValues:fc error:&e];
	if (!pd.fragmentFunction) { printf("FAIL %s: %s\n", fs.UTF8String, e.localizedDescription.UTF8String); return 1; }
	pd.colorAttachments[0].pixelFormat = f0;
	if (f1 != MTLPixelFormatInvalid) pd.colorAttachments[1].pixelFormat = f1;
	id<MTLRenderPipelineState> p = [dev newRenderPipelineStateWithDescriptor:pd error:&e];
	if (!p) { printf("FAIL %s pipeline: %s\n", fs.UTF8String, e.localizedDescription.UTF8String); return 1; }
	if (lean) printf("OK   %s (lean: dev %d, mode %d, style %.0f)\n", fs.UTF8String, devBits, mode, style);
	else printf("OK   %s\n", fs.UTF8String);
	return 0;
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		if (argc != 2) { fprintf(stderr, "usage: cloudcheck <clouds_passes.metal>\n"); return 2; }
		id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
		NSError *e = nil;
		NSString *src = [NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&e];
		MTLCompileOptions *o = [MTLCompileOptions new];
		o.languageVersion = MTLLanguageVersion3_0;
		o.mathMode = MTLMathModeFast;
		id<MTLLibrary> lib = [dev newLibraryWithSource:src options:o error:&e];
		if (!lib) { printf("FAIL compile %s\n%s\n", argv[1], e.localizedDescription.UTF8String); return 1; }
		int fail = 0;
		fail |= pso(dev, lib, @"cloud_march", MTLPixelFormatRGBA16Float, MTLPixelFormatR32Float, 0, 0, 0, 0);
		fail |= pso(dev, lib, @"cloud_resolve", MTLPixelFormatRGBA16Float, MTLPixelFormatR8Unorm, 0, 0, 0, 0);
		fail |= pso(dev, lib, @"cloud_resolve_st", MTLPixelFormatRGBA16Float, MTLPixelFormatR16Uint, 0, 0, 0, 0);
		fail |= pso(dev, lib, @"cloud_dome", MTLPixelFormatRGBA16Float, MTLPixelFormatInvalid, 0, 0, 0, 0);
		fail |= pso(dev, lib, @"cloud_shadow", MTLPixelFormatR16Float, MTLPixelFormatInvalid, 0, 0, 0, 0);
		fail |= pso(dev, lib, @"cloud_over", MTLPixelFormatRG11B10Float, MTLPixelFormatInvalid, 0, 0, 0, 0);
		for (int k = 0; k < 2; k++) {
			NSString *name = k ? @"cloud_bake_weather" : @"cloud_bake_noise";
			id<MTLFunction> f = [lib newFunctionWithName:name constantValues:[MTLFunctionConstantValues new] error:&e];
			id<MTLComputePipelineState> c = f ? [dev newComputePipelineStateWithFunction:f error:&e] : nil;
			if (!c) { printf("FAIL %s: %s\n", name.UTF8String, e.localizedDescription.UTF8String); fail = 1; }
			else printf("OK   %s (compute)\n", name.UTF8String);
		}
		// CloudConfig.modeBits() with the defaults: stable, batch, pair, edge gate, drift clamp, zoom age
		int mode = 1 | 4 | 8 | 16 | 32 | 64;
		for (int st = 0; st <= 2; st += 2) {
			fail |= pso(dev, lib, @"cloud_march", MTLPixelFormatRGBA16Float, MTLPixelFormatR32Float, 1, 0, mode, st);
			fail |= pso(dev, lib, @"cloud_resolve", MTLPixelFormatRGBA16Float, MTLPixelFormatR8Unorm, 1, 0, mode, st);
			fail |= pso(dev, lib, @"cloud_resolve_st", MTLPixelFormatRGBA16Float, MTLPixelFormatR16Uint, 1, 0, mode, st);
		}
		return fail;
	}
}
