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

// M3 entity casters (mc_shadow_entity_*): the frame's entity draws, recorded in the main pass, copied at the end of the
// world (the game's vertex buffers are pooled per frame) and drawn into the next frame's near cascade.
#define ENT_MAX 4096
#define ENT_BYTES (12u << 20)
typedef struct {
	id<MTLBuffer> src;      // pending: the game's vertex buffer (retained until the copy is encoded); nil once copied
	id<MTLTexture> tex;     // retained while listed
	uint64_t srcOffset;     // pending: byte offset of the draw's first vertex
	uint32_t dst;           // ready: byte offset in casters
	uint32_t quads, stride, uvOffset;
	float cutoff;
} EntDraw;

typedef struct {
	id<MTLDevice> device;
	id<MTLLibrary> library;
	id<MTLRenderPipelineState> solid, cutout;
	id<MTLRenderPipelineState> entity;   // M3: entity casters (shadow_entity_vs / shadow_entity_fs)
	id<MTLBuffer> casters;               // ENT_BYTES, private: last frame's entity vertices
	EntDraw pending[ENT_MAX], ready[ENT_MAX];
	int pendingCount, readyCount;
	uint32_t pendingBytes;
	float shift[3];                      // camera-relative offset of the ready casters for this frame (set by Java)
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
		if (s->cutout) s->entity = depthOnly(s, @"shadow_entity_vs", @"shadow_entity_fs", &e);
		if (s->entity) {
			s->casters = [ctx->device newBufferWithLength:ENT_BYTES options:MTLResourceStorageModePrivate];
			s->casters.label = @"mcopt shadow: entity casters";
		}
		if (!s->entity || !s->casters) {
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

static void entClear(EntDraw *list, int *count) {
	for (int i = 0; i < *count; i++) {
		[list[i].src release];
		[list[i].tex release];
		list[i].src = nil;
		list[i].tex = nil;
	}
	*count = 0;
}

void mc_shadow_free(Shadow *s) {
	if (!s) return;
	entClear(s->pending, &s->pendingCount);
	entClear(s->ready, &s->readyCount);
	[s->entity release];
	[s->casters release];
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
		// M3: last frame's entities into the near cascade (shifted onto this frame's camera)
		if (layer == 0 && s->readyCount > 0 && s->entity) {
			[r setRenderPipelineState:s->entity];
			[r setVertexBuffer:s->casters offset:0 atIndex:0];
			[r setFragmentSamplerState:s->atlasSampler atIndex:0];
			id<MTLTexture> bound = nil;
			for (int i = 0; i < s->readyCount; i++) {
				EntDraw *d = &s->ready[i];
				struct { float shift[3]; uint32_t stride; uint32_t uvOffset; float cutoff; float pad[2]; } p = {
					{s->shift[0], s->shift[1], s->shift[2]}, d->stride, d->uvOffset, d->cutoff, {0, 0}};
				[r setVertexBufferOffset:d->dst atIndex:0];
				[r setVertexBytes:&p length:sizeof p atIndex:4];
				[r setFragmentBytes:&p length:sizeof p atIndex:4];
				if (d->tex != bound) {
					[r setFragmentTexture:d->tex atIndex:0];
					bound = d->tex;
				}
				[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:d->quads * 6];
			}
		}
		[r endEncoding];
	}
	return profiled;
}

// ---- M3 entity casters ----------------------------------------------------------------------------------------------------
// One entity draw of the main pass: quads x 4 vertices of `stride` bytes at src + srcOffset (position float3 at 0, uv float2 at
// uvOffset), texture tex (alpha < cutoff: no shadow). Kept (retained) until mc_shadow_entity_commit copies them. 0 = recorded,
// 1 = dropped (list or buffer full).
int mc_shadow_entity_record(Shadow *s, id<MTLBuffer> src, uint64_t srcOffset, int quads, int stride, int uvOffset, id<MTLTexture> tex, float cutoff) {
	if (!s || !s->casters || !src || quads <= 0 || stride <= 0 || s->pendingCount >= ENT_MAX) return 1;
	uint32_t bytes = (uint32_t) quads * 4u * (uint32_t) stride;
	uint32_t dst = (s->pendingBytes + 15u) & ~15u;
	if (dst + bytes > ENT_BYTES || srcOffset + bytes > src.length) return 1;
	EntDraw *d = &s->pending[s->pendingCount++];
	d->src = [src retain];
	d->tex = [tex retain];
	d->srcOffset = srcOffset;
	d->dst = dst;
	d->quads = (uint32_t) quads;
	d->stride = (uint32_t) stride;
	d->uvOffset = (uint32_t) uvOffset;
	d->cutoff = cutoff;
	s->pendingBytes = dst + bytes;
	return 0;
}

// End of the world (before the hand): the recorded draws' vertices -> casters (one blit encoder on the frame's command buffer),
// and they become the list the next frame's near cascade draws. With nothing recorded, the list empties.
void mc_shadow_entity_commit(Enc *enc, Shadow *s) {
	if (!s) return;
	entClear(s->ready, &s->readyCount);
	if (s->pendingCount > 0) {
		id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
		@autoreleasepool {
			id<MTLBlitCommandEncoder> b = [cmd blitCommandEncoder];
			b.label = @"mcopt shadow: entity casters";
			for (int i = 0; i < s->pendingCount; i++) {
				EntDraw *d = &s->pending[i];
				[b copyFromBuffer:d->src sourceOffset:d->srcOffset toBuffer:s->casters destinationOffset:d->dst size:(NSUInteger) d->quads * 4u * d->stride];
			}
			[b endEncoding];
		}
		for (int i = 0; i < s->pendingCount; i++) {
			s->ready[i] = s->pending[i];
			[s->ready[i].src release]; // the blit holds it now
			s->ready[i].src = nil;
		}
		s->readyCount = s->pendingCount;
		s->pendingCount = 0;
	}
	s->pendingBytes = 0;
}

// Drops what was recorded (a frame that never reached its commit), keeping the ready list.
void mc_shadow_entity_reset(Shadow *s) {
	if (!s) return;
	entClear(s->pending, &s->pendingCount);
	s->pendingBytes = 0;
}

// The ready casters' shift for this frame's near cascade (camera of the frame they were recorded in - this frame's camera);
// clear: forget them (the camera jumped, another dimension).
void mc_shadow_entity_shift(Shadow *s, float x, float y, float z, int clear) {
	if (!s) return;
	if (clear) entClear(s->ready, &s->readyCount);
	s->shift[0] = x;
	s->shift[1] = y;
	s->shift[2] = z;
}

int mc_shadow_entity_count(Shadow *s) { return s ? s->readyCount : 0; }

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
