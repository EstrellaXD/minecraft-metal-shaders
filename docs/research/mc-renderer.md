# Minecraft 26.3 renderer architecture (renderpearl) — research report `mc-renderer`

All paths below are relative to the decompile root unless absolute:
`WORK = .../scratch-2026-10-06-836a24/work`

- Decompiled sources (Vineflower 1.12.0, Prism Java 25 runtime, zero decompile failures):
  `WORK/decomp/mc-26.3/` — 2123 `.java` files covering **all** of `com/mojang/renderpearl/**` (146), `com/mojang/blaze3d/**` (86), and **all** of `net/minecraft/client/**` (1891, incl. `renderer/**`, `gui/**`, `resources/**`, `Minecraft`, `Options`, `Camera`, `main/Main`, `multiplayer/ClientLevel`). Other `net/minecraft/**` (world, server) were passed as library only, not decompiled.
  Re-run recipe: `java -jar WORK/tools/vineflower-1.12.0.jar -s -e=<client.jar> -e=<lwjgl/joml/fastutil/guava/dfu jars> <subset.jar> <out>`; the subset jar was built with `unzip <client.jar> 'com/mojang/renderpearl/*' 'com/mojang/blaze3d/*' 'net/minecraft/client/*'`.
- Vanilla shaders + post-effect JSONs: `WORK/decomp/mc-26.3-shaders/assets/minecraft/{shaders,post_effect}/` (64 core + 10 post + 21 include, plus 5 post_effect JSON files).
- Vineflower jar: `WORK/tools/vineflower-1.12.0.jar`.

---

## 0. TL;DR (decision-relevant facts)

1. **Every backend gets SPIR-V, none get the original GLSL.** The frontend compiles Mojang-dialect GLSL (Vulkan-flavoured `#version 330` + `#include <ns:file.glsl>`) with **shaderc (LWJGL `org.lwjgl.util.shaderc`) targeting Vulkan 1.2, optimisation level 0, debug info on**. The OpenGL backend then **decompiles the SPIR-V back to GLSL 330 with SPIRV-Cross** (`org.lwjgl.util.spvc`) and calls `glCompileShader`. Vulkan feeds SPIR-V straight to `vkCreateShaderModule`. mcopt's Metal backend runs SPIRV-Cross to MSL. So **anything that goes through the renderpearl API runs on Metal, Vulkan and GL without extra work.**
2. **The renderpearl API is small and "WebGPU-like".** It has render passes with up to 8 colour targets plus a depth target, pipelines built from GLSL, uniform blocks, combined image samplers and texel buffers, push constants (128 B or less), and indirect or multi-draw. It has **no compute shaders, no storage buffers, no storage images, no comparison (shadow) samplers, no 3D or 1D textures, and no per-layer texture views.** The viewport always covers the whole attachment, and `RenderArea` only sets the scissor.
3. **Reversed-Z everywhere.** `Projection.getMatrix` swaps near and far (`Projection.java:67-71`). The default depth test is `GREATER_THAN_OR_EQUAL` (`DepthStencilState.java:4`), depth clears to `0.0`, and depth is `D32_FLOAT`. The Metal backend (mcopt) reports `isZZeroToOne=true`. On GL on macOS, `GL_ARB_clip_control` is missing, so Z is in [-1,1] there.
4. **The main target is LDR `RGBA8_UNORM` plus `D32_FLOAT`, at full window pixel size.** The window is created with `SDL_WINDOW_HIGH_PIXEL_DENSITY` (flags `8224 = 0x2000|0x20`, `Window.java:149`) and sized with `SDL_GetWindowSizeInPixels`. So **vanilla 26.3 already renders at native Retina resolution (3024×1964 fullscreen).** The resolution drop the user sees comes from Iris+GL cost, not from Minecraft capping it.
5. **Fabulous / `TransparencyChain` no longer exist.** They are replaced by **"Improved Transparency"**, a wavelet OIT path (`net/minecraft/client/renderer/oit/*`, `LevelRenderer.executeOit`). `GraphicsPreset.FABULOUS` is now only a preset.
6. **There is a built-in every-frame post-effect hook.** `GameRenderer.update()` always requests `minecraft:end_of_frame` (`GameRenderer.java:103,429`). Vanilla ships **no** `post_effect/end_of_frame.json`, and `ShaderManager` silently skips it when it is missing (`ShaderManager.java:345`). **A resource pack that adds `assets/minecraft/post_effect/end_of_frame.json` gets a full-screen post chain every frame**, with the main colour **and main depth** (`"use_depth_buffer": true`). It runs through renderpearl, so on mcopt it runs on Metal. Resource-pack overrides of core shaders (`assets/minecraft/shaders/core/*.vsh|fsh`, `shaders/include/*.glsl`) are also loaded through the resource manager and flow GLSL → shaderc → SPIR-V → SPIRV-Cross → MSL. There is one exception on mcopt: Sodium's 3 terrain pipelines use mcopt's hand-written MSL unless `-Dmcopt.metal.builtinMsl=false`.
7. **Post passes do not get camera matrices.** `PostPass` swaps `Projection` to an orthographic matrix, and `Globals` has no view, inverse-projection or sun data. Real shaders (shadows, sky-aware lighting) therefore need **a Fabric mod that adds its own frame-graph passes and UBOs**. Pure resource-pack shaders are limited to screen-space work, or to the hacky "encode data into pixels from core shaders" technique.
8. **Vulkan backend:** about 5.7k lines, labelled **Experimental** in the UI. `DEFAULT` tries **GL first** (`PreferredGraphicsApi.getBackendsToTry`: `{gl, vulkan}` unless VULKAN). On macOS it runs on **MoltenVK, which is bundled in `lwjgl-vulkan-natives-macos-arm64-3.4.3.jar` (`libMoltenVK.dylib`)**, using `VK_KHR_portability_enumeration` and `portability_subset`. It uses SPIR-V natively, which conceptually is the same path as mcopt (SPIR-V → SPIRV-Cross → MSL inside MoltenVK).

---

## 1. renderpearl API surface

Packages: `com.mojang.renderpearl.api.*` (public API), `backend.api.*` (what a backend implements), `frontend.*` (validation and compilation wrapper), `backend.{opengl,vulkan,common,util}`. Line counts: api ≈ 2.0k, frontend ≈ 3.0k, opengl ≈ 5.1k, vulkan ≈ 5.7k.

