# mcopt architecture deep-dive: where a shading pipeline plugs into the Metal backend

Scope: mcopt 0.2.0-alpha.3 (commit `e900f08`), Minecraft 26.3, Sodium 0.9.3-alpha.1. Paths below are relative to the
read-only clone `…/scratch-2026-10-06-836a24/mcopt/` unless prefixed `decomp:` (=
`…/scratch-2026-10-06-836a24/work/decomp/mc-26.3/`). Line numbers are from these exact files.

---

## 0. TL;DR, the facts that decide the design

1. **It already renders at full Retina resolution.** The window is created with `SDL_WINDOW_HIGH_PIXEL_DENSITY`
   (decomp `com/mojang/blaze3d/platform/Window.java:148-150`, flags `8224 = 0x2000 | 0x20`). The surface is configured with
   `SDL_GetWindowSizeInPixels` (decomp `net/minecraft/client/Minecraft.java:1240-1248`, `Window.java:386-397`), and `MainTarget`
   is created at `window.getWidth()/getHeight()`, i.e. physical pixels (decomp `GameRenderer.java:179-180`).
   `mc_layer_configure` sets `layer.drawableSize = (width, height)` in pixels (`metal/src/main/native/mcmetal.m:1088-1097`).
   On the 14" MBP a fullscreen Space is normally 3024x1890 (the notch strip is left out); windowed it's the window size ×2.
   There's no hidden downscale: the user's "1080p" limit only applies to the OpenGL+Iris instance.
2. **The author has a private shaderpack runtime and a private "native shading" pipeline, neither published.** The public
   code has their seams only: `MetalHooks` (pass redirector and labeler), `PassDelegate`, `MetalBridge`, memoryless-attachment
   handling in `mcmetal.m`, `mc_frame_cmd`/`mc_blit`, a G-buffer layout in `columns.metal`, and a reflective `mcopt.metal.shade.ShadeFar`
   lookup in `Lod.java`. The released jar (`…/instances/mcopt Metal 26.3/minecraft/mods/mcopt-0.2.0-alpha.3.jar`, 268 entries)
   contains no `pack`/`shade` classes or MSL, and git history has never had them. **We have to build this layer ourselves**,
   but the hooks were designed for exactly this.
3. **Every pipeline goes through one choke point**: `MetalPipeline.compile` (SPIR-V → SPIRV-Cross → MSL → `MTLLibrary`). There's
   a **built-in per-pipeline MSL override** (`-Dmcopt.metal.overrideMsl=DIR`, `MetalPipeline.java:144-145, 92-95, 187-196`),
   which F3+T resource reloads re-read (decomp `ShaderManager.java:185-228` builds a new `PipelineCache` and closes the old one).
4. **Sodium terrain is hand-written MSL** (`sodium_terrain.{vs,fs}.metal`) with a "pulled" twin that fetches its own
   vertices from Sodium's arenas by GPU address. That gives us the data and code to **re-draw terrain from the sun** for a
   shadow map. `mcprobe.m` is already a working template for "replay Sodium quads into my own depth target".
5. **Depth is reversed-Z, [0,1], cleared to 0.0** (decomp `Projection.java:66-71` swaps near/far; `GameRenderer.java:474-478`,
   `LevelRenderer.java:248-259`). Main color is `RGBA8_UNORM` (LDR), main depth `D32_FLOAT`, both with
   `USAGE_TEXTURE_BINDING`, so they're sampleable (decomp `MainTarget.java:18`, `RenderTarget.java:96-101`).
6. **Rows follow OpenGL order** (row 0 = bottom of the scene), because SPIRV-Cross flips vertex Y and front-face is set to
   clockwise (`MetalPipeline.java:322`, `mcmetal.m:786-797`). In any full-screen pass, texture row `r` ↔ NDC
   `y = (r+0.5)/h*2-1` with **no** flip (comment at `mcmetal.m:44-45`). Hand-written vertex MSL must negate `gl_Position.y`.
7. **MetalFX upscaling exists but is experimental and has a real depth bug**: by the time it runs (at `FogRenderer.endFrame`),
   the hand pass has already cleared main depth (decomp `GameRenderer.java:687`), so motion vectors and depth are wrong for
   everything but the hand unless a post effect is active. FrameGen handles this correctly by hooking `render3dHud`.
   MetalFX upscaling and FrameGen are mutually exclusive (`FrameGen.java:61`).
8. **Budget**: the README measures ~1000-1100 fps at 1920x1080 on an M4 mini with a 10-core GPU (same GPU class as the user's
   M4 MBP). Code comments give ~700 fps at 3456x2234 (`MetalRenderPass.java:290-291`). At 3024x1964 (5.94 Mpx, 2.86× 1080p) the
   vanilla look should cost roughly 1-1.5 ms/frame, which leaves about 7 ms of the 8.33 ms budget at 120 Hz for shading.

---

## 1. Repository map (what matters for shading)

