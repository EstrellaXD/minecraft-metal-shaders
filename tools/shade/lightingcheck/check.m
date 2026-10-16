// Copyright 2026 mcopt-shade contributors. Apache-2.0.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <math.h>
static id<MTLDevice> dev;
static id<MTLCommandQueue> queue;
enum { COUNT=384, FRAME=1024, LOOK=1088 };
static void check(BOOL ok,const char *why){if(!ok){fprintf(stderr,"FAIL %s\n",why);exit(1);}}
static void complete(id<MTLCommandBuffer> cb){[cb commit];[cb waitUntilCompleted];check(cb.status==MTLCommandBufferStatusCompleted,cb.error.description.UTF8String);}
static NSData *readData(NSString *path){NSData *d=[NSData dataWithContentsOfFile:path];check(d!=nil,path.UTF8String);return d;}
static NSString *source(NSString *path){return [[NSString alloc] initWithData:readData(path) encoding:NSUTF8StringEncoding];}
static id<MTLLibrary> library(NSString *folder,NSString *probe){
 NSError *error=nil;MTLCompileOptions *opts=[MTLCompileOptions new];opts.languageVersion=MTLLanguageVersion3_0;
 id<MTLLibrary> lib=[dev newLibraryWithSource:[source([folder stringByAppendingPathComponent:@"post.metal"]) stringByAppendingString:source(probe)] options:opts error:&error];
 check(lib!=nil,error.description.UTF8String);return lib;
}
static id<MTLComputePipelineState> pipeline(id<MTLLibrary> lib,NSString *name){NSError *error=nil;
 id<MTLComputePipelineState> p=[dev newComputePipelineStateWithFunction:[lib newFunctionWithName:name] error:&error];check(p!=nil,error.description.UTF8String);return p;}
