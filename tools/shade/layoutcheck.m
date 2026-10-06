// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading, not part of the mod.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
// layoutcheck SRC FRAME.bin (SRC = shade_common.metal + layoutcheck.metal, FRAME = a packed ShadeFrame): runs the echo kernel and compares every float it read with the bytes at the same offsets (the struct
// is all 16-byte fields in declaration order, so the k-th float4 must equal bytes [16k, 16k+16)).
int main(int argc, const char **argv) {
	@autoreleasepool {
		id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
		NSError *e = nil;
		id<MTLLibrary> lib = [dev newLibraryWithSource:[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil] options:nil error:&e];
		if (!lib) { printf("%s\n", e.localizedDescription.UTF8String); return 1; }
		id<MTLComputePipelineState> p = [dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"echo"] error:&e];
		NSData *frame = [NSData dataWithContentsOfFile:@(argv[2])];
		id<MTLBuffer> fb = [dev newBufferWithBytes:frame.bytes length:frame.length options:0];
		id<MTLBuffer> ob = [dev newBufferWithLength:16 * 80 options:0];
		id<MTLCommandQueue> q = [dev newCommandQueue];
		id<MTLCommandBuffer> cb = [q commandBuffer];
		id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
		[ce setComputePipelineState:p]; [ce setBuffer:fb offset:0 atIndex:16]; [ce setBuffer:ob offset:0 atIndex:0];
		[ce dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)]; [ce endEncoding];
		[cb commit]; [cb waitUntilCompleted];
		const float *o = ob.contents; const float *fl = frame.bytes; const int *in = frame.bytes;
		int fields = 63, bad = 0;
		for (int k = 0; k < fields; k++) for (int c = 0; c < 4; c++) {
			float want = (k == 60 || k == 61) ? (float) in[k * 4 + c] : fl[k * 4 + c]; // flags, flags2 are int4; sunPath (62) is float4
			if (o[k * 4 + c] != want) { bad++; if (bad < 10) printf("mismatch float4 #%d.%d: shader %g, bytes %g\n", k, c, o[k * 4 + c], want); }
		}
		printf("sizeof(ShadeFrame) in MSL = %g bytes (Java SIZE %lu); %s\n", o[fields * 4], (unsigned long) frame.length, bad ? "LAYOUT MISMATCH" : "layout OK: every field at the Java packer's offset");
		return bad ? 1 : 0;
	}
}
