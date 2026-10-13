// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading, not part of the mod.
//
// compilebench [options] FILE...   How long Metal takes to compile MSL the way mcopt does (MSL 3.0, fast math), one source
// after another (what the render thread does today) or several at once (what the mod's shade/compile path does).
//   -j N          N at once (0, the default: one after another on the main thread)
//   --async       with -j: Metal's completion-handler calls (newLibraryWithSource:options:completionHandler: ...) issued from
//                 a queue of that QoS, at most N in flight, instead of blocking calls on N GCD threads
//   --qos Q       QoS of the compiling threads: ui | initiated | default | utility | background (default: utility)
//   --maximize    device.shouldMaximizeConcurrentCompilation = YES first
//   --cold        empty this tool's own Metal shader cache first (MSL -> AIR and AIR -> GPU both cold: a first launch, or the
//                 first one after a macOS update). The cache is the one for this tool's bundle id only (built in by
//                 compilebench.sh), never the game's.
//   --nonce       prefix every source with a per-run comment: only the MSL -> AIR step goes cold (what
//                 -Dmcopt.metal.uncachedMsl does in the game); pipelines still hit the cache
//   --psos        per library also its pipelines: each fragment function with the library's vertex function into one
//                 RGBA16Float target, each kernel as a compute pipeline (an estimate of what each module builds)
//   --terrain VS  every FILE is a terrain fragment variant, built with vertex stage VS into a pipeline with Sodium's vertex
//                 layout, an RG11B10Float target and Depth32Float (what MetalPipeline.prewarm builds per lean variant)
//   --split       with -j: every library first, then every pipeline as a task of its own (pipelines of one library in
//                 parallel too)
// Prints one line per source (library ms, pipelines ms) and a summary: wall time, the sum of the steps, and the number of
// steps that failed (COMPILEBENCH_ERRORS=1 prints each pipeline error). compilebench.sh builds it and runs the usual cases.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <mach/mach_time.h>
#include <pthread/qos.h>
#include <unistd.h>

static double now(void) {
	static mach_timebase_info_data_t tb;
	if (!tb.denom) mach_timebase_info(&tb);
	return mach_absolute_time() * (double) tb.numer / tb.denom / 1e6;
}

typedef struct {
	NSString *path, *source;
	id<MTLLibrary> lib;
	double libMs, psoMs;
	int psos, failed;
} Item;

static id<MTLDevice> dev;
static MTLCompileOptions *options;
static NSString *terrainVs;
static id<MTLLibrary> terrainVsLib;
static MTLVertexDescriptor *sodiumLayout;

// The pipelines --psos / --terrain build for a compiled library.
static NSMutableArray *pipelinesOf(id<MTLLibrary> lib) {
	NSMutableArray *out = [NSMutableArray array];
	if (terrainVsLib) {
		MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
		pd.vertexFunction = [terrainVsLib newFunctionWithName:@"main0"];
		pd.fragmentFunction = [lib newFunctionWithName:@"main0"];
		pd.vertexDescriptor = sodiumLayout;
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRG11B10Float;
		pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
		[out addObject:pd];
		return out;
	}
	id<MTLFunction> vs = nil;
	if ([lib.functionNames containsObject:@"shade_fs_vs"]) vs = [lib newFunctionWithName:@"shade_fs_vs"];
	for (NSString *name in lib.functionNames) {
		id<MTLFunction> f = [lib newFunctionWithName:name];
		if (!vs && f.functionType == MTLFunctionTypeVertex) vs = f;
	}
	for (NSString *name in [lib.functionNames sortedArrayUsingSelector:@selector(compare:)]) {
		id<MTLFunction> f = [lib newFunctionWithName:name];
		if (f.functionConstantsDictionary.count) {
			// specialized with every constant zero / false (the modules pick their own values; the cost is about the same)
			MTLFunctionConstantValues *values = [MTLFunctionConstantValues new];
			uint64_t zero[2] = {0, 0};
			for (MTLFunctionConstant *c in f.functionConstantsDictionary.allValues) [values setConstantValue:zero type:c.type atIndex:c.index];
			NSError *e = nil;
			f = [lib newFunctionWithName:name constantValues:values error:&e];
			if (!f) continue;
		}
		if (f.functionType == MTLFunctionTypeKernel) {
			[out addObject:f];
		} else if (f.functionType == MTLFunctionTypeFragment && vs) {
			MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
			pd.vertexFunction = vs;
			pd.fragmentFunction = f;
			pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
			[out addObject:pd];
		}
	}
	return out;
}

