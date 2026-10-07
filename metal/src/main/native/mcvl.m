// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Volumetric light (mcopt.metal.shade.vl, resources/mcopt/shade/vl.metal): the passes' pipelines, their targets and the
// per-frame encoding. Everything is encoded on the frame's command buffer (mc_frame_cmd ends whatever the backend had
// open; its next pass opens a new encoder), between the level and the hand:
//   canopy (compute, 64 threads) -> march (VL res) -> temporal (VL res, history ping-pong) -> composite (full res, onto the
//   game's main colour with programmable blending: load, store).
// VL-resolution targets are private RenderTarget|ShaderRead RGBA16Float (compressible; lossy with -Dmcopt.shade.vl.lossy).
// Manual retain/release (the dylib builds with -fno-objc-arc).
#import "mcmetal.h"

int mc_shade_bind_render(id<MTLRenderCommandEncoder> r);
int mc_shade_bind_compute(id<MTLComputeCommandEncoder> c);

typedef struct {
	id<MTLLibrary> lib;
	id<MTLRenderPipelineState> march, temporal, composite;
	MTLPixelFormat compositeFormat;
	int compositeHdr;
	id<MTLComputePipelineState> canopy;
	id<MTLTexture> raw, hist[2], sky[2]; // sky: R8 sky masks (march output, read by the next frame's crepuscular taps)
	int width, height, cur, lossy;
	id<MTLBuffer> state;  // VlState: the canopy factor (GPU-written, read by the march)
} VlState;

static id<MTLRenderPipelineState> pso(id<MTLDevice> dev, id<MTLLibrary> lib, NSString *fragment, MTLPixelFormat format, int hdr,
	char *err, int errCap) {
	NSError *e = nil;
	MTLFunctionConstantValues *fc = [[MTLFunctionConstantValues new] autorelease];
	bool h = hdr != 0;
	[fc setConstantValue:&h type:MTLDataTypeBool atIndex:0];
	id<MTLFunction> fs = [lib newFunctionWithName:fragment constantValues:fc error:&e];
	if (!fs) {
		snprintf(err, errCap, "%s: %s", fragment.UTF8String, e.localizedDescription.UTF8String);
		return nil;
	}
	MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
	pd.label = fragment;
	pd.vertexFunction = [[lib newFunctionWithName:@"vl_vs"] autorelease];
	pd.fragmentFunction = [fs autorelease];
	pd.colorAttachments[0].pixelFormat = format;
	if ([fragment isEqualToString:@"vl_march"]) pd.colorAttachments[1].pixelFormat = MTLPixelFormatR8Unorm;
	if (hdr && [fragment isEqualToString:@"vl_composite"]) {
		pd.colorAttachments[0].blendingEnabled = YES;  // M2: scene * T (source alpha) + light, into the pre-exposed HDR scene
		pd.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorOne;
		pd.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorSourceAlpha;
		pd.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorZero;
		pd.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOne;
	}
	id<MTLRenderPipelineState> p = [dev newRenderPipelineStateWithDescriptor:pd error:&e];
	if (!p) snprintf(err, errCap, "%s pipeline: %s", fragment.UTF8String, e.localizedDescription.UTF8String);
	return p;
}

// Compiles the passes (source = shade_common.metal + vl.metal). NULL with the message in err on failure.
VlState *mc_vl_new(Ctx *ctx, const char *source, int lossy, char *err, int errCap) {
	@autoreleasepool {
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_0;
		o.mathMode = MTLMathModeFast;
		NSError *e = nil;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!lib) {
			snprintf(err, errCap, "%s", e.localizedDescription.UTF8String);
			return NULL;
		}
		VlState *s = calloc(1, sizeof(VlState));
		s->lib = lib;
		s->lossy = lossy;
		s->march = pso(ctx->device, lib, @"vl_march", MTLPixelFormatRGBA16Float, 0, err, errCap);
		s->temporal = s->march ? pso(ctx->device, lib, @"vl_temporal", MTLPixelFormatRGBA16Float, 0, err, errCap) : nil;
		id<MTLFunction> k = [[lib newFunctionWithName:@"vl_canopy"] autorelease];
		s->canopy = k ? [ctx->device newComputePipelineStateWithFunction:k error:&e] : nil;
		if (!s->march || !s->temporal || !s->canopy) {
			if (!s->canopy && s->march && s->temporal) snprintf(err, errCap, "vl_canopy: %s", e ? e.localizedDescription.UTF8String : "missing");
			[s->march release];
			[s->temporal release];
			[s->canopy release];
			[lib release];
			free(s);
			return NULL;
		}
		s->state = [ctx->device newBufferWithLength:16 options:MTLResourceStorageModeShared];
		memset(s->state.contents, 0, 16);
		s->state.label = @"mcopt shade: VL state";
		return s;
	}
}

void mc_vl_free(VlState *s) {
	if (!s) return;
	[s->lib release];
	[s->march release];
	[s->temporal release];
	[s->composite release];
	[s->canopy release];
	[s->raw release];
	[s->hist[0] release];
	[s->hist[1] release];
	[s->sky[0] release];
	[s->sky[1] release];
	[s->state release];
	free(s);
}

