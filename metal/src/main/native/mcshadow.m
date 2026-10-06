// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
//
// Sun shadow map for the native shading pipeline (-Dmcopt.shade=true, mcopt.metal.shade.shadow.ShadowPass): owns the
// cascade depth array, the compare sampler receivers bind at sampler(12), and the depth-only passes that replay Sodium's
// solid and cutout terrain from the light (shaders: resources/mcopt/shade/shadow.metal). Each cascade is its own render
// encoder on the frame's command buffer (mc_frame_cmd), encoded before the frame's first world pass, so the game's passes
// that follow never lose an encoder merge. ARC is off: everything here is retained/released by hand.
#import "mcmetal.h"
#include <string.h>

#define QUAD_INDICES 384  // 64 quads x {0, 1, 2, 2, 3, 0}
#define DRAW_BYTES 24     // per draw from Java: arena MTLBuffer (8), first chunk (4), chunk count (4), kind (4: 0 solid, 1 cutout), pad (4)

typedef struct {
	id<MTLDevice> device;
	id<MTLLibrary> library;
	id<MTLRenderPipelineState> solid, cutout;
	id<MTLRenderPipelineState> debug;  // made for the first target format the debug view draws into
	MTLPixelFormat debugFormat;
	id<MTLDepthStencilState> depthWrite;
	id<MTLSamplerState> compare;       // receivers: sampler(12)
	id<MTLSamplerState> atlasSampler;  // cutout test: nearest, level 0
	id<MTLTexture> map;                // Depth32Float 2D array, one slice per cascade
	id<MTLBuffer> quadIndices;
	int resolution, layers;
} Shadow;

static void copyErr(NSError *e, const char *fallback, char *err, int errCap) {
	strlcpy(err, e ? e.description.UTF8String : fallback, errCap);
}

static id<MTLRenderPipelineState> depthOnly(Shadow *s, NSString *vs, NSString *fs, NSError **e) {
	MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
	pd.label = vs;
	pd.vertexFunction = [[s->library newFunctionWithName:vs] autorelease];
	if (fs) pd.fragmentFunction = [[s->library newFunctionWithName:fs] autorelease];
	pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
	pd.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle;
	return [s->device newRenderPipelineStateWithDescriptor:pd error:e];
}

void mc_shadow_free(Shadow *s);

// source: shadow.metal. resolution: per cascade (square). Returns NULL with err filled on failure.
Shadow *mc_shadow_new(Ctx *ctx, const char *source, int resolution, int layers, char *err, int errCap) {
	@autoreleasepool {
		Shadow *s = calloc(1, sizeof(Shadow));
		s->device = ctx->device;
		s->resolution = resolution;
		s->layers = layers;
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_0;
		o.mathMode = MTLMathModeFast;
		NSError *e = nil;
		s->library = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (s->library) s->solid = depthOnly(s, @"shadow_vs", nil, &e);
		if (s->solid) s->cutout = depthOnly(s, @"shadow_cutout_vs", @"shadow_cutout_fs", &e);
		if (!s->cutout) {
			copyErr(e, "shadow pipelines failed", err, errCap);
			mc_shadow_free(s);
			return NULL;
		}

		MTLDepthStencilDescriptor *dd = [[MTLDepthStencilDescriptor new] autorelease];
		dd.depthCompareFunction = MTLCompareFunctionGreater;  // reversed Z: nearer the light = larger
		dd.depthWriteEnabled = YES;
		s->depthWrite = [ctx->device newDepthStencilStateWithDescriptor:dd];

		MTLSamplerDescriptor *sd = [[MTLSamplerDescriptor new] autorelease];
		sd.minFilter = MTLSamplerMinMagFilterLinear;  // hardware 2x2 PCF per tap
		sd.magFilter = MTLSamplerMinMagFilterLinear;
		sd.mipFilter = MTLSamplerMipFilterNotMipmapped;
		sd.sAddressMode = MTLSamplerAddressModeClampToBorderColor;
		sd.tAddressMode = MTLSamplerAddressModeClampToBorderColor;
		sd.borderColor = MTLSamplerBorderColorOpaqueBlack;  // depth 0 outside the map: nothing there casts
		sd.compareFunction = MTLCompareFunctionGreaterEqual;  // lit when receiver depth >= caster depth (reversed Z)
		sd.label = @"mcopt shadow compare";
		s->compare = [ctx->device newSamplerStateWithDescriptor:sd];

		MTLSamplerDescriptor *ad = [[MTLSamplerDescriptor new] autorelease];
		ad.minFilter = MTLSamplerMinMagFilterNearest;
		ad.magFilter = MTLSamplerMinMagFilterNearest;
		ad.mipFilter = MTLSamplerMipFilterNotMipmapped;
		ad.sAddressMode = MTLSamplerAddressModeClampToEdge;
		ad.tAddressMode = MTLSamplerAddressModeClampToEdge;
		s->atlasSampler = [ctx->device newSamplerStateWithDescriptor:ad];

		MTLTextureDescriptor *td = [[MTLTextureDescriptor new] autorelease];
		td.textureType = MTLTextureType2DArray;
		td.pixelFormat = MTLPixelFormatDepth32Float;
		td.width = resolution;
		td.height = resolution;
		td.arrayLength = layers;
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
		s->map = [ctx->device newTextureWithDescriptor:td];
		s->map.label = @"mcopt shadow map";

		uint16_t indices[QUAD_INDICES];
		static const uint16_t corners[6] = {0, 1, 2, 2, 3, 0};
		for (int q = 0, i = 0; q < 64; q++) {
			for (int c = 0; c < 6; c++) indices[i++] = (uint16_t) (q * 4 + corners[c]);
		}
		s->quadIndices = [ctx->device newBufferWithBytes:indices length:sizeof indices options:MTLResourceStorageModeShared];
		if (!s->map || !s->quadIndices || !s->compare || !s->depthWrite) {
			strlcpy(err, "shadow resources failed", errCap);
			mc_shadow_free(s);
			return NULL;
		}
		return s;
	}
}

