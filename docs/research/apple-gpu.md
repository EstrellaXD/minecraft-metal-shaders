# Apple GPU rendering reference for a native-Metal Minecraft shader pipeline (M4 10-core, 3024x1964, macOS 26)

Report name: `apple-gpu`. Research phase only: nothing here changes mcopt or the user's instances.

Almost every number below was **measured on this exact machine**: Apple M4, 10-core GPU, 16 GB, macOS 26.6.2, Metal 4, running on battery with Low Power Mode off. I used a purpose-built Metal benchmark that renders a synthetic Minecraft-like scene. The benchmark is reusable and lives in
`work/bench-apple-gpu/` (see section 10). Web sources are listed at the end. Where a number is an estimate rather than a
measurement, it is marked **(est.)**.

---

## 0. The decisions that matter most

1. **Most of the frame cost at 3024x1964 comes from memory bandwidth and geometry, not shading math.** An M4 moves about 80-85 GB/s in practice (I measured 83 GB/s on a buffer copy; the theoretical peak is 120 GB/s). Each uncompressed RGBA16F full-screen pass that reads and writes once costs **about 1.0-1.1 ms at native resolution**. A Complementary-style pipeline has roughly 10-15 composite passes, each reading several `colortex` targets, so at native resolution it spends 10+ ms on bandwidth alone. That is why the user has to drop to about 1080p today.
2. **Keep every intermediate image a compressible render target.** That means private storage, no `MTLTextureUsageShaderWrite`, written by a fragment shader. With scene-like content this makes a full-screen pass about 2-3x cheaper. Measured: an RGBA16F read+write pass costs 0.31-0.50 ms compressible versus 0.96-1.07 ms with ShaderWrite usage. The final tonemap pass costs 0.35-0.44 ms as a fragment shader versus 0.86 ms as compute. Bloom costs 0.74 ms versus 1.23 ms, and TAAU 0.62 ms versus 1.22 ms. Lossy compression (Apple8+) reaches about 2x even on noise. **Doing post-processing in compute with `imageStore`, the way GL shaderpacks do, is the slow path on Apple GPUs.**
3. **Use single-pass TBDR deferred shading.** The G-buffer stays in tile memory (memoryless), and a full-screen lighting draw in the same render pass reads it through programmable blending (`[[color(n)]]`). Store only what later passes need: HDR color, depth for MetalFX/TAA, and normals for SSR. It beats a classic stored G-buffer by 0.3-0.6 ms at native resolution. The opaque pass for 1.78M visible triangles is **geometry-bound**: 1.35-1.8 ms from 0.5x to native with Sodium-style face culling, and the lighting math is almost free inside it.
4. **The M4 has hardware ray tracing, and it is cheap enough to replace the shadow map.** One sun-shadow ray per pixel costs 0.42 ms at 0.5x and 1.6 ms at native in compute. Traced *inline* from the TBDR lighting fragment shader it adds only about 0.05-0.3 ms, because the pass is geometry-bound. Rendering a full shadow map of the same 5M-triangle world costs **5.2 ms** (2.8 ms when only sun-facing faces are drawn). BLAS per 16³ section: 0.21 ms to rebuild one section, 0.6 ms for 16, and a 5021-instance TLAS rebuild costs 0.36 ms. Alpha-tested leaves through an intersection function add only about 15-20%.
5. **MetalFX is good, but it is not free on a base M4 at Retina output.** The temporal scaler to 3024x1964 costs **3.3-3.5 ms from 0.5x, 3.9-4.3 ms from 0.67x, and 5.5 ms as AA-only at 1.0x** on its own. Its marginal cost inside a whole frame is about 4.5-6 ms. A simple custom TAAU written as a fragment pass costs 0.62 ms from 0.5x and 1.26 ms as native TAA. **Measured whole frames: native + custom TAA (8.0 ms) costs about the same as 0.5x + MetalFX (8.3-8.7 ms).** For a 120 fps target on this GPU, use custom TAAU/TAA by default and offer MetalFX as a quality option for 60-90 fps.
6. **MetalFX frame interpolation is not viable at full Retina resolution on this GPU.** It costs **11.8-12.9 ms per generated frame at 3024x1964** (3.1 ms at 1512x982). It mostly does not overlap other GPU work: in a two-queue test it hid only 15-25% of its cost. It also adds latency. **The MetalFX denoised scaler costs 11-16 ms**, which is too much for real-time on 10 cores.
7. **Measured whole frames** in the synthetic scene, at 3024x1964 output with RT sun shadows, SSR on 30% of the screen, bloom and AgX, all post-processing in fragment shaders:
   - **0.5x + TAAU: 3.4-3.6 ms**
   - **0.67x + TAAU: 4.6-4.7 ms**
   - **native + TAA: 8.0-8.1 ms**
   - **0.5x + MetalFX: 8.3-8.7 ms**
   - **0.67x + MetalFX: 10.8-11.1 ms**

   A real Minecraft frame adds water and translucency, entities, sky and clouds, particles and GUI, which I estimate at +2-4 ms. **120 fps at full Retina output with beautiful shaders is reachable** with 0.5-0.67x internal resolution, a custom TAAU and RT shadows. A 60 fps "ultra" tier has room for native resolution or MetalFX plus RT reflections and RTAO.

---

## 1. The hardware, as measured

### 1.1 Device capabilities (queried at runtime by `bench caps`)

| Item | Value |
|---|---|
| GPU family | Apple9 (same as A17 Pro, M3). Metal 3 and Metal 4. Not Apple10: **no GPU neural accelerators**, which arrived with M5/A19 |
| ALUs / clock / FP32 | 10 cores x 128 = 1280 ALUs, about 1.6 GHz, about 4.3 TFLOPS FP32 (flopper.io / eatyourbytes spec sheets) |
| Memory | LPDDR5X-7500 on a 128-bit bus, 120 GB/s theoretical; **83 GB/s measured** (256 MB blit copy) |
| recommendedMaxWorkingSetSize | 11.84 GB; maxBufferLength 8.88 GB |
| Ray tracing | `supportsRaytracing=1`, `supportsRaytracingFromRender=1` (hardware RT is Apple9+), function pointers in render pipelines =1 |
| Threadgroup memory | 32 KB. Imageblocks: explicit 32 KB, **implicit 256 KB on Apple9** (128 KB on Apple7/8) |
| Tile size | 32x32 without MSAA, 32x16 at 4x MSAA. Up to 8 color render targets. Implicit imageblock up to 128 B per pixel per sample |
| MetalFX | temporal: supported, `supportsMetal4FX=1`, input content scale **min 1.0, max 3.0**. Spatial: yes. Frame interpolator: yes. Denoised scaler: yes (Apple9+), scale 1.0-3.0 |
| Other | BC textures, 32-bit float filtering, read-write texture tier 2, argument buffers tier 2, sparse tile 16 KB, 10 concurrent compile tasks |