### 1.1 Backend selection and device creation

- `api/device/GpuBackend.java`: `getName()`, `loadLibrary()`, `unloadLibrary()`, `long createWindow(title,w,h,flags)` (SDL3 window), and `GpuDevice createDevice(GpuDebugOptions)`.
- `GlBackend.createDevice` returns `new FrontendGpuDevice(new GlDevice(...))` (`backend/opengl/GlBackend.java:69`). `VulkanBackend.createDevice` returns `new FrontendGpuDevice(new VulkanDevice(...))`. A backend implements **`backend.api.GpuDeviceBackend`**, and `FrontendGpuDevice` wraps it. mcopt's `MetalBackend` does the same (`MetalDevice` reports `DeviceInfo(..., isZZeroToOne=true, "Metal", ..., DeviceLimits(16,256,16384,maxBuf,MAX,8,MAX), DeviceFeatures(all true), HintsAndWorkarounds(false,false,true,false))`, `mcopt/metal/.../MetalDevice.java:55-61`).
- `net/minecraft/client/Minecraft.java:462-545` tries `PreferredGraphicsApi.getBackendsToTry()` in order and falls through on `BackendCreationException`. A `--graphicsBackend` launch argument forces one (`main/Main.java:82`). `options.txt` key: `preferredGraphicsBackend` with values `default|opengl|vulkan`. After an unclean start it resets to DEFAULT or OPENGL (`Minecraft.java:472-480`).

### 1.2 `GpuDevice` (`api/device/GpuDevice.java`)
```java
GpuSurface createSurface(long window, BooleanSupplier iconified);
CommandEncoder createCommandEncoder();            // a single shared encoder (FrontendGpuDevice.encoder)
GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAniso, OptionalDouble maxLod);
GpuTexture createTexture(label, @Usage int usage, GpuFormat fmt, int w, int h, int depthOrLayers, int mips);
GpuTextureView createTextureView(GpuTexture [, baseMip, mipLevels]);   // no layer/array slicing
GpuBuffer createBuffer(label, @Usage int usage, long size | ByteBuffer data);
CompletableFuture<CompiledRenderPipeline.Pending> compilePipeline(RenderPipeline, ShaderSource, Executor);
GpuQueryPool createTimestampQueryPool(int); DeviceInfo getDeviceInfo(); ...
```
- `DeviceInfo(name, vendor, driverInfo, boolean isZZeroToOne, backendName, timestampPeriod, DeviceLimits, DeviceFeatures, Set<String> exts, HintsAndWorkarounds, DeviceType)`.
- `DeviceLimits(maxAnisotropy, minUniformOffsetAlignment, maxTextureSize, maxMemoryAllocationSize, maxMultiDrawDirectInterleavedDrawCount, maxColorAttachments, maxDrawIndirectDrawCount)`.
- `DeviceFeatures(wireframeFillMode, shaderDrawParameters, multiDrawDirectInterleaved, multiDrawDirectSeparate, multiDrawIndirect, drawIndirect, nonZeroFirstInstance, persistentMapping)`.
- `HintsAndWorkarounds(writeToBufferIsSlow, anisotropyHasKnownIssues, isExplicitDepthRequired, multiDrawIndirectHasKnownIssues)`.

### 1.3 Textures, samplers, buffers
- `GpuTexture` usage bits (`api/textures/GpuTexture.java:11-15`): `COPY_DST=1, COPY_SRC=2, TEXTURE_BINDING=4, RENDER_ATTACHMENT=8, CUBEMAP_COMPATIBLE=16`. Render targets use 15.
- `GpuFormat` (`api/GpuFormat.java`): 8/16/32-bit UNORM, SNORM, UINT, SINT and FLOAT in 1–4 components, plus `RGB10A2_UNORM/UINT`, `RG11B10_FLOAT`, `D32_FLOAT`, `D32_FLOAT_S8_UINT`, `D24_UNORM_S8_UINT`, `D16_UNORM`, `S8_UINT`. HDR targets (`RGBA16_FLOAT`, `RGBA32_FLOAT`) are used as render targets with blending by OIT, so they are fully supported.
- `GpuSampler`: `AddressMode {REPEAT, CLAMP_TO_EDGE}` (no border, no mirror), `FilterMode {NEAREST, LINEAR}`, anisotropy, maxLod. **No compare mode.** A `sampler2DShadow` cannot get hardware PCF, so do a manual compare or use `textureGather`.
- `GpuBuffer` usage bits (`api/buffers/GpuBuffer.java:10-19`): `MAP_READ=1, MAP_WRITE=2, HINT_CLIENT_STORAGE=4, COPY_DST=8, COPY_SRC=16, VERTEX=32, INDEX=64, UNIFORM=128, UNIFORM_TEXEL_BUFFER=256, INDIRECT_PARAMETERS=512`. **No storage buffer.** `map(offset,len,read,write)` returns a `GpuBufferSlice.MappedView`.
- `TransientMemory` (`encoder.transientMemory()`): per-frame CPU, staging and GPU ring allocation (`allocateGpuMapped`, `uploadStaging`, `uploadGpu`).
- `DynamicGpuData` (`RenderSystem.getDynamicUniforms()`, `net/minecraft/client/renderer/DynamicGpuData.java:59-100`): `writeTransform(modelView, colorModulator, modelOffset, textureMatrix)` gives the `DynamicTransforms` UBO slice; `writeTerrainTransform(modelView, atlasW, atlasH)` gives `TerrainUniform`; `writeChunkSections(...)` gives `ChunkSection` UBOs. The data is reset each frame (`Minecraft.java` after present).

