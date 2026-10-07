// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
//
// Sun shadow map for the native shading pipeline (-Dmcopt.shade=true, mcopt.metal.shade.shadow.ShadowPass): owns the
// cascade depth array, the compare sampler receivers bind at sampler(12), and the depth-only passes that replay Sodium's
// solid and cutout terrain from the light (shaders: resources/mcopt/shade/shadow.metal). Each cascade is its own render
// encoder on the frame's command buffer (mc_frame_cmd), encoded before the frame's first world pass, so the game's passes
// that follow never lose an encoder merge. ARC is off: everything here is retained/released by hand.
#import "mcmetal.h"
#include <string.h>

#define CHUNK_QUADS 32     // ShadowCasters.CHUNK_QUADS (32: a third of the vertex launches were padding with 64)
#define QUAD_INDICES (CHUNK_QUADS * 6)  // {0, 1, 2, 2, 3, 0} per quad
#define DRAW_BYTES 24     // per draw from Java: arena MTLBuffer (8), first chunk (4), chunk count (4), kind (4: 0 solid, 1 cutout), pad (4)

typedef struct {
	id<MTLDevice> device;
	id<MTLLibrary> library;
	id<MTLRenderPipelineState> solid, cutout;
	id<MTLRenderPipelineState> debug;  // made for the format of the target the debug view goes into
	MTLPixelFormat debugFormat;
	id<MTLTexture> debugTiles;         // the debug view's tiles, drawn here and blitted into the target
	id<MTLDepthStencilState> depthWrite;
	id<MTLSamplerState> compare;       // receivers: sampler(12)
	id<MTLSamplerState> atlasSampler;  // cutout test: nearest, level 0
	id<MTLTexture> map;                // Depth16Unorm or Depth32Float 2D array, one slice per cascade
	MTLPixelFormat format;
	id<MTLBuffer> quadIndices;
	int resolution, layers;
	// M3 coloured shadows (mc_shadow_tint_*): the near cascade's translucent casters into a tint map
	id<MTLRenderPipelineState> tint;
	id<MTLTexture> tintMap;
	bool tintDirty;  // the map holds casters (it is cleared once when they're gone)
} Shadow;

static void copyErr(NSError *e, const char *fallback, char *err, int errCap) {
	strlcpy(err, e ? e.description.UTF8String : fallback, errCap);
}

static id<MTLRenderPipelineState> depthOnly(Shadow *s, NSString *vs, NSString *fs, NSError **e) {
	MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
	pd.label = vs;
	pd.vertexFunction = [[s->library newFunctionWithName:vs] autorelease];
	if (fs) pd.fragmentFunction = [[s->library newFunctionWithName:fs] autorelease];
	pd.depthAttachmentPixelFormat = s->format;
	pd.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle;
	return [s->device newRenderPipelineStateWithDescriptor:pd error:e];
}

void mc_shadow_free(Shadow *s);

