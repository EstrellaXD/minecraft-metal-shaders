// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Native shading's water module (-Dmcopt.shade=true, mcopt.shade.water; Java side mcopt.metal.shade.water.ShadeWater,
// shader side resources/mcopt/shade/water.metal). Two jobs:
//
//  1. The scene copy (bible hook H6): when the main pass switches to Sodium's translucent terrain, the open render encoder
//     is ended (its attachments stored), colour attachment 0 and the depth attachment are copied with one blit encoder
//     into textures of the same format and size (private, ShaderRead only, so they stay losslessly compressed), and the
//     pass is reopened on the same attachments with their contents loaded back. The water draws that follow read what
//     is behind and around them (refraction, absorption, foam, screen-space reflections) from the copies.
//  2. The bindings, fragment stage only, on every render encoder native shading binds (ShadeBindings.bind):
//       texture(18)  sceneCopy  texture2d<half>  the copy of colour attachment 0 (a 1x1 dummy until the first capture)
//       texture(19)  depthCopy  depth2d<float>   the copy of the depth attachment (a 1x1 dummy until the first capture)
//       buffer(23)   ShadeWaterParams (64 bytes, setFragmentBytes): this frame's water sprite rectangles and state; its
//                    state.x is 1 only after this frame's capture, so shaders never trust last frame's copies.
//     Slots: the bible reserves texture(17-19) for pass-local inputs (17 is M1's sky LUT); buffer 23 is free in every
//     pipeline that binds shade globals (far terrain's own pipelines use 22-28 but rebind shade after drawing).
// Manual retain/release (the dylib builds with -fno-objc-arc), like the other files.
#import "mcmetal.h"

typedef struct {
	float still[4];  // atlas uv rectangle (u0, v0, u1, v1) of minecraft:block/water_still
	float flow[4];   // the same for minecraft:block/water_flow
	float state[4];  // x copies valid this frame (0/1), y water surface height above the eye (camera-relative y, under water), z wetness, w 0
	float tune[4];   // x wave strength, y SSR max distance (blocks), z reflection strength, w caustic strength
} WaterParams;

typedef struct {
	id<MTLTexture> color, depth;            // the copies (recreated when the attachments' size or format change)
	id<MTLTexture> dummyColor, dummyDepth;  // bound until the first capture
	WaterParams params;
	int captures;
} WaterState;

// The one state mc_water_bind binds from (set by mc_water_new).
static WaterState *water;

WaterState *mc_water_new(Ctx *ctx) {
	@autoreleasepool {
		WaterState *s = calloc(1, sizeof(WaterState));
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageShaderRead;
		s->dummyColor = [ctx->device newTextureWithDescriptor:td];
		s->dummyColor.label = @"mcopt shade water: no scene copy";
		td.pixelFormat = MTLPixelFormatDepth32Float;
		s->dummyDepth = [ctx->device newTextureWithDescriptor:td];
		s->dummyDepth.label = @"mcopt shade water: no depth copy";
		water = s;
		return s;
	}
}

void mc_water_free(WaterState *s) {
	if (!s) return;
	if (water == s) water = NULL;
	[s->color release];
	[s->depth release];
	[s->dummyColor release];
	[s->dummyDepth release];
	free(s);
}

// This frame's parameters (16 floats: still, flow, state, tune); state.x (copies valid) is cleared until the capture.
void mc_water_params(WaterState *s, const float *p) {
	memcpy(&s->params, p, sizeof(WaterParams));
	s->params.state[0] = 0.0f;
}

static id<MTLTexture> copyTarget(Ctx *ctx, id<MTLTexture> old, id<MTLTexture> like, NSString *label) {
	if (old && old.width == like.width && old.height == like.height && old.pixelFormat == like.pixelFormat) return old;
	[old release];
	MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:like.pixelFormat width:like.width height:like.height mipmapped:NO];
	td.storageMode = MTLStorageModePrivate;
	td.usage = MTLTextureUsageShaderRead;  // no ShaderWrite / PixelFormatView: keeps lossless compression
	id<MTLTexture> t = [ctx->device newTextureWithDescriptor:td];
	t.label = label;
	return t;
}

// Binds the copies (or the dummies) and the parameters into enc's open render encoder, fragment stage.
void mc_water_bind(Enc *enc) {
	id<MTLRenderCommandEncoder> r = enc->render;
	WaterState *s = water;
	if (!r || !s) return;
	int valid = s->params.state[0] > 0.5f;
	[r setFragmentTexture:valid ? s->color : s->dummyColor atIndex:18];
	[r setFragmentTexture:valid ? s->depth : s->dummyDepth atIndex:19];
	[r setFragmentBytes:&s->params length:sizeof(WaterParams) atIndex:23];
}

// The scene copy at the switch to translucent terrain: ends the open render encoder (stored), copies colour attachment 0
// and the depth attachment, reopens the pass (loaded back) and binds the copies. Returns 1 when it did (the caller must
// then restore its encoder state: pipeline, uniforms, scissor, vertex buffers, shade globals), 0 when there was nothing
// to copy (no open encoder, no depth or colour attachment, or a memoryless one), with the encoder untouched.
int mc_water_capture(Enc *enc, WaterState *s) {
	if (!enc->render || !enc->depth || enc->colorCount < 1 || !enc->colors[0]) return 0;
	id<MTLTexture> color = enc->colors[0], depth = enc->depth;
	if (color.storageMode == MTLStorageModeMemoryless || depth.storageMode == MTLStorageModeMemoryless || color.sampleCount != 1 || depth.sampleCount != 1) return 0;
	@autoreleasepool {
		s->color = copyTarget(enc->ctx, s->color, color, @"mcopt shade water: scene copy");
		s->depth = copyTarget(enc->ctx, s->depth, depth, @"mcopt shade water: depth copy");
		if (!s->color || !s->depth) return 0;
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);  // ends the render encoder: every live attachment is stored
		id<MTLBlitCommandEncoder> b = [cb blitCommandEncoder];
		b.label = @"mcopt shade water: scene copy";
		[b copyFromTexture:color toTexture:s->color];
		[b copyFromTexture:depth toTexture:s->depth];
		[b endEncoding];
		mc_render_reopen(enc);
	}
	s->params.state[0] = 1.0f;
	s->captures++;
	mc_water_bind(enc);
	return 1;
}

id<MTLTexture> mc_water_scene_copy(WaterState *s) { return s->color; }
id<MTLTexture> mc_water_depth_copy(WaterState *s) { return s->depth; }