### 1.4 `CommandEncoder` / `RenderPass`
- `CommandEncoder` (`api/commands/CommandEncoder.java`) provides `createRenderPass(RenderPassDescriptor)` plus convenience overloads `(label, colorView, Optional<Vector4fc> clear[, depthView, OptionalDouble clearDepth[, RenderArea]])`, `clearColorTexture`, `clearColorAndDepthTextures`, `clearDepthTexture`, `writeToBuffer`, `copyToBuffer`, `writeToTexture(NativeImage|ByteBuffer...)`, `copyBufferToTexture`, `copyTextureToBuffer(...callback)`, **`copyTextureToTexture`** (useful for history buffers), `createFence`, `writeTimestamp`, and `submit()`.
- `RenderPassDescriptor(label, List<Attachment<Optional<Vector4fc>>> colorAttachments, Attachment<OptionalDouble> depthAttachment, RenderArea)`. The builder offers `withColorAttachment(view[,clear])`, `withUnusedColorAttachment()`, `withDepthAttachment(view[,clear])` and `withRenderArea`. **MRT is supported** (OIT transmittance uses 2 RGBA16F targets). **Depth-only passes are supported**: zero colour attachments, as in "Water mask" and "Integrate depth".
- `RenderPass` (`api/commands/RenderPass.java`) provides `setPipeline`, `setUniform(name, view, sampler)`, `setUniform(name, GpuBuffer|GpuBufferSlice)`, `pushConstants(ByteBuffer)`, `enable/disableScissor`, `setVertexBuffer(slot≤15, slice)`, `setIndexBuffer`, `draw`, `drawIndexed`, `multiDraw*`, `drawIndexedIndirect`, `drawIndirect`, and `drawMultipleIndexed(Collection<Draw<T>>, defaultIdx, defaultIdxType, dynamicUniformNames, T arg)` (per-draw UBO rebinding). Constants: `MAX_VERTEX_BUFFERS=16`.
- **Frontend validation** (`frontend/FrontendRenderPass.java:104-126`) checks that **the pass's colour attachment count equals the pipeline's colour-target count, and that each attachment's format equals the pipeline's `ColorTargetState.format()`**. Otherwise it throws `IllegalStateException`. Uniforms are bound by **name**; names the pipeline does not declare are silently ignored (`setUniform` looks up `uniformIndices().getOrDefault(name,-1)`, `:160-171`). UBO offsets must be aligned to `minUniformOffsetAlignment` (256 on Metal).
- Viewport: GL sets `glViewport(0,0,w,h)` and the scissor equals the RenderArea (`backend/opengl/GlCommandEncoder.java:155,176`). Vulkan sets the viewport to the full output (`VulkanRenderPass.java:79-86`). **Rendering cascades into an atlas therefore needs a clip-space remap in the vertex shader, with RenderArea used for scissoring.**

### 1.5 `RenderPipeline` (`api/pipeline/RenderPipeline.java`) — the "CreateInfo"
Fields: `Identifier location; Map<ShaderType, Identifier> shaders (VERTEX ".vsh", FRAGMENT ".fsh" only); ShaderDefines shaderDefines; List<BindGroupLayout> bindGroupLayouts; @Nullable DepthStencilState; PolygonMode {FILL, WIREFRAME}; boolean cull; List<@Nullable ColorTargetState> colorTargetStates (≤8); List<@Nullable VertexFormat> vertexFormatPerBuffer (16 slots); PrimitiveTopology; int pushConstantSize; int sortKey`.
Builder rules (`build()`, `:358-424`): location, VS, FS and topology are required. **All colour targets must share one BlendFunction**. There are at most 16 vertex attributes in total and a push-constant block of at most 128 bytes. A `Snippet` is a reusable partial builder.
- `ColorTargetState(Optional<BlendFunction>, GpuFormat format, writeMask)`; `DEFAULT = (none, RGBA8_UNORM, 15)`. `MAX_COLOR_TARGETS=8`.
- `DepthStencilState(CompareOp depthTest, boolean writeDepth, float depthBiasScaleFactor, float depthBiasConstant)`. Depth bias is available, which is useful for shadow maps. `DEFAULT=(GREATER_THAN_OR_EQUAL, true)`. Stencil is not exposed.
- `BlendFunction(BlendEquation color, BlendEquation alpha)` with presets `TRANSLUCENT`, `TRANSLUCENT_PREMULTIPLIED_ALPHA`, `ADDITIVE`, `MAX`, `LIGHTNING`, `GLINT`, `OVERLAY`, `INVERT`, `ENTITY_OUTLINE_BLIT`.
- `BindGroupLayout(List<UniformDescription(name, UniformType, @Nullable GpuFormat)>)` with `UniformType {COMBINED_IMAGE_SAMPLER, UNIFORM_BUFFER, TEXEL_BUFFER}`. Names must be unique across all layouts of a pipeline. Vanilla layouts are in `net/minecraft/client/renderer/BindGroupLayouts.java` (`DynamicTransforms, Projection, ChunkSection, TerrainUniform, Fog, Globals, Lighting, Sampler0/1/2, CloudInfo+CloudFaces(R8_SINT texel), InSampler, LightmapInfo, DepthBoundsSampler, Coeff0..1`, ...).
- The backend gets `backend/api/BackendRenderPipeline.CreateInfo(name, List<Shader(name, entryPoint="main", SpvModule)>, vertexBuffers, attribBindings(bufferSlot, location, offset, format), uniforms (flattened, index = binding), pushConstantsSize, depthStencilState, polygonMode, cull, colorTargetStates, topology)`.
- Registration: `RenderPipelines.register()` builds the "required" list, which is compiled at resource reload and makes the reload fail if any fail. `registerOptional()` pipelines only produce a warning. **Any other `RenderPipeline` instance (for example one created by a mod) is compiled lazily and synchronously on first `RenderSystem.getCompiledPipeline(p)`** (`blaze3d/pipeline/PipelineCache.get`, `:27-43`). The cache is keyed by object identity and cleared on every resource reload.

### 1.6 Shader compilation pipeline (frontend)
- Source lookup: `ShaderManager.loadConfigs` (`net/minecraft/client/renderer/ShaderManager.java:107-129`) lists **all resources under `shaders/`** from the active resource packs. `*.vsh` and `*.fsh` map to `(Identifier, ShaderType)`. `shaders/include/*.glsl` become includes with id `ns:name.glsl` (`includeShaderLocationToId`, `:178`). `post_effect/*.json` become `PostChainConfig`.
- **`#moj_import` is gone.** Includes are standard `#include <minecraft:fog.glsl>`, resolved through the shaderc include callback (`GlslCompiler.processInclude`, `:37-52`). Header guards are used.
- `frontend/shaders/GlslCompiler.java:74-94` shaderc options:
  - `set_target_env(vulkan, 0x402000 = Vulkan 1.2)`, `auto_bind_uniforms=true`, `preserve_bindings=false`, `generate_debug_info`, `optimization_level=0`.
  - Macros: `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` (if `isZZeroToOne`), `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` (if the hint is set; it is true on mcopt Metal), `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` (if `shaderDrawParameters`), plus every `ShaderDefines` entry (`withShaderDefine("ALPHA_CUTOUT", 0.5f)`, `"OIT"`, `"MULTIDRAW_TERRAIN"`, ...).
  - Entry point `main`. Stages are vertex (0) and fragment (1) only.
  - **Not enabled:** `vulkan_rules_relaxed`, `auto_map_locations`, `auto_combined_image_sampler`. All `in`/`out` variables therefore need explicit `layout(location=N)`. Loose non-opaque `uniform float x;` is **illegal**, so only uniform blocks are allowed. `gl_VertexIndex`/`gl_InstanceIndex` are used instead of `gl_VertexID`. LWJGL 3.4.3 shaderc does expose `shaderc_compile_options_set_vulkan_rules_relaxed`, `_set_auto_map_locations` and `_set_forced_version_profile`, which matters for a shaderpack translator.