static id<MTLTexture> target(id<MTLDevice> dev, MTLPixelFormat format, int w, int h, int lossy, NSString *label) {
	MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
	td.storageMode = MTLStorageModePrivate;
	td.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
	if (lossy) {
		if (@available(macOS 15.0, *)) td.compressionType = MTLTextureCompressionTypeLossy;
	}
	id<MTLTexture> t = [dev newTextureWithDescriptor:td];
	t.label = label;
	return t;
}

static void pass(id<MTLCommandBuffer> cb, id<MTLTexture> out, id<MTLTexture> out1, MTLLoadAction load, id<MTLRenderPipelineState> p,
	NSString *label, const void *params, int paramBytes, id<MTLBuffer> state, id<MTLTexture> t0, id<MTLTexture> t1) {
	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = out;
	rp.colorAttachments[0].loadAction = load;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	if (out1) {
		rp.colorAttachments[1].texture = out1;
		rp.colorAttachments[1].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[1].storeAction = MTLStoreActionStore;
	}
	id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
	r.label = label;
	[r setRenderPipelineState:p];
	mc_shade_bind_render(r);
	[r setFragmentBytes:params length:paramBytes atIndex:0];
	if (state) [r setFragmentBuffer:state offset:0 atIndex:1];
	if (t0) [r setFragmentTexture:t0 atIndex:0];
	if (t1) [r setFragmentTexture:t1 atIndex:1];
	[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[r endEncoding];
}

// One frame of volumetric light onto color (the game's main colour, read and written in place) from depth (its depth).
// params: VlParams (vl.metal), paramBytes of it; vlWidth x vlHeight the march resolution. flags: 1 canopy pass,
// 2 drop the history, 4 HDR composite (scene * T + light; M2). Returns 0, or -1 when the composite pipeline can't be built (err).
int mc_vl_render(Enc *enc, VlState *s, id<MTLTexture> color, id<MTLTexture> depth, const void *params, int paramBytes, int vlWidth,
	int vlHeight, int flags, char *err, int errCap) {
	@autoreleasepool {
		id<MTLDevice> dev = enc->ctx->device;
		int hdr = (flags & 4) != 0;
		if (!s->composite || s->compositeFormat != color.pixelFormat || s->compositeHdr != hdr) {
			[s->composite release];
			s->composite = pso(dev, s->lib, @"vl_composite", color.pixelFormat, hdr, err, errCap);
			s->compositeFormat = color.pixelFormat;
			s->compositeHdr = hdr;
			if (!s->composite) return -1;
		}
		int reset = (flags & 2) != 0;
		if (!s->raw || s->width != vlWidth || s->height != vlHeight) {
			[s->raw release];
			[s->hist[0] release];
			[s->hist[1] release];
			[s->sky[0] release];
			[s->sky[1] release];
			s->raw = target(dev, MTLPixelFormatRGBA16Float, vlWidth, vlHeight, s->lossy, @"mcopt shade: VL march");
			s->hist[0] = target(dev, MTLPixelFormatRGBA16Float, vlWidth, vlHeight, s->lossy, @"mcopt shade: VL history A");
			s->hist[1] = target(dev, MTLPixelFormatRGBA16Float, vlWidth, vlHeight, s->lossy, @"mcopt shade: VL history B");
			s->sky[0] = target(dev, MTLPixelFormatR8Unorm, vlWidth, vlHeight, 0, @"mcopt shade: VL sky mask A");
			s->sky[1] = target(dev, MTLPixelFormatR8Unorm, vlWidth, vlHeight, 0, @"mcopt shade: VL sky mask B");
			s->width = vlWidth;
			s->height = vlHeight;
			reset = 1;
		}
		float local[64];
		if (paramBytes > (int) sizeof(local)) paramBytes = sizeof(local);
		memcpy(local, params, paramBytes);
		if (reset && paramBytes >= 38 * 4) local[37] = 1.0f; // VlParams.temporal.y: the temporal pass drops the history
		params = local;
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		if (flags & 1) {
			id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
			c.label = @"mcopt shade: VL canopy";
			[c setComputePipelineState:s->canopy];
			mc_shade_bind_compute(c);
			[c setBytes:params length:paramBytes atIndex:0];
			[c setBuffer:s->state offset:0 atIndex:1];
			[c dispatchThreadgroups:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c endEncoding];
		}
		int next = 1 - s->cur;
		// the march writes this frame's sky mask and reads last frame's (the crepuscular streaks' occluders)
		pass(cb, s->raw, s->sky[next], MTLLoadActionDontCare, s->march, @"mcopt shade: VL march", params, paramBytes, s->state, depth, s->sky[s->cur]);
		pass(cb, s->hist[next], nil, MTLLoadActionDontCare, s->temporal, @"mcopt shade: VL temporal", params, paramBytes, nil, s->raw, s->hist[s->cur]);
		pass(cb, color, nil, MTLLoadActionLoad, s->composite, @"mcopt shade: VL composite", params, paramBytes, s->state, s->hist[next], depth);
		s->cur = next;
		return 0;
	}
}

// The canopy factor the GPU wrote (smoothed, raw shadowed fraction) as of the last completed frame, for logs and tests.
void mc_vl_canopy(VlState *s, float *out) {
	const float *c = (const float *) s->state.contents;
	out[0] = c[0];
	out[1] = c[1];
}