Feature table facts (Metal Feature Set Tables, May 2026): Apple9 adds hardware-accelerated mesh shading with up to 1,048,575 threadgroups per mesh grid, indirect mesh draws in ICBs, 64-bit atomics, **intersection function buffers**, acceleration structure build options and address-driven AS builds (Metal 4). Lossy texture compression exists since Apple8. **Mesh shading cannot be combined with function pointers or ray tracing in the same render pipeline.** That is fine here, because the inline-RT lighting draw is a separate vertex+fragment pipeline in the same pass.

### 1.2 What makes Apple GPUs fast or slow

Sources: the Apple TBDR article, WWDC20 10631, the M3/A17 tech talk 111375, plus my measurements.

- **Fast:**
  - Tile memory. Blending, MSAA resolve, programmable blending and G-buffer reads stay on chip.
  - Hidden-surface removal (HSR). Opaque overdraw is shaded once.
  - Lossless compression of private render targets.
  - FP16. On Apple9, FP32, FP16 and integer instructions can issue in parallel ("up to 2x ALU performance"). FP16 also uses fewer registers, and FP16 conversion on load/store is free.
  - Dynamic Caching on Apple9: registers, threadgroup, tile and stack memory share one dynamically allocated cache, so occupancy is managed automatically.
- **Slow:**
  - DRAM round trips. Every store action followed by a load, or every full-screen pass, costs about 80 GB/s worth of bytes.
  - Textures with `ShaderWrite` or `PixelFormatView` usage. These lose compression; measured 2x slower in full-screen passes.
  - Discard or depth writes in fragment shaders, which defeat HSR. Measured impact in this scene was small: 2.06 → 2.14 ms at native when *all* terrain used a discard-capable pipeline. It grows with material cost and overdraw.
  - Very high geometry counts. Vertex and tiling throughput is about **1.2-1.3 G triangles/s**: 5M triangles into a 64x64 target took 3.85 ms. Huge passes can also overflow the parameter buffer and trigger partial renders.
  - Sampling the current depth attachment in the same pass. This is undefined behavior and the barrier workarounds are very expensive.
  - Long dependent chains with no overlap between frames. Apple warns that false inter-frame dependencies hurt MetalFX in particular.

### 1.3 Cost of full-screen passes at 3024x1964 (5.94 MP), measured

GPU ms per pass with the queue kept full (`bench bw`, `bench compress`):

| Pass | RGBA8 | RG11B10F | RGBA16F | RGBA32F |
|---|---|---|---|---|
| Fragment fill (write only) | 0.33 | 0.27 | 0.60 | 1.04 |
| Fragment read+write, ShaderWrite usage (uncompressed) | 0.64 | 0.56 | 1.13 | 2.05 |
| Compute read+write (ShaderWrite) | 0.55 | 0.58 | 1.04 | 2.11 |
| **Fragment read+write, compressible RT (no ShaderWrite), scene-like content** | – | – | **0.31-0.50** | – |
| Same, incompressible random bits: lossless / **lossy** | – | – | 0.90 / **0.49** | – |
| 9-tap separable Gaussian, 2 compute passes | – | – | 2.12 | – |

Rules of thumb at native resolution: **about 0.08 ms per MB moved**. A typical "read 2 textures, write 1" RGBA16F composite costs about 1.5 ms uncompressed and about 0.6 ms compressed. At 0.5x internal resolution every number is ÷4.

> Benchmark gotcha that later agents should know about: re-rendering *identical* data into the same compressible target is short-circuited by the hardware and gives impossible numbers, such as 0.1 ms for a 95 MB pass. Always ping-pong, or vary the content. `compress_check.m` is the clean standalone test.

---

## 2. TBDR specifics, and how to use them for this pipeline

### 2.1 Single-pass deferred with a memoryless G-buffer (measured)

Opaque terrain: 1.78M visible triangles of 5M, frustum culled per chunk, 2 atlas samples per fragment.

| Variant (ms) | 0.5x 1512x982 | 0.67x 2016x1309 | 0.75x 2268x1473 | native 3024x1964 |
|---|---|---|---|---|
| Classic deferred: G-buffer stored (2xRGBA8 + R32F + D32F) + lighting pass | 1.76-1.86 | 1.92-2.17 | 2.01-2.09 | 2.68-2.78 |
| **TBDR single pass, memoryless G-buffer, 4-tap PCF** | 1.58-1.89 | 1.67-1.84 | 1.76-1.83 | 2.09-2.22 |
| Same, but stores normal + distance + depth for post | 1.62-1.97 | 1.75-1.80 | 1.87-2.04 | 2.24-2.32 |
| Same, 16-tap PCF / PCSS | about the same (fragment work is hidden behind geometry) | | | |
| Forward shading (lighting in the terrain fragment shader) | 1.55-1.60 | 1.68-1.73 | 1.74-2.02 | 1.98-2.17 |
| **+ Sodium-style camera-facing face culling** | **1.35** | **1.42** | **1.52** | **1.81** |
| Heavier material (4 aniso samples + ALU) | 1.84 | – | – | 2.81 |

Recipe:

```
render pass (one MTLRenderCommandEncoder):
  color0 = HDR scene     RGBA16F, private, usage RT|ShaderRead (stored)
  color1 = albedo/mat    RGBA8,  MTLStorageModeMemoryless, store DontCare
  color2 = normal+mat    RGBA8 (octahedral) – stored only if SSR/denoiser/TAA needs it; else memoryless
  color3 = linear dist   R32F, memoryless (needed because fragment shaders can't read the depth attachment)
  depth  = Depth32F      stored only if MetalFX/TAA/SSR need it; else memoryless
  draws: opaque terrain → cutout terrain (discard pipelines AFTER opaque) → entities
  then:  full-screen triangle, depth test off, fragment reads [[color(1)]],[[color(2)]],[[color(3)]], writes color0
         (sun + sky light, shadows: shadow-map PCF or inline RT ray, fog)
```