- `frontend/shaders/PipelineBuilder.java:63-352` performs SPIR-V reflection via `SPIRVModule` (spvc):
  - Vertex inputs are matched **by name** to `VertexFormat` elements (`Position`, `Color`, `UV0`, `UV1`, `UV2`, `Normal`, `ChunkPosition`, ...), with base-type and component checks.
  - VS outputs and FS inputs are matched by location, base type, vector length and `flat`.
  - Every descriptor must be named in the pipeline's BindGroupLayouts. Bindings are then **renumbered sequentially into set 0**.
  - Image dims: 2D and Cube map to `COMBINED_IMAGE_SAMPLER`, Buffer maps to `TEXEL_BUFFER`. **1D and 3D are rejected** (`:304-316`).
  - At most one push-constant block, no larger than the pipeline's declared size.
- Backend translation:
  - **OpenGL** (`backend/opengl/GlPipelineRecompiler.java:46-107`): SPIR-V → `spvc_context_create_compiler(..., GLSL)` with GLSL version option 330 and `es=false`. Interface variables are renamed `_vert_input_%02d`, `_interface_variable_%02d`, `_frag_output_%02d`, and UBOs `_uniform_%02d_%02d`. Then `glShaderSource/glCompileShader/glLinkProgram`. **So the GL backend never receives the original GLSL.**
  - **Vulkan**: `VulkanRenderPipeline.java:143` calls `VkShaderModuleCreateInfo.pCode(shader.module().spv())`. It uses `vkCreateGraphicsPipelines` without a VkPipelineCache, dynamic rendering (`VK_KHR_dynamic_rendering`) and push descriptors.
  - **Metal (mcopt)**: `MetalPipeline` runs SPIR-V → SPIRV-Cross MSL → `MTLLibrary`. Exceptions: `sodium:pipeline/{solid,cutout,translucent}_terrain` use hand-written `sodium_terrain.{vs,fs}.metal` (`MetalPipeline.java:87-90,158-161`). Debug flags: `-Dmcopt.metal.dumpMsl=DIR`, `-Dmcopt.metal.overrideMsl=DIR` (per-pipeline `NAME.vs.metal`/`NAME.fs.metal` override without a rebuild), and `-Dmcopt.metal.builtinMsl=false`.
- Natives present in Prism: `lwjgl-shaderc` (`libshaderc.dylib`), `lwjgl-spvc` (`libspirv-cross.dylib`), `lwjgl-vulkan` (`libMoltenVK.dylib`), `lwjgl-vma`, `lwjgl-sdl`, all at 3.4.3 (`~/Library/Application Support/PrismLauncher/libraries/org/lwjgl/`).

---

## 2. The frame

### 2.1 Top-level loop — `Minecraft.renderFrame` (`net/minecraft/client/Minecraft.java:1231-1340`)
1. Reconfigure the surface if needed. `PresentMode`: vsync gives FIFO, otherwise IMMEDIATE > MAILBOX > FIFO (`GpuSurface.PresentMode.getSupportedVsyncMode`). Then `windowSurface.acquireNextTexture()`.
2. `gameRenderer.update(deltaTracker)`: camera update and the **post-effect request list (always includes `minecraft:end_of_frame`)** (`GameRenderer.java:423-437`).
3. `gameRenderer.extract(...)` fills `GameRenderState` (window, options, lightmap, camera, `levelExtractor.extract`, GUI). This is the **extract → render split**: rendering only reads `*RenderState` objects.
4. `RenderSystem.executePendingTasks()`, then `gameRenderer.render()`.
5. `windowSurface.blitFromTexture(encoder, mainRenderTarget.getColorTextureView())` (`:1302-1308`). This is where a backend can upscale (MetalFX).
6. `encoder.submit()`, then `windowSurface.present()`, then `DynamicUniforms.reset()`, `levelRenderer.endFrame()`, and the frame limiter (only if `framerateLimit < 260`).

### 2.2 `GameRenderer.render()` (`net/minecraft/client/renderer/GameRenderer.java:466-515`)
1. Resize targets if the window size changed. Clear main colour (`clearColorOverride`) and depth to `0.0`.
2. `GlobalSettingsUniform.update(...)` writes the `Globals` UBO.
3. If a level is shown:
   1. `preparePostEffects(requested)` validates and loads the `PostChain`s.
   2. `lightmap.render(...)` updates the 16×16 RGBA8 lightmap via the `pipeline/lightmap` full-screen pass.
   3. `renderLevel()` (`:632-673`):
      - Builds the projection (bob, hurt and nausea warps) with `RenderSystem.setProjectionMatrix(levelProjectionMatrixBuffer...)`.
      - Builds the fog UBO.
      - Computes `consistentDepthRequired = !appliedPostEffects.isEmpty()` (`:668`).
      - Calls **`levelRenderer.render(resourcePool, renderOutline, cameraState, terrainFog, fogColor, renderSky, consistentDepthRequired)`**.
      - Calls `render3dHud()` for the **hand** (own projection with `hudFov`; a separate `hud3DTarget` D32 depth when post effects are active, then "Integrate 3d hud depth" into main depth), screen effects (fire, portal, pumpkin...) and the debug crosshair.
   4. `levelRenderer.blitEntityOutline()`.
   5. **`applyPostEffects()`**: each `PostChain.process(mainRenderTarget, resourcePool)` (`:497, 548-557`).