// source: shadow.metal. resolution: per cascade (square). depthBits: 16 (Depth16Unorm) or 32 (Depth32Float). Returns NULL
// with err filled on failure.
Shadow *mc_shadow_new(Ctx *ctx, const char *source, int resolution, int layers, int depthBits, char *err, int errCap) {
	@autoreleasepool {
		Shadow *s = calloc(1, sizeof(Shadow));
		s->device = ctx->device;
		s->resolution = resolution;
		s->layers = layers;
		s->format = depthBits == 16 ? MTLPixelFormatDepth16Unorm : MTLPixelFormatDepth32Float;
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
		td.pixelFormat = s->format;
		td.width = resolution;
		td.height = resolution;
		td.arrayLength = layers;
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
		s->map = [ctx->device newTextureWithDescriptor:td];
		s->map.label = @"mcopt shadow map";

		uint16_t indices[QUAD_INDICES];
		static const uint16_t corners[6] = {0, 1, 2, 2, 3, 0};
		for (int q = 0, i = 0; q < CHUNK_QUADS; q++) {
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
	[s->debugTiles release];
	[s->depthWrite release];
	[s->compare release];
	[s->atlasSampler release];
	[s->map release];
	[s->quadIndices release];
	[s->tint release];
	[s->tintMap release];
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

// Debug view: every cascade as a size x size tile, drawn into a small texture of its own and blitted into target at
// (x + c * (size + gap), y) (texture pixels, GL row order). A blit leaves the rest of the target alone: a render pass on it
// would load and store all of it. params: one MSL ShadowDebugParams (paramBytes) per cascade. Returns 0, or 1 when the
// debug pipeline can't be made.
int mc_shadow_debug(Enc *enc, Shadow *s, id<MTLTexture> target, int x, int y, int size, int gap, const void *params, int paramBytes) {
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
	int width = s->layers * size;
	if (x < 0 || y < 0 || x + width + (s->layers - 1) * gap > (int) target.width || y + size > (int) target.height) return 0;
	if (!s->debugTiles || (int) s->debugTiles.width != width || (int) s->debugTiles.height != size || s->debugTiles.pixelFormat != target.pixelFormat) {
		[s->debugTiles release];
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:target.pixelFormat width:width height:size mipmapped:NO];
		td.usage = MTLTextureUsageRenderTarget;
		td.storageMode = MTLStorageModePrivate;
		s->debugTiles = [s->device newTextureWithDescriptor:td];
	}
	id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
	@autoreleasepool {
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = s->debugTiles;
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;  // the tiles cover all of it
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cmd renderCommandEncoderWithDescriptor:rp];
		r.label = @"mcopt shadow debug";
		[r setRenderPipelineState:s->debug];
		[r setFragmentTexture:s->map atIndex:0];
		for (int c = 0; c < s->layers; c++) {
			[r setViewport:(MTLViewport) {c * size, 0, size, size, 0, 1}];
			[r setFragmentBytes:(const uint8_t *) params + c * paramBytes length:paramBytes atIndex:0];
			[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		}
		[r endEncoding];
		id<MTLBlitCommandEncoder> b = [cmd blitCommandEncoder];
		for (int c = 0; c < s->layers; c++) {
			[b copyFromTexture:s->debugTiles sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(c * size, 0, 0) sourceSize:MTLSizeMake(size, size, 1)
				toTexture:target destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(x + c * (size + gap), y, 0)];
		}
		[b endEncoding];
	}
	return 0;
}

// ---- coloured shadows (M3 weather) ----

// The tint map (RGBA16Float, resolution^2) and its pipeline (shadow.metal shadow_tint_vs / shadow_tint_fs; rgb blended by MIN,
// alpha by MAX). Returns 0, or 1 on failure (err filled).
int mc_shadow_tint_init(Shadow *s, int resolution, char *err, int errCap) {
	if (s->tint && s->tintMap && (int) s->tintMap.width == resolution) return 0;
	@autoreleasepool {
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.label = @"mcopt shadow tint";
		pd.vertexFunction = [[s->library newFunctionWithName:@"shadow_tint_vs"] autorelease];
		pd.fragmentFunction = [[s->library newFunctionWithName:@"shadow_tint_fs"] autorelease];
		pd.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle;
		MTLRenderPipelineColorAttachmentDescriptor *ca = pd.colorAttachments[0];
		ca.pixelFormat = MTLPixelFormatRGBA16Float;
		ca.blendingEnabled = YES;
		ca.rgbBlendOperation = MTLBlendOperationMin;
		ca.alphaBlendOperation = MTLBlendOperationMax;
		ca.sourceRGBBlendFactor = MTLBlendFactorOne;
		ca.destinationRGBBlendFactor = MTLBlendFactorOne;
		ca.sourceAlphaBlendFactor = MTLBlendFactorOne;
		ca.destinationAlphaBlendFactor = MTLBlendFactorOne;
		NSError *e = nil;
		id<MTLRenderPipelineState> pso = [s->device newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!pso) {
			copyErr(e, "shadow tint pipeline failed", err, errCap);
			return 1;
		}
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:resolution height:resolution mipmapped:NO];
		td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
		td.storageMode = MTLStorageModePrivate;
		id<MTLTexture> map = [s->device newTextureWithDescriptor:td];
		if (!map) {
			[pso release];
			strlcpy(err, "shadow tint map failed", errCap);
			return 1;
		}
		map.label = @"mcopt shadow tint";
		[s->tint release];
		[s->tintMap release];
		s->tint = pso;
		s->tintMap = map;
		s->tintDirty = true;  // undefined until the first pass clears it
	}
	return 0;
}

id<MTLTexture> mc_shadow_tint_texture(Shadow *s) { return s->tintMap; }

/*
 * The tint pass for the near cascade: clears the map to (1, 1, 1, 0) (no tint, no caster) and draws the translucent lists
 * (records as mc_shadow_render's; every record is drawn with the tint pipeline). params: the MSL ShadowTintParams (32 bytes:
 * the water sprites). With no draws the map is cleared only if it held casters. Returns 1 when it ran a pass.
 */
int mc_shadow_tint_render(Enc *enc, Shadow *s, const void *cascade, int cascadeBytes, id<MTLBuffer> chunks, uint64_t chunksOffset,
	id<MTLBuffer> regions, uint64_t regionsOffset, const uint8_t *draws, int drawCount, id<MTLTexture> atlas, const void *params, int paramBytes) {
	if (!s->tint || !s->tintMap) return 0;
	if (drawCount == 0 && !s->tintDirty) return 0;
	id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
	@autoreleasepool {
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = s->tintMap;
		rp.colorAttachments[0].loadAction = MTLLoadActionClear;
		rp.colorAttachments[0].clearColor = MTLClearColorMake(1.0, 1.0, 1.0, 0.0);
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cmd renderCommandEncoderWithDescriptor:rp];
		r.label = @"mcopt shadow tint";
		int res = (int) s->tintMap.width;
		[r setViewport:(MTLViewport) {0, 0, res, res, 0, 1}];
		if (drawCount > 0 && atlas) {
			[r setRenderPipelineState:s->tint];
			[r setCullMode:MTLCullModeNone];
			[r setDepthClipMode:MTLDepthClipModeClamp];
			[r setVertexBytes:cascade length:cascadeBytes atIndex:3];
			[r setVertexBuffer:chunks offset:chunksOffset atIndex:1];
			[r setVertexBuffer:regions offset:regionsOffset atIndex:2];
			[r setFragmentTexture:atlas atIndex:0];
			[r setFragmentSamplerState:s->atlasSampler atIndex:0];
			[r setFragmentBytes:params length:paramBytes atIndex:0];
			id<MTLBuffer> arena = nil;
			for (int i = 0; i < drawCount; i++) {
				const uint8_t *d = draws + i * DRAW_BYTES;
				id<MTLBuffer> vb;
				uint32_t first, count;
				memcpy(&vb, d, 8);
				memcpy(&first, d + 8, 4);
				memcpy(&count, d + 12, 4);
				if (count == 0) continue;
				if (vb != arena) {
					[r setVertexBuffer:vb offset:0 atIndex:0];
					arena = vb;
				}
				[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:QUAD_INDICES indexType:MTLIndexTypeUInt16 indexBuffer:s->quadIndices
					indexBufferOffset:0 instanceCount:count baseVertex:0 baseInstance:first];
			}
		}
		[r endEncoding];
	}
	s->tintDirty = drawCount > 0;
	return 1;
}