static BOOL buildOne(id desc) {
	NSError *e = nil;
	BOOL ok = [desc isKindOfClass:[MTLRenderPipelineDescriptor class]] ? [dev newRenderPipelineStateWithDescriptor:desc error:&e] != nil
		: [dev newComputePipelineStateWithFunction:desc error:&e] != nil;
	if (!ok && getenv("COMPILEBENCH_ERRORS")) fprintf(stderr, "pipeline failed: %s\n", e.localizedDescription.UTF8String);
	return ok;
}

static void compileLibrary(Item *it) {
	double t0 = now();
	NSError *e = nil;
	it->lib = [dev newLibraryWithSource:it->source options:options error:&e];
	it->libMs = now() - t0;
	if (!it->lib) {
		it->failed++;
		fprintf(stderr, "FAIL %s: %s\n", it->path.lastPathComponent.UTF8String, e.localizedDescription.UTF8String);
	}
}

static void buildPipelines(Item *it) {
	if (!it->lib) return;
	double t0 = now();
	for (id d in pipelinesOf(it->lib)) {
		if (buildOne(d)) it->psos++;
		else it->failed++;
	}
	it->psoMs = now() - t0;
}

static qos_class_t qosNamed(const char *q) {
	if (!strcmp(q, "ui")) return QOS_CLASS_USER_INTERACTIVE;
	if (!strcmp(q, "initiated")) return QOS_CLASS_USER_INITIATED;
	if (!strcmp(q, "default")) return QOS_CLASS_DEFAULT;
	if (!strcmp(q, "background")) return QOS_CLASS_BACKGROUND;
	return QOS_CLASS_UTILITY;
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		int jobs = 0, async = 0, cold = 0, nonce = 0, psos = 0, split = 0, maximize = 0;
		const char *qos = "utility";
		NSMutableArray<NSString *> *files = [NSMutableArray array];
		for (int i = 1; i < argc; i++) {
			if (!strcmp(argv[i], "-j") && i + 1 < argc) jobs = atoi(argv[++i]);
			else if (!strcmp(argv[i], "--async")) async = 1;
			else if (!strcmp(argv[i], "--qos") && i + 1 < argc) qos = argv[++i];
			else if (!strcmp(argv[i], "--maximize")) maximize = 1;
			else if (!strcmp(argv[i], "--cold")) cold = 1;
			else if (!strcmp(argv[i], "--nonce")) nonce = 1;
			else if (!strcmp(argv[i], "--psos")) psos = 1;
			else if (!strcmp(argv[i], "--split")) split = 1;
			else if (!strcmp(argv[i], "--terrain") && i + 1 < argc) terrainVs = [NSString stringWithUTF8String:argv[++i]];
			else [files addObject:[NSString stringWithUTF8String:argv[i]]];
		}
		if (!files.count) {
			fprintf(stderr, "usage: compilebench [-j N] [--async] [--qos Q] [--maximize] [--cold] [--nonce] [--psos | --terrain VS] [--split] FILE...\n");
			return 2;
		}
		if (cold) {
			// Only ever this tool's own cache: <user cache dir>/<bundle id>/com.apple.metal
			NSString *bundle = NSBundle.mainBundle.bundleIdentifier;
			if (![bundle isEqualToString:@"org.mcoptshade.compilebench"]) {
				fprintf(stderr, "--cold needs the bundle id compilebench.sh builds in (got %s)\n", bundle ? bundle.UTF8String : "none");
				return 2;
			}
			char dir[PATH_MAX];
			if (!confstr(_CS_DARWIN_USER_CACHE_DIR, dir, sizeof dir)) return 2;
			NSString *cache = [[[NSString stringWithUTF8String:dir] stringByAppendingPathComponent:bundle] stringByAppendingPathComponent:@"com.apple.metal"];
			[[NSFileManager defaultManager] removeItemAtPath:cache error:nil];
		}
		dev = MTLCreateSystemDefaultDevice();
		if (maximize) dev.shouldMaximizeConcurrentCompilation = YES;
		options = [MTLCompileOptions new];
		options.languageVersion = MTLLanguageVersion3_0;
		options.mathMode = MTLMathModeFast;
		NSString *prefix = nonce ? [NSString stringWithFormat:@"// compilebench %llu\n", mach_absolute_time()] : @"";
		if (terrainVs || psos) {
			sodiumLayout = [MTLVertexDescriptor vertexDescriptor];
			sodiumLayout.layouts[27].stride = 20;
			MTLVertexFormat f[4] = {MTLVertexFormatUInt2, MTLVertexFormatUChar4Normalized, MTLVertexFormatUShort2, MTLVertexFormatUChar4};
			int off[4] = {0, 8, 12, 16};
			for (int i = 0; i < 4; i++) {
				sodiumLayout.attributes[i].bufferIndex = 27;
				sodiumLayout.attributes[i].offset = off[i];
				sodiumLayout.attributes[i].format = f[i];
			}
		}
		if (terrainVs) {
			// compiled up front and outside the timing: in the game every variant's vertex stage is the same source, so after
			// the first one it comes from the cache
			NSError *e = nil;
			NSString *src = [NSString stringWithContentsOfFile:terrainVs encoding:NSUTF8StringEncoding error:nil];
			terrainVsLib = [dev newLibraryWithSource:[prefix stringByAppendingString:src ?: @""] options:options error:&e];
			if (!terrainVsLib) { fprintf(stderr, "FAIL %s: %s\n", terrainVs.UTF8String, e.localizedDescription.UTF8String); return 1; }
			psos = 1;
		}
		int n = (int) files.count;
		Item *items = calloc(n, sizeof(Item));
		for (int i = 0; i < n; i++) {
			NSString *src = [NSString stringWithContentsOfFile:files[i] encoding:NSUTF8StringEncoding error:nil];
			if (!src) { fprintf(stderr, "cannot read %s\n", files[i].UTF8String); return 1; }
			items[i].path = files[i];
			items[i].source = [prefix stringByAppendingString:src];
		}
		printf("device %s, maximumConcurrentCompilationTaskCount %lu, %s, -j %d%s%s, qos %s%s%s\n", dev.name.UTF8String,
			(unsigned long) dev.maximumConcurrentCompilationTaskCount, cold ? "cold" : nonce ? "nonce (MSL cold, pipelines warm)" : "warm",
			jobs, async ? " async" : "", split ? " split" : "", qos, psos ? ", pipelines" : "", terrainVs ? " (terrain)" : "");
		double t0 = now();
		if (jobs <= 0) {
			for (int i = 0; i < n; i++) {
				compileLibrary(&items[i]);
				if (psos) buildPipelines(&items[i]);
			}
		} else {
			dispatch_queue_t q = dispatch_queue_create("compilebench", dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_CONCURRENT, qosNamed(qos), 0));
			dispatch_semaphore_t slots = dispatch_semaphore_create(jobs);
			dispatch_group_t g = dispatch_group_create();
			if (async) {
				// Completion-handler calls: no thread waits on the compiler; at most `jobs` requests outstanding
				for (int i = 0; i < n; i++) {
					Item *it = &items[i];
					dispatch_semaphore_wait(slots, DISPATCH_TIME_FOREVER);
					dispatch_group_enter(g);
					dispatch_async(q, ^{
						double s = now();
						[dev newLibraryWithSource:it->source options:options completionHandler:^(id<MTLLibrary> lib, NSError *e) {
							it->lib = lib;
							it->libMs = now() - s;
							if (!lib) { it->failed++; fprintf(stderr, "FAIL %s: %s\n", it->path.lastPathComponent.UTF8String, e.localizedDescription.UTF8String); }
							dispatch_semaphore_signal(slots);
							dispatch_group_leave(g);
						}];
					});
				}
				dispatch_group_wait(g, DISPATCH_TIME_FOREVER);
				if (psos) {
					for (int i = 0; i < n; i++) {
						Item *it = &items[i];
						if (!it->lib) continue;
						for (id d in pipelinesOf(it->lib)) {
							dispatch_semaphore_wait(slots, DISPATCH_TIME_FOREVER);
							dispatch_group_enter(g);
							dispatch_async(q, ^{
								double s = now();
								void (^done)(id, NSError *) = ^(id state, NSError *e) {
									@synchronized (it->path) {
										it->psoMs += now() - s;
										if (state) it->psos++;
										else it->failed++;
									}
									dispatch_semaphore_signal(slots);
									dispatch_group_leave(g);
								};
								if ([d isKindOfClass:[MTLRenderPipelineDescriptor class]]) {
									[dev newRenderPipelineStateWithDescriptor:d completionHandler:^(id<MTLRenderPipelineState> st, NSError *e) { done(st, e); }];
								} else {
									[dev newComputePipelineStateWithFunction:d completionHandler:^(id<MTLComputePipelineState> st, NSError *e) { done(st, e); }];
								}
							});
						}
					}
					dispatch_group_wait(g, DISPATCH_TIME_FOREVER);
				}
			} else if (split) {
				for (int i = 0; i < n; i++) {
					Item *it = &items[i];
					dispatch_semaphore_wait(slots, DISPATCH_TIME_FOREVER);
					dispatch_group_async(g, q, ^{
						compileLibrary(it);
						dispatch_semaphore_signal(slots);
					});
				}
				dispatch_group_wait(g, DISPATCH_TIME_FOREVER);
				if (psos) {
					for (int i = 0; i < n; i++) {
						Item *it = &items[i];
						if (!it->lib) continue;
						for (id d in pipelinesOf(it->lib)) {
							dispatch_semaphore_wait(slots, DISPATCH_TIME_FOREVER);
							dispatch_group_async(g, q, ^{
								double s = now();
								BOOL ok = buildOne(d);
								@synchronized (it->path) {
									it->psoMs += now() - s;
									if (ok) it->psos++;
									else it->failed++;
								}
								dispatch_semaphore_signal(slots);
							});
						}
					}
					dispatch_group_wait(g, DISPATCH_TIME_FOREVER);
				}
			} else {
				for (int i = 0; i < n; i++) {
					Item *it = &items[i];
					dispatch_semaphore_wait(slots, DISPATCH_TIME_FOREVER);
					dispatch_group_async(g, q, ^{
						compileLibrary(it);
						if (psos) buildPipelines(it);
						dispatch_semaphore_signal(slots);
					});
				}
				dispatch_group_wait(g, DISPATCH_TIME_FOREVER);
			}
		}
		double wall = now() - t0, libSum = 0, psoSum = 0;
		int failed = 0, pipelines = 0;
		for (int i = 0; i < n; i++) {
			printf("  %-44s library %8.1f ms", items[i].path.lastPathComponent.UTF8String, items[i].libMs);
			if (psos) printf("   %2d pipelines %8.1f ms", items[i].psos, items[i].psoMs);
			printf("%s\n", items[i].failed ? "  (failures)" : "");
			libSum += items[i].libMs;
			psoSum += items[i].psoMs;
			failed += items[i].failed;
			pipelines += items[i].psos;
		}
		printf("TOTAL %d libraries%s: wall %.0f ms, sum of steps %.0f ms (libraries %.0f, pipelines %.0f)%s\n", n,
			psos ? [NSString stringWithFormat:@" + %d pipelines", pipelines].UTF8String : "", wall, libSum + psoSum, libSum, psoSum,
			failed ? [NSString stringWithFormat:@", %d FAILED", failed].UTF8String : "");
		return failed ? 1 : 0;
	}
}
