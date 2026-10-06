// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
// GPU time of the shadow pass on synthetic terrain: a 256x256 heightmap (one region record per 16x16 column of sections),
// top faces plus the sun-facing (+X) side faces where a column is higher than its -X... neighbour, i.e. what sun-facing culling keeps.
#import "mcmetal.h"
#include <stdio.h>
#include <math.h>
typedef struct Shadow Shadow;
void *mc_create(char *name, int nameCap, char *err, int errCap);
Enc *mc_enc_new(Ctx *ctx);
id<MTLCommandBuffer> mc_enc_commit(Enc *enc);
void mc_cmd_wait(id<MTLCommandBuffer> cmd);
Shadow *mc_shadow_new(Ctx *ctx, const char *source, int resolution, int layers, int depthBits, char *err, int errCap);
int mc_shadow_render(Enc *enc, Shadow *s, int layer, const void *cascade, int cascadeBytes, id<MTLBuffer> chunks, uint64_t chunksOffset,
	id<MTLBuffer> regions, uint64_t regionsOffset, const uint8_t *draws, int drawCount, id<MTLTexture> atlas, float slopeScale, float clamp);
static uint32_t q(float v) { return ((uint32_t) ((v + 8.0f) / 32.0f * 1048576.0f)) & 0xFFFFF; }
static void vert(uint32_t *o, float x, float y, float z, uint32_t section) {
	uint32_t X = q(x), Y = q(y), Z = q(z);
	o[0] = ((X >> 10) & 1023) | (((Y >> 10) & 1023) << 10) | (((Z >> 10) & 1023) << 20);
	o[1] = (X & 1023) | ((Y & 1023) << 10) | ((Z & 1023) << 20);
	o[2] = 0xFFFFFFFF; o[3] = 0; o[4] = section << 24;
}
static int H(int x, int z) { return 8 + (int) (6 * sin(x * 0.07) * cos(z * 0.05) + 3 * sin(x * 0.31 + z * 0.17)); }
int main(int argc, char **argv) {
	@autoreleasepool {
		char name[256], err[2048];
		Ctx *ctx = mc_create(name, 256, err, 2048);
		Enc *enc = mc_enc_new(ctx);
		NSString *src = [NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil];
		int res = argc > 2 ? atoi(argv[2]) : 2048;
		Shadow *s = mc_shadow_new(ctx, src.UTF8String, res, 2, getenv("BITS") ? atoi(getenv("BITS")) : 32, err, 2048);
		if (!s) { printf("%s\n", err); return 1; }
		int W = 256;
		long maxQuads = (long) W * W * 4;
		id<MTLBuffer> arena = [ctx->device newBufferWithLength:maxQuads * 4 * 20 options:MTLResourceStorageModeShared];
		uint32_t *v = arena.contents;
		// one region record per 16x16 column; section index 0 (all geometry in local y -8..24 range of section 0)
		int cols = (W / 16) * (W / 16);
		float *regions = calloc(cols * 4, 4);
		uint32_t *chunks = malloc(maxQuads / 32 * 8 * 2 + 1024);
		long quads = 0, chunkCount = 0;
		for (int cz = 0; cz < W / 16; cz++) for (int cx = 0; cx < W / 16; cx++) {
			int r = cz * (W / 16) + cx;
			regions[r * 4] = cx * 16 - W / 2; regions[r * 4 + 1] = -10; regions[r * 4 + 2] = cz * 16 - W / 2;
			long first = quads;
			for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
				int gx = cx * 16 + x, gz = cz * 16 + z, h = H(gx, gz);
				uint32_t *o = v + quads * 20;
				vert(o, x, h, z, 0); vert(o + 5, x, h, z + 1, 0); vert(o + 10, x + 1, h, z + 1, 0); vert(o + 15, x + 1, h, z, 0); quads++;
				int hn = H(gx + 1, gz);  // +X side face exposed down to the neighbour's height
				for (int y = hn; y < h; y++) {
					o = v + quads * 20;
					vert(o, x + 1, y, z, 0); vert(o + 5, x + 1, y + 1, z, 0); vert(o + 10, x + 1, y + 1, z + 1, 0); vert(o + 15, x + 1, y, z + 1, 0); quads++;
				}
			}
			for (long qq = first; qq < quads; qq += 32) {
				chunks[chunkCount * 2] = (uint32_t) (qq * 4);
				chunks[chunkCount * 2 + 1] = (uint32_t) ((fmin(32, quads - qq) - 1)) | (r << 6);
				chunkCount++;
			}
		}
		id<MTLBuffer> regionBuf = [ctx->device newBufferWithBytes:regions length:cols * 16 options:MTLResourceStorageModeShared];
		id<MTLBuffer> chunkBuf = [ctx->device newBufferWithBytes:chunks length:chunkCount * 8 options:MTLResourceStorageModeShared];
		uint8_t draws[24] = {0};
		memcpy(draws, &arena, 8); uint32_t c = getenv("CHUNKS") ? (uint32_t) atoi(getenv("CHUNKS")) : (uint32_t) chunkCount; memcpy(draws + 12, &c, 4);
		// light 30 degrees from zenith toward +X, ortho box +-128 around the origin
		float l[3] = {0.5f, 0.866f, 0}, x[3] = {-0.866f, 0.5f, 0}, y[3] = {0, 0, 1};
		float R = 128, D = 2 * R + 32;
		float m[20] = {0};
		for (int i = 0; i < 3; i++) { m[i * 4] = x[i] / R; m[i * 4 + 1] = y[i] / R; m[i * 4 + 2] = l[i] / D; }
		m[14] = 0.5f; m[15] = 1; m[16] = 0.0001f;
		// warm up, then time
		for (int it = 0; it < 3; it++) { mc_shadow_render(enc, s, 1, m, 80, chunkBuf, 0, regionBuf, 0, draws, 1, nil, -1, -0.01f); mc_cmd_wait(mc_enc_commit(enc)); }
		double total = 0; int N = 6, PER = getenv("PER") ? atoi(getenv("PER")) : 200;
		for (int it = 0; it < N; it++) {
			for (int k = 0; k < PER; k++) mc_shadow_render(enc, s, k & 1, m, 80, chunkBuf, 0, regionBuf, 0, draws, 1, nil, -1, -0.01f);
			id<MTLCommandBuffer> cb = mc_enc_commit(enc); mc_cmd_wait(cb);
			if (it > 1) total += cb.GPUEndTime - cb.GPUStartTime;
		}
		total /= PER; N -= 2;
		printf("res %d: %ld quads (%ld chunks, %.1f%% padding) in a 256x256 heightmap: %.3f ms GPU per cascade pass\n", res, quads, chunkCount,
			100.0 * (chunkCount * 32 - quads) / (chunkCount * 32), total / N * 1000);
	}
}
