// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading, not part of the mod.
// pipecheck LAYOUT VS.metal FS.metal [VENTRY FENTRY]   |   pipecheck compile FILE.metal
// Compiles both stages like mcopt (MSL 3.0, fast math) and builds a real MTLRenderPipelineState with mcopt's vertex
// layout for LAYOUT (sodium | none | pos | postex | poscolor | lut), RGBA8Unorm color + Depth32Float (lut: RGBA16Float only). Prints OK or the error.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

static id<MTLLibrary> lib(id<MTLDevice> dev, const char *path) {
	NSString *src = [NSString stringWithContentsOfFile:[NSString stringWithUTF8String:path] encoding:NSUTF8StringEncoding error:nil];
	if (!src) { printf("FAIL cannot read %s\n", path); exit(1); }
	MTLCompileOptions *o = [MTLCompileOptions new];
	o.languageVersion = MTLLanguageVersion3_0;
	o.mathMode = MTLMathModeFast;
	NSError *e = nil;
	id<MTLLibrary> l = [dev newLibraryWithSource:src options:o error:&e];
	if (!l) { printf("FAIL compile %s\n%s\n", path, [[e localizedDescription] UTF8String]); exit(1); }
	return l;
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
		if (argc == 3 && strcmp(argv[1], "compile") == 0) { lib(dev, argv[2]); printf("OK   %s\n", argv[2]); return 0; }
		if (argc < 4) { fprintf(stderr, "usage: pipecheck LAYOUT VS FS [VENTRY FENTRY] | pipecheck compile FILE\n"); return 2; }
		NSString *layout = [NSString stringWithUTF8String:argv[1]];
		id<MTLLibrary> vl = lib(dev, argv[2]), fl = lib(dev, argv[3]);
		NSString *ve = argc > 4 ? [NSString stringWithUTF8String:argv[4]] : @"main0";
		NSString *fe = argc > 5 ? [NSString stringWithUTF8String:argv[5]] : @"main0";
		MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
		pd.vertexFunction = [vl newFunctionWithName:ve];
		pd.fragmentFunction = [fl newFunctionWithName:fe];
		if (!pd.vertexFunction || !pd.fragmentFunction) { printf("FAIL missing entry point\n"); return 1; }
		MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
		BOOL useVd = YES;
		if ([layout isEqualToString:@"sodium"]) {
			vd.layouts[27].stride = 20;
			MTLVertexFormat f[4] = {MTLVertexFormatUInt2, MTLVertexFormatUChar4Normalized, MTLVertexFormatUShort2, MTLVertexFormatUChar4};
			int off[4] = {0, 8, 12, 16};
			for (int i = 0; i < 4; i++) { vd.attributes[i].bufferIndex = 27; vd.attributes[i].offset = off[i]; vd.attributes[i].format = f[i]; }
		} else if ([layout isEqualToString:@"pos"]) {
			vd.layouts[27].stride = 12;
			vd.attributes[0].bufferIndex = 27; vd.attributes[0].format = MTLVertexFormatFloat3;
		} else if ([layout isEqualToString:@"postex"]) {
			vd.layouts[27].stride = 20;
			vd.attributes[0].bufferIndex = 27; vd.attributes[0].format = MTLVertexFormatFloat3;
			vd.attributes[1].bufferIndex = 27; vd.attributes[1].offset = 12; vd.attributes[1].format = MTLVertexFormatFloat2;
		} else if ([layout isEqualToString:@"poscolor"]) {
			vd.layouts[27].stride = 16;
			vd.attributes[0].bufferIndex = 27; vd.attributes[0].format = MTLVertexFormatFloat3;
			vd.attributes[1].bufferIndex = 27; vd.attributes[1].offset = 12; vd.attributes[1].format = MTLVertexFormatUChar4Normalized;
		} else {
			useVd = NO;
		}
		if (useVd) pd.vertexDescriptor = vd;
		BOOL lut = [layout isEqualToString:@"lut"];
		pd.colorAttachments[0].pixelFormat = lut ? MTLPixelFormatRGBA16Float : MTLPixelFormatRGBA8Unorm;
		if (!lut) pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
		NSError *e = nil;
		MTLRenderPipelineReflection *refl = nil;
		id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:pd options:MTLPipelineOptionBindingInfo reflection:&refl error:&e];
		if (!pso) { printf("FAIL pipeline %s %s\n%s\n", argv[2], argv[3], [[e localizedDescription] UTF8String]); return 1; }
		NSMutableArray *vb = [NSMutableArray array], *fb = [NSMutableArray array];
		for (id<MTLBinding> b in refl.vertexBindings) if (b.used) [vb addObject:[NSString stringWithFormat:@"%@@%lu", b.name, (unsigned long) b.index]];
		for (id<MTLBinding> b in refl.fragmentBindings) if (b.used) [fb addObject:[NSString stringWithFormat:@"%@@%lu", b.name, (unsigned long) b.index]];
		printf("OK   %s | %s  vertex[%s] fragment[%s]\n", argv[2], argv[3], [[vb componentsJoinedByString:@" "] UTF8String], [[fb componentsJoinedByString:@" "] UTF8String]);
		return 0;
	}
}
