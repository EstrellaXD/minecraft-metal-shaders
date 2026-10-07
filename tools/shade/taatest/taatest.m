// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
//
// Headless check of the TAAU passes (mctaa.m + resources/mcopt/shade/taa.metal), no game: a synthetic scene (a checkered
// ground with thin lines under a sky, the kind of high-frequency pixel-art content that shimmers in Minecraft) is ray-cast
// per pixel with the jittered projection into a sub-rect of fixed-size input textures (colour RGBA8 + reversed-Z
// Depth32Float, like the game's world target), resolved by the real mc_taa_resolve into an output-size RGBA8 target, and
// compared with a 4x4-supersampled, unjittered ground truth at output size.
//   taatest <taa.metal> correct      error vs ground truth: plain (no AA) vs TAAU, still / turning / moving camera, 3 scales
//   taatest <taa.metal> perf W H S   GPU time of the two TAAU passes alone at output W x H from scale S
// Links the jar build's libmcmetal.dylib (Ctx/Enc from mcmetal.m, mc_taa_* from mctaa.m).
#import <Metal/Metal.h>
#import <simd/simd.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#import "mcmetal.h"

typedef struct Taa Taa;
Ctx *mc_create(char *name, int nameCap, char *err, int errCap);
Enc *mc_enc_new(Ctx *ctx);
id<MTLCommandBuffer> mc_enc_commit(Enc *enc);
Taa *mc_taa_new(Ctx *ctx, const char *source, int flags, char *err, int errCap);
int mc_taa_resize(Taa *t, Ctx *ctx, int width, int height);
void mc_taa_resolve(Enc *enc, Taa *t, id<MTLTexture> color, id<MTLTexture> depth, id<MTLTexture> reactive, id<MTLTexture> dst,
	const void *params, int paramsLength);

// Same layout as TaaParams in taa.metal.
typedef struct {
	simd_float4x4 invCur, prevVP;
	simd_float4 curRowW, input, output, jitter, blend, misc, sharpen;
} Params;

static NSString *sceneSource = @
	"#include <metal_stdlib>\n"
	"using namespace metal;\n"
	"struct Scene { float4x4 invVP; float4x4 VP; float4 camera; float4 size; };\n"
	"struct V { float4 position [[position]]; };\n"
	"vertex V scene_vs(uint id [[vertex_id]]) { float2 p = float2((id << 1) & 2, id & 2); V v; v.position = float4(p * 2.0 - 1.0, 0.0, 1.0); return v; }\n"
	// the scene at NDC ndc: rgb (an sRGB-like value, like the game's tonemapped output) and reversed-Z depth
	"static float4 shadeAt(float2 ndc, constant Scene &s, thread float &depth) {\n"
	"  float4 a = s.invVP * float4(ndc, 1.0, 1.0), b = s.invVP * float4(ndc, 0.0, 1.0);\n"
	"  float3 o = a.xyz / a.w, d = normalize(b.xyz / b.w - o);\n"
	"  if (d.y > -1e-4) { depth = 0.0; return float4(mix(float3(0.75, 0.85, 1.0), float3(0.3, 0.5, 0.9), saturate(d.y * 2.0)), 1.0); }\n"
	"  float t = (-2.0 - o.y) / d.y;\n"
	"  float3 p = o + d * t;\n"
	"  float4 c = s.VP * float4(p, 1.0);\n"
	"  depth = c.z / c.w;\n"
	"  float3 w = p + s.camera.xyz;\n"                                  // absolute position: the pattern is fixed in the world
	"  float2 cell = floor(w.xz);\n"
	"  float k = fmod(abs(cell.x + cell.y), 2.0);\n"
	"  float3 col = k < 0.5 ? float3(0.35, 0.55, 0.25) : float3(0.55, 0.45, 0.3);\n"
	"  float2 f = fract(w.xz * 0.25);\n"
	"  if (f.x < 0.03 || f.y < 0.03) col = float3(0.95, 0.95, 0.9);\n"   // thin lines every 4 blocks (aliasing bait)
	"  float2 g = fract(w.xz * 16.0);\n"                                // 1/16-block texels, like a pixel-art atlas
	"  col *= (g.x < 0.5) == (g.y < 0.5) ? 1.0 : 0.85;\n"
	"  return float4(col, 1.0);\n"
	"}\n"
	"struct Out { float4 color [[color(0)]]; float depth [[depth(any)]]; };\n"
	"fragment Out scene_fs(V v [[stage_in]], constant Scene &s [[buffer(0)]]) {\n"
	"  Out o; float z;\n"
	"  o.color = shadeAt(v.position.xy / s.size.xy * 2.0 - 1.0, s, z);\n"
	"  o.depth = z; return o;\n"
	"}\n"
	"fragment float4 truth_fs(V v [[stage_in]], constant Scene &s [[buffer(0)]]) {\n"
	"  float4 sum = 0.0; float z;\n"
	"  for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++) {\n"
	"    float2 p = floor(v.position.xy) + (float2(i, j) + 0.5) / 4.0;\n"
	"    sum += shadeAt(p / s.size.xy * 2.0 - 1.0, s, z);\n"
	"  }\n"
	"  return sum / 16.0;\n"
	"}\n"
	// bilinear upscale of the plain (unjittered) input's sub-rect: what render scale without TAAU would look like
	"fragment float4 upscale_fs(V v [[stage_in]], constant Scene &s [[buffer(0)]], texture2d<float> t [[texture(0)]]) {\n"
	"  constexpr sampler lin(filter::linear, address::clamp_to_edge);\n"
	"  return t.sample(lin, v.position.xy / s.size.xy * s.size.zw / float2(t.get_width(), t.get_height()));\n"
	"}\n";

