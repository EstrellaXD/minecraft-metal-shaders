// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Native shading (-Dmcopt.shade=true): the global resources every render encoder gets bound, for the vertex and fragment
// stages alike (see mcopt.metal.shade.ShadeBindings and resources/mcopt/shade/shade_common.metal):
//   buffer(16)  ShadeFrame, this frame's slot of a ring of shared buffers (written by the CPU at LevelRenderer.render HEAD)
//   texture(16) the sun shadow map: depth2d_array<float>, one slice per cascade, reversed Z (1 = nearest the light)
//   sampler(12) a comparison sampler for it: linear (2x2 hardware PCF), clamp to edge, GreaterEqual
//   texture(17) the sky-view LUT (RGBA16Float), rendered here once per frame (mc_shade_lut_render, sky_lut.metal)
//   sampler(13) linear, clamp to edge, for the LUT
// Until a shadow pass provides a map, texture(16) is a 1x1x2 Depth32Float array that is never sampled (ShadeFrame says
// shadows are off), so shaders that declare the slot always have a valid binding of the right type.
// Slots 16-19 (buffers, textures) and samplers 12-15 are reserved for shade; nothing in the backend uses them.
// Manual retain/release (the dylib builds with -fno-objc-arc), like the other files.
#import "mcmetal.h"

typedef struct {
	id<MTLTexture> dummyShadow;
	id<MTLSamplerState> compare;
	id<MTLTexture> lut;
	id<MTLSamplerState> linear;
	id<MTLRenderPipelineState> lutPipeline;
} ShadeState;

// The one state mc_shade_bind binds from (set by mc_shade_new).
static ShadeState *state;

// What mc_shade_bind binds; set from the render thread by mc_shade_set. Not retained here: the Java side owns them and
// keeps them alive while they're set.
static id<MTLBuffer> frameBuffer;
static uint64_t frameOffset;
static id<MTLTexture> shadowMap;
static id<MTLSamplerState> shadowSampler;

ShadeState *mc_shade_new(Ctx *ctx) {
	@autoreleasepool {
		ShadeState *s = calloc(1, sizeof(ShadeState));
		MTLTextureDescriptor *td = [MTLTextureDescriptor new];
		td.textureType = MTLTextureType2DArray;
		td.pixelFormat = MTLPixelFormatDepth32Float;
		td.width = 1;
		td.height = 1;
		td.arrayLength = 2;
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
		s->dummyShadow = [ctx->device newTextureWithDescriptor:td];
		[td release];
		s->dummyShadow.label = @"mcopt shade: no shadow map";
		MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
		sd.minFilter = MTLSamplerMinMagFilterLinear;
		sd.magFilter = MTLSamplerMinMagFilterLinear;
		sd.mipFilter = MTLSamplerMipFilterNotMipmapped;
		sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.rAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.compareFunction = MTLCompareFunctionGreaterEqual;  // reversed Z: lit when the receiver is at least as near the light
		s->compare = [ctx->device newSamplerStateWithDescriptor:sd];
		sd.compareFunction = MTLCompareFunctionNever;
		s->linear = [ctx->device newSamplerStateWithDescriptor:sd];
		[sd release];
		state = s;
		return s;
	}
}

id<MTLTexture> mc_shade_dummy_shadow(ShadeState *s) {
	return s->dummyShadow;
}

id<MTLSamplerState> mc_shade_compare_sampler(ShadeState *s) {
	return s->compare;
}

id<MTLTexture> mc_shade_lut_texture(ShadeState *s) {
	return s->lut;
}

void mc_shade_free(ShadeState *s) {
	if (!s) return;
	if (state == s) state = NULL;
	[s->dummyShadow release];
	[s->compare release];
	[s->linear release];
	[s->lut release];
	[s->lutPipeline release];
	free(s);
}

// Compiles the LUT pass (shade_common.metal + sky_lut.metal) and makes its width x height RGBA16Float target. 0 on success,
// else -1 with the compiler's message in err.
int mc_shade_lut_init(ShadeState *s, Ctx *ctx, const char *source, int width, int height, char *err, int errCap) {
	@autoreleasepool {
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_0;
		o.mathMode = MTLMathModeFast;
		NSError *e = nil;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!lib) {
			snprintf(err, errCap, "%s", e.localizedDescription.UTF8String);
			return -1;
		}
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.vertexFunction = [[lib newFunctionWithName:@"shade_lut_vs"] autorelease];
		pd.fragmentFunction = [[lib newFunctionWithName:@"shade_lut_fs"] autorelease];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
		id<MTLRenderPipelineState> pso = [ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
		[lib release];
		if (!pso) {
			snprintf(err, errCap, "%s", e.localizedDescription.UTF8String);
			return -1;
		}
		[s->lutPipeline release];
		s->lutPipeline = pso;
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:width height:height mipmapped:NO];
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
		[s->lut release];
		s->lut = [ctx->device newTextureWithDescriptor:td];
		s->lut.label = @"mcopt shade: sky LUT";
		return 0;
	}
}

// Renders the sky-view LUT from the current ShadeFrame, in its own encoder on the frame's command buffer (ending any open
// render encoder, which the backend reopens for its next pass), so everything encoded after it in this frame can read it.
void mc_shade_lut_render(Enc *enc, ShadeState *s) {
	if (!s->lutPipeline || !frameBuffer) return;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = s->lut;
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
		r.label = @"mcopt shade: sky LUT";
		[r setRenderPipelineState:s->lutPipeline];
		[r setFragmentBuffer:frameBuffer offset:frameOffset atIndex:16];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
	}
}

void mc_shade_set(id<MTLBuffer> frame, uint64_t offset, id<MTLTexture> shadow, id<MTLSamplerState> sampler) {
	frameBuffer = frame;
	frameOffset = offset;
	shadowMap = shadow;
	shadowSampler = sampler;
}

// Binds the globals into enc's open render encoder (nothing when none is open or nothing is set yet).
void mc_shade_bind(Enc *enc) {
	id<MTLRenderCommandEncoder> r = enc->render;
	if (!r || !frameBuffer) return;
	[r setVertexBuffer:frameBuffer offset:frameOffset atIndex:16];
	[r setFragmentBuffer:frameBuffer offset:frameOffset atIndex:16];
	if (shadowMap) {
		[r setVertexTexture:shadowMap atIndex:16];
		[r setFragmentTexture:shadowMap atIndex:16];
	}
	if (shadowSampler) {
		[r setVertexSamplerState:shadowSampler atIndex:12];
		[r setFragmentSamplerState:shadowSampler atIndex:12];
	}
	if (state && state->lut) {
		[r setVertexTexture:state->lut atIndex:17];
		[r setFragmentTexture:state->lut atIndex:17];
		[r setVertexSamplerState:state->linear atIndex:13];
		[r setFragmentSamplerState:state->linear atIndex:13];
	}
}
