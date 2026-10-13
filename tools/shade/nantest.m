// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading, not part of the mod.
// (1.1.1, macOS 27) Does this macOS's Metal compiler fold isfinite() under fast math? Our libraries compile with
// MTLMathModeFast; the temporal histories now test Inf/NaN by bit pattern (shadeBad), which no float optimization removes.
// Run it on a new macOS (a VM is enough) and compare with 26.6.2 (2026-10-13: isfinite(v)=0 for NaN and Inf in every mode,
// max(NaN, 0) = 0, bit test = isfinite):
//   clang -fobjc-arc -O1 -framework Foundation -framework Metal nantest.m -o nantest && ./nantest
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
static const char *src =
"#include <metal_stdlib>\nusing namespace metal;\n"
"kernel void k(device const half4 *in [[buffer(0)]], device uint4 *out [[buffer(1)]], uint i [[thread_position_in_grid]]) {\n"
"  half4 v = in[i] * in[i].w + 0.0h;\n"   // arithmetic so the compiler can reason about it
"  half4 m = half4(half3(max(float3(v.rgb), 0.0)), v.a);\n"
"  out[i] = uint4(all(isfinite(v)) ? 1u : 0u, all(isfinite(m)) ? 1u : 0u,\n"
"                 any((as_type<ushort4>(v) & ushort4(0x7c00)) == ushort4(0x7c00)) ? 0u : 1u, isnan(m.x) ? 1u : 0u);\n"
"}\n";
int main(void) {
	@autoreleasepool {
		id<MTLDevice> d = MTLCreateSystemDefaultDevice();
		id<MTLCommandQueue> q = [d newCommandQueue];
		__fp16 vals[3][4] = {{NAN, 1, 1, 1}, {INFINITY, 1, 1, 1}, {1, 1, 1, 1}};
		const char *names[3] = {"NaN", "Inf", "1.0"};
		MTLMathMode modes[3] = {MTLMathModeFast, MTLMathModeRelaxed, MTLMathModeSafe};
		const char *mn[3] = {"fast", "relaxed", "safe"};
		printf("%s\n", [[NSProcessInfo processInfo] operatingSystemVersionString].UTF8String);
		for (int m = 0; m < 3; m++) {
			MTLCompileOptions *o = [MTLCompileOptions new];
			o.languageVersion = MTLLanguageVersion3_0;
			o.mathMode = modes[m];
			NSError *e = nil;
			id<MTLLibrary> lib = [d newLibraryWithSource:@(src) options:o error:&e];
			id<MTLComputePipelineState> p = [d newComputePipelineStateWithFunction:[lib newFunctionWithName:@"k"] error:&e];
			id<MTLBuffer> in = [d newBufferWithBytes:vals length:sizeof vals options:0];
			id<MTLBuffer> out = [d newBufferWithLength:3 * 16 options:0];
			id<MTLCommandBuffer> cb = [q commandBuffer];
			id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
			[ce setComputePipelineState:p]; [ce setBuffer:in offset:0 atIndex:0]; [ce setBuffer:out offset:0 atIndex:1];
			[ce dispatchThreads:MTLSizeMake(3, 1, 1) threadsPerThreadgroup:MTLSizeMake(3, 1, 1)];
			[ce endEncoding]; [cb commit]; [cb waitUntilCompleted];
			uint32_t *r = out.contents;
			for (int i = 0; i < 3; i++)
				printf("%-7s %s: isfinite(v)=%u isfinite(max)=%u bitTestFinite=%u isnan(max)=%u\n", mn[m], names[i], r[i*4], r[i*4+1], r[i*4+2], r[i*4+3]);
		}
	}
}