4. `fogRenderer.endFrame()`, then clear main depth, then `guiRenderer.render()` (the GUI draws into the **same main target**, `:508`).

### 2.3 `LevelRenderer.render(...)` (`net/minecraft/client/renderer/LevelRenderer.java:185-325`)
It builds a **`FrameGraphBuilder`** (`com/mojang/blaze3d/framegraph`: `addPass`, `importExternal`, `createInternal(name, RenderTargetDescriptor)`, `FramePass.reads/readsAndWrites/requires/disableCulling/executes`). Passes that do not contribute to an external resource are culled unless `disableCulling()` is called (`FrameGraphBuilder.identifyPassesToKeep`, `:79-96`). Internal targets come from `CrossFrameResourcePool(3)` and are recycled by matching descriptor.

Order:
1. `submitFeatures` (CPU): entities, block entities, block breaking, transient blocks, particles, block outline, gizmos, all into `SubmitNodeStorage`. Then `featureRenderDispatcher.prepareFrame(...)` gives `PreparedFrame` (`feature/FeatureRenderDispatcher.java`).
2. Frame graph:
   - `targets.main = importExternal("main", gameRenderer.mainRenderTarget())` (`:209`).
   - If improved transparency is on, create `depth_bounds` (RGBA32F, cleared to `-FLT_MAX`), `depth_bounds_culled` (RGBA32F), 2× `transmittance` (RGBA16F), `accumulate` (RGBA16F), `cloud_depth` and `terrain_depth` (D32).
   - `always_on_top_depth` (D32) when needed. `entity_outline` (external `TextureTarget` RGBA8).
   - **pass "clear"**: clear main colour to fog colour (alpha 0) and depth to `0.0`.
   - **pass "sky"** (`addSkyPass`, `:363-381`): `SkyRenderer.render(skyFog, SkyRenderState)` draws the sky disc, sunrise fan, sun, moon, stars and dark disc (overworld) or the end sky.
   - Chunk draws are prepared: `prepareChunkRenders` / `prepareChunkRendersIndirect(terrainMatrix=viewRotationMatrix, ...)` (`:800-955`). These are per-layer `RenderPass.Draw` lists plus a `TerrainUniform` slice.
   - **pass "main"** (`addMainPass`, `:383-471`). One render pass named "Solid" (OIT on) or "Main" (OIT off) over main colour and depth:
     - `executeSolid`: terrain `ChunkSectionLayerGroup.OPAQUE` (solid and cutout layers) with `Sampler0` = block atlas and `Sampler2` = lightmap, then `featureFrame.executeSolid` (entities, block entities, opaque particles).
     - Classic transparency (OIT off): translucent features, then translucent terrain, then translucent-after-terrain features, then **clouds** (`CloudRenderer.render`), **weather** (`WeatherEffectRenderer.render`), **world border** (`WorldBorderRenderer.render`).
     - OIT (on): `executeOit` (`:552-637`). For each `OitStage {DEPTH_BOUNDS, TRANSMITTANCE, ACCUMULATE}` it draws terrain, features, world border, weather and clouds into the OIT targets, then culls depth bounds, then does the **"OIT Composite"** full-screen pass into main (premultiplied blend, writes depth).
     - `executeOutline` (entity outline target), `executeSeeThrough`, `executeAlwaysOnTop` (gizmos, optional depth integrate).
   - The **entity_outline post chain** (`post_effect/entity_outline.json`) is added to the same frame graph when glowing entities exist.
   - `frame.execute(...)`.
3. After the graph runs: `compileSections` (schedules section meshing), `uploadTerrainBuffersToGpu`, and `sectionOcclusionGraph.update(...)`. Note that **visibility for frame N+1 is computed after drawing frame N.**

### 2.4 Render targets and formats

| Target | Owner | Format | Notes |
|---|---|---|---|
| main | `GameRenderer.mainRenderTarget` = `blaze3d/pipeline/MainTarget` | **RGBA8_UNORM + D32_FLOAT** (`MainTarget.java:18`) | window pixel size; GUI is drawn here too; blitted to the swapchain |
| hud_3d_depth | `GameRenderer.hud3DTarget` | D32_FLOAT | hand depth when post effects are active |
| entity_outline | `LevelRenderer.entityOutlineTarget` | RGBA8_UNORM | glowing outline |
| depth_bounds / depth_bounds_culled | frame-graph internal | RGBA32_FLOAT | OIT only |
| transmittance ×2 (`OIT_TRANSMITTANCE_TARGET_COUNT = 8/4`) | internal | RGBA16_FLOAT | OIT wavelet coefficients (rank 2, 8 coeffs) |
| accumulate | internal | RGBA16_FLOAT | OIT |
| cloud_depth, terrain_depth | internal | D32_FLOAT | OIT |
| always_on_top_depth | internal | D32_FLOAT | gizmos |
| post-chain internal targets | `PostChain.addToFrame` | **RGBA8_UNORM** + D32 (hard-coded, `PostChain.java:146-151`) | `persistent: true` gives a history buffer |
| lightmap | `Lightmap` | RGBA8 16×16 | rendered by `core/lightmap.fsh` from `LightmapInfo` UBO |

There are **no** separate `translucent` / `item_entity` / `particles` / `weather` / `clouds` targets any more (those were Fabulous-era). They are replaced by the OIT targets above.

Vertex formats (`blaze3d/vertex/DefaultVertexFormat.java`):
- `BLOCK` (terrain) = `Position RGB32F, Color RGBA8, UV0 RG32F, UV2 RG16_SINT`. It has **no normal**, so normals must come from derivatives or from Sodium's own format.
- `CHUNK_DATA_INSTANCED` (slot 1) = `ChunkPosition RGB32_SINT, ChunkVisibility R32F`.
- `ENTITY` additionally has `UV1`, `UV2` and `Normal RGBA8_SNORM`.

Terrain world position in shader: `pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset` (camera-relative; `core/terrain.vsh`).

---

## 3. Vanilla post-processing and resource-pack shaders