static double render(id<MTLComputePipelineState> p,NSData *uniforms,id<MTLBuffer> out,int count,id<MTLBuffer> gpu){
 const char *u=uniforms.bytes;check(uniforms.length==FRAME+LOOK+96,"uniform size");
 id<MTLCommandBuffer> cb=[queue commandBuffer];id<MTLComputeCommandEncoder> e=[cb computeCommandEncoder];
 [e setComputePipelineState:p];[e setBuffer:out offset:0 atIndex:0];[e setBytes:u+FRAME+LOOK length:96 atIndex:1];
 [e setBytes:u length:FRAME atIndex:16];[e setBytes:u+FRAME length:LOOK atIndex:17];if(gpu)[e setBuffer:gpu offset:0 atIndex:18];
 [e dispatchThreads:MTLSizeMake(count,1,1) threadsPerThreadgroup:MTLSizeMake(128,1,1)];[e endEncoding];complete(cb);
 return (cb.GPUEndTime-cb.GPUStartTime)*1000;
}
static float lum(const float *p){return .2126f*p[0]+.7152f*p[1]+.0722f*p[2];}
static void initGpu(id<MTLBuffer> gpu,float ex){memset(gpu.contents,0,512);float *f=gpu.contents;f[44]=f[45]=f[46]=ex;f[47]=log2f(ex);}
static void addBin(id<MTLBuffer> gpu,float l,uint count){int bin=(int)floorf((log2f(fmaxf(l,1e-7))+14)/22*64);bin=MAX(0,MIN(63,bin));((uint32_t *)gpu.contents)[52+bin]+=count;}
static float expose(id<MTLComputePipelineState> p,NSData *u,id<MTLBuffer> gpu){
 id<MTLCommandBuffer> cb=[queue commandBuffer];id<MTLComputeCommandEncoder> e=[cb computeCommandEncoder];[e setComputePipelineState:p];
 [e setBytes:(const char *)u.bytes+FRAME length:LOOK atIndex:17];[e setBuffer:gpu offset:0 atIndex:18];
 [e dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];[e endEncoding];complete(cb);
 float value=((float *)gpu.contents)[44];check(isfinite(value)&&value>0,"finite exposure");
 for(int i=0;i<64;i++)check(((uint32_t *)gpu.contents)[52+i]==0,"histogram reset");return value;
}
static double median(NSArray<NSNumber *> *a){NSArray *s=[a sortedArrayUsingSelector:@selector(compare:)];return [s[s.count/2] doubleValue];}
int main(int argc,const char **argv){@autoreleasepool{
 check(argc==4,"fixture folder, probe source, mode");dev=MTLCreateSystemDefaultDevice();check(dev!=nil,"Metal device");queue=[dev newCommandQueue];
 NSString *folder=@(argv[1]),*probe=@(argv[2]);BOOL perf=strcmp(argv[3],"perf")==0;
 id<MTLLibrary> lib=library(folder,probe);id<MTLComputePipelineState> p=pipeline(lib,@"probe"),display=pipeline(lib,@"displayProbe"),meter=pipeline(lib,@"shade_exposure");
 NSString *baselineFolder=[folder stringByAppendingPathComponent:@"baseline"];
 BOOL baseline=[[NSFileManager defaultManager] fileExistsAtPath:[baselineFolder stringByAppendingPathComponent:@"post.metal"]];
 id<MTLLibrary> oldlib=baseline?library(baselineFolder,probe):nil;
 id<MTLComputePipelineState> old=baseline?pipeline(oldlib,@"probe"):nil,oldMeter=baseline?pipeline(oldlib,@"shade_exposure"):nil;
 id<MTLBuffer> result=[dev newBufferWithLength:COUNT*16 options:MTLResourceStorageModeShared],gpu=[dev newBufferWithLength:512 options:MTLResourceStorageModeShared];
 NSMutableArray *measurements=[NSMutableArray array];
 NSArray *names=@[@"outdoor",@"interior",@"cave",@"night",@"rain",@"storm",@"nightvision",@"underwater",@"nether",@"end",@"sunset",@"oldlight"];
 for(NSString *name in names){
  NSData *u=readData([folder stringByAppendingPathComponent:[name stringByAppendingString:@"-current.bin"]]);
  NSData *v=readData([folder stringByAppendingPathComponent:[name stringByAppendingString:@"-natural.bin"]]);
  float a[COUNT*4],b[COUNT*4];render(p,u,result,COUNT,nil);memcpy(a,result.contents,sizeof a);render(p,v,result,COUNT,nil);memcpy(b,result.contents,sizeof b);
  for(int i=0;i<COUNT*4;i++)check(isfinite(b[i])&&b[i]>=0,"finite nonnegative lighting");
  if(baseline){
   NSData *bu=readData([baselineFolder stringByAppendingPathComponent:[name stringByAppendingString:@"-current.bin"]]);
   check([u isEqualToData:bu],"Current uniforms match previous jar");render(old,bu,result,COUNT,nil);
   float maxError=0, maxRelative=0;float *previous=result.contents;
   for(int i=0;i<COUNT*4;i++){maxError=fmaxf(maxError,fabsf(a[i]-previous[i]));maxRelative=fmaxf(maxRelative,fabsf(a[i]-previous[i])/fmaxf(fabsf(previous[i]),.001));}
   printf("Current parity %s: max absolute %.8g, relative %.8g\n",name.UTF8String,maxError,maxRelative);
   check(maxRelative<.003,"Current lighting matches previous jar within half precision");
  }
  float weight=((const float *)v.bytes)[(FRAME+1072)/4];
  if(weight==0)check(memcmp(a,b,sizeof a)==0,"excluded scene unchanged");
  else {
   check(lum(b+7*4)<lum(a+7*4),"sheltered surface darkens");
   if(weight==1)check(lum(b+79*4)>lum(a+79*4),"clear-day sunlit surface brightens");
   check(lum(b+79*4)/lum(b+7*4)>lum(a+79*4)/lum(a+7*4),"daylight/interior contrast increases");
   for(int k=0;k<3;k++)check(fabsf((a[48*4+k]-a[k])-(b[48*4+k]-b[k]))<.004,"torch contribution preserved");
  }
  for(int mode=0;mode<2;mode++){
   NSData *s=mode?v:u;float *raw=mode?b:a;
   initGpu(gpu,1);addBin(gpu,lum(raw+7*4),8000);addBin(gpu,lum(raw+79*4),2000);
   float ex=expose(meter,s,gpu);
   if(baseline&&!mode){initGpu(gpu,1);addBin(gpu,lum(raw+7*4),8000);addBin(gpu,lum(raw+79*4),2000);check(ex==expose(oldMeter,s,gpu),"Current GPU exposure matches previous jar");}
   render(display,s,result,COUNT,gpu);float *hdr=result.contents;
   for(int i=0;i<COUNT*4;i++)check(isfinite(hdr[i])&&hdr[i]>=0&&hdr[i]<=4.0001,"HDR within available peak");
   [measurements addObject:@{@"scene":name,@"mode":mode?@"natural":@"current",@"exposure":@(ex),@"raw_indoor":@(lum(raw+7*4)),@"raw_outdoor":@(lum(raw+79*4)),@"hdr_indoor":@(lum(hdr+7*4)),@"hdr_outdoor":@(lum(hdr+79*4)),@"hdr_ratio":@(lum(hdr+79*4)/fmaxf(lum(hdr+7*4),1e-6))}];
  }
 }
 // A tiny sun/emissive patch must not drive exposure; a broad window remains part of the meter.
 for(NSString *mode in @[@"current", @"natural"]){
  NSData *u=readData([folder stringByAppendingPathComponent:[@"interior-" stringByAppendingFormat:@"%@.bin",mode]]);
  initGpu(gpu,1);addBin(gpu,.2,10000);float plain=expose(meter,u,gpu);
  initGpu(gpu,1);addBin(gpu,.2,9900);addBin(gpu,1000,100);float tiny=expose(meter,u,gpu);
  check(fabsf(plain-tiny)<1e-5,"small bright sources excluded from metering");
  initGpu(gpu,1);addBin(gpu,.2,8000);addBin(gpu,8,2000);float window=expose(meter,u,gpu);
  check(window<plain,"broad window participates in metering");
 }
 // GPU adaptation rate from an identical one-EV error toward the target, independent of the CPU reference.
 NSData *natural=readData([folder stringByAppendingPathComponent:@"interior-natural.bin"]);
 NSMutableData *live=[natural mutableCopy];float *l=(float *)((char *)live.mutableBytes+FRAME);l[1008/4+3]=0;
 initGpu(gpu,1);addBin(gpu,.1,8000);addBin(gpu,5,2000);expose(meter,natural,gpu);float target=((float *)gpu.contents)[45];
 initGpu(gpu,target/2);addBin(gpu,.1,8000);addBin(gpu,5,2000);float up=log2f(expose(meter,live,gpu)/(target/2));
 initGpu(gpu,target*2);addBin(gpu,.1,8000);addBin(gpu,5,2000);float down=-log2f(expose(meter,live,gpu)/(target*2));
 check(down>up*4&&up>0,"daylight settles faster than dark adaptation");
 NSData *json=[NSJSONSerialization dataWithJSONObject:measurements options:NSJSONWritingPrettyPrinted error:nil];
 [json writeToFile:[folder stringByAppendingPathComponent:@"contrast.json"] atomically:YES];
 printf("OK %lu scene/mode fixtures x %d surfaces; actual HDR/exposure, torch preservation, guard cases, adaptation%s on %s\n",names.count*2,COUNT,baseline?", previous-jar Current parity":"",dev.name.UTF8String);
 if(perf){
  int count=3840*2160;id<MTLBuffer> large=[dev newBufferWithLength:(NSUInteger)count*16 options:MTLResourceStorageModePrivate];
  NSData *current=readData([folder stringByAppendingPathComponent:@"outdoor-current.bin"]);natural=readData([folder stringByAppendingPathComponent:@"outdoor-natural.bin"]);
  NSMutableArray *samples[3]={[NSMutableArray array],[NSMutableArray array],[NSMutableArray array]};
  NSMutableString *csv=[NSMutableString stringWithString:@"round,variant,gpu_ms\n"];
  int variants=baseline?3:2;
  for(int round=-6;round<30;round++)for(int j=0;j<variants;j++){
   int k=(round&1)?variants-1-j:j;id<MTLComputePipelineState> state=k==2?old:p;
   double ms=render(state,k==1?natural:current,large,count,nil);check(isfinite(ms)&&ms>0,"valid GPU timing");
   if(round>=0){[samples[k] addObject:@(ms)];[csv appendFormat:@"%d,%@,%.6f\n",round,(@[@"current",@"natural",@"previous_jar"])[k],ms];}
  }
  [csv writeToFile:[folder stringByAppendingPathComponent:@"performance.csv"] atomically:YES encoding:NSUTF8StringEncoding error:nil];
  NSMutableArray *deltas=[NSMutableArray array];for(int i=0;i<30;i++)[deltas addObject:@([samples[1][i] doubleValue]-[samples[0][i] doubleValue])];
  NSMutableDictionary *report=[@{@"device":dev.name,@"scope":@"3840x2160 production diffuse lighting kernel + float4 store; excludes full game, shadow/texture reads and other passes",@"samples_per_variant":@30,@"current_median_ms":@(median(samples[0])),@"natural_median_ms":@(median(samples[1])),@"paired_delta_median_ms":@(median(deltas))} mutableCopy];
  if(baseline)report[@"previous_jar_median_ms"]=@(median(samples[2]));
  [[NSJSONSerialization dataWithJSONObject:report options:NSJSONWritingPrettyPrinted error:nil] writeToFile:[folder stringByAppendingPathComponent:@"performance.json"] atomically:YES];
  printf("4K lighting kernel: Current %.4f ms, Natural %.4f ms, paired delta %+.4f ms GPU\n",median(samples[0]),median(samples[1]),median(deltas));
 }
}return 0;}
