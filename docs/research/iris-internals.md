# Iris internals on 26.3 and the cost of running Iris shader packs on Metal

Report name: `iris-internals`. Research only; nothing was installed into the user's instances and Minecraft/Prism were not launched.

Paths used below:
- `IRIS` = `work/src/iris` (shallow clone of `github.com/IrisShaders/Iris`, branch `26.3`, HEAD `770155c`, 2026-10-04, 60 commits of history fetched)
- `SPIKE` = `work/spike/iris-msl` (translation spike, reusable)
- `PACK` = `work/packs/ComplementaryReimagined_r5.9.3/shaders` (unzipped pack, read-only use)
- `DECOMP` = `work/decomp/mc-26.3` (another agent's Vineflower output of the 26.3 client)
- `MCOPT` = `mcopt/` (read-only)

(all relative to `.../scratch-2026-10-06-836a24/`)

---

## 0. TL;DR (decision-relevant first)

1. **Iris does not work off the GL backend, and it fails badly with mcopt Metal.** `IrisMixinPlugin` decides at mixin time by reading `options.txt` (`preferredGraphicsBackend` containing "vulkan"). It never sees mcopt's Metal backend, which `PreferredGraphicsApiMixin` puts first. With `"default"` (both of the user's instances use it), all 180 mixins gated by `IrisMixinPlugin` apply, and Iris issues raw GL calls during `RenderSystem.initRenderer`. With no GL context, that aborts the JVM. That is the same crash signature as upstream issue #3357 (`--graphicsBackend vulkan`, Apple M3: `IrisRenderSystem.<clinit> -> SamplerLimits -> glGetInteger`). That is why the "Shaders" instance needs `-Dmcopt.metal=false`.
2. **Iris's execution layer is deeply GL.** 100 of 638 `common` source files contain GL call sites (671 call sites in total). 87 files import `org.lwjgl.opengl`. 95 import `renderpearl.backend.opengl`, 73 of them for `GlStateManager`. 17 mixins target GL-backend classes directly. Render targets are raw GL texture names (`GlStateManager._genTexture`). Programs are subclasses of the GL backend's `GlProgram`. There are 106 `getGlId()` texture-ID call sites. The renderpearl API itself cannot express several things Iris needs: compute, SSBOs, image load/store, 3D textures, compare samplers, mipmap generation and geometry/tessellation stages.
3. **The shader side of a Metal port is solved.** I ran the real Iris 1.11.7 front end headlessly: include expansion, JCPP and `TransformPatcher`/glsl-transformer, with a stub `Iris` class and a fake `GpuDevice`. The output then went through LWJGL shaderc (GLSL to SPIR-V, Vulkan 1.2 target, **`vulkan_rules_relaxed` + `auto_map_locations`**) and LWJGL SPIRV-Cross (SPIR-V to MSL 3.1, macOS).
   - **All 56 shader stages (28 programs) of Complementary Reimagined r5.9.3, default settings, translate to MSL that the macOS Metal runtime compiler accepts** (`MTLDevice newLibraryWithSource`).
   - The "max features" variant also translates: COLORED_LIGHTING=128, WORLD_SPACE_REFLECTIONS=1, RAIN_PUDDLES=1. That covers compute with shared memory and `barrier()`, `imageStore` to 3D images from a *vertex* shader, std430 SSBOs, and 18 samplers. It needs two fixes: force `#version 460 core`, and use SPIRV-Cross **argument buffers**.
   - Minecraft's own shaderc options (no relaxed rules) fail on every stage ("non-opaque uniforms outside a block", "SPIR-V requires location").
4. **Doing the translation in Metal will not by itself make per-pixel shading cheaper.** On Apple Silicon, macOS OpenGL is already layered on Metal. A native path removes GL driver/CPU overhead and unlocks Metal-only features: MetalFX upscaling, argument buffers, and compute/images that macOS GL 4.1 lacks. A Metal-native path could therefore *turn on* Complementary features that the pack disables under `MC_OS_MAC`, such as colored lighting and improved rain. But full 3024x1964 is 2.87x the pixels of 1080p. If the GL path is GPU-bound at about 1080p today, full-Retina Complementary at 120 fps is not realistic without **rendering lower and upscaling with MetalFX**. I could not measure fps, because the game could not be launched.
5. **Licensing:**
   - Iris is LGPL-3.0-only.
   - Its glsl-transformer dependency is **AGPL-3.0**. Iris's own `LICENSE-DEPENDENCIES` flags it.
   - Complementary's license (v1.7) says modifying its code is *not* "Normal Usage". Redistributing a modified or translated copy is only allowed under strict rename/"look different" rules.
   - Runtime, on-device translation that is never redistributed is the same thing Iris already does. Shipping pre-translated Complementary MSL is not OK. (This is not legal advice.)