### 3.1 PostChain mechanics
- JSON schema (`PostChainConfig.java`): `{ "targets": { "<id>": { "width"?, "height"?, "persistent"?: bool, "clear_color"?: ARGB } }, "passes": [ { "vertex_shader": "ns:path", "fragment_shader": "ns:path", "inputs": [ {"sampler_name": "X", "target": "minecraft:main"|"<internal>", "use_depth_buffer"?: bool, "bilinear"?: bool} | {"sampler_name": "X", "location": "ns:tex", "width": W, "height": H, "bilinear"?} ], "output": "<target>", "uniforms": { "BlockName": [ {"name":..., "type":"float|vec2|vec3|vec4|int|...","value":[...]} ] } } ] }`.
- Each pass becomes a `RenderPipeline` from `POST_PROCESSING_SNIPPET` (Globals layout, TRIANGLES) with location `<chainId>/<i>`. It has a BindGroupLayout of `<sampler_name>Sampler` per input, plus `SamplerInfo` (UBO: `vec2 OutSize; vec2 <input>Size...`), plus each custom uniform block, and `ColorTargetState.DEFAULT` (RGBA8) (`PostChain.createPass`, `:91-132`). It draws a 3-vertex full-screen triangle (`core/screenquad.vsh` uses `gl_VertexIndex`).
- `PostPass.addToFrame` (`:74-143`) calls `RenderSystem.backupProjectionMatrix()`, sets an **orthographic** `Projection`, calls `bindDefaultUniforms` (binds `Projection`, `Fog`, `Globals`, `Lighting` **only if the pipeline declares them**, and the post snippet declares only `Globals`), binds `SamplerInfo`, the custom UBOs and the inputs, then draws 3 vertices.
- Texture inputs load `assets/<ns>/textures/effect/<path>.png`, which is useful for blue noise or LUTs.
- **Custom uniform values are constants from JSON.** The only time-varying data available to a post pass is `Globals` (`ScreenSize`, `GameTime`, `CameraBlockPos`, `CameraOffset`, `MenuBlurRadius`, `UseRgss`, `GlintAlpha`) plus texture contents.
- Validation: post effects may only reference external target `minecraft:main` (`LevelTargetBundle.MAIN_TARGETS`, used in `GameRenderer.preparePostEffects`). The entity outline chain can also use `minecraft:entity_outline`.

### 3.2 Can a pack or mod add an every-frame full-screen chain? **Yes.**
- **Resource pack, no code:** add `assets/minecraft/post_effect/end_of_frame.json`. `GameRenderer.update()` adds `END_OF_FRAME_POST_EFFECT` to the request list **every frame** (`GameRenderer.java:429`). It runs after the world, hand and entity outline, and **before the GUI**.
  - When the file is absent, `isPostEffectValid` logs "Requested post effect does not exist" once and puts it in `failedPostEffects`. That list is reset on resource reload (`onResourceManagerReload` sets `shouldResetFailedPostEffects`).
  - `ShaderManager.PostChainCache.loadPostChain` deliberately suppresses the warning for `end_of_frame` (`ShaderManager.java:345`). This is clearly an intended extension point.
  - Inputs can include `{"sampler_name":"Depth","target":"minecraft:main","use_depth_buffer":true}`, which gives the main **D32_FLOAT** reversed-Z depth, including the hand, because `consistentDepthRequired` integrates hand depth when any post effect is active.
- **Mod:** `GameRenderer.END_OF_FRAME_POST_EFFECT` is a `public static` **non-final** field (`:103`), so a mod can point it at its own chain id. Better, a mod can add its own `FramePass`es or `PostChain`s directly (see §6).
- **Everything runs through renderpearl**, so on mcopt it executes on Metal: shaderc → SPIR-V → mcopt SPIRV-Cross → MSL.

### 3.3 Core shader overrides in 26.3
- Still supported: `ShaderManager.loadConfigs` reads `shaders/**` from the resource manager, so the top resource pack wins. Overridable files are `assets/minecraft/shaders/core/*.vsh|*.fsh`, `shaders/include/*.glsl` and `shaders/post/*`. Sodium's are `assets/sodium/shaders/blocks/block_layer_opaque.{vsh,fsh}` plus `include/chunk_vertex.glsl`, `chunk_material.glsl`, `fog.glsl`, `globals.glsl`.
- Constraints: Vulkan-GLSL rules (§1.6). Interface names and locations must still match the pipeline's `VertexFormat` and BindGroupLayouts. You **cannot add new uniforms or samplers** without code, because every descriptor must exist in the pipeline's BindGroupLayout. You also cannot add colour outputs, because the colour target count is fixed by the pipeline. Shader defines come from Java.
- **On mcopt Metal:** overrides flow through SPIR-V → MSL **for all vanilla pipelines**. They are **ignored for Sodium's three terrain pipelines**, because of mcopt's built-in MSL, unless `-Dmcopt.metal.builtinMsl=false` is set or `-Dmcopt.metal.overrideMsl` is used.
- The vanilla "core-shader shaderpack" trick still works: core shaders encode data such as the projection matrix (`ProjMat`), `ModelViewMat`, or a sun direction derived from sky shader geometry into reserved pixels of the main target, and post shaders decode them. It is fragile but needs no code.

---

## 4. Per-frame data available for shading