| Area | Files | Role |
|---|---|---|
| Backend entry | `metal/src/main/java/mcopt/metal/MetalBackend.java` (35 lines), `mixin/PreferredGraphicsApiMixin.java` | `GpuBackend` "Metal": window with `SDL_WINDOW_METAL`, `FrontendGpuDevice(new MetalDevice())`. The mixin prepends Metal to `PreferredGraphicsApi.getBackendsToTry`; `-Dmcopt.metal=false` opts out. |
| Device | `MetalDevice.java` | Textures, buffers, samplers, pipelines (`compilePipeline` compiles on the frontend's worker thread). Timestamp queries are stubbed (empty). |
| Command encoder | `MetalEncoder.java` (634) | One per device. Render passes, clears (deferred), copies, submit/fences, present, MetalFX `upscale`. |
| Render pass | `MetalRenderPass.java` (386) | Lazy uniform binding, draws, Sodium terrain capture, `PassDelegate` path. |
| Pipeline | `MetalPipeline.java` (363) | SPIR-V→MSL, MSL override/dump/cache, built-in Sodium MSL, pulled twin. |
| Surface | `MetalSurface.java` (110) | `CAMetalLayer` from `SDL_Metal_CreateView`, pacing, present. |
| Native | `metal/src/main/native/mcmetal.{h,m}` (66 + 1467) | All Metal calls (`Ctx`, `Enc` structs). |
| Terrain | `MetalTerrain.java`, `MetalOccPick.java`, `native/mcterrain.m`, `resources/mcopt/metal/msl/sodium_terrain.*.metal` | Pulled-vertex terrain, hi-Z split-pass occlusion, cloud culling. |
| Upscale / FG | `MetalFx.java`, `mixin/GameRendererFxMixin.java`, `mixin/CameraFxMixin.java`, `FrameGen.java` (766), `native/mcframegen.m`, `mixin/GameRendererFrameGenMixin.java`, `mixin/GuiRendererFrameGenMixin.java` | MetalFX temporal scaler and frame interpolation. |
| Bridge / hooks | `MetalBridge.java`, `MetalHooks.java`, `PassDelegate.java` | Public API intended for the private pack/shade code. |
| Far terrain | `lod/*` (~12k lines), `native/mclod.m`, `lodseam.m`, `resources/mcopt/lod/columns.metal` (3759) | Clipmap far terrain. It shows how to draw into the level pass and its G-buffer variant. |
| Debug | `MetalProbe.java` + `native/mcprobe.m`, `GpuTimes.java`, `MetalEvents.java` (JFR), `rec/*` (+`native/rec.m`, `rec.metal`) | Instruments. |
| Config | `Profile.java`, `resources/mcopt/profiles/{alpha,recommended}.properties` | Any `mcopt.*` key in `<gameDir>/config/mcopt.properties` becomes a system property before mixins load. |

The native lib `libmcmetal.dylib` is built from `mcmetal.m mcterrain.m mcprobe.m mcframegen.m mclod.m lodseam.m lodmesh.c lodnoise.c rec.m`
(`metal/build.gradle:23-30`), extracted to a temp dir at runtime (`Native.java:131-140`), and called through Java FFM.
Encoder calls are linked "critical" (no thread transition). Our own `mcshade.m` can live in the same dylib, so it can use the `Enc`
struct directly (`mcmetal.h`).

---

## 2. Frame lifecycle on Metal

### 2.1 Device and queue
- `mc_create` (`mcmetal.m:55-97`): `MTLCreateSystemDefaultDevice`, one `MTLCommandQueue` (`maxCommandBufferCount:64`). It
  compiles a built-in library (`present_vs/fs` flip-blit, `clear_vs/fs`, `motion_cs` for MetalFX) and a fan index buffer
  (65536 vertices).
- `MetalDevice()` (`MetalDevice.java:50-69`) reports `DeviceInfo(..., zZeroToOne=true, ...)`, every `DeviceFeatures` true (multi-draws
  are native loops), `DeviceType.INTEGRATED`.
- Textures: `mc_texture_new` always uses `MTLStorageModePrivate`. Usage is ShaderRead if `USAGE_TEXTURE_BINDING`, RenderTarget if
  `USAGE_RENDER_ATTACHMENT`, plus ShaderRead for every `D32_FLOAT` when terrain occlusion is built (`MetalDevice.java:99-109`).
  **ShaderWrite is never set**, so compute kernels can't write game textures. If we need that, add bit 2 in
  `MetalDevice.createTexture` (or use our own textures).
- Buffers: always `MTLStorageModeShared` (`mc_buffer_new`, `mcmetal.m:260-274`). "Arena buffer"-labelled Sodium arenas come from a
  pre-provisioned pool (`MetalDevice.createBuffer`, 117-141; `mc_pool_add`, `mcmetal.m:165-235`).

### 2.2 Surface, Retina, present
- `MetalSurface(ctx, encoder, window)`: `SDL_Metal_CreateView` → `SDL_Metal_GetLayer` (`MetalSurface.java:40-45`).
- `configure(config)` → `mc_layer_configure` (`mcmetal.m:1088-1097`): `BGRA8Unorm`, `framebufferOnly=YES`, `drawableSize = config
  w×h` (pixels), `displaySyncEnabled` = vsync, `maximumDrawableCount` 3 (or `-Dmcopt.metal.drawables=2`). **No colorspace is
  set and EDR is off** (opportunity, see §10).
- `blitFromTexture(enc, view)` (`MetalSurface.java:66-89`), called by the frontend with the final frame (GUI included):
  1. the `Rec.frame` hook (recorder, opt-in);
  2. FrameGen takes over if enabled;
  3. paced mode (vsync off, default on): `Native.pace(margin)` (`mc_pace`, `mcmetal.m:1159-1172`) uses a CVDisplayLink grid
     to present only the last frame that can make each refresh. Other frames render fully but skip the present;
  4. `layerNext` → `MetalEncoder.presentTexture` → `mc_present` (`mcmetal.m:1199-1218`) draws the frame into the drawable
     with a full-screen triangle (flips rows), then `presentDrawable` on the frame's command buffer. Opt-ins
     `-Dmcopt.metal.presentQueue=true|acquire` present from a second queue.
- `present()` does nothing (the present was scheduled in the command buffer) except FrameGen's sleep.

### 2.3 Command recording model (`Enc` in `mcmetal.h:18-58`)
- **One `MTLCommandBuffer` per frame (`cmd`)** plus an optional **`pre` command buffer** committed just before it. `pre` holds
  terrain culling lists and arena copies; nothing else orders the two, and hazard tracking takes care of the shared buffers
  (`mcmetal.h:38-46`).
- One render encoder (`enc->render`) is open at a time. **Pass merging:** `mc_render_begin` (`mcmetal.m:843-886`) *continues*
  the open encoder when the next pass has the same attachments and no color clear (returns 1). A depth clear on the same
  attachments is done in place by a full-screen depth-only triangle (`foldDepthClear`, 749-783; returns 2). In practice "Sky" and
  "Main" usually merge into one `MTLRenderCommandEncoder`.
- **Store actions are decided at `endRender`** (`mcmetal.m:455-470`): `DontCare` for attachments cleared while the encoder was
  open (`enc->dead`, set by `mc_discard`) and for **memoryless** textures, `Store` otherwise.
- **Deferred clears:** `clearColorTexture`/`clearDepthTexture` don't encode anything. They set `MetalTexture.pendingColorClear`/
  `pendingDepthClear` and fold into the next pass's load action (`MetalEncoder.deferClear`, 406-418). **Before sampling a
  texture outside a render pass you must call `flushClear`** (`MetalEncoder.flushClear`, 383-400; public:
  `MetalBridge.flushClear(encoder, tex)`). `createRenderPass` flushes pending clears of non-attachment textures automatically
  (246-249).
- Any blit (`blit()`), `mc_frame_cmd` (`mcmetal.m:492-496`), `mc_render_suspend` or `mc_fx_upscale` **ends** the open render
  encoder. To encode compute between passes, call `mc_frame_cmd(enc)` and create your own compute encoder on it, as the private
  native shading did.
- `openRender` (`mcmetal.m:786-797`) sets the viewport to the attachment size and `setFrontFacingWinding:Clockwise` (because of
  the Y flip). A new encoder has **no bindings**. Anything bound at fixed slots must be rebound after every new encoder,
  including after MetalTerrain's split (`MetalRenderPass.rebind`, 148-162).

### 2.4 renderpearl `RenderPass` → Metal mapping
| renderpearl call | mcopt implementation |
|---|---|
| `CommandEncoderBackend.createRenderPass(desc)` | `MetalEncoder.createRenderPass` (236-334): asks `MetalHooks.redirector`, flushes clears, `Native.renderBegin` (= `mc_render_begin`), optional labeler, returns `new MetalRenderPass(...)` |
| `setPipeline(p)` | `MetalRenderPass.setPipeline` (84-103) → `mc_r_pipeline` (PSO with or without depth, depth state, cull back/none, fill/lines, depth bias, primitive) |
| `setUniform(i, v)` | stored in `pending[i]`; bound lazily at the next draw only if changed (`bindUniforms`, 232-244). UBO → `mc_r_buffer(i)` (vertex+fragment), sampler → `mc_r_texture(i)` + sampler `i`, texel buffer → `MTLTexture` buffer view at texture `i` |
| `pushConstants(bytes)` | `mc_r_bytes(26)` vertex+fragment (`setVertexBytes`, ≤4 KB) |
| `setVertexBuffer(slot, slice)` | `mc_r_vertex_buffer(27+slot)` (slots 0-3 only) |
| `setIndexBuffer` | remembered in `enc` (16/32-bit) |
| `draw*`, `multiDraw*`, `*Indirect` | `mc_r_draw*`: Metal loops, one Metal draw per element (`mcmetal.m:963-1031`, `mc_r_draw` at 973). Triangle fans are re-indexed with `fanIndices` |
| `enableScissor` | `mc_r_scissor` |
| debug groups / timestamps | no-ops |

### 2.5 Transient memory
`MetalTransientMemory` (134 lines): per-submit 1 MiB shared `MTLBuffer` blocks via renderpearl's `TransientBlockAllocator`.
Staging, GPU and mapped are the same memory, so every upload is one memcpy. Blocks rotate back after the GPU finishes the
submit (`endSubmit` → `encoder.afterGpuFinishes`). `MetalEncoder.writeToBuffer` (457-477) memcpys directly when the destination
is idle or can be "renamed" (≤256 KB, whole-buffer). Otherwise it does a staging copy plus a blit (which ends the open render encoder).
Our per-frame uniforms can use `encoder.transientMemory.uploadGpu(...)` (package-private) or our own ring of 3.

### 2.6 Submit, fences, pacing
- `MetalEncoder.submit` (119-161): `transientMemory.endSubmit`, `terrain.releaseHeld(true)`, `mc_enc_commit` (`mcmetal.m:556-596`:
  commits `pre`, then `cmd`, then the queued present), then waits to keep `MAX_IN_FLIGHT = 2` frames in flight (`-Dmcopt.metal.inFlight`),
  i.e. submitting frame N waits for N-2 (`retire`, 210-224, runs that frame's `afterGpuFinishes` callbacks).
- `createFence` (567-596) names the current submit index. With `mcopt.cpu.fence=true` (on in the alpha profile) it names the
  just-committed one. Waiting retires frames in order.
- Hazards: Metal's automatic tracking. There are no barriers anywhere (`MetalEncoder` javadoc, lines 27-30). Private textures and
  shared buffers are all tracked by default.

### 2.7 One frame, in order, on Metal with Sodium (pass labels as the backend sees them)
From decomp `GameRenderer.render` (466-515), `renderLevel` (632-673), `render3dHud` (675-724), `LevelRenderer.render` (185-300),
`addMainPass` (383-470):

1. `GameRenderer.render`: clear main color+depth (depth 0.0) (474-478); `GlobalSettingsUniform.update`.
2. `lightmap.render` → pass **"Update light"** (`pipeline/lightmap`, renders the 16×16 lightmap texture).
3. `renderLevel` → `RenderSystem.setProjectionMatrix` (662) → `LevelRenderer.render`:
   - frame-graph pass "clear": main cleared to **fog color, depth 0.0** (248-259);
   - "sky" → `SkyRenderer.render` → pass **"Sky"** (decomp `SkyRenderer.java:129-136`): `minecraft:pipeline/sky`,
     `sunrise_sunset`, `stars`, `celestial` (sun, moon), `end_sky` (debug groups "Sky disc", "Dark disc", "Sun", "Moon",
     "Stars", "Sunrise sunset", "End sky");
   - "main" → pass **"Main"** (or **"Solid"** with Improved Transparency) on main color+depth (LevelRenderer:441-449):
     `executeSolid` (514-526): Sodium terrain group OPAQUE (`sodium:pipeline/solid_terrain`, `cutout_terrain`), then
     `featureFrame.executeSolid` (entities, block entities, opaque particles: `entity_*`, `item_*`, `opaque_particle`, …).
     **mcopt's far terrain draws here, at `executeSolid` RETURN** (`mixin/lod/LodLevelRendererMixin.java:29-32`).
     Then, without Improved Transparency, `executeClassicTransparency` (674-697) in the same pass: translucent features
     (`entity_translucent*`, `translucent_particle`), **`sodium:pipeline/translucent_terrain`**, translucent-after-terrain
     features, `minecraft:pipeline/clouds`/`flat_clouds`, `pipeline/weather`, `pipeline/world_border`.
     With Improved Transparency: `executeOit` (553-640), several OIT passes ("OIT Depth Bounds/Transmittance/Accumulate for …",
     RGBA32F/RGBA16F targets), then "OIT Composite" (`pipeline/oit_composite`). This is expensive at 5.9 Mpx, so **keep it OFF**.
   - "Outline" (entity glow → entity_outline target), "See through features", "Always on top features" (block outline,
     gizmos: `pipeline/lines*`, `secondary_block_outline`, `debug_*`).
4. `render3dHud` (675-724): **clears main depth to 0.0** (687), pass **"Item in hand"** (decomp `GameRenderer.java:401-406`), then
   "Screen effects" (`block_screen_effect`, `fire_screen_effect`, vignette …), "3d crosshair".
5. `blitEntityOutline`, `applyPostEffects` (vanilla post chains: "Post pass <name>", e.g. spider/creeper vision).
6. `FogRenderer.endFrame()` (503) **← MetalFx/FrameGen "before GUI" hook point**; clear depth again (504).
7. GUI: `guiRenderer.render()` (`pipeline/gui*`, `gui_text*`, `text*`, panorama, …).
8. Frontend calls `blitFromTexture` (present) and `submit`.

Under Metal, "Sky" + "Main" share attachments and usually merge into one encoder. MetalTerrain's split (when
`MetalOccPick` picks it) suspends and resumes the encoder in the middle of the solid terrain.

---

## 3. Shader translation path

### 3.1 `MetalPipeline.compile(encoder, CreateInfo)` (77-140)
`CreateInfo` (decomp `com/mojang/renderpearl/backend/api/BackendRenderPipeline.java:16-28`) = `name, shaders (SpvModule: type +
spv ByteBuffer), vertexBuffers, attribBindings, uniforms (BindGroupLayout.UniformDescription: name, type, gpuFormat),
pushConstantsSize, depthStencilState, polygonMode, cull, colorTargetStates, primitiveTopology`. The frontend compiles core GLSL
to SPIR-V with **LWJGL shaderc** (decomp `com/mojang/renderpearl/frontend/shaders/GlslCompiler.java:17`).

Steps:
1. `translate()` both stages (`translateNow`, 304-333): `spvc` context → MSL compiler, options `MSL_VERSION=30000`,
   `PLATFORM=MACOS`, `TEXTURE_BUFFER_NATIVE=true`, vertex: `FLIP_VERTEX_Y=true`. Resource bindings: for `i < uniforms.size()`,
   (set 0, binding i) → `msl_buffer/texture/sampler = i`; push constants → buffer 26 (`bind`, 335-340).
2. `-Dmcopt.metal.dumpMsl=DIR` writes `DIR/<name>.vs.metal|.fs.metal` (177-185).
3. Built-in Sodium MSL: if `name ∈ SODIUM_TERRAIN` (160-163: `sodium:pipeline/solid_terrain` → "", `cutout_terrain` →
   `#define ALPHA_CUTOUT 0.5`, `translucent_terrain` → `#define ALPHA_CUTOUT 0.01`) and `-Dmcopt.metal.builtinMsl` (default true),
   the vertex stage becomes `sodium_terrain.vs.metal` and the fragment stage defines + `sodium_terrain.fs.metal` (87-91).
   Sodium's OIT variants are named differently and keep their SPIR-V.
4. `-Dmcopt.metal.overrideMsl=DIR` replaces each stage with `DIR/<sanitized>.vs.metal|.fs.metal` if present (92-95, 187-196).
   The entry point name is **kept from the generated source** (`generated.entry`, normally `main0`).
5. `mc_library_new` (`mcmetal.m:344-354`): **MSL 3.0, `MTLMathModeFast`** (beware NaN/Inf semantics in lighting code).
6. `mc_pipeline_new` twice: with depth (D32Float) and, if `depthStencilState == null`, without depth (108-114). `describe()` (204-234)
   packs vertex layouts (buffer index `27 + slot`), attributes, color targets (format, ABGR write mask, blend), depth format and topology class.
7. Pulled twin for solid/cutout Sodium terrain when `MetalTerrain.OCC` (115-123): `"#define PULLED\n" + resource("sodium_terrain.vs.metal")`
   with **the same fragment library** and no vertex descriptor.
8. Depth state `mc_depth_state_new(compare, write)`.
9. Timing stats (`compileStats`, read reflectively by the private bench).

### 3.2 Binding contract (every translated or overridden shader must follow it)
| Metal slot | Content |
|---|---|
| `buffer(i)`, `texture(i)`, `sampler(i)` for `i < uniforms.size()` | uniform `i` in the pipeline's `BindGroupLayout` order (UBO, combined image sampler, or texel buffer as `texture_buffer`) |
| `buffer(20)`, `buffer(21)`, `buffer(22)` | MetalTerrain pulled draw: quad list, regions, arena GPU addresses (`MetalTerrain.java:33`) |
| `buffer(22..28)`, `texture(20)`, `sampler(14)` | far terrain's own draws (restored with `reapplyPipeline`, but **these slots are left clobbered**) |
| `buffer(26)` | push constants (≤128 B tracked for rebind, `MetalRenderPass.java:54`) |
| `buffer(27..30)` | vertex buffers 0-3 (`MetalConst.VERTEX_BUFFER_BASE = 27`) |
| fragment `color(k)` | must match `colorTargetStates[k]` format (PSO is baked); depth attachment always `Depth32Float` |

**Free slots for our global resources** (shadow map, our frame UBO, G-buffer reads): buffers 16-19, textures 16-19 (avoid 20),
samplers 12-13/15 (avoid 14; Metal has 16 sampler slots, and pipelines use 0..n-1). Bind them after every
`mc_render_begin` that returned 0, after `mc_render_resume`, and after far terrain draws. The best place is
`MetalEncoder.createRenderPass` right after `Native.renderBegin` and `MetalRenderPass.rebind`.

### 3.3 Conventions to respect in hand-written MSL
- Vertex: `out.gl_Position.y = -out.gl_Position.y;` (`sodium_terrain.vs.metal:63`; SPIRV-Cross does this automatically).
- Depth: reversed-Z, [0,1], near → 1, far plane (finite, `cameraState.depthFar`) → 0, clear 0, "nearer = larger".
- Full-screen passes: sample with `uv = (pixel + 0.5) / size`; `ndc = uv * 2 - 1` (no y flip) because textures hold GL row order.
  The final `mc_present` flips into the drawable.
- Fast math is on.

### 3.4 Injecting our own MSL by pipeline name
- **Quickest path (no Java change):** `-Dmcopt.metal.dumpMsl=/tmp/msl` once to get templates. Copy the files to
  `-Dmcopt.metal.overrideMsl=DIR` with the sanitized name `name.replaceAll("[^A-Za-z0-9._-]", "_")`, e.g.
  `minecraft_pipeline_sky.fs.metal`, `minecraft_pipeline_celestial.fs.metal`, `sodium_pipeline_solid_terrain.fs.metal`. The
  stdout line `mcopt-metal: using <file>` confirms the match. F3+T recompiles. Flags can also go in
  `config/mcopt.properties` (`Profile.applyFlags`, `Profile.java:62-103`).
- **Limits:** (1) only the pipeline's own uniforms are bound, so extra resources need a Java/native binding hook (§3.2);
  (2) color outputs can't exceed the pipeline's declared targets (no MRT G-buffer through overrides alone); (3) the **pulled
  terrain vertex shader is not overridable** (`resource()` is used directly at 118). If the varyings change, edit
  `sodium_terrain.vs.metal` itself so the plain and pulled variants stay identical (they share `shade()`); (4) MSL cache
  (`-Dmcopt.startup.mslCache`) only caches SPIRV-Cross output; overrides are applied after it.
- **Proper path (our fork):** replace the file lookup with a registry `Map<String, ShaderVariant>` in `MetalPipeline.compile`
  (e.g. a new `mcopt.metal.shade` package that supplies MSL plus extra PSO variants per pipeline name). Also keep `CreateInfo`
  in `MetalPipeline` (today it's dropped), so a `PassDelegate` can build MRT/HDR variants lazily.

---

## 4. MetalTerrain and the Sodium terrain shaders

### 4.1 Sodium's compact vertex (20 B, `USE_VERTEX_COMPRESSION`)
From `sodium_terrain.vs.metal:43-67`, Sodium's `chunk_vertex.glsl`, and `mcprobe.m:16`:

| word | format | meaning |
|---|---|---|
| 0 `a_Position.x` | 3×10 bits | high bits of x,y,z (20-bit fixed point) |
| 1 `a_Position.y` | 3×10 bits | low bits. `local = ((hi<<10)|lo) * (32/2^20) - 8` (section-local, -8..+24) |
| 2 `a_Color` | RGBA8 unorm | **biome tint × AO × directional face shade, all baked** (alpha used for cutout) |
| 3 `a_TexCoord` | 2×16 | 15-bit atlas uv + sign bit for the "shrink" bias |
| 4 `a_LightAndData` | 4×8 uint | x = block light, y = sky light (lightmap coord ×256), z = **material** (bit0 use-mips, bits1-2 alpha-cutoff index), w = **draw id = section index in region** (x: bits 5-7, y: bits 0-1, z: bits 2-4) |

Region = 8×4×8 sections (`RenderRegion.REGION_WIDTH/HEIGHT/LENGTH`). Position = `local + regionOrigin(camera-relative, push
constant u_RegionOffset) + sectionOffset*16`.
**Not available:** normals, tangents, block id or entity id (`mc_Entity`), mid-texcoord, emission. The material byte only has
mip/cutoff bits.

### 4.2 Uniforms (pipeline order = Metal slot)
`0 u_Globals` (UBO: `u_ProjectionMatrix, u_ModelViewMatrix, u_FogColor, u_EnvironmentFog, u_RenderFog, u_TexelSize,
u_TexCoordShrink, u_FadePeriodInv, u_UseRGSS`), `1 u_SectionTimeInfo` (texel buffer, fade-in times), `2 u_LightTex`
(lightmap + sampler), `3 u_BlockTex` (block atlas + sampler). Push constants `{packed_float3 u_RegionOffset; int u_CurrentTime;
uint u_RegionID}` at buffer 26. These match Sodium's `ShaderChunkRenderer` constant pool (`u_Globals`, `u_SectionTimeInfo`,
`u_LightTex`, `u_BlockTex`, defines `ALPHA_CUTOUT`, `USE_VERTEX_COMPRESSION`, `USE_FOG`).
Vertex outputs today: `half4 v_Color` (= color × lightmap sample), `float2 v_TexCoord`, `half2 v_Fog` (36 B/vertex, tuned because
**the vertex stage bounds terrain on Apple GPUs**, see the header comment of `sodium_terrain.vs.metal:6-12`). The fragment shader
(`sodium_terrain.fs.metal`) does Sodium's sharp-pixel sampling (+ optional RGSS), the cutout test, and linear fog blend.

### 4.3 Two draw paths
- **Plain:** Sodium's per-region `multiDrawIndexed(firstIndexOffsets, indexCounts, vertexOffsets)` (one draw per facing range) →
  `mc_r_multi_draw_indexed_separate`, with the vertex fetch from the arena slice bound at buffer 27 (`MetalRenderPass.java:314-325`).
- **Split / pulled** (`MetalTerrain`, 528 lines; default `-Dmcopt.metal.occ=auto`): while the pipeline has a pulled twin and the
  frame splits, `MetalRenderPass.multiDrawIndexed` calls `terrain.record(vertexBuffer0, indexCounts, vertexOffsets)` instead of
  drawing (`record`, 222-296). It builds `chunks[] = {firstVertex, (quads-1) | arena<<6 | region<<9}` (64-quad chunks) and
  `regions[] = {origin, time, id}` (a copy of each push constant). Arenas are tracked by index (max 8). `flush` → `occlude` (347-371)
  lists last frame's visible quads in the `pre` buffer (`mc_occ_last`) and draws them with **one `drawIndexedIndirect`**
  (`drawPulled`, 479-488; instance = 64 quads, index buffer of 384 uint16). Then `split` (379-399): `mc_occ_suspend` ends the
  encoder, builds hi-Z from depth (`hiz_build`), runs culling kernels (`terrain_chunk/cull/scan/compact`, `mcterrain.m:94-216`),
  `mc_render_resume` reloads attachments and draws the newly visible quads. Bounds/boxes/visibility caches are kept per arena (`ArenaCache`).
- `MetalOccPick` (59 lines) A/B-times split vs plain per resolution and picks the faster. Per its javadoc, on the M4 mini the
  split is **+43% at 1920x1080 and 9-13% slower at 3456x2234**, so at the user's 5.9 Mpx the plain path may often win.
  **Translucent terrain is never pulled** (it keeps Sodium's sorted index order, 116-117).

### 4.4 Data for a **shadow-map pass**
What a depth-only sun pass needs: every quad in solid+cutout (+translucent for colored shadows) within shadow distance, the
arena buffers, a per-region origin, and a light view-projection built camera-relative (positions are already camera-relative).

Three ways to get the geometry:
- **A. Reuse what the frame already records** (zero CPU work): add an "always record" mode in `MetalRenderPass.multiDrawIndexed`
  for `sodium:*_terrain` (today it records only when `split`). The `chunks`, `regions` and `arenas` arrays are exactly the inputs
  `drawPulled` needs. A shadow vertex function = `sodium_terrain.vs.metal` `PULLED` with `lightViewProj` instead of
  `u_ProjectionMatrix*u_ModelViewMatrix`, cutout via atlas alpha (copy `probe_opaque_fs`, `mcprobe.m:46-63`).
  **Limitation:** it's Sodium's *camera* list (frustum + cave culling), so off-screen casters (a mountain behind you, a roof
  overhead) are missing and shadows pop when turning. Acceptable as a first prototype.
- **B. Full list from Sodium's regions** (correct casters): `SodiumWorldRenderer.instance()` → `RenderSectionManager` →
  `RenderRegionManager.getLoadedRegions()` → for each `RenderRegion` (x,y,z; `getResources()` holds the geometry arena
  `GpuBuffer`), for each pass `getStorage(pass)` → `SectionRenderDataStorage.getDataPointer(section)` →
  `SectionRenderDataUnsafe.getBaseVertex(ptr)`, `getVertexCount(ptr, facing)`, `getSliceMask(ptr)`. Cull sections against the
  light frustum (CPU or GPU) and build the same `chunks/regions` arrays, then reuse `drawPulled`. mcopt already has Sodium
  accessors (`mixin/cpu/Cull*Access`). This is what Iris does in spirit (a separate shadow render list).
- **C. Ask Sodium to draw again:** `SodiumWorldRenderer.drawChunkLayer(RenderPass, group, ChunkRenderMatrices, x, y, z, sampler,
  oitStage)` into a renderpearl pass on a shadow depth texture with shadow matrices. This is simple, but it's still the camera list,
  Sodium's fragment shader runs, and a color attachment matching RGBA8 is needed (use a memoryless one).

Template code: `mcprobe.m` (`probe_vs`, `mc_probe` at 139-245) already replays Sodium quads with a `Draw{origin, quadBase, quads, kind}`
table into a `Depth32Float` target cleared to 0 (reversed Z) in its own command buffer. Turn that into: the pulled vertex
function, an orthographic light projection (consider forward Z for shadows, or keep reversed with GREATER), a 2048² or 4096²
`Depth32Float` (16.8 MB / 67 MB), and front-face or slope-scaled bias via `setDepthBias`.
Where to encode it: before the "Main" pass opens (frame N, with list A from frame N-1, or list B), or after the opaque phase for
deferred lighting (§6).

### 4.5 Getting normals and materials without changing Sodium's vertex format
- **Normal:** in the pulled path the vertex function can read the quad's other corners (`v - (vid & 3)*5`) and compute
  `normalize(cross(p1-p0, p3-p0))`. In the plain path the arena is bound at `buffer(27)`, and `[[vertex_id]]` includes
  `baseVertex`, so `const device uint *arena [[buffer(27)]]` + `q = vid & ~3u` works the same way. In the fragment stage,
  `normalize(cross(dfdx(P), dfdy(P)))` is a fallback.
- **Undo baked face shade:** with the normal known, divide the vertex color by vanilla's per-face shade (up 1.0, down 0.5,
  N/S 0.8, E/W 0.6) when not in Nether shading. Alternatively, mixin Sodium's light pipeline to disable directional shading.
- **Material id (water, leaves, glass, emissive, metals …): atlas material mask.** On atlas (re)stitch, build an
  `R8Uint` texture the size of the block atlas where each sprite's rectangle holds a material id derived from the sprite or block
  name (`water_still/flow`, `lava_*`, `*leaves*`, `glass*`, `glowstone`, `sea_lantern`, `torch`, …). Sample it with the vertex's
  atlas uv in vertex or fragment stages. This gives Iris-like `mc_Entity` classes for waving plants, water, and emission **without
  touching Sodium's mesher**. Translucent-pass membership plus the water sprite identify water exactly.
  The far-terrain G-buffer already uses `MAT_WATER = 5`, `MAT_GENERIC = 0` (`columns.metal:1336-1375`).

---

## 5. MetalFX temporal upscaling, FrameGen, LodTaa

### 5.1 MetalFX render scale (`MetalFx.java`, 132 lines)
- **On/off:** `-Dmcopt.fx.scale=S` with `S < 1` (default 1 = off). Other flags: `mcopt.fx.jitterSign`, `mcopt.fx.motionSign` (±1, "only
  their direction convention is in question, settled by comparing against native renders on the bench", 25-30). It's in neither profile
  and not in the README, so it's **experimental**. PR #10 (open, fork `ssquadteam/mcopt-fork@lunar-compat`) adds a Sodium
  video-settings page ("Metal Renderer": Render Scale + Frame Generation, live, saved to `config/mcopt.properties`; files
  `MetalOptions.java`, `Pipelines.java`, `Msl.java`) and is worth borrowing for our own settings UI.
- **Swap:** `GameRendererFxMixin.mcopt$beginWorld` (31-37) runs at the first `RenderSystem.getDevice()` in `GameRenderer.render`
  and sets `mainRenderTarget = fx.beginWorld(...)`, a `MainTarget` of `round(w*S) x round(h*S)` (RGBA8 + D32F). Resizing it
  also resizes `hud3DTarget` and `levelRenderer.resize(w,h)` (86-103). `ScreenSize` (GlobalSettingsUniform) follows the world target
  (`@ModifyArg`, 47-56). `mcopt$endWorld` (39-45) runs at `FogRenderer.endFrame()`, restores the real target and calls
  `MetalFx.endWorld` → `MetalEncoder.upscale` → `mc_fx_upscale`.
- **Jitter:** `CameraFxMixin` (TAIL of `Camera.extractRenderState`) pre-multiplies `cameraState.projectionMatrix` by a translation of
  `2*jitter/size` (Halton 2,3; `PHASES = max(8, round(8/S²))`), so every world draw (vanilla and Sodium) is jittered.
- **Motion vectors:** camera-only. `motion_cs` (`mcmetal.m:44-53`) reprojects each pixel's depth with `prevViewProj · T(camΔ) ·
  inverse(curViewProj)` (unjittered; `MetalFx.camera` 73-78, `endWorld` 107-113). No per-object motion (entities, particles,
  water animation smear). Teleport >8 blocks or resize → `reset`.
- **Scaler:** `MTLFXTemporalScalerDescriptor` with color = output format of the world target (RGBA8Unorm), depth D32F, motion
  RG16Float, `depthReversed = YES`, `motionVectorScale = sign*inputSize`. **No exposure texture or auto-exposure, no reactive mask,
  no `inputContentProperties`.** The output goes to a private texture, then a **blit copy** into the game's main color (because main
  textures lack ShaderWrite), costing one extra full-res copy per frame (`mcmetal.m:1394-1467`).
- **Excluded:** GUI (drawn after the restore at native res). **Included at low res:** sky, world, translucents, clouds, weather,
  hand, screen effects, vanilla post effects.
- **Known issues found while reading:**
  1. **Wrong depth and motion:** `render3dHud` clears main depth to 0.0 before the hand (decomp `GameRenderer.java:687`) unless a
     post chain forces `consistentDepthRequired`. `endWorld` runs later, so MetalFX gets a depth that is far (0) everywhere except the
     hand. Motion is then correct for rotation and wrong for translation parallax, so near geometry ghosts while moving. Fix: like
     `GameRendererFrameGenMixin`, wrap `render3dHud` and dispatch `motion_cs` and a depth copy before it.
  2. The hand is jittered and upscaled but gets no motion of its own (it moves with the camera).
  3. Translucent and particle ghosting (no reactive mask). The LDR RGBA8 input hurts bright highlights.
  4. Exclusive with FrameGen (`FrameGen.ENABLED` requires `MetalFx.SCALE >= 1`, `FrameGen.java:61`).

### 5.2 FrameGen (`FrameGen.java` 766, `mcframegen.m` 636)
`-Dmcopt.metal.framegen=true|paced` (default false), macOS 26 `MTLFXFrameInterpolator`. The javadoc calls it "an experiment for how
it feels, not a frame rate". Real frames are capped at ≤ ½ refresh, and one interpolated frame is shown between them on the
CVDisplayLink grid (`presentDrawable:atTime:`), which forces display sync. Capture: `levelDrawn` (wraps `render3dHud`, **before** the
hand clears depth) computes motion from depth (`fg_motion_depth`, hand pixels ≥ 0.43 depth get 0 motion). `beforeGui` (at
`FogRenderer.endFrame`) copies the level image. The GUI is re-drawn over black and over white (`GuiRendererFrameGenMixin`) and composited
onto the generated frame with `b*(white-black)+black` (`fg_composite`). It supports a **distance-in-alpha** motion source for "native
shading" (`fg_motion_distance`: the lit scene copy holds eye distance in alpha, 65000 = sky). Options: `.view=generated|motion`,
`.dump=turn,move,first,seq`, `.log=true` (writes `framegen/presents.csv`), `.margin`, `.even`.

### 5.3 LodTaa / LodTaaTile
These cover far terrain only: `-Dmcopt.lod.taa=ALPHA` filters the far-terrain fragments against a history band; `-Dmcopt.lod.taaTile=ALPHA` does
the same as a **tile function dispatched inside the open level encoder** (`lodseam.m:213-256`:
`MTLTileRenderPipelineDescriptor`, `dispatchThreadsPerTile`). That last one is a **working in-repo example of tile shading on the level
pass**, the technique we want for deferred lighting and in-tile post-processing. It doesn't apply to the main image.

---

## 6. Hook points for the shading pipeline

| Need | Best hook | Notes |
|---|---|---|
| **Per-frame setup** (camera, sun dir, matrices, time, weather) | `LevelRenderer.render` HEAD (pattern: `LodLevelRendererMixin.mcopt$lodBegin`) + `CameraRenderState` | `cameraState.projectionMatrix` (jittered if MetalFX), `viewRotationMatrix`, `pos`, `fogData`; sun angle from `ClientLevel` / `SkyRenderState` |
| **(a) Shadow map** | Encode in our own render encoder **before** the "Main"/"Solid" pass: `MetalEncoder.createRenderPass` when `descriptor.label()` is "Main"/"Solid" (or a mixin at `LevelRenderer.addMainPass` executes-lambda start) | Geometry from §4.4 (A or B) via the pulled vertex function. Entities: replay later, or skip at first. Bind the result to slot 16-19 in every later encoder |
| **(b1) Forward lit terrain** (lite tier) | Edit `sodium_terrain.{vs,fs}.metal`, or a per-name MSL registry, plus a binding hook for shadow map and our UBO | Simplest. Keeps RGBA8 LDR, single pass, every vanilla pass intact. Entities via overrides of `minecraft:pipeline/entity_*` |
| **(b2) Deferred / tile-deferred** (full tier, the author's design) | `MetalHooks.setRedirector(...)` → `Redirect(colors[], clears, w, h, depth, depthClear, delegate)` for "Main"/"Solid" **or** `Redirect.keep(delegate)` | The delegate's `setPipeline(enc, pipeline, uniforms)` binds **its own PSO** per game pipeline (MRT G-buffer variants, keyed by `MetalBridge.pipelineName`) and returns the uniform→slot map. `beforeDraw` gates draws, `begin/end` bracket the pass. Memoryless G-buffer attachments are already supported (store/load actions). Light in tile memory at `end` via a tile function or a full-screen fragment with programmable blending. G-buffer layout used by far terrain: att0 lit color, att1 albedo `half4`, att2 octahedral normal.xy + AO + material/255, att3 light (x block, y sky), att4 depth `R32Float` color (`columns.metal:1336-1375`) |
| **(c) Water / translucent** | `MetalRenderPass.setPipeline` when switching to `sodium:pipeline/translucent_terrain` | To refract or reflect, the opaque scene color and depth must be readable: suspend the encoder (`mc_render_suspend` pattern, as MetalTerrain does), copy color+depth (or compute SSR inputs), resume. Or write depth to a color attachment and read it in-tile. Override the translucent fragment for water shading (normal waves, Fresnel, sky reflection, SSR). Keep Improved Transparency off |
| **(d) Sky / atmosphere** | MSL override of `minecraft:pipeline/sky` (+`sunrise_sunset`, `stars`, `celestial`) in the "Sky" pass, or redirect "Sky" and draw a full-screen atmosphere | The sky pass draws before terrain into the same attachments. A full-screen sky triangle at depth 0 is fine |
| **(e) Post chain** (bloom, tonemap, volumetrics, AO, TAA) | **Depth-based work before the hand:** wrap `GameRenderer.render3dHud` (copy of `GameRendererFrameGenMixin.mcopt$frameGenLevel`). **Color post before GUI:** `GameRenderer.render` at `FogRenderer.endFrame()` (MetalFx/FrameGen hook) | Encode with `mc_frame_cmd(enc)` + compute, or full-screen render passes into our own textures, then write the result into main color. For HDR: render the world into an `RGBA16Float` target swapped in like `MetalFx.beginWorld`. **Every PSO bakes RGBA8**, so this needs per-format PSO variants in `MetalPipeline` (lazy map keyed by attachment format, requires keeping `CreateInfo`) |
| **(f) Depth and scene color** | `MetalBridge.textureHandle(mainTarget.getDepthTexture()/getColorTexture())`, after `MetalBridge.flushClear` | Main depth holds the world until `render3dHud` (then only the hand). Main color is final world+hand at `FogRenderer.endFrame`. `hud3DTarget` (D32F) holds hand depth when post chains are active |
| MetalFX ordering | Shade at render scale → (our TAA or MetalFX) → GUI | Fix the depth bug first (§5.1). Feed MetalFX HDR or exposure-aware input if HDR is added |

### 6.1 Pipeline name → content (namespaced `minecraft:pipeline/...` and `sodium:pipeline/...`; 102 `withLocation` entries in decomp `RenderPipelines.java`)
- **Sky:** `sky`, `sunrise_sunset`, `stars`, `celestial` (sun/moon/end flash), `end_sky`; nether/end use fog plus these.
- **Terrain (with Sodium):** `sodium:pipeline/solid_terrain`, `cutout_terrain`, `translucent_terrain` (+ Sodium OIT variants with
  other names). Vanilla `solid_terrain`, `cutout_terrain`, `translucent_terrain`, `*_multidraw` are unused with Sodium.
- **Block models outside chunks** (falling blocks, pistons, item frames): `solid_block`, `cutout_block`, `translucent_block`.
- **Entities:** `entity_solid`, `entity_solid_offset_forward`, `entity_cutout*`, `entity_translucent*`, `entity_shadow`, `eyes`,
  `energy_swirl`, `breeze_wind`, `armor_*`, `banner_pattern`, `leash`, `end_crystal_beam`, `beacon_beam_*`, `lightning`,
  `dragon_rays`, `end_portal`, `end_gateway`, `item_cutout`, `item_translucent*`, glints (`glint`, `*_glint`), `crumbling`.
- **Particles:** `opaque_particle`, `translucent_particle`.
- **Clouds:** `clouds`, `flat_clouds` (mcopt culls faces in split frames, `MetalRenderPass.drawClouds` 293-304). **Weather:**
  `weather`. **World border:** `world_border`. **Water mask:** `water_mask`, `oit_water_mask`.
- **Block outline / debug:** `lines`, `lines_translucent*`, `lines_depth_bias`, `secondary_block_outline`, `debug_*`.
- **Text in world:** `text*`, `text_see_through`, `text_polygon_offset`.
- **Screen effects (hand pass):** `block_screen_effect`, `fire_screen_effect`, `vignette`, `crosshair`.
- **GUI:** `gui`, `gui_invert`, `gui_text*`, `gui_textured*`, `gui_opaque_textured_background`, `gui_nausea_overlay`,
  `panorama`, `mojang_logo`.
- **Utility:** `lightmap`, `animate_sprite_blit/interpolate`, `entity_outline_blit`, `outline_cull/no_cull`, OIT
  (`blit_depth_bounds`, `oit_depth_bounds_cull`, `blit_depth*`, `oit_composite`), `integrate_depth`, `tracy_blit`.

---

## 7. Building the mod

Verified by a parallel agent's baseline build (`work/build-baseline.log`: `BUILD SUCCESSFUL in 1m 17s`, Loom resolved
`1.17.21`, output `mcopt/metal/build/libs/metal-0.2.0-alpha.3.jar`, 853,889 bytes):
```sh
export JAVA_HOME="…/scratch-2026-10-06-836a24/work/tools/jdk-25.0.4.1+1/Contents/Home"   # Temurin 25.0.4.1+1, aarch64
cd <our fork of mcopt>
./gradlew :metal:build          # → metal/build/libs/metal-<version>.jar (natives inside)
tools/release                   # → dist/mcopt-<version>.jar and dist/mcopt-metal-<version>.zip
```
- Gradle wrapper 9.5.1 (`gradle/wrapper/gradle-wrapper.properties`). Plugin `net.fabricmc.fabric-loom` `1.17-SNAPSHOT` from
  `maven.fabricmc.net` (`settings.gradle`). `minecraft 26.3`, `fabric-loader 0.19.5`, Sodium `compileOnly
  maven.modrinth:sodium:mc26.3-0.9.3-alpha.1-fabric` (Modrinth maven, `metal/build.gradle:8-20`). `fabric_api_version` is declared
  but unused.
- Java: `options.release = 25`, `compileJava` adds `--add-modules jdk.incubator.vector` (`build.gradle:49-54`). The JDK running
  Gradle must be 25 (there's no toolchain block).
- Natives: task `compileNative` (Exec `clang -fno-objc-arc -O2 -Wall -arch arm64 -mmacosx-version-min=15.0 -dynamiclib`, frameworks
  CoreGraphics, CoreVideo, Foundation, Metal, MetalFX, QuartzCore), wired into `processResources`. New `.m` files **must be added to the
  `sources` list** in `build.gradle:24`. ARC is off, so manage retain/release by hand like the existing files.
- `gradle.properties`: `-Xmx2G`, parallel, configuration cache off.
- Gotchas: (1) the first build downloads Gradle, MC, and Loom caches (≈1-2 min). (2) The read-only clone already contains `build/`
  and `.gradle/` from the baseline build, so **copy the repo to our own working dir before editing**. (3) `run/` is gitignored
  (author uses Loom `runClient`), but Sodium is compileOnly, so a dev client would need `modRuntimeOnly` Sodium. Testing in a
  **separate Prism instance** is simpler (never the user's instances). (4) `java -jar` of the mod shows install help (`InstallHelp`).
  (5) The fabric.mod.json pins `sodium >=0.9.3-alpha.1 <0.9.4-alpha`.

---

## 8. Benchmark, automation and profiling tools in the tree

There's **no in-tree benchmark**. `mcopt.bench.Bench` (phases: settle, spin, flight, capture …; fields `phase`, `settledAt`) is a
private mod, referenced only reflectively (`cpu/Cpu.java:135-154`, `cpu/AbShots.java:25-40`, `rec/Recorder.java:128-129, 258-280`,
`GpuTimes.snapshot`, `MetalPipeline.compileStats`). What works without it:

| Tool | Trigger | Output |
|---|---|---|
| FPS / GPU wait | `-Dmcopt.metal.stats=true` | stdout per second: `mcopt-metal stats: N fps, X ms/frame, Y ms of it waiting on the GPU, P presents` (+ terrain culling counts) (`MetalEncoder.stats`, 164-183) |
| **Per-encoder GPU time** | `-Dmcopt.metal.trace=N` | prints every encoder op of submit N (pass labels, attachments, merges, draws) and **average vertex/fragment µs per encoder over submits N..N+99** (`MetalEncoder` 41, 185-208; `mc_profile_*` counter sampling at stage boundaries). This is the main tool for pricing new shader passes |
| Pass names in GPU captures | `MetalHooks.setLabeler((enc, name) -> …)` (inert by default) | label native encoders, e.g. for Xcode GPU capture |
| GPU completion and present cadence | `-Dmcopt.metal.gpuTimes=true`, `-Dmcopt.metal.latency=true` | arrays exported via `GpuTimes.snapshot` (bench-only consumer) |
| JFR spans | `-Dmcopt.metal.jfr=true` | `mcopt.MetalOperation` events (allocate, upload, commit, wait, present) |
| Stall diagnostics | `-Dmcopt.metal.cbdiag=MS` | stderr lines for slow command buffers and big allocations |
| Video | `-Dmcopt.rec=/path/out.mp4` (+ `.size`, `.fps`, `.start=world`, `.duration`, `.crop`, `.codec=vt`) | H.264 via VideoToolbox, GUI included, plus `OUT.csv` with camera per frame |
| Terrain visibility | `-Dmcopt.metal.probe=N` | how many Sodium quads reached a pixel |
| A/B screenshots | `-Dmcopt.cpu.abShots=…` | **needs the private bench** (`SETTLE` phase), so not usable as is |
| Loading-screen cap | `-Dmcopt.cpu.loadingFps=N` | caps fps on `LevelLoadingScreen` |
| Startup timing | `MetalPipeline.compileStats()` | pipelines compiled, translate/library/state ms, MSL cache hits |

Outside the mod: `MTL_HUD_ENABLED=1` (Metal Performance HUD, live fps/GPU ms in-window) and `xcrun xctrace record --template
'Metal System Trace'` for automated GPU timelines. **Recommendation:** write our own small bench mixin (auto-join a fixed test world
via `--quickPlaySingleplayer`, a scripted camera path on fixed ticks, `Screenshot.grab(gameDir, name, mainRenderTarget, 1, cb)` at
fixed frames as `AbShots.frameEnd` does, log `mcopt.metal.stats` and `trace` output, then `Minecraft.stop()`). Run it in a separate Prism
instance.

---

## 9. Existing shader hooks and plans in the code

- README "Plans": "**Our own shaders (BSL-level) as an option**".
- **Shaderpack runtime** (`-Dmcopt.pack` / `mcopt.metal.pack`, native `mcpack.m`, cache dir `mcopt-pack-cache/` in `.gitignore`):
  `MetalEncoder.createRenderPass` comment (237), `PassDelegate` javadoc, `MetalBridge` javadoc (19-22: "Everything the pack package needs
  from the backend goes through here"), `MetalBridge.library()` ("mcpack.m's functions live in the same image"),
  `createHeadlessContext/Encoder` + `commitAndWait` + `readTexture` (headless pack tests), `DONT_CARE` loads for full-screen pack passes
  (`mcmetal.m:862`), `MetalDevice.createTexture` comment "Shaderpacks sample the game's depth (depthtex0) too" (103), FrameGen
  `HAND_DEPTH = 0.43` ("the pack runtime draws the hand at 0.4375..0.5625, like Iris"), MetalTerrain "shader packs: never" (split off).
  This strongly suggests the author translates **Iris-format packs** to Metal.
- **Native shading pipeline** (`-Dmcopt.shade=native`, package `mcopt.metal.shade`, native `mcshade.m`, MSL `mcopt/shade/common.metal`):
  `Enc.renderSerial` (`mcmetal.h:48`), `mc_frame_cmd` (`mcmetal.m:490-496`), memoryless G-buffer store/load handling (458-460, 864, 907),
  `MetalBridge.takePendingDepthClear` ("move the deferred main-depth clear to its tile-local replacement"), `Redirect.keep` = "native
  shading's lite tier" (`MetalEncoder.java:240`), far-terrain G-buffer shaders `lod_comp_gbuffer`, `lod_mesh_gbuffer`,
  `lod_mesh_plant_gbuffer` (`columns.metal:1336-1375, 3005-3047, 3208`) with a hard-coded `gbuffer = false` in `Lod.java:455, 495`,
  `Lod.shadeFar()` reflectively looking up `mcopt.metal.shade.ShadeFar.setReach(float)` (`Lod.java:277-296`), FrameGen's
  distance-in-alpha motion for "its lit scene copy" (depth "lives only in tile memory").
- Design we can infer: a **tile-based deferred renderer**. The level pass is redirected to MRT targets where the G-buffer
  (att1-4) is memoryless and only the lit color (att0) is stored. Main depth is replaced by a tile-local one, lighting is resolved in
  tile memory, and a "lit scene copy" with eye distance in alpha feeds later passes (fog, FrameGen). This is the right architecture
  for Apple GPUs at 5.9 Mpx: a stored 4-target G-buffer + depth (~20 B/px) would cost about 238 MB of write+read per frame, roughly
  28 GB/s at 120 fps, close to a quarter of the M4's ~120 GB/s.

---

## 10. Recommendations for the implementation phase

1. **Fork** mcopt into our own working dir. Keep the Apache-2.0/PolyForm headers; MSL derived from `sodium_terrain.*` stays PolyForm Shield.
2. **Milestone 1 (fast, visible):** forward-lit terrain (lite tier). Edit `sodium_terrain.vs/fs.metal`: add face normal (corner fetch)
   and world position varyings, undo baked face shade, Lambert/sun-sky lighting from `a_LightAndData.xy` + sun direction, a shadow
   map (§4.4 A first, then B), PCF/PCSS, a simple sky override, tonemap-ish grading at `FogRenderer.endFrame`. Add a resource-binding
   hook (slots 16-19) in `MetalEncoder.createRenderPass`, `MetalRenderPass.rebind`, and after `Lod` draws.
3. **Milestone 2:** water (translucent override + scene copy at the solid→translucent switch, SSR, waves via the atlas material mask),
   volumetric light and fog at low res before the hand, bloom. Add an **HDR world target** (RGBA16F) with per-format PSO variants.
4. **Milestone 3 (best perf):** tile-deferred through `MetalHooks.Redirect` + `PassDelegate` with memoryless G-buffer and
   tile-function lighting (template: `lodseam.m:213-256`), matching the author's design.
5. **Upscaling:** fix MetalFx's depth/motion capture (hook `render3dHud`), add a reactive mask for translucents/particles, and
   consider rendering shading at 0.67-0.75 scale with MetalFX to native for 120 fps.
6. **XDR display:** consider `RGBA16Float` drawable + `wantsExtendedDynamicRangeContent` + extended-linear Display P3 in
   `mc_layer_configure`/`mc_present` for real HDR highlights (the user's 14" MBP has an XDR panel).
7. **Settings UI:** borrow PR #10's Sodium options page (`MetalOptions`).

## Appendix: complete `mcopt.*` flag list (from source)
Metal backend: `mcopt.metal` (false = OpenGL), `.arenaReserve`, `.builtinMsl`, `.cbdiag`, `.drawables`, `.dumpMsl`, `.overrideMsl`,
`.uncachedMsl`, `.framegen` (+`.dump`, `.even`, `.log`, `.margin`, `.view`), `.gpuTimes`, `.inFlight`, `.jfr`, `.latency`, `.occ`
(auto|true|false), `.pace`, `.paceAdapt`, `.paceMarginMs`, `.paceSync`, `.presentQueue` (true|acquire), `.probe`, `.stats`, `.trace`.
MetalFX: `mcopt.fx.scale`, `.jitterSign`, `.motionSign`. Startup: `mcopt.startup.mslCache` (true|verify), `.mslCacheDir`,
`.narrator`, `.crashPreload`, `.blockCache`, `.fadeMs`. Config: `mcopt.profile` (alpha default|recommended|none). Others: `mcopt.cpu.*`
(lists, model, fence, texelCache, cullReuse, pass, cmdAhead, record, abShots, loadingFps …), `mcopt.chunk.*`, `mcopt.alloc.lightMap`,
`mcopt.qos.*`, `mcopt.edgeCap*`, `mcopt.gen.*`, `mcopt.preload*`, `mcopt.rec*`, `mcopt.lod*` (≈80 far-terrain flags).
**There's no `mcopt.shade.*` or `mcopt.pack` flag in the public source.**