typedef struct { simd_float4x4 invVP, VP; simd_float4 camera, size; } Scene;

static simd_float4x4 perspective(float fovY, float aspect, float n, float f) {
	float y = 1.0f / tanf(fovY * 0.5f), x = y / aspect;
	// reversed Z: near -> 1, far -> 0 (renderpearl's), view space looks down -z
	simd_float4x4 m = {{{x, 0, 0, 0}, {0, y, 0, 0}, {0, 0, n / (f - n), -1}, {0, 0, n * f / (f - n), 0}}};
	return m;
}
static simd_float4x4 rotation(float yaw, float pitch) {
	float cy = cosf(yaw), sy = sinf(yaw), cp = cosf(pitch), sp = sinf(pitch);
	simd_float4x4 ry = {{{cy, 0, -sy, 0}, {0, 1, 0, 0}, {sy, 0, cy, 0}, {0, 0, 0, 1}}};
	simd_float4x4 rx = {{{1, 0, 0, 0}, {0, cp, sp, 0}, {0, -sp, cp, 0}, {0, 0, 0, 1}}};
	return simd_mul(rx, ry);
}
static simd_float4x4 translation(float x, float y, float z) {
	simd_float4x4 m = matrix_identity_float4x4;
	m.columns[3] = simd_make_float4(x, y, z, 1);
	return m;
}
static float halton(int i, int b) {
	float r = 0, f = 1;
	for (; i > 0; i /= b) {
		f /= b;
		r += f * (i % b);
	}
	return r;
}

static id<MTLDevice> dev;
static id<MTLRenderPipelineState> scenePso, truthPso, upscalePso;
static id<MTLDepthStencilState> depthAlways;

static id<MTLTexture> tex(MTLPixelFormat f, int w, int h, MTLTextureUsage u) {
	MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:f width:w height:h mipmapped:NO];
	d.storageMode = MTLStorageModePrivate;
	d.usage = u;
	return [dev newTextureWithDescriptor:d];
}

