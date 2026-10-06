// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
// Headless test of mcshadow.m + shadow.metal + shade_shadow.metal against a synthetic Sodium arena.
#import "mcmetal.h"
#include <stdio.h>
#include <math.h>
typedef struct Shadow Shadow;
void *mc_create(char *name, int nameCap, char *err, int errCap);
Enc *mc_enc_new(Ctx *ctx);
id<MTLCommandBuffer> mc_enc_commit(Enc *enc);
void mc_cmd_wait(id<MTLCommandBuffer> cmd);
Shadow *mc_shadow_new(Ctx *ctx, const char *source, int resolution, int layers, int depthBits, char *err, int errCap);
id<MTLTexture> mc_shadow_texture(Shadow *s);
id<MTLSamplerState> mc_shadow_compare_sampler(Shadow *s);
int mc_shadow_render(Enc *enc, Shadow *s, int layer, const void *cascade, int cascadeBytes, id<MTLBuffer> chunks, uint64_t chunksOffset,
	id<MTLBuffer> regions, uint64_t regionsOffset, const uint8_t *draws, int drawCount, id<MTLTexture> atlas, float slopeScale, float clamp);

static uint32_t q(float v) { return ((uint32_t) ((v + 8.0f) / 32.0f * 1048576.0f)) & 0xFFFFF; }
static void vert(uint32_t *o, float x, float y, float z, float u, float v, uint32_t color) {
	uint32_t X = q(x), Y = q(y), Z = q(z);
	o[0] = ((X >> 10) & 1023) | (((Y >> 10) & 1023) << 10) | (((Z >> 10) & 1023) << 20);
	o[1] = (X & 1023) | ((Y & 1023) << 10) | ((Z & 1023) << 20);
	o[2] = color;
	uint32_t uu = (uint32_t) (lroundf(u * 32768.0f) + (u > 0.5f ? -1 : 1)) & 0x7FFF, vv = (uint32_t) (lroundf(v * 32768.0f) + (v > 0.5f ? -1 : 1)) & 0x7FFF; /* Sodium shrinks toward the center */
	o[3] = uu | (vv << 16);
	o[4] = (0 << 24) | 0;  // section 0, light 0
}
// horizontal quad at height y over [x0,x1]x[z0,z1], uv over [u0,u1]x[0,1]
static void hquad(uint32_t *o, float y, float x0, float x1, float z0, float z1, float u0, float u1) {
	vert(o + 0, x0, y, z0, u0, 0, 0xFFFFFFFF);
	vert(o + 5, x0, y, z1, u0, 1, 0xFFFFFFFF);
	vert(o + 10, x1, y, z1, u1, 1, 0xFFFFFFFF);
	vert(o + 15, x1, y, z0, u1, 0, 0xFFFFFFFF);
}