- Load actions: use **Clear** or **DontCare**, never Load, unless the previous contents are really needed. Use a **DontCare** store for everything not read later. mcopt already does this automatically for memoryless attachments (`mcmetal.m:456-460` in `endRender`, and at 864/907).
- **Raster order groups**: needed only when many overlapping light volumes read and modify the same pixel. Minecraft lights through baked light maps, so a single full-screen lighting draw needs none.
- **Tile shaders and imageblocks** (`dispatchThreadsPerTile`): useful for per-tile light culling if dynamic coloured point lights are ever added (torches as real lights), and for a custom MSAA resolve before translucents. The explicit imageblock limit is 32 KB per tile, implicit 256 KB on Apple9. They are **not needed** for the base design. Neighbourhood passes (blur, TAA, SSR) cannot run from tile memory anyway.
- **Order the passes** as SOLID → CUTOUT (discard) → entities → full-screen lighting, then translucents in a forward pass that reads a copy of the scene. Do not interleave discard pipelines with opaque draws (Apple's HSR guidance). Many Iris packs alpha-test in `gbuffers_terrain` for *all* terrain. A native pipeline should keep a separate no-discard pipeline for solid terrain.
- **Position invariance**: if any pass re-renders the same geometry with depth compare `Equal`, compile with `preserveInvariance` and mark `[[position, invariant]]` (WWDC20 10631).

### 2.2 Geometry: the actual bottleneck of the opaque pass and of shadow maps

- The vertex and tiling rate is about 1.2-1.3 G triangles/s. With 1.78M triangles in view, the opaque pass sits at about 1.5 ms whatever the resolution.
- Sodium-style **face-direction culling** saved about 0.3-0.4 ms, roughly 20-25% of the pass. Keep it, as Sodium already does. For shadow passes, draw only the 3 sun-facing face directions: 5.2 → 2.8 ms.
- Option: **mesh shaders**, hardware-accelerated on Apple9, could do per-section frustum, occlusion and face culling on the GPU. Meshlets stay on chip; write no primitive for a culled triangle instead of emitting it degenerate (M3/A17 tech talk). This is a later optimization: mcopt already culls on the CPU and the GPU.

### 2.3 Precision and formats

- Use `half` everywhere in shading: colors, normals, BRDF terms. Keep `float` for positions, depth and distances, and matrices.
- HDR targets:
  - `RG11B10Float` (4 B) where alpha isn't needed. Bandwidth equals RGBA8 (0.27 ms fill).
  - `RGBA16Float` (8 B) for TAA history, MetalFX input and output.
  - Avoid RGBA32F.
- Normals: octahedral in RGBA8 or RG8 snorm. The MetalFX denoiser wants **signed world-space normals**, but it is too expensive here anyway.
- Shadow maps: `Depth16Unorm` is enough for an orthographic 512-block range (0.008-block steps) and halves bandwidth compared with D32F.
- Consider **lossy compression** (`MTLTextureCompressionTypeLossy`) for intermediate HDR targets that tolerate it, such as bloom chain, VL and cloud buffers. It is guaranteed 2x. It requires private storage and no ShaderWrite or PixelFormatView usage.

---

## 3. MetalFX on M4 at 3024x1964

### 3.1 Temporal scaler: API facts (SDK 26.0 headers, `MTLFXTemporalScaler.h`)

- Inputs:
  - jittered `colorTexture` (formats: RGBA16F, BGRA8 and RGB10A2 all work, at about the same cost)
  - `depthTexture` (Depth32F; set `depthReversed`, since Minecraft 26.x and mcopt use reversed Z)
  - `motionTexture` (RG16F)
  - optional `exposureTexture` (1x1 R16F) or `autoExposureEnabled`
  - `reactiveMaskTexture` (macOS 14.4+, values 0..1, where 1 means ignore history)
  - `preExposure`
  - `jitterOffsetX/Y`, in input pixels, range -0.5..0.5
  - `motionVectorScaleX/Y`
  - `reset`
  - `inputContentWidth/Height`: dynamic resolution, enabled with `inputContentPropertiesEnabled` plus `inputContentMinScale/MaxScale`
- Output: private storage only. Measured output usage = Read|Write|RT, so **the output is not a compressible RT**, and every later full-res pass that reads it pays the uncompressed rate.
- Motion vector convention: with scale 1 a vector points to the pixel's position in the *previous* frame, in pixels. Apple's example: an object moving down-right 10 px has motion (-10,-10). The usual setup stores UV deltas and sets scale = (inputWidth, inputHeight), as mcopt does in `mc_fx_upscale`. **Vectors must be de-jittered** (WWDC26 359).
- Jitter: Halton(2,3). Apple recommends **32 phases for 2x** ("about eight samples per output pixel"). mcopt uses `PHASES = max(8, 8/scale²)` (`MetalFx.java:32`), which gives 32 at 0.5x. Correct.
- Texture mip bias for temporal upscaling = `log2(inW/outW) - 1`, so -2 at 0.5x and -1.58 at 0.67x (WWDC22 10103). For Minecraft's nearest-filtered pixel-art atlas, start milder (-1 at 0.5x) because of shimmer.
- Placement: after the jittered render and **before** post-processing (bloom, tonemap, motion blur) and UI. Exposure must roughly match the tonemapper's. A wrong value causes flicker and ghosting. Debug it with `MTLFX_EXPOSURE_TOOL_ENABLED`: a constant mid-grey checkerboard means the value is correct (WWDC25 211).
- Reactive mask: only for things without correct motion or depth (particles, rain, water surface animation). Apple says to use it only if raising the input resolution is not an option.
- Scale: the device allows up to 3.0, but Apple advises "not higher than 2x scaling if it is not needed". Practical presets: 0.5 = performance, 0.58-0.67 = balanced/quality.
- Creation: `requiresSynchronousInitialization=YES` blocks for **160-1840 ms**, measured; the first instance pays the most. With NO, MetalFX returns immediately and uses a slower interim upscaler while it compiles. Create the scaler at window resize, not every frame.
- `MTL4FXTemporalScaler` (made with `-newTemporalScalerWithDevice:compiler:`, encoded into an `MTL4CommandBuffer`): **same speed** (about 4.0 ms wall time versus 3.4 ms with the classic API in `mtl4fx_check.m`). Adopt it only if the backend moves to Metal 4 command buffers.
- Quality: MetalFX temporal is FSR2-class (its legal notices reference AMD FSR; Digital Foundry judged it competitive in RE Village). It is better than a simple TAAU, mainly on disocclusions and thin geometry.

### 3.2 Measured cost (`bench fx`, `fx2`, `overlap`, `frame`)

| MetalFX effect → 3024x1964 | GPU ms |
|---|---|
| Temporal, input 1008x655 (0.33x) | 2.6 |
| **Temporal, input 1512x982 (0.5x)** | **3.3-3.5** (serial 3.7-4.5) |
| **Temporal, input 2016x1309 (0.67x)** | **3.9-4.3** |
| Temporal, input 2268x1473 (0.75x) | 4.5 |
| Temporal, 3024x1964 (AA only, 1.0x) | 5.5 |
| CPU encode cost | 0.01-0.03 ms per frame |
| **Marginal cost inside a whole frame** (MetalFX frame minus TAAU frame, plus TAAU's own cost) | **about 4.5-6** |
| Spatial scaler, 0.5x or 0.67x input | 1.7 |
| Denoised scaler 0.5x / 0.67x (creation takes 2.5-2.7 s) | **11.0 / 16.2** |
| **Frame interpolation, 3024x1964 BGRA8** (depth and motion at full size, or at 0.5x paired with the scaler) | **11.8-12.9 per generated frame** (10.1 when paired) |
| Frame interpolation, 1512x982 | 3.1 |
| Two queues, interpolation running alongside rendering | wall time 446-450 ms versus a sum of 541-615 ms: only 15-25% hidden |
| Two queues, temporal scaler alongside rendering | 422-429 ms versus a sum of 438-510 ms: mostly not hidden |

Conclusions:

- At 120 fps the temporal scaler alone takes about 40-55% of the frame on this GPU. It pays off only when the per-pixel work it removes is larger than about 5 ms, for example heavy RT or volumetrics at native resolution. For the default 120 fps tier, a **custom TAAU** costs 0.62 ms from 0.5x, 0.72 ms from 0.67x and 1.26 ms as native TAA, as compressible fragment passes. Keep MetalFX as a "quality" option and use dynamic resolution.
- **Frame interpolation**: generating one 3024x1964 frame costs more than rendering a real 0.5x frame. Apple asks for a base of at least 30 fps and an interpolation step placed after tonemapping, with three UI strategies (composited, offscreen, every-frame). Pacing must show at most two histogram buckets in the Metal HUD, and interpolation adds latency of about half to one base frame. **Recommendation: leave mcopt's `-Dmcopt.metal.framegen` off at full Retina on M4.** It could only make sense for an "output 1512x982" mode or on M4 Pro/Max class GPUs.
- **Denoiser**: Apple's guidance on inputs is useful (clean albedo, de-jittered motion vectors, world normals in a signed format, primary-surface replacement for mirrors). At 11-16 ms it is not a real-time tool on 10 cores, so use hand-written temporal accumulation instead (see section 4).

### 3.3 Problems in mcopt's current MetalFX path (`MetalFx.java`, `mcmetal.m:1386-1467`)

1. **Motion is camera-only** (`motion_cs` reprojects depth, `mcmetal.m:44-56`). Entities, players, animated water and particles will ghost or smear. A native shader pipeline should write per-object motion in the G-buffer pass from previous-frame model matrices, and use the reactive mask for particles and weather.
2. **No `exposureTexture` and no `autoExposureEnabled`**. Apple calls the exposure value "essential for a high quality outcome". It hardly matters for vanilla SDR, but it does for HDR shaders.
3. **An extra full-screen blit**: `copyFromTexture:fx->output toTexture:dst` (`mcmetal.m:1466`) costs about 0.3-0.5 ms at Retina resolution. Make the main target satisfy `outputTextureUsage` (Read|Write|RT) and let MetalFX write into it directly.
4. Texture mip bias isn't adjusted for the lower internal resolution.

---

## 4. Hardware ray tracing on M4 for a voxel world

### 4.1 Measured (`bench rt`, `rtflags`, `rtalpha`)

The scene has 5,021 non-empty 16³ sections and 4.99M triangles (2.5M quads), in a 32x32-chunk world. Vertex buffer 152 MB. Vertex format `float3` with a 16-byte stride; Sodium's packed format would have to be expanded for the BLAS, or use `MTLAttributeFormat` options.

| Item | Measured |
|---|---|
| BLAS per section, all 5,021 built in one encoder (world load) | 119-131 ms GPU (**74 ms with `PreferFastBuild`**). 506 MB before compaction (`MinimizeMemory`: 481 MB). Scratch 285 MB |
| Compaction of one section | 227,904 → 103,640 B (**-55%**), so about 230 MB for the whole world when compacted |
| **Rebuild 1 section BLAS (block placed or broken)** | **0.21 ms**, dominated by fixed overhead |
| **Rebuild 16 sections in one encoder (chunk streaming)** | **0.58-0.61 ms** |
| **TLAS rebuild, 5,021 instances** | **0.35-0.36 ms per frame** (1.04 MB) |
| Single flat BLAS of everything | 142-145 ms build; traces 30-35% faster than the TLAS (shadow 0.28 versus 0.42 ms at 0.5x). Unusable for edits |
| Usage flags on section BLASes | `PreferFastBuild` builds 38% faster with **identical trace speed**; `PreferFastIntersection` / `MinimizeMemory` show no measurable trace gain |

Per-pixel ray costs, with rays starting from G-buffer positions (ms):

| Effect | 0.5x | 0.67x | 0.75x | native |
|---|---|---|---|---|
| **Sun shadow, 1 ray/px (any-hit, opaque)** | **0.42** | **0.67** | 0.82 | 1.60 |
| Soft sun shadow, 1 ray jittered in a cone | 0.45 | 0.79 | 1.00 | 1.75 |
| Sun shadow with **alpha-tested leaves** (non-opaque geometry plus an intersection function; leaves = 10% of quads) | 0.47 | 0.77 | – | – |
| AO, 1 ray/px, 4 blocks | 0.60 | 1.13 | 1.28 | 2.34 |
| AO, 4 rays/px | 1.78 | 3.25 | 3.89 | 7.24 |
| Mirror reflection, 1 closest-hit ray on *every* pixel | 0.79 | 1.41 | 1.71 | 3.16 |
| Primary visibility from the camera | 0.78 | 1.36 | 1.73 | 3.09 |
| **TBDR lighting pass with the shadow ray traced inline in the fragment shader** (whole G-buffer + lighting pass) | **1.65** (versus 1.58 with a shadow map) | 1.90 | 1.87 | 2.39 (versus 2.09-2.22) |

### 4.2 Design guidance

- **Structure**: one primitive AS per non-empty 16³ section, allocated from an `MTLHeap` so a single `useHeap:` makes them all resident; pass `stages:MTLRenderStageFragment` when tracing from fragment shaders. Build with `PreferFastBuild`. Compact sections that have been stable for a few seconds: `writeCompactedAccelerationStructureSize` → `copyAndCompact`. Rebuild the TLAS only on frames where a section was added, removed or rebuilt, since instances are static and identity-transformed.
  - Use region-relative coordinates for blocks far from the origin; float precision degrades beyond about 100k blocks.
  - Mask bits per instance separate terrain, leaves, water and entities.
- **Batch all dirty sections of a frame into one encoder**, each with its own scratch slice (Apple: batch builds and reuse scratch). The cost is about 0.2 ms plus roughly 0.025 ms per extra section.
- **Use the intersector object API, not intersection queries.** Queries add scratch traffic and disable the Apple9 hardware **reorder stage**. Write one small intersection function per logical routine (leaf alpha, glass) rather than an uber-function, and keep the ray payload small (M3/A17 tech talk). Metal 4 **intersection function buffers** (Apple9) allow per-ray-type function sets: `set_geometry_multiplier(nRayTypes)` / `set_base_id(rayType)`.
- **Foliage**: mark leaf, grass and flower geometry non-opaque and alpha-test the atlas inside the intersection function (+15-20%, measured). Give each triangle its UV and atlas tile with per-primitive data (`primitiveDataBuffer` on the triangle geometry descriptor; `result.primitive_data` in MSL) instead of looking it up through index buffers.
- **Entities and block entities**: either a small rasterized entity shadow map (about 1024², 32-48 block radius, a few thousand triangles, under 0.2 ms (est.)), or per-entity BLASes rebuilt each frame in one batch plus a separate entity TLAS level or instance mask.
- **What to ray trace in the 120 fps tier**:
  - The sun and moon shadow, inline in the TBDR lighting draw. It is crisp at any distance with no acne or distortion, and replaces the 2.8-5.2 ms shadow-map pass.
  - Optionally reflections on water pixels only: measured cheaper than 24-step SSR on the same pixels (whole frame 10.46 versus 11.5 ms at 0.67x + MetalFX), and it reflects off-screen geometry. Hit shading then needs per-primitive material plus one more shadow ray.
- **60 fps tier**: add RTAO (1 ray, 2-4 blocks, temporal accumulation) and soft shadows (1 cone ray plus temporal accumulation). **Do not** plan around the MetalFX denoiser on 10 cores. Use a cheap custom temporal accumulation with variance clamp, reusing the TAA reprojection, at about 0.2-0.4 ms (est.).
- Volumetric light needs a visibility query per ray-march step. Keep a cheap low-resolution shadow map for VL only (1024², 96-128 block radius, sun-facing faces, about 0.6-0.9 ms every frame or cached), or march fewer steps with RT any-hit rays at quarter resolution (est. 0.4-0.8 ms).

---

## 5. Shadows and post-processing effects for a Minecraft look

### 5.1 Shadow maps when not using RT (fallback for M1/M2, whose ray tracing is not hardware-accelerated)

- What packs do: Complementary r5.9.3 uses **one distorted orthographic map**. `shadowMapResolution` = 2048 by default and 4096 at `SHADOW_QUALITY>=5` (`lib/common.glsl:492-503`). `shadowDistance` is 64-256 depending on the profile (256 for the COMPLEMENTARY profile, 192 for HIGH; `shaders.properties`). The distortion is `distortFactor = dist*shadowMapBias + (1-shadowMapBias)` with `shadowMapBias = 1 - 25.6/shadowDistance` (`lib/common.glsl:740`, `lib/lighting/shadowSampling.glsl:4`). It uses hardware filtering (`shadowHardwareFiltering = true`, `pipelineSettings.glsl`).
- **Measured cost is in geometry, not resolution**:

| Shadow pass (ms) | Cost |
|---|---|
| 2048², all 5M triangles | 5.15-5.20 |
| 4096², all 5M triangles | 5.7-6.8 |
| 2048², back-face culled | 4.6 |
| 2048², sun-facing face directions only | 2.8 |
| Sun-facing, chunks within 128 / 96 / 64 / 32 blocks | 0.90 / 0.61 / 0.34 / 0.13 |

- **Recommended fallback**: 2-3 cascades in an atlas. The near cascade (about 32-64 blocks) re-renders every frame (0.13-0.34 ms). Far cascades update every 4-8 frames, staggered, or when the sun angle moves more than about 0.1°, or when a section inside them changes. Draw only sun-facing faces. Snap cascades to texel increments to avoid shimmer. Measured "cached cascades" average 0.7-1.0 ms per frame. Use Depth16.
- Filtering: hardware 2x2 bilinear compare (`sample_compare` with a linear compare sampler) plus 4-8 Poisson taps rotated per pixel by interleaved-gradient noise, resolved by TAA. In the TBDR lighting pass, 4 versus 16 taps versus PCSS made no measurable difference because the pass is geometry-bound.

### 5.2 Water reflections

- Screen-space reflection (SSR) is expensive on Apple GPUs because of scattered R32F reads. Full screen at 16 steps: 1.12 ms at 0.5x and **6.1 ms at native**; 32 steps: 1.39 / 8.1 ms.
- Run it only on water and reflective pixels, from a stencil or material mask:
  - 30% of rows at 24 steps is already included in the frame numbers.
  - March at half resolution with Hi-Z, add a binary refinement (4 steps), and fall back to the sky probe.
- **On M4, RT reflections for water are a strong alternative** (section 4.2).

### 5.3 Other effects

- **Volumetric light** (god rays): 16 shadow taps at half resolution with dither plus temporal accumulation: **0.32 ms at 0.5x**, 0.47 at 0.67x, 0.80 at native; 32 taps: 0.43 / 0.67 / 1.16.
- **SSAO**: 8 samples at half resolution: 0.08 / 0.16 / 0.36 ms (0.5x / 0.67x / native). That is cheap. Minecraft's vertex AO plus SSAO, or RTAO in the 60 fps tier.
- **Bloom**: 13-tap downsample (CoD/Jimenez) over 6 levels plus tent upsample with additive blending, **fragment passes into compressible RGBA16F (or lossy / RG11B10F) targets: 0.74 ms** at the 3024x1964 output (the compute version is 1.23 ms). Computing the first level from the *internal-resolution* image before upscaling saves the largest full-resolution read (est. 0.2-0.3 ms).
- **Tonemapping**: AgX (Troy Sobotka; Blender 4.x default; the minimal polynomial fit used in `bench.metal:agx()`) gives the most "filmic" saturated-highlight handling and suits the BSL/Complementary look. Alternatives are ACES fitted (Hill/Narkowicz) and Khronos PBR Neutral. Measured final pass (bloom composite + AgX + vignette → 8-bit): **0.35 ms as a fragment pass** versus 0.86 ms in compute. Merge colour grading, vignette and film grain into this single pass.
- **XDR display (EDR)**: the Liquid Retina XDR can show more than SDR white. Configure the layer for EDR and tonemap AgX to the headroom reported each frame by `NSScreen.maximumExtendedDynamicRangeColorComponentValue`, so bloom and sun highlights really glow. Cost (est.): +0.1-0.2 ms for the 8 B/px output plus compositor work.
  - Layer settings: `wantsExtendedDynamicRangeContent = YES`, `pixelFormat = RGBA16Float`, colour space `extendedLinearDisplayP3`.
  - mcopt's layer is currently `BGRA8Unorm` + `framebufferOnly` (`mcmetal.m:1088-1096`).
  - Make it optional ("XDR mode").
- **AA and upscaling**, measured on whole frames:

| Mode | Cost / note |
|---|---|
| Native, no AA | 6.6-7.2 ms. At 254 ppi, aliasing is less visible |
| Native + custom TAA | 8.0-8.3 ms |
| 0.5x + MetalFX | 8.3-8.7 ms |
| 0.67x + custom TAAU | 4.6-4.7 ms |

  Use temporal accumulation as the stable base. MSAA 4x is cheap on Apple TBDR for edges, because the GPU tracks unique colours per pixel, but it does not fix texture or shader aliasing, and with a G-buffer it multiplies tile memory. It is only worth considering for a forward "lite" tier.

### 5.4 Clouds, sky, translucents (not in the synthetic scene; estimates)

- Volumetric clouds: ray-march at quarter to half resolution with 8-16 steps, temporal reprojection and a bilateral upsample: 0.4-1.2 ms (est.).
- Sky: a small analytic or LUT sky (64x32 to 256x128 transmittance and multiscatter LUTs) costs under 0.1 ms (est.).
- Water and translucents: a forward pass on about 20-40% of pixels, reading the opaque HDR copy for refraction plus fog: 0.3-0.7 ms (est.).

---

## 6. Metal 4 on macOS 26: what actually helps

None of these change the GPU cost of a pass much. They reduce CPU overhead, stutter and pass count:

1. **Color attachment mapping** (`MTLLogicalToPhysicalColorAttachmentMap`, render pass with `supportColorAttachmentMapping`). This is **the most relevant feature for a shaderpack runtime.** Iris programs each write a different `RENDERTARGETS`/`DRAWBUFFERS` subset of `colortex*`. Without mapping, every subset change needs a new render pass, which on TBDR means a store and reload of every attachment. With mapping, one pass can keep all colortex targets attached and remap per pipeline.
2. **Flexible render pipeline states** (`MTL4Compiler`, unspecialized colour formats, write masks and blending, then `newRenderPipelineStateBySpecializationWithDescriptor`). Shaderpacks create many pipeline variants that differ only in blend or format. Specialization is near-instant, which avoids compile hitches.
   - Compile on `maximumConcurrentCompilationTaskCount` threads (10 here) at `QOS_CLASS_DEFAULT`.
   - Fully compile the hot pipelines in the background, because the unspecialized fragment output costs a little at runtime.
   - Use the pipeline dataset serializer plus `metal-tt` for ahead-of-time archives of the built-in pipeline.
3. **Residency sets**: mcopt already uses one (`mcmetal.m:136-176`). Keep few, large sets attached to the queue. The CAMetalLayer exposes its own residency set.
4. **Barriers** (`barrierAfterQueueStages:beforeStages:`) and command allocators: explicit dependencies remove the false inter-frame serialization that Apple warns hurts MetalFX. This gives better overlap of frame N+1 geometry with frame N post.
5. **Unified compute encoder**: dispatches, blits and AS builds run concurrently in one encoder without extra synchronization. That suits BLAS rebuilds, TLAS, motion vectors and post compute.
6. **Render encoder suspend/resume** across command buffers: parallel CPU encoding of a single GPU pass, for example the terrain G-buffer split across threads.
7. `MTL4FX*` effects: same speed as the classic API (measured), required only if encoding into MTL4 command buffers.
8. Metal 4 ML encoder and tensor ops: the M4 has no GPU neural accelerators (M5 does), so they are not useful here.

Recommendation: there is no need to port mcopt's backend to MTL4 command buffers before the shader pipeline exists. Adopt (1) and (2) when building the shaderpack runtime, and (4) to (6) when optimizing frame overlap.

---

## 7. Frame budgets at 3024x1964 output

**Measured components** are from the synthetic scene. **(est.)** marks parts that scene doesn't have. "Internal" means the jittered render resolution.

### 7.1 120 fps target (8.33 ms GPU)

Recommended: **internal 0.5-0.6x, custom TAAU, RT shadows.**

| Pass | Resolution | ms |
|---|---|---|
| BLAS rebuild of dirty sections + TLAS rebuild (only on frames with changes) | – | 0-0.6 (avg about 0.2) |
| Opaque + cutout terrain, TBDR single pass with **inline RT sun shadow**, face culling | 1512x982 | 1.4-1.65 |
| Entities, block entities, hand into the same G-buffer | internal | 0.3-0.8 (est.) |
| Sky LUT + clouds (quarter-res ray march + reprojection) | ¼-½ internal | 0.4-0.8 (est.) |
| Motion vectors (per-object, written in the G-buffer pass, otherwise camera pass) | internal | 0.1 |
| Volumetric light, 12-16 taps against a cached 1024² VL shadow map, half-res + TAA | ½ internal | 0.3-0.5 (map amortized 0.2) |
| Water / translucents forward + refraction | internal | 0.3-0.6 (est.) |
| Water reflections: SSR on water pixels only (or 1 RT ray per water pixel) | internal | 0.3-0.5 |
| **Custom TAAU → 3024x1964** (fragment, compressible history) | output | 0.62-0.72 |
| Bloom (fragment, compressible; first level from internal image) | output pyramid | 0.5-0.74 |
| Final: bloom + AgX + grade → drawable | output | 0.35 |
| GUI + present | output | 0.2-0.4 (est.) |
| **Total** | | **about 5.2-7.7 ms** → 120 fps with headroom. Measured core of this list (synthetic): **3.4-3.6 ms** |

Same table at 0.67x internal: core measured at 4.6-4.7 ms, total about 6.5-9 ms. Use dynamic resolution between 0.5 and 0.67 to hold 120 Hz.

### 7.2 60 fps target (16.7 ms GPU)

Two good options:

| Option | Pass list | Total |
|---|---|---|
| **A. Native everything + custom TAA** (sharpest) | Opaque TBDR + inline RT shadow (native) 2.0-2.4 · entities 0.5-1.0 (est.) · clouds 0.6-1.2 (est.) · VL native half-res 0.8 · translucents 0.5-0.8 (est.) · SSR or RT reflections on water 0.6-1.2 · RTAO 1 ray + accumulation 1.2-2.6 · TAA 1.26 · bloom 0.74 · final 0.35 · GUI 0.3 | about **9-12 ms** (measured core "native + TAA + RT shadows + SSR" = 8.0-8.1) |
| **B. 0.67-0.75x + MetalFX quality** | Core measured 10.8-12.0 ms + RTAO/soft shadows/clouds/translucents 2-4 (est.) | about **13-16 ms** |

Option A is cheaper *and* sharper on this GPU. MetalFX (B) becomes the better choice only when native-resolution per-pixel work grows past about 5 ms: many RT rays, high-quality volumetrics, parallax occlusion mapping.

### 7.3 What *not* to spend on, at Retina output on M4

| Avoid | Cost |
|---|---|
| Full shadow map of the whole render distance every frame | 2.8-5.2 ms |
| Full-screen SSR | 6-8 ms at native |
| MetalFX frame interpolation | about 12 ms |
| MetalFX denoiser | 11-16 ms |
| Compute post-processing writing ShaderWrite textures | 2x cost |
| Classic stored G-buffer | +0.3-0.6 ms |
| 4096² shadow maps | +0.5-1.5 ms over 2048² |
| RGBA32F intermediates | – |
| More than about 3-4 full-resolution passes after upscaling | – |

---

## 8. Recommended native pipeline (concrete)

```
per frame (one MTLCommandBuffer, targets double-buffered so frames overlap):
 0. compute encoder: rebuild dirty section BLASes (PreferFastBuild, batched) → TLAS (only if changed);
    entity BLASes or entity shadow map; sky LUTs (tiny)
 1. [optional] VL/cloud shadow map 1024², sun-facing faces, cached/staggered (Depth16)
 2. RENDER PASS @ internal (jittered):
      attachments: HDR RGBA16F (store) | albedo RGBA8 (memoryless) | normal RGBA8 (store) | dist R32F (memoryless)
                   | motion RG16F (store) | depth D32F (store)
      solid terrain (no-discard pipeline) → cutout (discard) → entities/hand
      full-screen lighting draw: reads [[color(n)]], traces 1 any-hit sun ray (TLAS + useHeap fragment),
      sky light, block light (lightmap), fog → HDR
 3. RENDER PASS @ internal: translucents (water/glass) forward, sampling an HDR copy for refraction
 4. fragment passes @ ½ internal: clouds, VL (temporal); SSR or compute RT reflections on water mask
 5. TAAU fragment pass internal → 3024x1964 RGBA16F history (compressible; no ShaderWrite)
      [quality option: MetalFX temporal instead, with exposure + reactive mask for particles/rain]
 6. bloom pyramid (fragment, compressible/lossy), final AgX/grade → drawable (BGRA8, or RGBA16F EDR)
 7. GUI at native on top; present (CAMetalDisplayLink / mcopt pacing at 120 Hz)
```

All per-pixel shading should use `half` except positions and depth. Every intermediate image should have usage `RenderTarget|ShaderRead` and private storage. Use `MTLTextureCompressionTypeLossy` for bloom, VL and clouds.

---

## 9. mcopt integration notes (read-only findings)

- The public repo already contains **hooks for a native shading pipeline and a shaderpack runtime whose sources are not published**:
  - `mcmetal.m:458` and `:490`: "native shading pipeline (mcshade.m)", memoryless G-buffer handling.
  - `mcmetal.h:64-65`: `mc_blit` / `mc_frame_cmd` for the shaderpack runtime and native shading.
  - `MetalEncoder.java:237-240`: "Shaderpack runtime (-Dmcopt.pack) … native shading's lite tier".
  - `PassDelegate.java`: lets a delegate take over a pass's pipelines.
  - `MetalBridge.java:19`: "the shaderpack runtime binds its own functions (mcpack.m)".
  - `FrameGen.java:54`: `-Dmcopt.shade=native`.
  - `lod/Lod.java:37`.
  - The README "Plans" lists "Our own shaders (BSL-level) as an option".

  These are exactly the seams a new pipeline should plug into: per-pass delegate, frame command buffer access, memoryless attachments.
- `MetalFx.java` / `mc_fx_*`: correct jitter phases and reversed depth. Gaps: camera-only motion, no exposure, an extra 0.3-0.5 ms blit (section 3.3).
- `mc_layer_configure` (`mcmetal.m:1088`): BGRA8 + framebufferOnly. Switch to RGBA16Float with EDR for an XDR mode.
- **Complementary disables its custom-image features on Macs** (`#if defined IRIS_FEATURE_CUSTOM_IMAGES && … && !defined MC_OS_MAC`, `lib/common.glsl:33`), because macOS OpenGL 4.1 lacks image load/store. A Metal pipeline (read-write texture tier 2, compute) can support voxelized colored lighting and floodfill. Write those volumes from compute; they aren't full-screen render targets, so the ShaderWrite penalty doesn't matter much.

---

## 10. Benchmark harness (reusable)

Location: `work/bench-apple-gpu/` (absolute: `/Users/gaelquintanilla/Documents/Minecraft Metal Shaders/work/bench-apple-gpu/`)

**Files**

| File | Contents |
|---|---|
| `bench.m` | Host code, about 1,770 lines |
| `bench.metal` | MSL compiled at runtime (no offline `metal` toolchain is installed), about 700 lines. Includes: TBDR G-buffer and lighting variants, inline RT lighting, shadow PCF/PCSS, VL, SSAO, SSR, bloom (compute and fragment), AgX final, TAAU (compute and fragment), RT shadow/AO/reflection/primary kernels, leaf alpha intersection function |
| `compress_check.m` | Clean standalone test for framebuffer compression |
| `mtl4fx_check.m` | Classic versus Metal 4 MetalFX scaler |
| `results-*.txt` | Raw outputs of every run quoted above (`results-frame-v1-unoptimized.txt` is the first frame run with compute post and full shadow maps) |

**Build**

`clang -fobjc-arc -O2 bench.m -framework Metal -framework MetalFX -framework Foundation -framework QuartzCore -o bench`

**Run**

`./bench caps validate bw compress tbdr hsr post rt rtflags rtalpha fx fx2 overlap frame` (any subset). The terrain is generated in about 0.3 s.

**Methodology**

- Each test enqueues 16-600 command buffers back to back so GPU clocks ramp.
- Reported figure = (last GPUEndTime − first GPUStartTime) / n, which is throughput.
- "median/cb" is per-command-buffer GPU time. It is inflated when consecutive buffers overlap, so ignore it for render passes.
- Run-to-run noise is ±5-8%.

**Caveats**

- The scene is synthetic. It has no water, entities, particles, clouds or GUI.
- Materials use 2 atlas samples.
- The terrain includes caves, so the shadow pass sees about 5M triangles.
- Measured on battery power. Clocks were stable, with no thermal warnings.
- The user's game was not running.

---

## Sources

- Apple, *Metal Feature Set Tables* (PDF, May 21 2026): https://developer.apple.com/metal/Metal-Feature-Set-Tables.pdf
- Apple docs, *Tailor your apps for Apple GPUs and tile-based deferred rendering*: https://developer.apple.com/documentation/metal/tailor-your-apps-for-apple-gpus-and-tile-based-deferred-rendering
- WWDC20 10631, *Bring your Metal app to Apple silicon Macs*: https://developer.apple.com/videos/play/wwdc2020/10631/
- Tech Talk 111375, *Explore GPU advancements in M3 and A17 Pro*: https://developer.apple.com/videos/play/tech-talks/111375/
- WWDC22 10103, *Boost performance with MetalFX Upscaling*: https://developer.apple.com/videos/play/wwdc2022/10103/
- WWDC23 10128, *Your guide to Metal ray tracing*: https://developer.apple.com/videos/play/wwdc2023/10128/
- WWDC25 211, *Go further with Metal 4 games* (MetalFX upscaling, interpolation, denoiser, Metal 4 RT): https://developer.apple.com/videos/play/wwdc2025/211/
- WWDC25 254, *Explore Metal 4 games* (encoders, argument tables, residency, barriers, MTL4Compiler, color attachment mapping): https://developer.apple.com/videos/play/wwdc2025/254/
- WWDC26 359, *Build real-time neural rendering pipelines with Metal* (MetalFX denoising practices, de-jittered motion vectors, M5 neural accelerators): https://developer.apple.com/videos/play/wwdc2026/359/
- Apple docs, MetalFX overview: https://developer.apple.com/documentation/metalfx ; *Displaying HDR content in a Metal layer*: https://developer.apple.com/documentation/metal/displaying-hdr-content-in-a-metal-layer ; CAMetalDisplayLink: https://developer.apple.com/documentation/quartzcore/cametaldisplaylink
- macOS 26.0 SDK headers: `MetalFX/MTLFXTemporalScaler.h`, `MTLFXFrameInterpolator.h`, `MTLFXTemporalDenoisedScaler.h`, `MTL4FX*.h`, `Metal/MTLAccelerationStructure.h` (usage flags `PreferFastIntersection`, `MinimizeMemory` new in 26.0), `Metal/MTLTexture.h` (compression rules)
- Apple Developer Forums on MetalFX cost: https://developer.apple.com/forums/thread/728545 , https://developer.apple.com/forums/thread/715215
- M4 GPU specs: https://www.eatyourbytes.com/applecpu-detail/apple-m4-10-core-gpu/ , https://flopper.io/gpu/apple-m4
- MetalFX / FSR relationship and Digital Foundry view: https://www.3dtested.com/pc-components/gpus/amd-fsr-is-the-building-block-for-apples-metalfx-upscaling-tech-the-apps-legal-info-references-the-usage-of-amd-fsr , https://leclaireur.fnac.com/article/191987-apple-concurrence-avec-succes-le-dlss-et-le-fsr-avec-metalfx/
- Cyberpunk 2077 on M4-class Macs (MetalFX and frame generation defaults): https://www.notebookcheck.net/Cyberpunk-2077-tested-on-M1-Air-M1-Max-M3-Max-and-M4-MacBook-Pro-Here-s-how-well-it-runs-on-each.1061925.0.html
- Complementary Reimagined r5.9.3 sources (local, extracted by another agent): `work/packs/ComplementaryReimagined_r5.9.3/shaders/{shaders.properties, lib/common.glsl, lib/pipelineSettings.glsl, lib/lighting/shadowSampling.glsl}`
- mcopt sources (local, read-only): `mcopt/metal/src/main/{native/mcmetal.m, native/mcframegen.m, java/mcopt/metal/MetalFx.java, FrameGen.java, PassDelegate.java, MetalEncoder.java}`
