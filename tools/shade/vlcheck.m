// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
// Dev tool: compiles the volumetric light MSL (ShadeMsl.prelude + vl.metal, as VlConfig.source() assembles it) like
// mcvl.m does (MSL 3.0, fast math) and builds every pipeline mcvl.m builds: march and temporal (RGBA16Float; vl_temporal_fold with its RGBA16Float lightT target), vl_composite
// for the LDR main target (RGBA8Unorm, programmable blending), vl_composite_hdr for both M2 HDR world formats (RG11B10Float
// and RGBA16Float, One / SourceAlpha blending), and the canopy kernel with VL_HDR false and true. Usage: vlcheck <file.metal>
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

static int build(id<MTLDevice> dev, id<MTLLibrary> lib, NSString *fs, MTLPixelFormat fmt, bool hdr) {
	NSError *e = nil;
	MTLFunctionConstantValues *fc = [MTLFunctionConstantValues new];
	[fc setConstantValue:&hdr type:MTLDataTypeBool atIndex:0];
	id<MTLFunction> f = [lib newFunctionWithName:fs constantValues:fc error:&e];
	if (!f) { printf("FAIL %s: %s\n", fs.UTF8String, e.localizedDescription.UTF8String); return 1; }
	MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
	pd.vertexFunction = [lib newFunctionWithName:@"vl_vs"];
	pd.fragmentFunction = f;
	pd.colorAttachments[0].pixelFormat = fmt;
	if ([fs isEqualToString:@"vl_march"]) pd.colorAttachments[1].pixelFormat = MTLPixelFormatR8Unorm;
	if ([fs isEqualToString:@"vl_temporal_fold"]) pd.colorAttachments[1].pixelFormat = MTLPixelFormatRGBA16Float;
	if (hdr && [fs isEqualToString:@"vl_composite_hdr"]) {
		pd.colorAttachments[0].blendingEnabled = YES;
		pd.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorOne;
		pd.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorSourceAlpha;
	}
	MTLAutoreleasedRenderPipelineReflection refl = nil;
	id<MTLRenderPipelineState> p = [dev newRenderPipelineStateWithDescriptor:pd options:MTLPipelineOptionBindingInfo reflection:&refl error:&e];
	if (!p) { printf("FAIL %s pipeline: %s\n", fs.UTF8String, e.localizedDescription.UTF8String); return 1; }
	NSMutableArray *b = [NSMutableArray array];
	for (id<MTLBinding> x in refl.fragmentBindings) [b addObject:[NSString stringWithFormat:@"%@:%lu", x.name, (unsigned long) x.index]];
	printf("OK   %s%s fragment[%s]\n", fs.UTF8String, hdr ? " (HDR)" : "", [[b componentsJoinedByString:@" "] UTF8String]);
	return 0;
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		if (argc != 2) { fprintf(stderr, "usage: vlcheck <file.metal>\n"); return 2; }
		id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
		NSError *e = nil;
		NSString *src = [NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&e];
		MTLCompileOptions *o = [MTLCompileOptions new];
		o.languageVersion = MTLLanguageVersion3_0;
		o.mathMode = MTLMathModeFast;
		id<MTLLibrary> lib = [dev newLibraryWithSource:src options:o error:&e];
		if (!lib) { printf("FAIL compile %s\n%s\n", argv[1], e.localizedDescription.UTF8String); return 1; }
		int fail = 0;
		fail |= build(dev, lib, @"vl_march", MTLPixelFormatRGBA16Float, false);
		fail |= build(dev, lib, @"vl_temporal", MTLPixelFormatRGBA16Float, false);
		fail |= build(dev, lib, @"vl_temporal_fold", MTLPixelFormatRGBA16Float, true);
		fail |= build(dev, lib, @"vl_composite", MTLPixelFormatRGBA8Unorm, false);
		fail |= build(dev, lib, @"vl_composite_hdr", MTLPixelFormatRG11B10Float, true);
		fail |= build(dev, lib, @"vl_composite_hdr", MTLPixelFormatRGBA16Float, true);
		fail |= build(dev, lib, @"vl_composite_pre", MTLPixelFormatRG11B10Float, true); // M2.2: fold before the TAAU resolve
		fail |= build(dev, lib, @"vl_composite_pre", MTLPixelFormatRGBA16Float, true);
		for (int h = 0; h < 2; h++) {
			bool hdr = h != 0;
			MTLFunctionConstantValues *fc = [MTLFunctionConstantValues new];
			[fc setConstantValue:&hdr type:MTLDataTypeBool atIndex:0];
			id<MTLFunction> kf = [lib newFunctionWithName:@"vl_canopy" constantValues:fc error:&e];
			id<MTLComputePipelineState> k = kf ? [dev newComputePipelineStateWithFunction:kf error:&e] : nil;
			if (!k) { printf("FAIL vl_canopy%s: %s\n", hdr ? " (HDR)" : "", e.localizedDescription.UTF8String); fail = 1; }
			else printf("OK   vl_canopy%s (compute)\n", hdr ? " (HDR)" : "");
		}
		return fail;
	}
}