static void drawScene(id<MTLCommandBuffer> cb, id<MTLRenderPipelineState> pso, id<MTLTexture> color, id<MTLTexture> depth, int vw, int vh,
	Scene *s, id<MTLTexture> src) {
	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = color;
	rp.colorAttachments[0].loadAction = MTLLoadActionClear;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	if (depth) {
		rp.depthAttachment.texture = depth;
		rp.depthAttachment.loadAction = MTLLoadActionClear;
		rp.depthAttachment.clearDepth = 0;
		rp.depthAttachment.storeAction = MTLStoreActionStore;
	}
	id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
	[r setViewport:(MTLViewport) {0, 0, vw, vh, 0, 1}];  // the live sub-rect, like the game's dynamic resolution
	[r setRenderPipelineState:pso];
	if (depth) [r setDepthStencilState:depthAlways];
	[r setFragmentBytes:s length:sizeof *s atIndex:0];
	if (src) [r setFragmentTexture:src atIndex:0];
	[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[r endEncoding];
}

static uint8_t *readback(id<MTLTexture> t, id<MTLCommandQueue> q) {
	int w = (int) t.width, h = (int) t.height;
	id<MTLBuffer> b = [dev newBufferWithLength:(NSUInteger) w * h * 4 options:MTLResourceStorageModeShared];
	id<MTLCommandBuffer> cb = [q commandBuffer];
	id<MTLBlitCommandEncoder> bl = [cb blitCommandEncoder];
	[bl copyFromTexture:t sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(w, h, 1) toBuffer:b
		destinationOffset:0 destinationBytesPerRow:w * 4 destinationBytesPerImage:(NSUInteger) w * h * 4];
	[bl endEncoding];
	[cb commit];
	[cb waitUntilCompleted];
	uint8_t *p = malloc((size_t) w * h * 4);
	memcpy(p, b.contents, (size_t) w * h * 4);
	return p;
}

// mean absolute error (0-255 scale, rgb), and the share of pixels off by more than 24/255
static void compare(const uint8_t *a, const uint8_t *b, int n, double *mae, double *bad) {
	double s = 0;
	long big = 0;
	for (int i = 0; i < n; i++) {
		int e = 0;
		for (int c = 0; c < 3; c++) {
			int d = abs(a[i * 4 + c] - b[i * 4 + c]);
			s += d;
			if (d > e) e = d;
		}
		if (e > 24) big++;
	}
	*mae = s / (3.0 * n);
	*bad = 100.0 * big / n;
}

typedef struct { float yawRate, speed; } Motion;

// Runs `frames` frames of a camera motion at scale s; returns the TAAU result's error vs truth on the last frame, plus the
// plain (no AA, bilinear upscale) error and frame-to-frame flicker of the TAAU output over the last 8 frames (still camera).
static void run(Ctx *ctx, Enc *enc, Taa *taa, const char *name, Motion m, float s, int W, int H, int frames, float sharpen) {
	int aw = (int) lroundf(W * s), ah = (int) lroundf(H * s);
	int allocW = (int) lroundf(W * fminf(1.0f, s + 0.1f)), allocH = (int) lroundf(H * fminf(1.0f, s + 0.1f));
	id<MTLTexture> color = tex(MTLPixelFormatRGBA8Unorm, allocW, allocH, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
	id<MTLTexture> depth = tex(MTLPixelFormatDepth32Float, allocW, allocH, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
	id<MTLTexture> plain = tex(MTLPixelFormatRGBA8Unorm, allocW, allocH, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
	id<MTLTexture> plainDepth = tex(MTLPixelFormatDepth32Float, allocW, allocH, MTLTextureUsageRenderTarget);
	id<MTLTexture> out = tex(MTLPixelFormatRGBA8Unorm, W, H, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
	id<MTLTexture> truth = tex(MTLPixelFormatRGBA8Unorm, W, H, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
	id<MTLTexture> up = tex(MTLPixelFormatRGBA8Unorm, W, H, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
	mc_taa_resize(taa, ctx, 1, 1);
	mc_taa_resize(taa, ctx, W, H);
	simd_float4x4 proj = perspective(70.0f * M_PI / 180.0f, (float) W / H, 0.05f, 512.0f);
	simd_float4x4 prevVP = matrix_identity_float4x4;
	simd_float3 prevCam = 0;
	int phases = (int) fmaxf(8, fminf(32, roundf(8 / (s * s))));
	double flicker = 0;
	uint8_t *last = NULL;
	for (int f = 0; f < frames; f++) {
		float t = f / 120.0f;
		simd_float3 cam = {0.3f, 0.0f, -m.speed * t};
		simd_float4x4 view = rotation(0.4f + m.yawRate * t, 0.35f);
		float jx = halton(f % phases + 1, 2) - 0.5f, jy = halton(f % phases + 1, 3) - 0.5f;
		simd_float4x4 vp = simd_mul(proj, view);
		simd_float4x4 vpJit = simd_mul(translation(2 * jx / aw, 2 * jy / ah, 0), vp);
		Scene sc = {simd_inverse(vpJit), vpJit, simd_make_float4(cam, 0), simd_make_float4(aw, ah, aw, ah)};
		id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
		drawScene(cb, scenePso, color, depth, aw, ah, &sc, nil);
		[cb commit];
		Params p;
		p.invCur = simd_inverse(vp);
		p.prevVP = f == 0 ? vp : simd_mul(prevVP, translation(cam.x - prevCam.x, cam.y - prevCam.y, cam.z - prevCam.z));
		p.curRowW = simd_make_float4(vp.columns[0].w, vp.columns[1].w, vp.columns[2].w, vp.columns[3].w);
		p.input = simd_make_float4(aw, ah, 0, 0);
		p.output = simd_make_float4(W, H, 1.0f / W, 1.0f / H);
		p.jitter = simd_make_float4(jx, jy, (float) aw / W, (float) ah / H);
		bool still = m.yawRate == 0 && m.speed == 0;
		p.blend = simd_make_float4(0.08f, still ? 1.75f : 1.25f, 32, f == 0 ? 1 : 0);
		p.misc = simd_make_float4(1, 0, 0, 0);
		p.sharpen = simd_make_float4(sharpen, 0.15f, 0, 0); // Stable's motion floor (TaaConfig.motionAlpha)
		mc_taa_resolve(enc, taa, color, depth, nil, out, &p, sizeof p);
		id<MTLCommandBuffer> c = mc_enc_commit(enc);
		[c waitUntilCompleted];
		if (c.error) { printf("GPU error: %s\n", c.error.description.UTF8String); exit(1); }
		prevVP = vp;
		prevCam = cam;
		if (f >= frames - 9) {
			uint8_t *now = readback(out, ctx->queue);
			if (last) {
				double mae, bad;
				compare(now, last, W * H, &mae, &bad);
				flicker += mae / 8;
			}
			free(last);
			last = now;
		}
		if (f == frames - 1) {
			Scene truthScene = {simd_inverse(vp), vp, simd_make_float4(cam, 0), simd_make_float4(W, H, W, H)};
			Scene plainScene = {simd_inverse(vp), vp, simd_make_float4(cam, 0), simd_make_float4(aw, ah, aw, ah)};
			Scene upScene = {simd_inverse(vp), vp, simd_make_float4(cam, 0), simd_make_float4(W, H, aw, ah)};
			id<MTLCommandBuffer> tb = [ctx->queue commandBuffer];
			drawScene(tb, truthPso, truth, nil, W, H, &truthScene, nil);
			drawScene(tb, scenePso, plain, plainDepth, aw, ah, &plainScene, nil);
			drawScene(tb, upscalePso, up, nil, W, H, &upScene, plain);
			[tb commit];
			[tb waitUntilCompleted];
			uint8_t *a = readback(truth, ctx->queue), *b = readback(up, ctx->queue);
			double maeTaa, badTaa, maePlain, badPlain;
			compare(last, a, W * H, &maeTaa, &badTaa);
			const char *dump = getenv("TAATEST_DUMP");  // dir: raw RGBA dumps (truth, plain, taau) for a look
			if (dump) {
				const uint8_t *imgs[3] = {a, b, last};
				const char *kinds[3] = {"truth", "plain", "taau"};
				for (int k = 0; k < 3; k++) {
					char path[1024];
					snprintf(path, sizeof path, "%s/%s_%.2f_%s_%dx%d.rgba", dump, name, s, kinds[k], W, H);
					FILE *fp = fopen(path, "wb");
					if (fp) { fwrite(imgs[k], 4, (size_t) W * H, fp); fclose(fp); }
				}
			}
			compare(b, a, W * H, &maePlain, &badPlain);
			printf("%-8s scale %.2f (%4dx%-4d -> %dx%d)  plain: err %5.2f, %5.2f%% bad   TAAU: err %5.2f, %5.2f%% bad   flicker %5.2f\n", name, s, aw, ah, W, H,
				maePlain, badPlain, maeTaa, badTaa, flicker);
			free(a);
			free(b);
		}
	}
	free(last);
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		if (argc < 3) {
			fprintf(stderr, "usage: taatest <taa.metal> correct | perf W H S\n");
			return 2;
		}
		char name[256], err[4096];
		Ctx *ctx = mc_create(name, sizeof name, err, sizeof err);
		if (!ctx) { printf("mc_create: %s\n", err); return 1; }
		dev = ctx->device;
		Enc *enc = mc_enc_new(ctx);
		NSString *src = [NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:nil];
		Taa *taa = mc_taa_new(ctx, src.UTF8String, getenv("TAALOSSY") ? 2 : 0, err, sizeof err);
		if (!taa) { printf("FAIL compile taa.metal: %s\n", err); return 1; }
		NSError *e = nil;
		id<MTLLibrary> lib = [dev newLibraryWithSource:sceneSource options:nil error:&e];
		if (!lib) { printf("scene: %s\n", e.description.UTF8String); return 1; }
		MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
		pd.vertexFunction = [lib newFunctionWithName:@"scene_vs"];
		pd.fragmentFunction = [lib newFunctionWithName:@"scene_fs"];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
		pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
		scenePso = [dev newRenderPipelineStateWithDescriptor:pd error:&e];
		pd.depthAttachmentPixelFormat = MTLPixelFormatInvalid;
		pd.fragmentFunction = [lib newFunctionWithName:@"truth_fs"];
		truthPso = [dev newRenderPipelineStateWithDescriptor:pd error:&e];
		pd.fragmentFunction = [lib newFunctionWithName:@"upscale_fs"];
		upscalePso = [dev newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!scenePso || !truthPso || !upscalePso) { printf("scene pso: %s\n", e.description.UTF8String); return 1; }
		MTLDepthStencilDescriptor *dd = [MTLDepthStencilDescriptor new];
		dd.depthCompareFunction = MTLCompareFunctionAlways;
		dd.depthWriteEnabled = YES;
		depthAlways = [dev newDepthStencilStateWithDescriptor:dd];
		printf("OK   taa.metal compiled, pipelines built on %s\n", name);

		if (strcmp(argv[2], "compile") == 0) { // validate.sh: both TAA_HDR variants, a resolve into each destination format
			for (int hdr = 0; hdr < 2; hdr++) {
				Taa *t = mc_taa_new(ctx, src.UTF8String, hdr, err, sizeof err);
				if (!t) { printf("FAIL compile taa.metal (hdr %d): %s\n", hdr, err); return 1; }
				mc_taa_resize(t, ctx, 64, 64);
				id<MTLTexture> c = tex(MTLPixelFormatRGBA8Unorm, 48, 48, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
				id<MTLTexture> d = tex(MTLPixelFormatDepth32Float, 48, 48, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
				MTLPixelFormat formats[2] = {MTLPixelFormatRGBA8Unorm, MTLPixelFormatRGBA16Float};
				for (int k = 0; k < 2; k++) {
					id<MTLTexture> o = tex(formats[k], 64, 64, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
					Params p = {matrix_identity_float4x4, matrix_identity_float4x4, simd_make_float4(0, 0, 0, 1), simd_make_float4(48, 48, 0, 0),
						simd_make_float4(64, 64, 1 / 64.0f, 1 / 64.0f), simd_make_float4(0, 0, 0.75f, 0.75f), simd_make_float4(0.1f, 1, 32, 1),
						simd_make_float4(1, 0, 0, 0), simd_make_float4(0.3f, 0, 0, 0)};
					mc_taa_resolve(enc, t, c, d, nil, o, &p, sizeof p);
					id<MTLCommandBuffer> cb = mc_enc_commit(enc);
					[cb waitUntilCompleted];
					if (cb.error) { printf("FAIL taa resolve (hdr %d, format %lu): %s\n", hdr, (unsigned long) formats[k], cb.error.description.UTF8String); return 1; }
				}
				printf("OK   taa.metal: TAAU resolve (TAA_HDR %d) + output to RGBA8Unorm and RGBA16Float\n", hdr);
			}
			return 0;
		}
		if (strcmp(argv[2], "perf") == 0) {
			int W = argc > 3 ? atoi(argv[3]) : 3024, H = argc > 4 ? atoi(argv[4]) : 1964;
			float s = argc > 5 ? atof(argv[5]) : 0.67f;
			int aw = (int) lroundf(W * s), ah = (int) lroundf(H * s);
			id<MTLTexture> color = tex(MTLPixelFormatRGBA8Unorm, aw, ah, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
			id<MTLTexture> depth = tex(MTLPixelFormatDepth32Float, aw, ah, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
			id<MTLTexture> out = tex(MTLPixelFormatRGBA8Unorm, W, H, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
			mc_taa_resize(taa, ctx, W, H);
			simd_float4x4 proj = perspective(70.0f * M_PI / 180.0f, (float) W / H, 0.05f, 512.0f);
			// Only the TAAU passes run in the timed loop, over two pre-rendered inputs (different jitter, so successive frames
			// differ: identical re-renders are short-circuited by the hardware, apple-gpu.md), with the queue kept full like the
			// game; the cost per frame is the GPU span of the loop divided by its frames.
			id<MTLTexture> color2 = tex(MTLPixelFormatRGBA8Unorm, aw, ah, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
			id<MTLTexture> depth2 = tex(MTLPixelFormatDepth32Float, aw, ah, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
			simd_float4x4 vp = simd_mul(proj, rotation(0.4f, 0.35f));
			for (int k = 0; k < 2; k++) {
				float jx = halton(k + 1, 2) - 0.5f, jy = halton(k + 1, 3) - 0.5f;
				simd_float4x4 vpJit = simd_mul(translation(2 * jx / aw, 2 * jy / ah, 0), vp);
				Scene sc = {simd_inverse(vpJit), vpJit, simd_make_float4(0, 0, 0, 0), simd_make_float4(aw, ah, aw, ah)};
				id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
				drawScene(cb, scenePso, k ? color2 : color, k ? depth2 : depth, aw, ah, &sc, nil);
				[cb commit];
				[cb waitUntilCompleted];
			}
			int frames = 400, from = 80;
			NSMutableArray<id<MTLCommandBuffer>> *cbs = [NSMutableArray array];
			for (int f = 0; f < frames; f++) {
				float jx = halton(f % 2 + 1, 2) - 0.5f, jy = halton(f % 2 + 1, 3) - 0.5f;
				Params p = {simd_inverse(vp), vp, simd_make_float4(vp.columns[0].w, vp.columns[1].w, vp.columns[2].w, vp.columns[3].w),
					simd_make_float4(aw, ah, 0, 0), simd_make_float4(W, H, 1.0f / W, 1.0f / H), simd_make_float4(jx, jy, (float) aw / W, (float) ah / H),
					simd_make_float4(0.08f, 1.25f, 32, f == 0), simd_make_float4(1, 0, 0, 0), simd_make_float4(getenv("NOSHARP") ? 0.0f : 0.3f, 0, 0, 0)};
				mc_taa_resolve(enc, taa, f % 2 ? color2 : color, f % 2 ? depth2 : depth, nil, getenv("NOOUT") ? nil : out, &p, sizeof p);
				[cbs addObject:mc_enc_commit(enc)];
				if (cbs.count > 3) [cbs[cbs.count - 4] waitUntilCompleted];
			}
			[cbs.lastObject waitUntilCompleted];
			int n = frames - from;
			double total = cbs.lastObject.GPUEndTime - cbs[from].GPUStartTime;
			printf("perf: %dx%d -> %dx%d (scale %.2f): TAAU resolve%s %.3f ms GPU (GPU span / %d frames, queue kept full)\n", aw, ah, W, H, s, getenv("NOOUT") ? "" : " + output", total / n * 1e3, n);
			return 0;
		}

		int W = 960, H = 600;
		Motion still = {0, 0}, turn = {0.35f, 0}, move = {0, 4.0f};
		float scales[] = {1.0f, 0.8f, 0.67f};
		for (int i = 0; i < 3; i++) {
			run(ctx, enc, taa, "still", still, scales[i], W, H, 48, 0);
			run(ctx, enc, taa, "turning", turn, scales[i], W, H, 48, 0);
			run(ctx, enc, taa, "moving", move, scales[i], W, H, 48, 0);
		}
		run(ctx, enc, taa, "sharp", still, 0.67f, W, H, 48, 0.4f);
		return 0;
	}
}