int main(int argc, char **argv) {
	@autoreleasepool {
		char name[256], err[2048];
		Ctx *ctx = mc_create(name, 256, err, 2048);
		if (!ctx) { printf("mc_create: %s\n", err); return 1; }
		Enc *enc = mc_enc_new(ctx);
		NSString *src = [NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil];
		Shadow *s = mc_shadow_new(ctx, src.UTF8String, 2048, 2, getenv("BITS") ? atoi(getenv("BITS")) : 32, err, 2048);
		if (!s) { printf("mc_shadow_new: %s\n", err); return 1; }
		NSData *mat = [NSData dataWithContentsOfFile:@(argv[2])];
		const float *m = mat.bytes;  // 32 matrix, 3 light, 2 texelWorld, 2 depthPerTexel, 3 cam
		const float *light = m + 32, *texelWorld = m + 35, *dpt = m + 37, *cam = m + 39;

		// arena: 2 solid quads (floor, occluder), then 1 cutout quad; padding vertices first to test firstVertex
		id<MTLBuffer> arena = [ctx->device newBufferWithLength:20 * 64 options:MTLResourceStorageModeShared];
		uint32_t *v = arena.contents;
		memset(v, 0xAB, 20 * 64);
		hquad(v + 5 * 8, 0, 0, 16, 0, 16, 0, 0.5f);   // floor, vertices 8..11
		hquad(v + 5 * 12, 6, 6, 10, 6, 10, 0, 0.5f);  // occluder, 12..15
		hquad(v + 5 * 16, 4, 8, 12, 12, 16, 0, 1);    // cutout, 16..19: u 0..0.5 opaque, 0.5..1 transparent
		vert(v + 5 * 20, 14, 0, 0, 0, 0, 0xFFFFFFFF); vert(v + 5 * 21, 14, 4, 0, 0, 0, 0xFFFFFFFF); vert(v + 5 * 22, 14, 4, 4, 0, 0, 0xFFFFFFFF); vert(v + 5 * 23, 14, 0, 4, 0, 0, 0xFFFFFFFF); // wall +X
		// region: origin (992, 64, -2016) relative to camera, Sodium style
		double ox = 992, oy = 64, oz = -2016;
		int ix = (int) cam[0], iy = (int) cam[1], iz = (int) cam[2];
		float fx = cam[0] - ix, fy = cam[1] - iy, fz = cam[2] - iz;
		float region[4] = {(float) (ox - ix) - fx, (float) (oy - iy) - fy, (float) (oz - iz) - fz, 0};
		id<MTLBuffer> regions = [ctx->device newBufferWithBytes:region length:16 options:MTLResourceStorageModeShared];
		uint32_t chunks[6] = {8, (2 - 1) | (0 << 6), 16, (1 - 1) | (0 << 6), 20, 0};
		id<MTLBuffer> chunkBuf = [ctx->device newBufferWithBytes:chunks length:sizeof chunks options:MTLResourceStorageModeShared];
		// atlas 2x1: left texel opaque, right transparent
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:2 height:1 mipmapped:NO];
		id<MTLTexture> atlas = [ctx->device newTextureWithDescriptor:td];
		uint32_t px[2] = {0xFFFFFFFF, 0x00FFFFFF};
		[atlas replaceRegion:MTLRegionMake2D(0, 0, 2, 1) mipmapLevel:0 withBytes:px bytesPerRow:8];
		uint8_t draws[48] = {0};
		memcpy(draws, &arena, 8); uint32_t f0 = 0, c0 = 1, k0 = 0; /* solid: chunk 0 */ memcpy(draws + 8, &f0, 4); memcpy(draws + 12, &c0, 4); memcpy(draws + 16, &k0, 4);
		memcpy(draws + 24, &arena, 8); uint32_t f1 = 1, c1 = 1, k1 = 1; memcpy(draws + 32, &f1, 4); memcpy(draws + 36, &c1, 4); memcpy(draws + 40, &k1, 4);
		for (int c = 0; c < 2; c++) {
			float cascade[20] = {0};
			memcpy(cascade, m + 16 * c, 64);
			cascade[16] = 1.0f * dpt[c];
			uint8_t d3[72] = {0}; memcpy(d3, draws, 48); memcpy(d3 + 48, &arena, 8); uint32_t f2 = 2, c2 = 1, k2 = 0; memcpy(d3 + 56, &f2, 4); memcpy(d3 + 60, &c2, 4); memcpy(d3 + 64, &k2, 4);
			mc_shadow_render(enc, s, c, cascade, 80, chunkBuf, 0, regions, 0, d3, 3, atlas, -(getenv("SLOPE") ? atof(getenv("SLOPE")) : 1.0f), -(getenv("CLAMP") ? atof(getenv("CLAMP")) : 8.0f) * dpt[c]);
		}
		// receivers: a grid over the floor (y = 0), the occluder top (y = 6), the cutout top (y = 4, opaque half) and the wall (x = 14)
		// expected by ray casting toward the light against the occluder, the cutout's opaque half and the wall
		enum { N = 4096 };
		static float pp[N * 4], expect[N];
		int n = 0;
		float L[3] = {light[0], light[1], light[2]};
		#define ADD(X, Y, Z, NX) do { pp[n*4] = (X); pp[n*4+1] = (Y); pp[n*4+2] = (Z); pp[n*4+3] = (NX); n++; } while (0)
		for (float x = -40.25f; x < 16; x += 0.5f) for (float z = 0.25f; z < 16; z += 0.5f) if (n < N - 200) ADD(x, 0, z, 0);
		for (float x = 6.25f; x < 10; x += 0.5f) for (float z = 6.25f; z < 10; z += 0.5f) ADD(x, 6, z, 0);
		for (float x = 8.25f; x < 10; x += 0.5f) for (float z = 12.25f; z < 16; z += 0.5f) ADD(x, 4, z, 0);
		for (float y = 0.25f; y < 4; y += 0.5f) for (float z = 0.25f; z < 4; z += 0.5f) ADD(14, y, z, 1);
		int edge[N];
		for (int i = 0; i < n; i++) {
			float px = pp[i*4], py = pp[i*4+1], pz = pp[i*4+2];
			int hit = 0; float edgeDist = 1e9f;
			// horizontal rects: {h, x0, x1, z0, z1}
			float rects[2][5] = {{6, 6, 10, 6, 10}, {4, 8, 10, 12, 16}};
			for (int r = 0; r < 2; r++) {
				float t = (rects[r][0] - py) / L[1];
				if (t <= 1e-3f) continue;
				float hx = px + L[0] * t, hz = pz + L[2] * t;
				float dx = fminf(hx - rects[r][1], rects[r][2] - hx), dz = fminf(hz - rects[r][3], rects[r][4] - hz);
				float in = fminf(dx, dz);
				if (in > 0) hit = 1;
				edgeDist = fminf(edgeDist, fabsf(in));
			}
			if (fabsf(L[0]) > 1e-4f) {  // the wall plane x = 14, y 0..4, z 0..4
				float t = (14 - px) / L[0];
				if (t > 1e-3f) {
					float hy = py + L[1] * t, hz = pz + L[2] * t;
					float in = fminf(fminf(hy, 4 - hy), fminf(hz, 4 - hz));
					if (in > 0) hit = 1;
					edgeDist = fminf(edgeDist, fabsf(in));
				}
			}
			float nlx = pp[i*4+3] > 0.5f ? L[0] : L[1];
			expect[i] = (hit || nlx <= 0) ? 0 : 1;
			edge[i] = edgeDist < 0.35f;  // PCF and texel snapping blur ~a texel or two around shadow edges
			pp[i*4] += region[0]; pp[i*4+1] += region[1]; pp[i*4+2] += region[2];
		}
		NSString *recv = [NSString stringWithContentsOfFile:@(argv[3]) encoding:NSUTF8StringEncoding error:nil];
		NSString *k = [NSString stringWithFormat:@"#include <metal_stdlib>\nusing namespace metal;\n%@\n"
			"kernel void eval(depth2d_array<float> map [[texture(16)]], sampler cmp [[sampler(12)]], constant float *m [[buffer(0)]],"
			" const device float4 *p [[buffer(1)]], device float *o [[buffer(2)]], uint i [[thread_position_in_grid]]) {\n"
			" float4x4 vp0 = float4x4(float4(m[0],m[1],m[2],m[3]), float4(m[4],m[5],m[6],m[7]), float4(m[8],m[9],m[10],m[11]), float4(m[12],m[13],m[14],m[15]));\n"
			" float4x4 vp1 = float4x4(float4(m[16],m[17],m[18],m[19]), float4(m[20],m[21],m[22],m[23]), float4(m[24],m[25],m[26],m[27]), float4(m[28],m[29],m[30],m[31]));\n"
			" float3 nrm = p[i].w > 0.5 ? float3(1, 0, 0) : float3(0, 1, 0); float3 L = float3(m[32], m[33], m[34]); float nl = dot(nrm, L);\n"
			" o[i * 3] = shade_shadow(map, cmp, p[i].xyz, nrm, L, vp0, vp1, float2(m[35], m[36]), 1.0 / 2048.0);\n"
			" float m0, m1; o[i * 3 + 1] = shade_shadow_cascade(map, cmp, 0u, vp0, p[i].xyz, nrm, L, nl, m[35], 1.0 / 2048.0, m0);\n"
			" o[i * 3 + 2] = shade_shadow_cascade(map, cmp, 1u, vp1, p[i].xyz, nrm, L, nl, m[36], 1.0 / 2048.0, m1);\n"
			" if (m0 >= 1.0) o[i * 3 + 1] = -1.0;\n"
			"}\n", recv];
		NSError *e = nil;
		MTLCompileOptions *co = [MTLCompileOptions new]; co.languageVersion = MTLLanguageVersion3_0; co.mathMode = MTLMathModeFast;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:k options:co error:&e];
		if (!lib) { NSLog(@"%@", e); return 1; }
		id<MTLComputePipelineState> pso = [ctx->device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"eval"] error:&e];
		id<MTLBuffer> ptsBuf = [ctx->device newBufferWithBytes:pp length:sizeof pp options:MTLResourceStorageModeShared];
		id<MTLBuffer> out = [ctx->device newBufferWithLength:4 * 3 * N options:MTLResourceStorageModeShared];
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
		[ce setComputePipelineState:pso];
		[ce setTexture:mc_shadow_texture(s) atIndex:16];
		[ce setSamplerState:mc_shadow_compare_sampler(s) atIndex:12];
		[ce setBytes:m length:4 * 37 atIndex:0];
		[ce setBuffer:ptsBuf offset:0 atIndex:1];
		[ce setBuffer:out offset:0 atIndex:2];
		[ce dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		[ce endEncoding];
		id<MTLBuffer> depth = [ctx->device newBufferWithLength:2048 * 2048 * 4 options:MTLResourceStorageModeShared];
		id<MTLBlitCommandEncoder> be = [cb blitCommandEncoder];
		[be copyFromTexture:mc_shadow_texture(s) sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(2048, 2048, 1)
			toBuffer:depth destinationOffset:0 destinationBytesPerRow:2048 * (getenv("BITS") && atoi(getenv("BITS")) == 16 ? 2 : 4) destinationBytesPerImage:2048 * 2048 * (getenv("BITS") && atoi(getenv("BITS")) == 16 ? 2 : 4)];
		[be endEncoding];
		id<MTLCommandBuffer> done = mc_enc_commit(enc);
		mc_cmd_wait(done);
		if (done.error) { NSLog(@"GPU error %@", done.error); return 1; }
		const float *o = out.contents;
		int fails = 0;
		for (int c = 0; c < 3; c++) {
			int tested = 0, wrongLit = 0, wrongDark = 0; double worstAcne = 0, worstLeak = 0;
			for (int i = 0; i < n; i++) {
				float val = o[i * 3 + c];
				if (val < 0 || edge[i]) continue;
				tested++;
				if (expect[i] > 0.5f && val < 0.9f) { wrongDark++; worstAcne = fmax(worstAcne, 1 - val); }
				if (expect[i] < 0.5f && val > 0.1f) { wrongLit++; worstLeak = fmax(worstLeak, val); }
			}
			printf("  %-9s tested %4d: %d acne (worst %.2f), %d leaks (worst %.2f)\n", c == 0 ? "combined" : c == 1 ? "near" : "far", tested, wrongDark, worstAcne, wrongLit, worstLeak);
			if (c == 0) fails += wrongDark + wrongLit;
		}
		int bits = getenv("BITS") ? atoi(getenv("BITS")) : 32;
		long covered = 0; float mn = 1, mx = 0;
		for (long i = 0; i < 2048L * 2048; i++) {
			float di = bits == 16 ? ((const uint16_t *) depth.contents)[i] / 65535.0f : ((const float *) depth.contents)[i];
			if (di > 0) { covered++; mn = fminf(mn, di); mx = fmaxf(mx, di); }
		}
		printf("near cascade: %ld texels covered (%.1f blocks^2 at %.4f blocks/texel), depth %.4f..%.4f\n", covered, covered * texelWorld[0] * texelWorld[0], texelWorld[0], mn, mx);
		printf("light (%.3f, %.3f, %.3f)\n", light[0], light[1], light[2]);
		return fails ? 2 : 0;
	}
}
