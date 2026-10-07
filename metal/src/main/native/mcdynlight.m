// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Native shading's dynamic lights (-Dmcopt.shade=true, mcopt.shade.dynLights; Java side mcopt.metal.shade.light.DynLights,
// shader side resources/mcopt/shade/dynlight.metal and dynlight_vis.metal):
//   - a ring of shared buffers with each frame's lights and light grid (ShadeDynLights, written by the CPU in place),
//   - the block occupancy around the camera (one shared buffer, 128^3 bits, filled by the CPU from chunk sections),
//   - the visibility volumes: a 3D R8 texture, 32 x 32 x (32 x slabs), one 32^3 slab per occluded light, computed once per
//     frame before the world passes (shade_dl_vis),
// bound on every render encoder native shading binds (ShadeBindings.bind), vertex and fragment stages:
//   buffer(24)  this frame's ShadeDynLights      texture(23)  the visibility volumes
//   texture(24), texture(25) (fragment only): the irradiance volume (rgb light, mean light direction) the terrain samples
// Both slots are unused by the game's pipelines (uniforms and samplers from 0, Sodium's 20-22/26, vertex buffers 27-30,
// water's 18-23); far terrain's own pipelines use 24 but rebind shade after drawing.
// Manual retain/release (the dylib builds with -fno-objc-arc), like the other files.
#import "mcmetal.h"

#define DL_RING_MAX 8

typedef struct {
	id<MTLBuffer> ring[DL_RING_MAX];
	int ringSize, slot;
	id<MTLBuffer> occupancy;
	id<MTLTexture> vis;
	int slabs;
	id<MTLComputePipelineState> visPso, irrPso;
	id<MTLTexture> irr, dir;  // the irradiance volume: rgb light, mean light direction (one voxel per block)
} DlState;

// The one state mc_dl_bind binds from (set by mc_dl_new).
static DlState *dl;

// source: dynlight.metal (with SHADE_DL_KERNELS) + dynlight_vis.metal; irrW x irrH x irrD: the irradiance volume; bytes: the ShadeDynLights size; ring: slots (> frames in flight); slabs: visibility volumes
// (<= 64); occBytes: the occupancy buffer's size. NULL with the compiler's message in err.
DlState *mc_dl_new(Ctx *ctx, const char *source, int bytes, int ring, int slabs, int occBytes, int irrW, int irrH, int irrD, char *err, int errCap) {
	@autoreleasepool {
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_0;
		o.mathMode = MTLMathModeFast;
		NSError *e = nil;
		id<MTLLibrary> lib = [[ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e] autorelease];
		if (!lib) {
			snprintf(err, errCap, "%s", e.localizedDescription.UTF8String);
			return NULL;
		}
		id<MTLFunction> fn = [[lib newFunctionWithName:@"shade_dl_vis"] autorelease];
		id<MTLComputePipelineState> pso = fn ? [ctx->device newComputePipelineStateWithFunction:fn error:&e] : nil;
		if (!pso) {
			snprintf(err, errCap, "shade_dl_vis: %s", e ? e.localizedDescription.UTF8String : "missing");
			return NULL;
		}
		id<MTLFunction> fi = [[lib newFunctionWithName:@"shade_dl_irr"] autorelease];
		id<MTLComputePipelineState> ipso = fi ? [ctx->device newComputePipelineStateWithFunction:fi error:&e] : nil;
		if (!ipso) {
			[pso release];
			snprintf(err, errCap, "shade_dl_irr: %s", e ? e.localizedDescription.UTF8String : "missing");
			return NULL;
		}
		DlState *s = calloc(1, sizeof(DlState));
		s->visPso = pso;
		s->irrPso = ipso;
		s->ringSize = ring < 2 ? 2 : ring > DL_RING_MAX ? DL_RING_MAX : ring;
		for (int i = 0; i < s->ringSize; i++) {
			s->ring[i] = [ctx->device newBufferWithLength:bytes options:MTLResourceStorageModeShared | MTLResourceCPUCacheModeWriteCombined];
			memset(s->ring[i].contents, 0, bytes);
			s->ring[i].label = @"mcopt shade: dynamic lights";
		}
		s->occupancy = [ctx->device newBufferWithLength:occBytes options:MTLResourceStorageModeShared];
		memset(s->occupancy.contents, 0, occBytes);
		s->occupancy.label = @"mcopt shade: block occupancy";
		s->slabs = slabs < 1 ? 1 : slabs > 64 ? 64 : slabs;
		MTLTextureDescriptor *td = [[MTLTextureDescriptor new] autorelease];
		td.textureType = MTLTextureType3D;
		td.pixelFormat = MTLPixelFormatR8Unorm;
		td.width = 32;
		td.height = 32;
		td.depth = 32 * s->slabs;
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
		s->vis = [ctx->device newTextureWithDescriptor:td];
		s->vis.label = @"mcopt shade: dynamic light visibility";
		td.width = irrW;
		td.height = irrH;
		td.depth = irrD;
		td.pixelFormat = MTLPixelFormatRGBA16Float;
		s->irr = [ctx->device newTextureWithDescriptor:td];
		s->irr.label = @"mcopt shade: dynamic light irradiance";
		s->dir = [ctx->device newTextureWithDescriptor:td];
		s->dir.label = @"mcopt shade: dynamic light direction";
		dl = s;
		return s;
	}
}