6. **Prior art and signals:**
   - The Iris developers are not porting Iris to Vulkan. They are building a separate, closed, Slang + TypeScript loader called **"Aperture"** (issue #3171, #3279, `IrisShaders/Aperture-Example-Pack`, docs `aperture/migration.mdx`). Slang has a native Metal backend, but Aperture packs are not OptiFine packs.
   - **mcopt already contains seams for an unreleased Metal "shaderpack runtime"**: `MetalHooks.Redirect`/`PassRedirector`, `PassDelegate`, and `MetalBridge`, which says "the shaderpack runtime binds its own functions (mcpack.m)". The pack package and `mcpack.m` are absent from the repo and from the release jar.
   - Other small projects, Cinder (Apache-2.0) and Fornax (MIT, Vulkan + MetalFX + Metal RT interop on 26.2), exist but are not OptiFine-compatible.

---

## 1. Source and version facts

- Iris repo branches include `26.1`, `26.2` and `26.3`, with no 1.11.x tags. `build.gradle.kts` on `26.3` HEAD says `MOD_VERSION = "1.11.6"`, `MINECRAFT_VERSION = "26.3"`, `SODIUM_DEPENDENCY_FABRIC = sodium-fabric:0.9.2+mc26.3`, Java toolchain 25, loom 1.17.20. The installed jar's `fabric.mod.json` says `1.11.7+mc26.3` (Modrinth: 1.11.7 published 2026-09-30, "Fixes a lot of 26.3 bugs").
- Class-name diff of the jar against the source: only `mixin/texunits/MixinGlStateManager` is in the source but not the jar. Everything else matches (plus vendored `de.odysseus.ithaka.digraph` and the `api` module). So **source HEAD ≈ 1.11.7**.
- Size: 768 Java files / 64,105 lines across all modules; `common/src/main/java` is 638 files / 56,726 lines.

| package | files | lines |
|---|---|---|
| pipeline (+transform) | 53 | 10,096 |
| shaderpack (parsing, options, includes, idmaps) | 86 | 9,489 |
| mixin | 160 | 6,950 |
| gl | 84 | 6,129 |
| gui | 30 | 4,125 |
| uniforms | 36 | 3,506 |
| compat (sodium, dh, …) | 37 | 3,389 |
| shadows | 16 | 2,877 |
| pbr | 28 | 1,863 |
| vertices | 27 | 1,809 |
| targets | 13 | 1,222 |

Bundled jars (`META-INF/jars`): `glsl-transformer-3.0.0-pre3`, `antlr4-runtime-4.13.1`, `jcpp-1.4.14`, fabric-api-base, fabric-key-mapping-api-v1.

---

## 2. Architecture on 26.3: how Iris plugs into renderpearl

### 2.1 Three integration layers

| layer | what Iris uses | evidence |
|---|---|---|
| renderpearl **API/frontend** (backend-agnostic) | `RenderPipeline`, `CompiledRenderPipeline`, `FrontendRenderPipeline`, `FrontendRenderPass.setUniform`, `GpuBuffer`, `GpuTexture`/`GpuTextureView`, `RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(..)`, `BindGroupLayout` | 149 files import `com.mojang.renderpearl.*`; e.g. `pathways/FullScreenQuadRenderer.java` builds its quad as a `GpuBuffer` |
| renderpearl **GL backend internals** | `GlStateManager` (73 files), `GlProgram` (10), `GlTexture` (9), `GlDevice` (9), `GlRenderPass` (6), `GlConst` (5), `GlRenderPipeline` (4), `VertexArray`, `Uniform`, `GlCommandEncoder`, `GlBuffer`, `GlDebug`, `GlBackend`, `FrameBufferAttachment` | 95 files import `com.mojang.renderpearl.backend.opengl.*` |
| **raw LWJGL GL** | GL11C…GL46C, plus ARB/EXT/KHR/AMD/NVX/ATI extensions | 87 files import `org.lwjgl.opengl.*` (GL43C ×26, GL20C ×23, GL46C ×21, GL30C ×21, GL11C ×13, …) |

There is also one Vulkan-backend import: `pbr/texture/PBRAtlasTexture.java` imports `com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture`.

### 2.2 The key seam: how Iris swaps every game shader

`mixin/MixinShaderManager_Overrides.java` (a mixin on `RenderSystem`, lines 68-110) injects at RETURN of `RenderSystem.getCompiledPipelineNullable(RenderPipeline)`:

```java
ShaderKey shaderKey = IrisPipelines.getPipeline(irisPipeline, newProgram);
GlProgram program = shaderKey == null ? null : irisPipeline.getShaderMap().getShader(shaderKey);
...
VertexArray vertexArray = VertexArray.createSource(GL.getCapabilities(), new HashSet<>()).apply(program, createInfo);
FrontendRenderPipeline replacement = new FrontendRenderPipeline(old2.name(), new GlRenderPipeline(device, createInfo, program, vertexArray), ...);
cir.setReturnValue(replacement);
```

- Iris keeps the frontend pipeline's **name**, uniform indices and colour-target states, but substitutes a `GlRenderPipeline` built around its own `GlProgram` subclass.
- Attribute locations come from `GL33C.glGetAttribLocation(program, "iris_"+element)`.
- `IrisPipelines` maps 62 vanilla/Sodium `RenderPipeline`s for the main pass and 45 for the shadow pass onto 90 `ShaderKey`s. Those resolve to 43 OptiFine `ProgramId`s (gbuffers_*/shadow_*), with fallbacks.
- On Metal, the equivalent seam is the same mixin target. You would return a `FrontendRenderPipeline` wrapping an **mcopt `MetalPipeline`** built from the Iris-transformed shader.

`pipeline/programs/ExtendedShader extends GlProgram` (`FallbackShader` too). Its `iris$setupState()` (lines ~205-262) does the following per bind:
- calls `GlStateManager._glUseProgram`;
- uploads `iris_ModelViewMatInverse`/`iris_NormalMat`/`iris_ProjMatInverse` via `IrisRenderSystem.uniformMatrix*fv`;
- binds samplers/images, pushes custom uniforms, applies blend overrides;
- binds the Iris framebuffer: `writingToBeforeTranslucent.bind()` / `writingToAfterTranslucent.bind()`.

That last step is how gbuffer draws get redirected into colortex targets. It is pure GL FBO state, and the engine's render pass never knows about it.

### 2.3 Other GL-backend mixins that carry semantics

- `mixin/MixinGlCommandEncoder.java`:
  - redirects `_viewport`, `_scissorBox`, `_glBindFramebuffer` and `glDrawBuffers` inside `createRenderPass`, to keep the shadow viewport and FBO (lines 64-111);
  - cancels `setupDraw` for Iris custom passes;
  - converts `GL_TRIANGLES` to `GL_PATCHES` for tessellation (lines 168-182).
- `mixin/MixinGlRenderPipeline.java` disables cull in shadows, flips depth funcs for reverse-Z in the shadow pass, and does per-buffer blend.
- `GlStateManager` mixins: `MixinGlStateManager_BlendOverride`, `_DepthColorOverride`, `_FramebufferBinding`, `statelisteners/*` and `texture/*`. These implement OptiFine-style "lock blend/colour mask/depth" semantics on top of the GL state machine.
- `mixin/MixinGlRenderPass.java` actually targets `FrontendRenderPass.setUniform`, to capture `Sampler0`/`u_BlockTex` as the albedo atlas. That one is backend-agnostic.

### 2.4 IrisMixinPlugin and Vulkan, and what that means for mcopt

`mixin/IrisMixinPlugin.java`:
- Its static block reads `<gameDir>/options.txt` and sets `usingVulkan = options.get("preferredGraphicsBackend").toLowerCase().contains("vulkan")` (lines 20-59).
- `shouldApplyMixin`: `if (mixinClassName.contains("VKOnly")) return usingVulkan; return !usingVulkan;` (lines 71-74).

The plugin is used by `mixins.iris.json` (145 entries), `mixins.iris.compat.sodium.json` (21), `vertexformat` (7), `fantastic` (7) and `bettermipmaps`. So when "vulkan" is chosen, **every** Iris mixin is skipped except `VKOnly_InitKeys`, which registers the I key. The I key then opens `ShaderPackScreenPlaceholder`.

`compat/sodium/config/ShaderPackScreenPlaceholder.java` shows "Iris cannot run when using Vulkan. Would you like to switch to OpenGL? This will close your game." Its "Switch" button sets `preferredGraphicsBackend = OPENGL`, saves, halts the integrated server, disconnects and calls `Minecraft.stop()`.

`compat/sodium/config/IrisConfig.java` (lines 40-120):
- with Vulkan, the Sodium options page only gets the placeholder;
- on GL, it overlays Sodium's `general.graphics_api` option with a confirm screen: "Setting the backend to Vulkan will remove your ability to use shaders."

Consequences:
- **Iris never checks the backend that is actually running.**
  - `--graphicsBackend vulkan` gives a crash. Upstream #3357 (open, macOS Apple M3, Iris 1.11.6): `GL11C.nglGetIntegerv` with no context, at `SamplerLimits.<init>` ← `IrisRenderSystem.<clinit>` ← `Iris.setDebug` ← `Iris.duringRenderSystemInit` ← `RenderSystem.initRenderer`. Same signature: #3339 and #3372.
  - Both user instances have `preferredGraphicsBackend:"default"` in options.txt (checked read-only). mcopt's `PreferredGraphicsApiMixin` returns `[Metal, GL, Vulkan]` unless `-Dmcopt.metal=false`. So Iris + mcopt-Metal hits the same path: GL calls with no GL context.
  - mcopt has no Iris detection (it only special-cases Distant Horizons in `Profile.java:49`). Hence the user's `-Dmcopt.metal=false` in the "mcopt Shaders 26.3" instance.
- Any Metal support for Iris packs needs a patched or forked Iris, or a separate runtime. Iris cannot be "configured" into it.

### 2.5 Sodium integration (and the conflict with mcopt's terrain fast path)

- `compat/sodium/mixin/MixinChunkMeshFormats` overwrites `ChunkMeshFormats.getCurrent()` to return `WorldRenderingSettings.INSTANCE.getVertexFormat()`. When a pack is active, that is Iris's `XHFPModelVertexType`: Sodium's compact format extended with block id (`mc_Entity`), normal/tangent, `mc_midTexCoord` and `at_midBlock`.
- `MixinShaderChunkRenderer` forces the shader lookup to use `COMPACT`. `SodiumTransformer` rewrites pack terrain shaders to Sodium 0.9's interface:
  - `u_ModelViewMatrix` and `iris_undoRevZ(u_ProjectionMatrix)`;
  - `layout(std140) uniform iris_SodiumPushConstants { vec3 iris_RegionOffset; int iris_CurrentTime; uint iris_RegionID; }`;
  - fade via `texelFetch(u_SectionTimeInfo, …)` (a texel buffer).
- mcopt conflict 1: `MetalPipeline.java` lines ~154-163 substitute **hand-written MSL** by pipeline *name* (`sodium:pipeline/solid_terrain` / `cutout_terrain` / `translucent_terrain`). Iris keeps those names on its replacement pipelines, so mcopt would silently replace the pack's gbuffers_terrain.
- mcopt conflict 2: `MetalTerrain` hard-codes `VERTEX_BYTES = 20` (Sodium compact) for its pulled-vertex/occlusion path. It already says "shader packs: never" in its javadoc.
- So with a pack active, **mcopt's terrain fast path must be bypassed or taught the Iris vertex format**.

---

## 3. The pipeline Iris implements (OptiFine/Iris spec) and what it needs from a GPU API

### 3.1 Frame structure (`pipeline/IrisRenderingPipeline.java`)

- **`beginLevelRendering()`** (line 874):
  - clears custom images (`ImageClearPass`);
  - clears shadow depth via the renderpearl `CommandEncoder.clearDepthTexture`;
  - runs **shadowcomp compute programs** (`computeProgram.dispatch(shadowMapResolution, …)`);
  - clears shadow colour targets (`ClearPassCreator`);
  - runs `updateNotifier.onNewFrame()` and `customUniforms.update()`;
  - resizes render targets to the main target (`renderTargets.resizeIfNeeded`), recreating clear passes and rebuilding the colour-space converter.
- **Shadow pass** (`shadows/ShadowRenderer`, line 1026 `renderShadows`): re-renders terrain (Sodium, from the light frustum, `MixinRenderSectionManagerShadow`), entities and block entities with `shadow*` programs into `shadowtex0/1` + `shadowcolor0..N`. Then `ShadowCompositeRenderer` runs `shadowcomp*` passes.
- **gbuffers**: every vanilla/Sodium draw is redirected (§2.2) to the matching `gbuffers_*` program writing up to 8 colortex targets (`RENDERTARGETS`/`DRAWBUFFERS`). `WorldRenderingPhase` sets `renderStage`.
- **`beginTranslucents()`** (line 1058):
  - `renderTargets.copyPreTranslucentDepth()` → `depthtex1`. `DepthCopyStrategy` uses `glCopyImageSubData`, a blit-FBO or `glCopyTexSubImage2D`.
  - `deferredRenderer.renderAll()` (deferred passes, including compute).
- **translucent gbuffers** (water, etc.), hand (`pathways/HandRenderer`, separate depth).
- **`finalizeLevelRendering()`** (line 1086): `compositeRenderer.renderAll()` then `finalPassRenderer.renderFinalPass()`. `finalizeGameRendering()` runs the colour-space conversion (compute).
- **`CompositeRenderer.renderAll()`** (lines 271-361), per pass:
  - optional compute dispatches + `glMemoryBarrier(IMAGE|TEXTURE_FETCH|SSBO)`;
  - mipmap generation for `mipmappedBuffers`;
  - viewport scale;
  - full-screen quad drawn with `GlStateManager._drawElements` after binding `FullScreenQuadRenderer` through the GL backend's `VertexArray`;
  - then a loop that unbinds every texture unit.

### 3.2 Resources

- **Render targets**:
  - `colortex0..` up to `IrisLimits.MAX_COLOR_BUFFERS = 32` (aliases gcolor/gdepth/gnormal/composite/gaux1-4).
  - Each `targets/RenderTarget` owns a **main and an alt raw GL texture** (`GlStateManager._genTexture()`, lines 44-45). Formats come from `const int colortexNFormat`.
  - Complementary uses R11F_G11F_B10F, RGB8_SNORM, RGB16F, RGBA8, RGBA8_SNORM, RGB8, RGBA16F and R8. Metal has no 3-channel formats, so map RGB8, RGB16F and RGB8_SNORM to RGBA.
- **Buffer flipping**: `targets/BufferFlipper` keeps the set of flipped targets. Each pass writes the alt/main variant and reads the other. Which ones flip is computed from each program's written targets; that is backend-agnostic logic.
- **Depth**: `depthtex0` (main depth), `depthtex1` (pre-translucent copy), `depthtex2` (pre-hand), `shadowtex0/1`. `sampler2DShadow` is hardware compare (`shadowHardwareFiltering = true` in Complementary).
- **Clears**: `ClearPassCreator` (per-target clear colours, `colortexNClear`).
- **Mipmaps**: `colortexNMipmapEnabled` (Complementary: 0, 3, 5) generates mips before a pass that samples them.
- **Custom textures**: `CustomTextureManager` handles noisetex, custom PNG/raw textures and LUTs.
- **Custom images**: `image.<name> = <sampler> <format> …` in shaders.properties gives 2D/3D storage images (`gl/image/GlImage`, `glBindImageTexture`).
- **SSBOs**: `bufferObject.N = size` (`gl/buffer/ShaderStorageBuffer`, std430).
- **Compute**: `ComputeProgram` handles `.csh` per stage (setup, begin, shadowcomp, prepare, deferred, composite), `glDispatchCompute[Indirect]`.
- **Uniforms**:
  - CPU-side providers in `uniforms/*` (36 files, ~3,000 lines): camera, celestial, world time, fog, biome, idmaps, matrices, viewport, system time, Iris-exclusive.
  - There are roughly 149 `uniform*("name")` registrations plus `custom/` uniforms from shaders.properties (expression language).
  - Upload is `IrisRenderSystem.uniform*` at `glGetUniformLocation` locations (`gl/program/ProgramUniforms.Builder.location`, line 293; e.g. `gl/uniform/FloatUniform.update`).
  - **This is a clean seam**: replace location with a byte offset in a uniform block.
  - Complementary programs declare about 85-115 loose uniforms each, about 1.1-1.4 KB in std140, so `setFragmentBytes`-sized.

### 3.3 GLSL that packs rely on and what Iris's transformer does

Complementary's `.vsh`/`.fsh` start with `#version 130`, which is the compatibility profile. Its compute file starts with `#version 430 compatibility`. `TransformPatcher` handles the version:
- it upgrades `version < 330` to `#version 330 core` (lines 151-183);
- core/compat programs go to `CompositeTransformer` / `SodiumTransformer` / `VanillaTransformer`, or the `*CoreTransformer`s;
- then `GlintTransformer`, `TextureTransformer`, `CommonTransformer.transformDepth` and `CompatibilityTransformer.transformEach/Grouped`.

What those passes rewrite, mostly in `CommonTransformer.transform` (lines 319-470) and `SodiumTransformer`:
- `gl_FragData[i]` / `gl_FragColor` become `layout(location=i) out vec4 iris_FragDataI`, plus an alpha-test append (`iris_currentAlphaTest`).
- `attribute`/`varying` become `in`/`out`.
- `texture2D/3D/Lod/Proj/Grad`, `texelFetch2D/3D`, `shadow2D/shadow2DLod` (wrapped) and `textureSize2D` become the core equivalents.
- `gl_FogFragCoord`, `gl_FrontColor`, `gl_TexCoord`, `gl_Color` and `gl_Fog` become `iris_*`/`irs_*`/`irisInt_Fog` stand-ins.
- `gl_ModelViewMatrix(Inverse)`, `gl_ProjectionMatrix(Inverse)`, `gl_NormalMatrix`, `gl_TextureMatrix[n]`, `gl_Vertex`, `gl_Normal`, `gl_MultiTexCoordN` and `ftransform()` are rewritten to Sodium/vanilla inputs and Iris uniforms.
- `mc_Entity`, `mc_midTexCoord` and `at_midBlock` are decoded from Iris's extended vertex format.
- `DepthTransformer` handles the zero-to-one depth range: Iris already supports `isZZeroToOne` devices, which Metal is.
- `LayoutTransformer` adds explicit in/out locations but **only runs when `IrisLimits.VK_CONFORMANCE` is true** (hard-coded `false`). It handles inputs and outputs only, **not loose uniforms**.
- Commit `83c2be1` ("don't use 420pack, use manual binding") means that Iris's output may contain `layout(binding=…)` SSBOs under `#version 400`.

What survives Iris and blocks a straight SPIR-V/MSL path:

| feature in Iris output | status on SPIR-V to MSL | fix |
|---|---|---|
| ~100 **loose `uniform` non-opaque variables** per program, some with initializers (`uniform float cloudHeight = 192.0;`, `heavyFog=false`, `isElytraFlying=false`) | Vulkan GLSL rejects them | shaderc `vulkan_rules_relaxed` packs them into `gl_DefaultUniformBlock` (std140). Initializers are dropped (warning), so the runtime must pre-fill them. Offsets come from SPIRV-Cross reflection. |
| in/out without `location` | rejected in SPIR-V | `auto_map_locations` (or enable Iris's `LayoutTransformer`) |
| `gl_FragData`, `texture2D`, compat built-ins | already rewritten by Iris | none |
| SSBO `layout(std430, binding=N)` under `#version 400 core` | glslang: "'binding' not supported for this version" | force `#version 450/460 core` (or `GL_ARB_shading_language_420pack`) |
| more than 16 samplers in one stage (Complementary composite1 with colored lighting: 18) | Metal limit 16 samplers/stage (`[[sampler(n)]]`, n ≤ 15) | SPIRV-Cross `MSL_ARGUMENT_BUFFERS` (tier 2 on all Apple-silicon Macs), or constexpr samplers via `spvc_compiler_msl_remap_constexpr_sampler*`, because Iris sampler states are known at link time |
| one buffer per loose uniform (OpenGL-target SPIR-V) | over 31 Metal buffer slots, so it fails | do not use the GL target; use the relaxed Vulkan rules |
| `sampler2DShadow` compare | MSL `depth2d::sample_compare`; needs `MTLSamplerDescriptor.compareFunction` | renderpearl `GpuSampler` has **no compare op**, so this needs an mcopt-side sampler |
| image load/store (`image2D/3D`, `uimage3D r16ui`) | MSL `texture3d<…, access::write/read_write>`; tier-2 read-write formats on M-series | storage-texture creation needs an mcopt-side API |
| compute + `shared` + `barrier()` / `memoryBarrier*` | MSL `kernel` + `threadgroup` + `threadgroup_barrier`; GL `glMemoryBarrier` between passes becomes an encoder boundary or `memoryBarrier(scope:)` | — |
| writes to images/SSBOs from a **vertex** shader (Complementary voxelization in `shadow.vsh`) | compiles in Metal | ensure the pipeline allows vertex-stage writes |
| geometry shaders (`.gsh`, Iris supports them) | **Metal has none**; SPIRV-Cross cannot emit them | must be rewritten (compute or mesh shaders) or the pack is unsupported. Complementary has none; some voxel/PT packs historically use gsh (not verified here). |
| tessellation (`.tcs/.tes`) | SPIRV-Cross can emit MSL with compute-emulated control stage (MoltenVK style) | heavy runtime work; Complementary does not use it |
| MRT | Iris ≤ 8 draw buffers; Metal 8 colour attachments; renderpearl `ColorTargetState.MAX_COLOR_TARGETS = 8` | parity |
| Y orientation | the GL bottom-left convention | use the same `FLIP_VERTEX_Y` convention mcopt already uses (`MetalPipeline.java:322`). Memory layout then matches GL, so `gl_FragCoord`, texcoords and derivatives stay consistent; only cull winding and final present flip. Reasoned, not tested in-game. |

### 3.4 What the renderpearl API (26.3) can and cannot express

From `DECOMP/com/mojang/renderpearl/api`:
- `ShaderType` = {VERTEX, FRAGMENT} only;
- `UniformType` = {COMBINED_IMAGE_SAMPLER, UNIFORM_BUFFER, TEXEL_BUFFER};
- `GpuTexture` usages = COPY_DST/SRC, TEXTURE_BINDING, RENDER_ATTACHMENT, CUBEMAP_COMPATIBLE (**no storage**);
- `GpuBuffer` usages have no storage flag;
- `GpuDevice.createSampler(addressU, addressV, min, mag, aniso, maxLod)` has **no compare op**;
- `createTexture(..., depthOrLayers, mips)` has **no 3D type**;
- `CommandEncoder` has render passes, clears, buffer/texture copies, fences and timestamps, but **no compute dispatch, barriers, mip generation or scaled blit**;
- MRT and per-target blend/write mask are supported.

**Conclusion: a "pure renderpearl" Iris can cover packs that use only gbuffers + shadow + deferred/composite fragment passes.** Mips can be emulated with draws into mip views (`createTextureView(tex, baseMip, n)`), and compare samplers can be emulated with manual compares, at some cost. Complementary in its default macOS configuration needs only that: in the translated output for 28 programs, no images/SSBO/compute are referenced, only 62 `texelFetch`/46 `textureLod` plus `sampler2DShadow` ×2. Anything with compute, SSBOs, images or 3D textures needs an **mcopt-specific extension API** (`MetalBridge` already exposes native handles, `newTexture`, `newBuffer`, encoders).

---

## 4. Census of GL call sites in Iris, and the porting cost

Method:
- I wrote a Python categoriser over all `.java` files in common/fabric/neoforge: `SPIKE/tools/glcat.py <iris-root>`.
- It counts call sites of `GLxx[C].gl*`/extension classes, `GlStateManager.*` and `IrisRenderSystem.*`. Calls inside `IrisRenderSystem.java` itself (879 lines, 125 call sites) are excluded.
- **Total: 671 call sites in 100 files.** Split by source: raw LWJGL 205, GlStateManager 262, IrisRenderSystem wrappers ~207.

| category | call sites | files | most frequent |
|---|---|---|---|
| texture create/upload/params/mipmap/copy | 128 | 21 | `IrisRenderSystem.texParameteri` ×53, `_deleteTexture` ×10, `texImage2D` ×7, `_genTexture` ×7, `_pixelStore` ×6 |
| shader/program compile, link, introspection | 106 | 25 | `_glUseProgram` ×13, `glAttachShader` ×10, `_glBindAttribLocation` ×8, `glGetUniformBlockIndex` ×6 |
| uniform upload / location lookup | 82 | 32 | `_glGetUniformLocation` ×22, `_glUniform1i` ×9, `glUniformMatrix4fv` ×6 |
| fixed-function state (blend/depth/cull/mask/viewport/scissor/polygon) | 79 | 21 | `_viewport` ×10, `disableBlend` ×7, `_colorMask` ×5, `_scissorBox` ×5 |
| texture/sampler unit binding | 77 | 24 | `_bindTexture` ×28, `_activeTexture` ×26, `bindTextureForSetup` ×6 |
| framebuffers (create/attach/bind/draw-buffers/blit/clear) | 67 | 15 | `_glBindFramebuffer` ×23, `framebufferTexture2D` ×4, `blitFramebuffer` ×2 |
| queries/caps/debug | 59 | 15 | `_getInteger` ×13, `glDebugMessageControl*` ×12 |
| buffers / SSBO / UBO | 30 | 7 | `_glBindBuffer` ×6, `bindBufferBase` ×4 |
| sampler objects | 14 | 2 | `samplerParameteri` ×7 |
| images + memory barriers | 11 | 9 | `memoryBarrier` ×6, `glBindImageTexture(EXT)` |
| vertex arrays / draws | 9 | 4 | `_glBindVertexArray` ×3, `_drawElements` |
| compute dispatch | 6 | 3 | `glDispatchCompute[Indirect]` |

Files with the most call sites (excluding `IrisRenderSystem`):

| file | call sites |
|---|---|
| gl/GLDebug | 34 |
| compat/dh/IrisGenericRenderProgram | 31 |
| compat/dh/IrisLodRenderProgram | 23 |
| shadows/ShadowRenderer | 20 |
| pbr/util/TextureManipulationUtil | 19 |
| pipeline/programs/FallbackShader | 17 |
| pipeline/CompositeRenderer | 16 |
| gl/image/GlImage | 16 |
| mixin/MixinGlCommandEncoder | 15 |
| gl/framebuffer/GlFramebuffer | 15 |
| targets/backed/NoiseTexture | 14 |
| gl/texture/GlTexture | 14 |
| gl/buffer/ShaderStorageBuffer | 14 |
| IrisRenderingPipeline | 13 |
| FinalPassRenderer | 13 |
| ShadowCompositeRenderer | 13 |
| ProgramCreator | 13 |
| ExtendedShader | 12 |
| CenterDepthSampler | 12 |

Hidden GL coupling that the call-site count understates:
- **106** `getGlId()`/`iris$getGlId()` uses in 30 files. Textures travel as `int` GL names, including 67 `addDynamicSampler(IntSupplier…)` and 9 `addExternalSampler`.
- **630** `GLxx.GL_*` constant references.
- **17** mixins target GL-backend classes (`GlStateManager` ×8, `GlCommandEncoder` ×2, `GlProgram` ×2, `GlRenderPipeline`, …) and 27 mixin files touch the GL backend. The GL-state "override/lock" model (BlendModeOverride, DepthColorStorage, alpha test via uniform, per-buffer blend) has to become pipeline-state baking.
- `ExtendedShader`/`FallbackShader` *are* `GlProgram`s. `RenderTarget`, `GlImage`, `ShaderStorageBuffer`, `GlFramebuffer` and `GlSampler` are raw GL objects.

### Honest effort and risk estimate

The alternative to a port, B below, is a Metal-native pack runtime inside mcopt that reuses Iris's *front end* as a library: pack parsing/options/idmaps/includes/JCPP/`TransformPatcher`/uniform providers. That is what mcopt's dormant `PassRedirector/PassDelegate/MetalBridge` seams are shaped for.

| work item | approach A: port Iris's execution layer to renderpearl + mcopt side-API | approach B: Metal-native runtime reusing the Iris front end |
|---|---|---|
| shader compile: Iris GLSL → (fixups) → shaderc relaxed → SPIR-V → reflection → mcopt MSL pipeline with argument buffers/constexpr samplers | 1-2k LOC | same |
| uniforms: location → block offset, initializer pre-fill | 0.5-1k LOC | same |
| render targets, flipping, clears, depth copies, mips, custom textures/images, SSBOs | 2-3k LOC (rewrite `gl/`, `targets/`) | 2-3k LOC (native, MetalBridge) |
| program injection + pass redirection (replace GL FBO binding and state-lock mixins) | 2-3k LOC; fights `GlCommandEncoder` semantics | 1.5-2.5k LOC via `MetalHooks.Redirect` + `PassDelegate` |
| composite/deferred/final/shadowcomp renderers, compute, barriers | 1.5k LOC + compute side-API | 1.5k LOC |
| shadow pass through Sodium + mcopt terrain (Iris vertex format vs `MetalTerrain`) | high risk | high risk |
| PBR atlas, glint, hand, center-depth sampler, colour-space compute, debug | 1k LOC | 1k LOC |
| **total** | **~10-15k LOC touched across ~100 Iris files (of 56.7k); a permanent fork with frequent upstream drift (Iris shipped 1.11.5 → 1.11.7 in two weeks on 26.3)** | **~8-12k new LOC in mcopt; Iris jar used only as a parsing/transform library** |

Hardest parts, in order:
1. Draw routing parity: which of 62+45 pipelines go to which program in which phase, with state locks, alpha-test, per-buffer blend and translucency ordering.
2. The shadow pass through Sodium with Iris's vertex format, against mcopt's own terrain path (name-based MSL substitution, `VERTEX_BYTES = 20`, occlusion/indirect draws).
3. Uniform semantics. About 150 built-ins plus custom-uniform expressions, many of which depend on frame-capture state (`CapturedRenderingState`). This part is backend-agnostic, but its value timing matters.
4. Resource hazards: flips, plus reads of a target that is also an attachment. GL tolerates feedback loops; Metal does not.
5. Feature gaps: geometry shaders are impossible natively; tessellation is hard.
6. Testing without launching the user's game.

---

## 5. Translation spike: real Iris output to SPIR-V to MSL

**Harness:** `SPIKE/run.sh` and `SPIKE/src/Spike.java`.
- It uses the real Iris 1.11.7 jar plus its bundled glsl-transformer/antlr/jcpp, the MC 26.3 client jar, Sodium 0.9.3 and LWJGL 3.4.3 shaderc + spvc from Prism's libraries.
- It is compiled with the JDK in `work/tools/jdk-25.0.4.1+1`.
- Two tricks make Iris run headless:
  - (a) `SPIKE/src/net/irisshaders/iris/Iris.java` is a **stub that shadows the real `Iris` class** and provides only `logger`, `getIrisConfig()`, `getVersion()` and `getReleaseTarget()`.
  - (b) `RenderSystem.DEVICE` is set by reflection to a `Proxy` `GpuDevice` whose `getDeviceInfo().isZZeroToOne()` is true, matching Metal.
- Steps:
  1. Include expansion (same algorithm as `shaderpack/include/IncludeProcessor`).
  2. `JcppProcessor.glslPreprocessSource(src, StandardMacros-like defines)`. MC_VERSION=260300, IRIS_VERSION=11107, MC_GL(SL)_VERSION=460, MC_OS_MAC, IS_IRIS, IRIS_FEATURE_* and MC_RENDER_STAGE_*. Pack options are applied by rewriting `#define` lines, as Iris does.
  3. `TransformPatcher.patchSodium/patchComposite/patchVanilla/patchCompute`.
  4. shaderc under three configs:
     - **A** = Minecraft's own `GlslCompiler` options;
     - **B** = A + `vulkan_rules_relaxed` + `auto_map_locations` + `auto_combined_image_sampler`;
     - **C** = OpenGL 4.5 SPIR-V target.
  5. SPIRV-Cross to MSL 3.1 for macOS, `TEXTURE_BUFFER_NATIVE`, `FLIP_VERTEX_Y` on vertex shaders (the same options as mcopt's `MetalPipeline`).
  6. Validation with `SPIKE/tools/mslcheck`, a 40-line ObjC tool that calls `MTLCreateSystemDefaultDevice` + `newLibraryWithSource:` with MSL 3.1. That is the real runtime compiler; `xcrun metal` is not installed (missing Metal Toolchain).
- Usage: `SPIKE_OUT=... SPIKE_JVM_OPTS="--enable-native-access=ALL-UNNAMED -Dspike.forceVersion=460 -Dspike.argbuf=true -Dspike.mac=false -Dspike.options=COLORED_LIGHTING=128" ./run.sh sodium:world0/gbuffers_terrain composite:world0/composite1 compute:world0/shadowcomp vanilla:world0/gbuffers_entities lines:world0/gbuffers_line`.

**Results** (outputs in `SPIKE/out*`; each program dir holds `*.pre.*`, `*.iris.*` GLSL, `*.{A,B,C}.*.spv/.metal`, `REPORT.txt`):

| run | programs | Iris transform | A (MC options) | B (relaxed) | C (GL SPIR-V) | Metal runtime compile of B |
|---|---|---|---|---|---|---|
| `out`, `out-all`, `out-lines`: default options, MC_OS_MAC | 28 programs = 56 stages: all gbuffers_*, shadow, deferred1, composite, composite1, composite3-7, final, gbuffers_line (as `lines`) | 56/56 OK (8-430 ms; first call ~300-400 ms JIT warm-up) | **0/56** | **56/56** | 56/56 SPIR-V, but MSL fails | **56/56 OK** |
| `out-cl`: COLORED_LIGHTING=128, WSR=1, RAIN_PUDDLES=1, no MC_OS_MAC | shadowcomp (.csh), shadow, deferred1, terrain, composite1, water | OK | 0 | shadow.vsh **fails**: `shadow.vert:820: error: 'binding' : not supported for this version or the enabled extensions` (Iris emitted `#version 400 core` with `layout(std430, binding = 0) buffer blockDataBuffer`) | same | composite1.frag **fails**: `'sampler' attribute parameter is out of bounds: must be between 0 and 15` (18 textures + 18 samplers) |
| `out-cl2`: same + `forceVersion=460` + argument buffers | shadowcomp, shadow, composite1, deferred1, terrain | OK | 0 | **9/9** | — | **9/9 OK**: `kernel` with `threadgroup` arrays and `threadgroup_barrier`; `voxel_img.write(...)` from the vertex function; `device blockDataBuffer*` in the argument buffer |

Concrete errors:
- **Config A**, every stage: `'location' : SPIR-V requires location for user input/output` and `'non-opaque uniforms outside a block' : not allowed when using GLSL for Vulkan`. Typically 88-110 errors per stage.
- **Config C:**
  - `'buffer' attribute parameter is out of bounds: must be between 0 and 30` (`constant int& isEyeInWater [[buffer(32)]]`): every loose uniform becomes its own `[[buffer(n)]]`.
  - A SPIRV-Cross address-space bug: `no matching function for call to 'iris_depthMain' … cannot bind reference in address space 'constant'`.
  - So **C is a dead end; B is the path.**
- **Harness artefact, not a translation blocker:** calling `gbuffers_line` as a non-lines vanilla program fails with `'projectionMatrix' : undeclared identifier`. With `isLines=true`, which is what Iris does, it translates.

Shape of the output (B):
- Each stage gets `constant gl_DefaultUniformBlock& [[buffer(0)]]`, with 84-92 members, about 1.1-1.4 KB.
- Gbuffer vertex stages also bind `iris_SodiumPushConstants` and `u_Globals` UBOs and `texture_buffer<int> u_SectionTimeInfo`.
- Fragment stages use 0-14 texture/sampler pairs (18 with colored lighting), 1-4 colour outputs, and `depth2d::sample_compare` for `shadowtex0/1`.
- Complementary gbuffers_terrain.fsh preprocesses to 10.5k lines. That becomes 3,538 lines of Iris GLSL, 315 KB of SPIR-V (unoptimised, names kept for reflection) and 6.8k lines of MSL. The Metal compile takes about 170-220 ms cold.
- Whole-pack cost: 17 programs × 3 shaderc configs + 2 SPIRV-Cross runs took 7 s wall, including JVM start and javac. So expect about 2-3 s for one config, parallelisable, plus about 2 s of cold Metal compile, cached by the OS afterwards.

**Not verified by the spike:**
- runtime correctness (no draws were made);
- uniform values;
- GPU cost at 3024x1964;
- sampler/attachment hazards;
- `gl_FragCoord` orientation in-game.

---

## 6. Licensing

- **Iris**: `LICENSE` is GNU LGPL v3; `fabric.mod.json` says `"license": "LGPL-3.0-only"`. Modifying it and using it personally is fine. Distributing a modified Iris requires LGPL compliance: source of the modified library, relinkability.
- **glsl-transformer** (bundled, `IrisShaders/glsl-transformer`): **AGPL-3.0** per GitHub. Iris's own `LICENSE-DEPENDENCIES` warns that "glsl-transformer is licensed under the GNU Affero General Public License version 3. This may affect your ability to distribute Iris." Any distributed fork or addon that links it inherits AGPL obligations. `glsl-preprocessor` is GPL-3.0; jcpp is Apache-2.0.
- **mcopt**: Apache-2.0, plus PolyForm-Shield-1.0.0 files adapted from Sodium. Apache-2.0 can flow into (A)GPL works. PolyForm Shield is non-OSI with a non-compete clause, so do not mix those files into an (L/A)GPL distribution without checking. **Personal local use has no distribution issue.**
- **Toolchain**: SPIRV-Cross Apache-2.0; shaderc/glslang Apache-2.0/BSD-style; Slang Apache-2.0 with LLVM exception (GitHub shows NOASSERTION because of multi-file licences).
- **Complementary Reimagined r5.9.3**: `License.txt`, "Complementary License Agreement 1.7".
  - §0.2: "Normal Usage" explicitly excludes "Redistributing (parts of) The Pack" and "Modifying code of The Pack".
  - §1.3: a "Modified Pack" may only be redistributed if it credits the original, keeps the licence, has no "Complementary" in its name with at least 3 letters of difference, looks noticeably different in daytime overworld, has no other licence, and contains significant human-written additions.
  - §2.1: everything else is All Rights Reserved.
  - Implications:
    - (a) a **runtime translator** that, like Iris, transforms the user's own copy on their machine and redistributes nothing is the defensible option for personal use;
    - (b) **do not publish translated or hand-ported Complementary MSL**, or a "Metal Complementary";
    - (c) hand-editing the pack's code is technically outside "Normal Usage" even locally (§3.2 says they may choose not to act on harmless cases).
  - Writing **original** shaders, or porting a permissively licensed pack, avoids this entirely. Not legal advice.

---

## 7. Related work and signals (Vulkan, Metal, backend-agnostic)

- **Iris upstream:**
  - No PRs or branches about Vulkan, Metal or SPIR-V. The only "metal" hit is PR #3159, which forces core profile on the lines program for macOS.
  - Issues:
    - #3171: "Vulkan support is being developed as a separate mod", later named "aperture".
    - #3279: when will Aperture be released.
    - #3357: crash with `--graphicsBackend vulkan` on macOS.
    - #3153: macOS `#version 330 compatibility` regression for lines.
    - #3050: "Shaders not loaded on Mac M5 Max".
  - `docs/usage/drivers.md`: "All drivers on ARM / M1 macs: ❌ Not supported".
- **Aperture** (IrisShaders):
  - The pipeline is defined in TypeScript (`pack.ts`: `configureRenderer`/`configurePipeline`, `createObjectShader`, `forStage(...).createComposite`, explicit barriers, no buffer flipping).
  - Shaders are **Slang** (`objects/basic.slang`, `import aperture;`).
  - The repo is `IrisShaders/Aperture-Example-Pack`; the docs page `aperture/migration.mdx` is marked outdated.
  - It is not released or open-sourced (Modrinth search: nothing).
  - This shows Iris's own team chose a new format rather than making OptiFine packs backend-agnostic.
- **mcopt itself**:
  - `MetalBridge` ("The shaderpack runtime's (mcopt.metal.pack) window into this package … mcpack.m's functions live in the same image");
  - `MetalHooks.Redirect`/`PassRedirector` (redirect a render pass's attachments to pack targets);
  - `PassDelegate.setPipeline(enc, pipeline, uniforms)` (bind the pack's own pipeline per game pipeline);
  - `MetalEncoder.createRenderPass` honours redirects (lines ~236-300). A comment mentions a "native shading's lite tier".
  - None of the pack code, nor `mcpack.m`, is in the repo or in the `0.2.0-alpha.3` jar (`nm` shows no pack symbols).
  - README roadmap: "Our own shaders (BSL-level) as an option".
- **Cinder** (`thewander02/cinder`, Apache-2.0): a Vulkan-native loader on MC 26.2 using "Minecraft's generic GPU API". It is at milestone one (a single final pass) and explicitly not Iris/OptiFine compatible.
- **Fornax** (`Icehunter/fornax`, MIT, pushed 2026-10-04): a 26.2 Vulkan engine companion to Sodium. It has its own TOML+GLSL pack format, MetalFX scaler/frame interpolation and Metal ray-tracing shadows via Vulkan-Metal interop on macOS. Useful reference code (MIT) for MetalFX usage; not OptiFine-compatible.

---

## 8. Recommendations for the implementation phase

1. **Do not try to make stock Iris run on mcopt's Metal backend.** It is GL to the core, and it would need a permanent fork with AGPL/LGPL obligations if shared.
2. If running *existing* OptiFine packs, Complementary first, matters to the user, build a **Metal-native pack runtime in mcopt** using the existing `MetalHooks`/`PassDelegate`/`MetalBridge` seams:
   - use the Iris jar only as a front-end library (pack/option parsing, idmaps, includes, JCPP, `TransformPatcher`, uniform providers); the spike proves this works headless;
   - shader path = Iris GLSL → force `#version 460 core` → shaderc **relaxed Vulkan rules + auto-map locations** → SPIRV-Cross MSL with **argument buffers** (or constexpr samplers), `FLIP_VERTEX_Y`, MSL 3.x;
   - map uniforms through reflection of `gl_DefaultUniformBlock` and pre-fill initializers;
   - start with the fragment-only feature set (no compute/SSBO/images), which is exactly Complementary's default `MC_OS_MAC` configuration; then add compute, images and SSBOs through MetalBridge to unlock colored lighting and the other features Complementary disables on Mac.
3. **Disable or adapt mcopt's terrain fast path when a pack is active.** It substitutes hand-written MSL by name and assumes 20-byte Sodium vertices.
4. **Plan for MetalFX temporal upscaling as the way to reach "full Retina".** Render the pack at about 0.5-0.67 scale, take jitter from the pack's TAA or mcopt's `mcopt.fx.*`, and output 3024x1964. Measure per-pass GPU ms with mcopt's `GpuTimes` before promising 120 fps.
5. For a redistributable "beautiful shaders on Metal" result, the **original-shader route** (mcopt's "BSL-level" plan, or Slang/MSL written from scratch) avoids Complementary's licence and every translation edge case. The spike's harness and outputs remain useful as a reference for what a modern pack's passes look like.
