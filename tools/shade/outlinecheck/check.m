// Copyright 2026 mcopt-shade contributors. Apache-2.0.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
static void check(BOOL ok,NSString *why){if(!ok){fprintf(stderr,"FAIL: %s\n",why.UTF8String);exit(1);}}
static NSString *readText(NSString *dir,NSString *name){return [NSString stringWithContentsOfFile:[dir stringByAppendingPathComponent:name] encoding:NSUTF8StringEncoding error:nil];}
int main(int argc,const char **argv){@autoreleasepool{
 NSString *dir=[NSString stringWithUTF8String:argv[1]];id<MTLDevice> d=MTLCreateSystemDefaultDevice();check(d!=nil,@"Metal device");NSError *err=nil;MTLCompileOptions *opt=[MTLCompileOptions new];opt.languageVersion=MTLLanguageVersion3_0;
 id<MTLLibrary> frag=[d newLibraryWithSource:readText(dir,@"fragment.metal") options:opt error:&err];check(frag!=nil,err.description);id<MTLFunction> fs=[frag newFunctionWithName:readText(dir,@"fragment.entry")];
 for(int points=0;points<2;points++){
  NSString *base=points?@"points":@"lines";
  id<MTLLibrary> lib=[d newLibraryWithSource:readText(dir,[base stringByAppendingString:@".metal"]) options:opt error:&err];check(lib!=nil,err.description);
  id<MTLFunction> vs=[lib newFunctionWithName:readText(dir,[base stringByAppendingString:@".entry"])];
  for(int topology=1;topology<=3;topology++)for(int hdr=0;hdr<2;hdr++){
   MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=vs;pd.fragmentFunction=fs;pd.inputPrimitiveTopology=topology;pd.colorAttachments[0].pixelFormat=hdr?MTLPixelFormatRGBA16Float:MTLPixelFormatRGBA8Unorm;pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
   MTLVertexDescriptor *vd=[MTLVertexDescriptor vertexDescriptor];vd.attributes[0].format=MTLVertexFormatFloat3;vd.attributes[0].bufferIndex=27;vd.attributes[0].offset=0;vd.attributes[1].format=MTLVertexFormatFloat4;vd.attributes[1].bufferIndex=27;vd.attributes[1].offset=12;vd.attributes[2].format=MTLVertexFormatFloat;vd.attributes[2].bufferIndex=27;vd.attributes[2].offset=28;vd.layouts[27].stride=32;pd.vertexDescriptor=vd;
   id<MTLRenderPipelineState> pipeline=[d newRenderPipelineStateWithDescriptor:pd error:&err];
   if(points&&topology!=MTLPrimitiveTopologyClassPoint){check(pipeline==nil&&[err.localizedDescription containsString:@"point size"],@"old output reproduces reported non-point pipeline error");}
   else check(pipeline!=nil,err.description);
  }
 }
 puts("PASS: original outline failure reproduced; corrected line and triangle pipelines and real point output compile in SDR and HDR");
}return 0;}