| Data | Where (Java) | GPU-visible in vanilla? |
|---|---|---|
| `Globals` UBO: `ivec3 CameraBlockPos; float GlintAlpha; vec3 CameraOffset; float GameTime; vec2 ScreenSize; int MenuBlurRadius; int UseRgss` | `GlobalSettingsUniform.update` (`:16-43`), `include/globals.glsl` | yes, every pipeline that declares GLOBALS (post too) |
| `GameTime` = `((level.getGameTime() % 24000) + partialTick) / 24000` | `GlobalSettingsUniform.java:34` | yes, but this is **total game ticks, not day time** (it does not follow `/time set` or `doDaylightCycle`) |
| Projection matrix (`Projection { mat4 ProjMat; }`) | `RenderSystem.setProjectionMatrix`; world projection built in `GameRenderer.renderLevel` from `CameraRenderState.projectionMatrix` (+bob/nausea) | yes for world pipelines; **replaced by ortho in post passes** |
| View rotation / model-view | `CameraRenderState.viewRotationMatrix` (rotation only, camera-relative world); `RenderSystem.getModelViewStack()`; terrain `TerrainUniform { mat4 ModelViewMat; ivec2 TextureSize; }`; others `DynamicTransforms { mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset; }` | yes, per pipeline |
| Camera position | `CameraRenderState.pos` (double); GPU gets `CameraBlockPos` + `CameraOffset` | yes |
| Previous-frame matrices, jitter, motion vectors | **none in vanilla** (no prev-matrix state anywhere in `Camera`/`GameRenderer`) | no; must be tracked by the mod (mcopt's MetalFX code already does its own) |
| Sun, moon, star angle; rain brightness; star brightness; sky colour; sunrise colour; moon phase; end-flash | `SkyRenderState` filled in `SkyRenderer.extractRenderState` (`:104-126`) from `EnvironmentAttributes.SUN_ANGLE` etc. (radians) | only implicitly, through sky geometry. **Sun direction** = `RotY(-90°)·RotX(sunAngle)·(0,1,0)` (sun quad at height 100; `SkyRenderer.renderSunMoonAndStars` `:185-212`) |
| Fog: `Fog { vec4 FogColor; float FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogSkyEnd, FogCloudsEnd; }` | `FogRenderer` / `FogData` (`CameraRenderState.fogData`) | yes |
| Lightmap: 16×16 RGBA8 (U = block light, V = sky light), built from `LightmapRenderState(skyFactor, blockFactor, blockLightTint, skyLightColor, ambientColor, brightness, darkness, nightVision, bossDarkening)` | `Lightmap.java`, `LightmapRenderStateExtractor` | yes, as `Sampler2`; per-vertex `UV2` = (block, sky) light ×16 |
| Entity light directions `Lighting { vec3 Light0_Direction; vec3 Light1_Direction; }` | `Lighting.setupFor(Entry.LEVEL/ITEMS_3D)` | yes; these are fixed directions, **not the sun** |
| Weather | `WeatherRenderState.intensity/radius`, rain and snow columns; `SkyRenderState.rainBrightness` | only indirectly |
| Render distance, FOV, cloud status, improved transparency, etc. | `OptionsRenderState` | no |
| Fluid in camera | `CameraRenderState.fogType` | no (only through fog) |
| Depth far | `CameraRenderState.depthFar = max(renderDistance*4, cloudRange*16)` (`Camera.java:95`); near = 0.05 | no |

Everything a mod needs is on the CPU side in `GameRenderer.gameRenderState().levelRenderState` (`cameraRenderState`, `skyRenderState`, `weatherRenderState`, `gameTime`, `worldPartialTicks`), so a mod can pack its own UBO each frame.

---

## 5. Vulkan backend

- Selection (`net/minecraft/client/PreferredGraphicsApi.java:33-37`): `VULKAN` gives `{vulkan, gl}`, anything else gives `{gl, vulkan}`. The UI string is "Prefer Vulkan (Experimental) — Might reduce performance or cause instability". `DEFAULT` also calls `VulkanBackend.checkBackendAvailable()`, but only for telemetry. Launch arg: `--graphicsBackend vulkan`. mcopt's `PreferredGraphicsApiMixin` prepends Metal.
- Requirements (`backend/vulkan/VulkanFeatureSets.java:24-41`):
  - Extensions: `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`, `VK_KHR_synchronization2`, `VK_EXT_vertex_attribute_divisor`, `VK_KHR_swapchain`.
  - Features: `multiDrawIndirect`, `drawIndirectFirstInstance`, `samplerAnisotropy`, `shaderDrawParameters`, `timelineSemaphore`, `hostQueryReset`, `synchronization2`, `dynamicRendering`, `vertexAttributeInstanceRateDivisor`.
  - `VK_KHR_portability_subset` (`triangleFans`) is required if present. On macOS it uses `VK_KHR_portability_enumeration` (`VulkanInstance.java:67-90`).
  - Optional: `VK_EXT_multi_draw`, calibrated timestamps, wireframe, AMD/NV checkpoints.
- On this Mac: LWJGL ships **MoltenVK** inside `lwjgl-vulkan-natives-macos-arm64-3.4.3.jar` (`macos/arm64/org/lwjgl/vulkan/libMoltenVK.dylib`, 3.8 MB), so "Prefer Vulkan" should create a device through MoltenVK. This was not verified at runtime because we must not launch the game.
- Architecture: VMA allocator, timeline-semaphore frame pacing, `VulkanTransientMemory`, push descriptors per draw (`VulkanRenderPass.java:298`), no `VkPipelineCache` (`vkCreateGraphicsPipelines(device, 0L, ...)`). The SPIR-V from the shared frontend is used **directly** (`VulkanRenderPipeline.java:143`). That is the same contract mcopt uses for Metal, except mcopt must translate SPIR-V to MSL itself, which MoltenVK also does internally. Both use `isZZeroToOne=true`.
- Maturity is "experimental but complete". It implements the full `GpuDeviceBackend` with the same feature level as GL. Iris does not support it (Iris uses 79 classes touching `org.lwjgl.opengl` and 91 touching renderpearl GL internals).

---

## 6. Feasibility: adding shader passes through renderpearl (works on Metal, Vulkan and GL)

General recipe for a Fabric mod:
- Define `RenderPipeline`s with `RenderPipeline.builder(...)`, shaders in `assets/<modid>/shaders/...`, and its own `BindGroupLayout`s (for example `"ShaderParams"` as a UBO and `"ShadowMap"` as a combined image sampler).
- Compile lazily with `RenderSystem.getCompiledPipeline(p)`, or pre-warm by mixing into `RenderPipelines.requiredPipelines()/optionalPipelines()` or calling `getCompiledPipeline` after reload.
- Allocate textures with `RenderSystem.getDevice().createTexture(...)` or `TextureTarget`, or frame-graph `createInternal(RenderTargetDescriptor)`.
- Write per-frame UBOs from `levelRenderState` data via `encoder.writeToBuffer` / `MappableRingBuffer` (the pattern used by `PostPass.infoUbo` and `Lightmap.ubo`).
- Hook with Mixin into `LevelRenderer.render` (frame graph) or `GameRenderer.render` / `renderLevel`.

### (a) Sun-view shadow depth pass for terrain
- API support: a depth-only `RenderPassDescriptor` (no colour), a `D32_FLOAT` texture with usage `RENDER_ATTACHMENT|TEXTURE_BINDING`, `DepthStencilState(GREATER_THAN_OR_EQUAL or LESS, true, slopeBias, constBias)`, and `cull` on or off. All are supported.
- **Vanilla terrain:** call `levelRenderer.prepareChunkRenders(shadowViewMatrix, false)` to get a `ChunkSectionsToRender` with a `TerrainUniform` built from your matrix (`LevelRenderer.java:800-851`). Then `renderLayers` with an override pipeline: `ChunkSectionsToRender.renderGroup` only accepts the wireframe override, so a mixin or accessor is needed to pass your shadow pipeline. Bind your own `Projection` UBO (sun ortho).
  - **Problem 1:** the draw list is the **camera** visible set (`visibleSections`, frustum and occlusion culled). Casters outside the view frustum are missing, so you need your own section list (iterate `ViewArea` sections in the shadow frustum, as Iris does).
  - **Problem 2:** pipeline validation requires the attachment count and formats to equal the pipeline's colour targets. A depth-only shadow pipeline must declare **zero** colour targets, so vanilla terrain pipelines cannot be reused as-is. Write `shadow_terrain` pipelines with the same `DefaultVertexFormat.BLOCK` binding.
- **With Sodium (the user's setup):** Sodium 0.9 replaces chunk rendering (`sodium:pipeline/*_terrain`, its own vertex format and region buffers). The shadow pass must reuse Sodium's `RenderSectionManager` / `ChunkRenderer` with a sun-view camera, culled by the shadow frustum. Iris does exactly this, via Sodium hooks. On mcopt Metal, Sodium terrain uses hand-written MSL, so a shadow variant must either be added to mcopt's MSL set or go through SPIR-V with `builtinMsl=false`.
- **Entities:** `FeatureRenderDispatcher.PreparedFrame.executeSolid(renderPass)` re-issues entity draws, but with their own pipelines (RGBA8 colour target). That needs either a dummy RGBA8 colour attachment in the shadow pass plus a `Projection`/modelview override, or per-pipeline shadow twins. Terrain-only shadows are the pragmatic first step.
- Cascades: there are no layered render targets or per-layer views, so use **separate D32 textures per cascade** or one atlas with clip-space remap in the VS plus `RenderArea` scissor. Sampling has no compare samplers, so do manual compare or PCF with `texture()` / `textureGather()` on `sampler2D`.

### (b) Deferred lighting pass
- **MRT G-buffer inside vanilla's main pass is costly.** The "Solid/Main" pass (`addMainPass`, `:440-452`) has one colour attachment. Adding attachments would require **every** pipeline drawn in that pass (terrain, all entity, block-entity and particle pipelines, beacon, glint, ...) to declare the same number and formats of colour targets (`FrontendRenderPass.setPipeline` check), and the blend function must be identical across targets. That means rewriting ~60 pipelines.
- **Cheaper, robust alternative: depth-reconstructed deferred.** After the "main" pass, read main depth (D32) and colour. Reconstruct view-space position with your own inverse projection UBO and normals from depth derivatives, or from a small dedicated normal pre-pass for terrain only. Then apply sun shadow (a), SSAO, sky light and fog in a full-screen pass. An emissive or material-ID channel could come from a terrain-only G-buffer pass that re-draws Sodium terrain into an `RGBA8`/`RG16` target with a depth-equal test.
- The main target is LDR RGBA8. For HDR lighting:
  - **Option 1:** render the lit result into a mod-owned `RGBA16_FLOAT` target, then tonemap back into main.
  - **Option 2:** swap `MainTarget` to RGBA16F. That requires patching `ColorTargetState.DEFAULT` users (all world **and GUI** pipelines draw into main), and `blitFromTexture` must accept it. Option 1 is far less invasive.

### (c) Full-screen composite pass
- **Easiest:** a `PostChain` (`end_of_frame.json` in a built-in resource pack shipped by the mod, or `ShaderManager.getPostChain(id, MAIN_TARGETS).addToFrame(...)`). This is limited to RGBA8 intermediates, constant uniforms and an ortho projection.
- **Flexible:** a mod `FramePass` inside `LevelRenderer.render`'s frame graph, injected after `addMainPass` and before `frame.execute` (`:273-282`):
  ```java
  FramePass p = frame.addPass("mod_composite");
  targets.main = p.readsAndWrites(targets.main);  // keeps it from being culled
  p.executes(() -> {
      try (RenderPass rp = RenderSystem.getDevice().createCommandEncoder()
              .createRenderPass(() -> "Composite", hdrView, Optional.empty())) {
          rp.setPipeline(RenderSystem.getCompiledPipeline(MY_COMPOSITE));
          rp.setUniform("SceneColor", main.getColorTextureView(), sampler);
          rp.setUniform("SceneDepth", main.getDepthTextureView(), nearest);
          rp.setUniform("ShaderParams", myUboSlice);   // matrices, sun dir, time, weather
          rp.draw(3, 1, 0, 0);
      }
  });
  ```
  Internal targets (`frame.createInternal(name, new RenderTargetDescriptor(w, h, new TextureProperties(clear, GpuFormat.RGBA16_FLOAT), null))`) are pooled. Use `copyTextureToTexture` or persistent `TextureTarget`s for TAA or history.
- Ordering note: post effects and the hand are drawn after `LevelRenderer.render`. Compositing inside the level frame graph happens before the hand (correct for world lighting). A final tonemap or bloom belongs after the hand, before the GUI (the `applyPostEffects` slot).

### Hard limits to design around
- No compute or storage resources in renderpearl. Effects such as histogram auto-exposure or clustered lighting must be fragment-shader based (mip-chain reductions via render passes), or done natively in mcopt's Metal code outside renderpearl, which would be Metal-only.
- At most 8 colour targets, one blend function per pipeline, at most 16 vertex attributes, at most 128 B of push constants, 2D and Cube textures only.
- Shader dialect: Vulkan GLSL with explicit locations and only block uniforms. Iris/OptiFine shaderpacks (compatibility-profile GLSL with loose uniforms, `gl_FragData`, `texture2D`, built-ins like `gl_ModelViewMatrix`) **will not compile as-is through Mojang's shaderc options**. A translator would need preprocessing (Iris already transforms packs with glsl-transformer) plus shaderc with `vulkan_rules_relaxed`/`auto_map_locations`, or a custom path straight to SPIR-V/MSL in mcopt.
- Shader compile cost: shaderc runs at O0 with debug info on every reload and launch, with no SPIR-V disk cache in vanilla. mcopt relies on Metal's on-disk cache (≈0.7 s cold, per mcopt's comment).