void mc_dl_free(DlState *s) {
	if (!s) return;
	if (dl == s) dl = NULL;
	for (int i = 0; i < s->ringSize; i++) [s->ring[i] release];
	[s->occupancy release];
	[s->vis release];
	[s->visPso release];
	[s->irrPso release];
	[s->irr release];
	[s->dir release];
	free(s);
}

// CPU addresses: ring slot i (0..ring-1), or the occupancy buffer (i = -1).
void *mc_dl_contents(DlState *s, int i) {
	if (i < 0) return s->occupancy.contents;
	return i < s->ringSize ? s->ring[i].contents : NULL;
}

// The ring slot encoders bind from now on.
void mc_dl_slot(DlState *s, int slot) {
	if (slot >= 0 && slot < s->ringSize) s->slot = slot;
}

// Binds this frame's lights and the visibility volumes into enc's open render encoder (vertex and fragment stages).
void mc_dl_bind(Enc *enc) {
	id<MTLRenderCommandEncoder> r = enc->render;
	DlState *s = dl;
	if (!r || !s) return;
	id<MTLBuffer> b = s->ring[s->slot];
	[r setVertexBuffer:b offset:0 atIndex:24];
	[r setFragmentBuffer:b offset:0 atIndex:24];
	[r setVertexTexture:s->vis atIndex:23];
	[r setFragmentTexture:s->vis atIndex:23];
	[r setFragmentTexture:s->irr atIndex:24];
	[r setFragmentTexture:s->dir atIndex:25];
}

// The visibility volumes for this frame (params: DlVisParams, volumes of them), on the frame's command buffer before the
// world passes (ends any open render encoder; the backend reopens its own).
void mc_dl_vis(Enc *enc, DlState *s, const void *params, int length, int volumes) {
	if (!s || volumes <= 0) return;
	if (volumes > s->slabs) volumes = s->slabs;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
		c.label = @"mcopt shade: dynamic light visibility";
		[c setComputePipelineState:s->visPso];
		[c setBytes:params length:length atIndex:0];
		[c setBuffer:s->occupancy offset:0 atIndex:1];
		[c setTexture:s->vis atIndex:0];
		[c dispatchThreads:MTLSizeMake(32, 32, 32 * volumes) threadsPerThreadgroup:MTLSizeMake(8, 8, 4)];
		[c endEncoding];
	}
}

// The irradiance volume from this frame's lights (ring slot s->slot) and the visibility slabs, after mc_dl_vis.
void mc_dl_irr(Enc *enc, DlState *s) {
	if (!s) return;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
		c.label = @"mcopt shade: dynamic light irradiance";
		[c setComputePipelineState:s->irrPso];
		[c setBuffer:s->ring[s->slot] offset:0 atIndex:0];
		[c setTexture:s->vis atIndex:0];
		[c setTexture:s->irr atIndex:1];
		[c setTexture:s->dir atIndex:2];
		[c dispatchThreads:MTLSizeMake(s->irr.width, s->irr.height, s->irr.depth) threadsPerThreadgroup:MTLSizeMake(8, 8, 4)];
		[c endEncoding];
	}
}
