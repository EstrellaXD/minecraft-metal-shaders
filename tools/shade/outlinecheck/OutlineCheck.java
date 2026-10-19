// Copyright 2026 mcopt-shade contributors. Apache-2.0.
package mcopt.metal;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.zip.ZipFile;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Runs the production translator on the user's MaLiLib shader; no copy of that mod is bundled. */
public final class OutlineCheck {
 static String read(ZipFile zip,String name)throws Exception{return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  Path out=Path.of(args[2]);Files.createDirectories(out);
  try(ZipFile mali=new ZipFile(args[0]);ZipFile mc=new ZipFile(args[1])){
   for(boolean vertex:new boolean[]{true,false}){
    String source=read(mali,"assets/malilib/shaders/position_color_lines."+(vertex?"vsh":"fsh")).replace("#version 330","#version 450");
    for(String name:new String[]{"dynamictransforms","projection"})source=source.replace("#include <minecraft:"+name+".glsl>",read(mc,"assets/minecraft/shaders/include/"+name+".glsl"));
    long compiler=shaderc_compiler_initialize(),opts=shaderc_compile_options_initialize();
    shaderc_compile_options_set_auto_bind_uniforms(opts,true);shaderc_compile_options_set_auto_map_locations(opts,true);
    long result=shaderc_compile_into_spv(compiler,source,vertex?shaderc_glsl_vertex_shader:shaderc_glsl_fragment_shader,"outline","main",opts);
    check(shaderc_result_get_compilation_status(result)==0,shaderc_result_get_error_message(result));
    ByteBuffer bytes=shaderc_result_get_bytes(result).order(ByteOrder.nativeOrder());
    SpvModule module=new SpvModule(){public void close(){} public ByteBuffer spv(){return bytes;}public ShaderType type(){return vertex?ShaderType.VERTEX:ShaderType.FRAGMENT;}public SpvModule.Reflection reflect(){return null;}public SpvModule.Reflection getReflectionInfoIfAvailable(){return null;}};
    Method translate=MetalPipeline.class.getDeclaredMethod("translate",SpvModule.class,int.class,boolean.class);translate.setAccessible(true);
    for(boolean points:new boolean[]{true,false,true,false}){
     Object translated=translate.invoke(null,module,2,vertex&&points);Method msl=translated.getClass().getDeclaredMethod("msl"),entry=translated.getClass().getDeclaredMethod("entry");msl.setAccessible(true);entry.setAccessible(true);
     String code=(String)msl.invoke(translated),base=vertex?(points?"points":"lines"):"fragment";
     if(vertex)check(code.contains("[[point_size]]")==points,"point output matches topology, including cache hits");
     Files.writeString(out.resolve(base+".metal"),code);Files.writeString(out.resolve(base+".entry"),(String)entry.invoke(translated));
    }
    shaderc_result_release(result);shaderc_compile_options_release(opts);shaderc_compiler_release(compiler);
   }
  }
  check(MetalPipeline.mslMismatches==0,"cached output matches fresh translation");
  System.out.println("PASS: MaLiLib GLSL through production translator; lines omit point size, points retain it; topology-aware cache agrees");
 }
}