void mc_shadow_free(Shadow *s) {
	if (!s) return;
	[s->library release];
	[s->solid release];
	[s->cutout release];
	[s->debug release];
	[s->depthWrite release];
	[s->compare release];
	[s->atlasSampler release];
	[s->map release];
	[s->quadIndices release];
	free(s);
}

id<MTLTexture> mc_shadow_texture(Shadow *s) { return s->map; }
id<MTLSamplerState> mc_shadow_compare_sampler(Shadow *s) { return s->compare; }

/*
 * One cascade: clears slice `layer` to 0 and draws the lists. cascade: the MSL ShadowCascade (cascadeBytes), draws: drawCount
 * records of DRAW_BYTES (see above). chunks / regions: the frame's ShadowChunk and ShadowRegion arrays. Every arena must
 * stay alive until the command buffer completes (Java marks them used by this submit). Returns the profiling encoder index
 * this pass took (-1 when not profiling), so a -Dmcopt.metal.trace run can attribute its GPU time.
 */
int mc_shadow_render(Enc *enc, Shadow *s, int layer, const void *cascade, int cascadeBytes, id<MTLBuffer> chunks, uint64_t chunksOffset,
	id<MTLBuffer> regions, uint64_t regionsOffset, const uint8_t *draws, int drawCount, id<MTLTexture> atlas, float slopeScale, float clamp) {
	id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
	int profiled = -1;
	@autoreleasepool {
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.depthAttachment.texture = s->map;
		rp.depthAttachment.slice = layer;
		rp.depthAttachment.loadAction = MTLLoadActionClear;
		rp.depthAttachment.clearDepth = 0.0;  // reversed Z: nothing casts
		rp.depthAttachment.storeAction = MTLStoreActionStore;
		rp.renderTargetWidth = s->resolution;
		rp.renderTargetHeight = s->resolution;
		if (enc->samples && enc->sampleCount + 4 <= (int) enc->samples.sampleCount) {
			MTLRenderPassSampleBufferAttachmentDescriptor *sb = rp.sampleBufferAttachments[0];
			sb.sampleBuffer = enc->samples;
			sb.startOfVertexSampleIndex = enc->sampleCount;
			sb.endOfVertexSampleIndex = enc->sampleCount + 1;
			sb.startOfFragmentSampleIndex = enc->sampleCount + 2;
			sb.endOfFragmentSampleIndex = enc->sampleCount + 3;
			profiled = enc->sampleCount / 4;
			enc->sampleCount += 4;
		}
		id<MTLRenderCommandEncoder> r = [cmd renderCommandEncoderWithDescriptor:rp];
		r.label = layer == 0 ? @"mcopt shadow cascade 0" : @"mcopt shadow cascade 1";
		[r setViewport:(MTLViewport) {0, 0, s->resolution, s->resolution, 0, 1}];
		[r setDepthStencilState:s->depthWrite];
		[r setCullMode:MTLCullModeNone];  // only light-facing ranges are listed; unassigned quads (plants) are two-sided
		[r setDepthClipMode:MTLDepthClipModeClamp];  // pancaking: casters in front of the near plane land at depth 1
		[r setDepthBias:0 slopeScale:slopeScale clamp:clamp];
		[r setVertexBytes:cascade length:cascadeBytes atIndex:3];
		[r setVertexBuffer:chunks offset:chunksOffset atIndex:1];
		[r setVertexBuffer:regions offset:regionsOffset atIndex:2];
		if (atlas) {
			[r setFragmentTexture:atlas atIndex:0];
			[r setFragmentSamplerState:s->atlasSampler atIndex:0];
		}
		int kind = -1;
		id<MTLBuffer> arena = nil;
		for (int i = 0; i < drawCount; i++) {
			const uint8_t *d = draws + i * DRAW_BYTES;
			id<MTLBuffer> vb;
			uint32_t first, count, k;
			memcpy(&vb, d, 8);
			memcpy(&first, d + 8, 4);
			memcpy(&count, d + 12, 4);
			memcpy(&k, d + 16, 4);
			if (count == 0 || (k == 1 && !atlas)) continue;
			if ((int) k != kind) {
				[r setRenderPipelineState:k == 1 ? s->cutout : s->solid];
				kind = (int) k;
			}
			if (vb != arena) {
				[r setVertexBuffer:vb offset:0 atIndex:0];
				arena = vb;
			}
			[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:QUAD_INDICES indexType:MTLIndexTypeUInt16 indexBuffer:s->quadIndices
				indexBufferOffset:0 instanceCount:count baseVertex:0 baseInstance:first];
		}
		[r endEncoding];
	}
	return profiled;
}

// Binds the map at texture(textureSlot) and the compare sampler at sampler(samplerSlot), vertex and fragment, on the open
// render encoder (no-op when none is open). For the shade infrastructure's global binds.
void mc_shadow_bind(Enc *enc, Shadow *s, int textureSlot, int samplerSlot) {
	id<MTLRenderCommandEncoder> r = enc->render;
	if (!r) return;
	[r setVertexTexture:s->map atIndex:textureSlot];
	[r setFragmentTexture:s->map atIndex:textureSlot];
	[r setVertexSamplerState:s->compare atIndex:samplerSlot];
	[r setFragmentSamplerState:s->compare atIndex:samplerSlot];
}

// Debug view: draws slice `layer` as a size x size tile at (x, y) (texture pixels, GL row order) into target, which keeps its
// contents. params: the MSL ShadowDebugParams (16 bytes). Returns 0, or 1 when the debug pipeline can't be made.
int mc_shadow_debug(Enc *enc, Shadow *s, id<MTLTexture> target, int x, int y, int size, const void *params, int paramsBytes) {
	if (!s->debug || s->debugFormat != target.pixelFormat) {
		@autoreleasepool {
			[s->debug release];
			s->debug = nil;
			MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
			pd.vertexFunction = [[s->library newFunctionWithName:@"shadow_debug_vs"] autorelease];
			pd.fragmentFunction = [[s->library newFunctionWithName:@"shadow_debug_fs"] autorelease];
			pd.colorAttachments[0].pixelFormat = target.pixelFormat;
			NSError *e = nil;
			s->debug = [s->device newRenderPipelineStateWithDescriptor:pd error:&e];
			s->debugFormat = target.pixelFormat;
			if (!s->debug) {
				NSLog(@"mcopt-shade: shadow debug pipeline: %@", e);
				return 1;
			}
		}
	}
	id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
	@autoreleasepool {
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = target;
		rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cmd renderCommandEncoderWithDescriptor:rp];
		r.label = @"mcopt shadow debug";
		[r setViewport:(MTLViewport) {x, y, size, size, 0, 1}];
		[r setRenderPipelineState:s->debug];
		[r setFragmentTexture:s->map atIndex:0];
		[r setFragmentBytes:params length:paramsBytes atIndex:0];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
	}
	return 0;
}
