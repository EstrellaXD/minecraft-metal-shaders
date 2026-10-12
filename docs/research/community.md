# Community research: Minecraft shaders and Metal on Apple Silicon (as of 2026-10-06)

Report name: `community`. Scope: what people on X/Twitter, GitHub, Modrinth and minecraft.net are doing about
(1) mcopt / Noah Dunnagan, (2) Mojang's Vulkan/renderpearl move and the shader mods, (3) native Metal / MetalFX /
frame generation / ray tracing in Minecraft, (4) Claude Opus 5.5 for game and graphics work, and (5) running
shaderpacks fast on M-series Macs. Everything below has a URL or a local path. Items I could not confirm are marked
**UNVERIFIED**. X pages were read logged out through the browser pane. Logged out, replies and profile timelines are
hidden, so reply threads are not covered.

Local clones made for the implementation agents (all `git clone --depth 1`, read-only reference):

| Path (under `work/community/`) | Upstream | Commit / date | License |
|---|---|---|---|
| `vitrail/` | github.com/avpbynf/Vitrail-Shaders | f42e548, 2026-09-27 (v0.12.0-beta) | LGPL-3.0-only |
| `minecraft-metal-renderer/` | github.com/Kausik-Velaga/minecraft-metal-renderer | 342f6e3, 2026-10-02 | MIT |
| `metallum-sergey/` | github.com/SergeyWithNormalName/metallum | 7e1da46, 2026-10-06 | MIT |
| `metallum-kokodio/` | github.com/kokodio/metallum (upstream Metallum) | fe3cc3b, 2026-08-23 | MIT |
| `mcupscaler/` | github.com/ConnRaus/MCUpscaler | ce9a9cc, 2026-10-06 | MIT (bundled DLSS/FFX have their own licences) |

---

## TL;DR: the facts that matter for the decision

1. **Nobody has shipped shaderpacks on mcopt's Metal backend.** None of the 18 mcopt forks contains shader work.
   Upstream mcopt lists "Our own shaders (BSL-level) as an option" under Plans in its README
   (`mcopt/README.md:48`), but no code for it exists yet.
2. **OptiFine-format packs already run on Minecraft 26.3's Vulkan backend: Vitrail** (LGPL-3.0, about 116k lines,
   526 Java files). It rewrites pack GLSL into Vulkan GLSL and hands it to the game's own shaderc, which produces
   SPIR-V. Its compatibility table lists Complementary Reimagined r5.9 as "drawn whole and close to the reference",
   coloured lighting included. It works on Macs through MoltenVK, and the author tests on a Mac mini M4. **Only 23
   of its files touch Vulkan internals** (compute, storage images and buffers, comparison samplers, descriptor sets
   and argument buffers, mip blits, device features). Everything else sits on the public renderpearl and Sodium
   APIs. mcopt's Metal backend consumes the same frontend SPIR-V (through SPIRV-Cross), so porting Vitrail onto
   mcopt is a realistic engineering path.
3. **A GLSL shaderpack loader already runs on a native Metal renderpearl backend:** Kausik-Velaga's "Minecraft Shader
   Loader 0.1.0" (MIT, released 2026-10-03). It runs BSL 10.1.8 defaults on Metal at 115–120 FPS at 1280×720 on an
   M3 Pro. **Its shader-loader module imports zero classes from its own Metal renderer and only uses the public
   `com.mojang.renderpearl.api.*` API**, plus one `frontend.FrontendCommandEncoder` import. Limits: BSL only, no
   compute, vanilla terrain only (no Sodium), and still alpha.
4. **The fastest "native own shaders" data point at exactly the user's resolution:** a Metallum fork
   (SergeyWithNormalName, MIT). It has its own MSL lighting: HDR/EDR, cascaded shadows, voxel GI, SSR and water.
   It ran at **86.6 FPS (L4 shadows) to about 105 FPS (L3) at 3024×1964 at 120 Hz on an M1 Pro**, with render
   distance 16, VSync off and MetalFX off. That is the same panel resolution as the user's MacBook Pro 14".
5. **MetalFX is not free at Retina output.** Measured on an M1 Pro with output at 3024×1964, the MetalFX
   Temporal scaler alone costs **4.9 ms at 50% input and 9.2 ms at 100%**. MetalFX frame interpolation at native
   Retina output costs about **22 ms** together with Temporal 50%, and the author marks 60→120 FI at native
   Retina as "DO NOT RETRY" on M1 Pro. Noah also left MetalFX off by default in mcopt (`MetalFx.java:24`, default
   `mcopt.fx.scale=1`; FrameGen default `false`). **Implication: a 120 FPS target with rich shaders at full Retina
   on a 10-core M4 is unlikely. About 60–100 FPS with good shaders is realistic. 120 FPS needs a lighter,
   Apple-tuned native shader set.**
6. **Iris will not support Vulkan or Metal.** Iris is being discontinued in favour of "Aperture", a new Vulkan
   engine with its own pack format (Slang, no old packs), currently in private beta. Mojang made Vulkan the default
   in 26.4 Snapshot 1 (2026-09-22). On a Mac, Vulkan runs through MoltenVK.
7. Hardware ray tracing on Mac exists: MetalHardwareRayTracing (MHRT) and Rockstar Shaders, both for 26.3. They are
   far too heavy for 120 FPS at Retina on a base M4: about 9–12 ms of tracing at 1080p on an M4 Pro, and 20.8–34.3
   ms of GPU time at 1728×1084 on an M4 Max.

---

## 1. mcopt and Noah Dunnagan (@itsnoahd on X, noahdunnagan on GitHub)

### X posts
- **2026-10-02 12:04, status 2106082788998893692.** https://x.com/itsnoahd/status/2106082788998893692
  Noah asked what the FPS ceiling for Minecraft on a Mac is. His answer: about 1024 median FPS at full native 5K on
  his Apple Studio Display, with default settings. He says he compared against 20 of the top performance mods and
  his single mod was 8x faster with no quality cuts. The post includes a video. Logged out, it showed about 1M
  views, 203 replies, 197 reposts, 7K likes and 4K bookmarks.
- **2026-10-04 12:48, status 2106818802000240891.** https://x.com/itsnoahd/status/2106818802000240891
  He describes the earlier post as a "fun shader experiment" that reached 1M+ people. He then released the Fabric
  mod, saying it made his M4 Max run Minecraft at about 1,000 FPS at 5K 120 Hz, and links github.com/noahdunnagan/mcopt.
  Logged out it showed 557.3K views, 81 replies, 313 reposts, 9K likes and 6K bookmarks.
- **UNVERIFIED.** A search-engine summary says Noah described the mod in the thread as a Metal renderer with Sodium
  terrain (unchanged), a Distant Horizons clone, frame generation and MetalFX, and said he left the MetalFX code in
  but switched it off by default because it did not help much. I could not open the replies. **The source does
  confirm the defaults:** `MetalFx.java:24` reads `mcopt.fx.scale` with default `"1"`, and `FrameGen.java:60` reads
  `mcopt.metal.framegen` with default `"false"`.
- Bio as shown on X: CSE at Railway. The profile timeline is login-gated, so I found no other posts about BSL-level
  shaders, shader screenshots or work in progress.

### GitHub: github.com/noahdunnagan/mcopt
- Created 2026-10-04T17:34Z. At research time: 357 stars, 18 forks, 4 open issues.
- Releases (from `gh api .../releases`):
  - v0.2.0-alpha.1, 2026-10-04: first alpha.
  - v0.2.0-alpha.2, 2026-10-05: Distant Horizons detection makes mcopt stay on OpenGL; less GC from lighting and
    textures; far-terrain flicker fix.
  - v0.2.0-alpha.3, 2026-10-06: caps FPS at twice the refresh rate while chunks generate; smoother pacing at 120 Hz;
    removes mcopt's FPS counter (use F3+F6 instead); the render thread no longer stops mid-frame to wait for the GPU;
    server-side point-of-interest, chunk-tick and chunk-lookup optimizations.
- README Plans (`mcopt/README.md:44-51`): far terrain on by default, **"Our own shaders (BSL-level) as an option"**,
  native bit-exact world generation, more tick and chunk work, faster startup, wider testing.
- README Numbers: Mac mini M4 (10-core GPU, 16 GB), 1920×1080, render distance 16, VSync off: 1072–1119 FPS
  spinning and 994–1002 FPS flying on 0.2.0-alpha.1.
- **Issue #6, "What LLM do you use?" (2026-10-05).** https://github.com/noahdunnagan/mcopt/issues/6
  Noah answered that he is a big fan of Claude Opus 5.5 and finds its code the highest quality.
- Issue #2 and PR #7, by ByteV0rtex. https://github.com/noahdunnagan/mcopt/issues/2 ,
  https://github.com/noahdunnagan/mcopt/pull/7
  - The problem: with VSync off, presenting from the frame's own command buffer stalls vanilla's
    `MappableRingBuffer`.
  - The fix is a second present queue. On an M4 Pro mini at 144 Hz it took the 1% low from 138 to 452 FPS.
  - Noah shipped it in alpha.3 as opt-in `-Dmcopt.metal.presentQueue=true`. On his laptop (2560×1440 window,
    120 Hz) the 1% low went from 491 to 778 FPS.
  - ByteV0rtex later reported (2026-10-06) that alpha.3 with the flag drops to about 200 FPS on the 144 Hz mini.
    Still unresolved, so it matters for pacing work at 120 Hz.
  - ByteV0rtex also says he is building his own Metal mod, "Silicon", on top of Metallum. His fork is
    github.com/ByteV0rtex/metallum, a fork of kokodio/metallum.
- PR #4, by CazzaCoder: Distant Horizons on the Metal backend. https://github.com/noahdunnagan/mcopt/pull/4
  It fixes the fog range in `sodium_terrain.vs.metal`, then tells DH the API is "VULKAN" while Metal is active.
  Result: 160–300 FPS on an M4 Pro, against 60–70 FPS for DH on OpenGL. Generated with Claude Code.
- PR #9: compatibility with Better Block Entities. PR #10, by ssquadteam: Lunar Client support, Sodium 0.9.2, a
  **"Metal Renderer" page in Sodium's video settings with Render Scale (MetalFX) and Frame Generation, switched
  live**, and 26.2 support. https://github.com/noahdunnagan/mcopt/pull/10
- Forks: 18 at research time. I compared each against `noahdunnagan:main` with the compare API. None contains
  shader work. `Sraghav63/mcopt-win` adds a Windows port. The rest are 0–9 commits of CI noise.
- Issue #5 ("it is fake"): the poster saw 718 FPS in-game but about 50 in Apple's HUD. The explanation: the HUD
  counts presented frames, capped by the display. The 50 instead of 60 was a real pacing bug, tracked in #2.

---

## 2. Mojang's Vulkan/renderpearl move, and what it means for Iris and shaders

### Mojang (official)
- **2026-02-18, "Another step towards Vibrant Visuals for Java Edition".**
  https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition
  - Java is switching from OpenGL to Vulkan. macOS gets Vulkan through a translation layer, which Mojang says has
    no performance penalty.
  - OpenGL will be removed after a testing period, with advance notice.
  - Modders are told to move off direct OpenGL use and reuse the internal rendering APIs.
  - The article gives no date for Vibrant Visuals on Java.
- **26.2, 2026-06-16:** Vulkan ships as an experimental "Graphics API" option. Sources: endgametools summary
  (https://endgametools.com/en/minecraft/news/minecraft-vulkan) and https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2 .
  Requirement: Vulkan 1.2 with dynamic rendering and push descriptors.
- **26.3, 2026-09-15:** Default is still OpenGL. Source: the endgametools summary. I did not open the 26.3
  article itself.
- **26.4 Snapshot 1, 2026-09-22:** "Default" now behaves like "Prefer Vulkan", and the game no longer flips the API
  setting automatically after a startup crash. https://www.minecraft.net/en-us/article/minecraft-26-4-snapshot-1
  - The same snapshot rewrote the order-independent-transparency shaders: depth-bin accumulation replaces the
    wavelet math, and the defines changed (`OIT_NUMBER_OF_DEPTH_BINS`, `OIT_TRANSMITTANCE_TARGET_COUNT`).
    Relevant if we port or keep the core shaders forward-compatible.
- No Vibrant Visuals in Java 26.3 (consistent with the established facts).

### Iris team (@IrisShaders)
- **2026-02-18, status 2024298162564059576.** https://x.com/IrisShaders/status/2024298162564059576
  The new mod is named Aperture. It is in private beta, does not support old packs, and the Vulkan rewrite had
  just started. In the quoted reply the team says Iris will be discontinued and that it has worked on a new shader
  mod for two years.
- **2026-05-12, status 2054389223231500347.** https://x.com/IrisShaders/status/2054389223231500347
  Aperture betas went out privately to shader developers, running on 26.2 with Vulkan and Sodium.
- **2026-08-03, status 2084326767725367520.** https://x.com/IrisShaders/status/2084326767725367520
  Still developing Aperture and running into UBO limits on older NVIDIA cards.
- **2026-02-15, status 2022923939178226017.** https://x.com/IrisShaders/status/2022923939178226017
  Reply to Elliot Arledge's 1.16.5 Metal terrain mod: a cool idea, but most of the gain comes from skipping bad
  driver paths.
- Aperture example pack (Slang, public): https://github.com/IrisShaders/Aperture-Example-Pack (last pushed
  2026-01-08).
- Iris itself is still maintained for OpenGL. The `26.3` branch had commits up to 2026-10-04.
  https://github.com/IrisShaders/Iris/tree/26.3
- **Conclusion:** no Iris or Aperture support for Metal is coming. Aperture targets Vulkan, so on a Mac it would
  run through MoltenVK. It has no public release and does not load OptiFine-format packs such as Complementary.

### Shader engines that run on the Vulkan backend today
- **Vitrail Shaders (avpbynf).** https://github.com/avpbynf/Vitrail-Shaders ,
  https://modrinth.com/mod/vitrail-shaders (about 17.5K downloads). LGPL-3.0, 88 stars, 23 forks.
  - Version v0.12.0-beta (2026-09-27) ships `vitrail-0.12.0-beta+mc26.3.jar` (Fabric and NeoForge in one jar).
    It needs Sodium 0.9.x. Issue #453 shows it running with Sodium 0.9.3-alpha.1 on 26.3.
  - **How it works:** it reads OptiFine-format packs from `shaderpacks/` and rewrites each GLSL unit into Vulkan
    GLSL once at load. The game's embedded compiler turns that into SPIR-V. Nothing is translated per frame.
  - **Compatibility** (`work/community/vitrail/docs/compatibility.md`): about 25 packs are listed. Complementary
    Reimagined and Unbound r5.9 (including their top profiles with coloured lighting), BSL, Bliss, Photon,
    Reverie, Sildur's, Solas, Mellow and miniature are drawn "close to the reference". It serves
    `IRIS_FEATURE_` `COMPUTE_SHADERS`, `CUSTOM_IMAGES`, `SSBO`, `PER_BUFFER_BLENDING`, `HIGHER_SHADOWCOLOR`,
    `SEPARATE_HARDWARE_SAMPLERS`, `ENTITY_TRANSLUCENT` and `BLOCK_EMISSION_ATTRIBUTE`.
  - **Mac-specific work** (CHANGELOG and docs):
    - Passes reading more than 16 textures go through Metal argument buffers. Metal allows 16 sampler slots per
      stage when MoltenVK uses push descriptors (`render/WideSamplerSets.java`).
    - Compute threadgroup memory over 32 KB is moved to a buffer (Photon needs 36,864 bytes).
    - Coloured lighting is enabled on Macs: Vitrail stopped telling packs they run on Apple's OpenGL.
    - Names that are Metal or C++ keywords are escaped.
    - The author tests on a Mac mini M4 (issue #397 comment, 2026-09-11).
  - **Mac performance data:**
    - Issue #161 (2026-08-23), https://github.com/avpbynf/Vitrail-Shaders/issues/161 : at 854×480, Complementary
      r5.8.1 Low ran at about 57 FPS on Vitrail with MoltenVK, against about 87 FPS on Iris with OpenGL. Part of
      the cause was about 150 `vkQueueSubmit` per frame, which MoltenVK turns into Metal command-buffer boundaries,
      against 45–50 without Vitrail. Later releases reduced this to about 90–100 per frame.
    - CHANGELOG ("Shadow Reuse"): on a Mac mini M4, Complementary Reimagined defaults at 854×480 with shadow
      distance 32 ran at 116 FPS, or 133 FPS with the shadow map reused.
  - **Open issues relevant to Macs:**
    - #453: crash with 26.3, Sodium 0.9.3-alpha.1 and an Apple M5 when Vitrail's render scale is below 100 (depth
      attachment size mismatch).
    - #447: held-item glitch on a Mac.
    - #397: UI not loading on a Mac with Feather client.
  - **Vulkan-coupled files** (grep of `org.lwjgl.vulkan` and `renderpearl.backend.vulkan` in the 26.3 sources): 23
    files. Key ones:
    - `common/src/mc26.3/java/dev/vitrail/mixin/VulkanBackendMixin.java` (309 lines): enables
      `vertexPipelineStoresAndAtomics`, `shaderStorageImageExtendedFormats`, `independentBlend` and `geometryShader`.
    - `.../VulkanRenderPassMixin.java` (198): pushes descriptors for storage images and buffers and swaps in
      comparison samplers.
    - `common/src/main/java/dev/vitrail/mixin/VulkanCommandEncoderMixin.java` (331): mip chains through
      `vkCmdBlitImage` and pass barriers.
    - `render/PackCompute.java` (1434): compute passes, built through shaderc kind 2.
    - `render/storage/StorageImages.java` (1020) and `StorageBuffers.java` (365).
    - `render/ShadowCompare.java` (255): the hardware shadow-compare sampler, which renderpearl's `GpuSampler`
      cannot describe.
    - `render/WideSamplerSets.java` (225) and `render/DescriptorSetPools.java` (165).
  - Sodium coupling: 25 files import `net.caffeinemc...`, including `DefaultChunkRenderer`, `ShaderChunkRenderer`,
    `DrawBackend`, `ChunkVertexEncoder` and `TerrainRenderPass`. It widens Sodium's chunk vertex format with
    `a_BlockId`, `a_MidTexCoord`, `a_MidBlock`, `a_TangentFrame` and `a_TintAndAo`.
  - The README discloses that AI tools do a real share of the typing and debugging, with a human reviewing.
  - Its docs list related projects: Sulkan, Aperture and Iris.
- **Sulkan.** https://github.com/mravatins/sulkanShaders , https://sulkan.org . GPLv3. A Vulkan shader engine for
  Fabric with its own native pack format, not OptiFine packs. The site says 26.3 and version 0.5.1. The repo README
  still says 26.2. MetalRender's README warns users not to use it.
- **Shaders on Vulkan with MoltenVK on a Mac:** confirmed for Vitrail (above) and Rockstar Shaders (section 3).

---

## 3. Native Metal, MetalFX, frame generation and ray tracing projects for Minecraft on Mac

### Metal renderpearl backends for 26.x (mcopt's peers)
| Project | MC | What it is | Shaders? | Notes |
|---|---|---|---|---|
| **Kausik-Velaga/minecraft-metal-renderer** (MIT, created 2026-10-03) https://github.com/Kausik-Velaga/minecraft-metal-renderer | 26.3 | `MetalBackend` with SPIR-V → SPIRV-Cross → MSL (`MetalShaderCompiler.java`), SDL Metal view | **Yes: optional "Minecraft Shader Loader 0.1.0" runs BSL 10.1.8** | Fully AI-generated with Codex (disclosed). Details below. |
| **Metallum, kokodio** (MIT, 75 stars) https://github.com/kokodio/metallum , https://modrinth.com/mod/metallum-mc (about 5.3K downloads) | 26.2 | The original 26.x Metal backend. Java plus Swift through Panama. "Compatible with Sodium". | No | Many forks: SergeyWithNormalName, LifeDreamer24, yicmwang, ByteV0rtex, EternityQwQ/MetalUniversal (iOS). |
| **Metallum fork, SergeyWithNormalName** (MIT) https://github.com/SergeyWithNormalName/metallum | 26.2, Sodium 0.9.1 | **Its own native renderer:** FP16 HDR/EDR, clustered lights, cascaded sun shadows, voxel occupancy clipmaps, one-bounce voxel GI, SSR and voxel reflections, water caustics, cloud shadows, volumetric light, MetalFX Spatial/Temporal/Dynamic, frame interpolation, ProMotion scheduler | **Yes, its own, no Iris** | About 43k lines of Swift, 6.1k lines of MSL (`src/main/metal/*.metal`), 157k lines of Java. Benchmarks run on an M1 Pro at **3024×1964@120**. Details below. |
| anodic (owenvanvooren, MIT) https://github.com/owenvanvooren/anodic | 26.2 | Metal TAA (jittered camera, depth reprojection) for Metallum 0.0.23+ | AA only | Described by its author as vibe coded. |
| MetalUniversal (EternityQwQ, MIT) https://github.com/EternityQwQ/MetalUniversal | 26.2 | Metallum fork for iOS and macOS | No | |
| MHRT's own Metal backend (section below) | 26.3 | The ray-tracing mod also replaces the backend | RT | M4 Pro, 1920×1080, render distance 16, no RT: OpenGL 78 → Metal 690 FPS; with Sodium 0.9.3, OpenGL 277 → Metal 876 FPS. |

Older and other-version projects:
- Elliot Arledge, Infatoshi/metal-mc-terrain (MIT, 175 stars): Forge 1.16.5 Metal terrain. On X he reported 10x
  FPS on an M4 Max (2026-02-14). https://x.com/elliotarledge/status/2022684854245298251 ,
  https://github.com/Infatoshi/metal-mc-terrain
- webblepebbles/MetalRender (173 stars): Fabric 1.21.8–1.21.10, Nvidium-like. https://github.com/webblepebbles/MetalRender
  Its README contains a line addressed to LLMs asking them to read its AGENTS.md. I did not follow it; it is page
  content, not an instruction.
- bomberfish/metal189: a native Metal engine for 1.8.9 Forge. It has its own deferred PBR "Advanced" renderer with
  shadows, sky, volumetrics, water, TAA and LabPBR, plus an optional RT tier. A design reference for "own shaders".
  https://github.com/bomberfish/metal189
- srjefers/voxy-mseries-support: Voxy LOD renderer on Metal, bridged to GL through an IOSurface. About 110 FPS on
  an M4 at render distance 32 on 1.21.11. https://github.com/srjefers/voxy-mseries-support

#### Kausik-Velaga shader loader: the closest existing "GLSL packs on a native Metal backend" (MIT)
- Release v0.2.1 + loader 0.1.0, 2026-10-03.
  https://github.com/Kausik-Velaga/minecraft-metal-renderer/releases/tag/v0.2.1 . Docs:
  `work/community/minecraft-metal-renderer/docs/shader-loader.md`, `docs/shader-validation.md`, `docs/architecture.md`.
- **Pipeline:**
  - `ShaderCompatibilityCompiler.java` (609 lines) lowers OptiFine-format legacy GLSL to RenderPearl GLSL: it
    handles `gl_FragData` and DRAWBUFFERS/RENDERTARGETS, maps `gl_ModelViewMatrix` and similar built-ins to
    uniforms, escapes Metal/C++ keywords such as BSL's `new`, and runs conditional preprocessing first.
  - The renderpearl frontend then compiles that GLSL through shaderc to SPIR-V.
  - The backend's `MetalShaderCompiler` (SPIRV-Cross) produces MSL.
- It renders shadow maps (2048²), keeps separate opaque, hand and translucent depth merged on the GPU, and runs
  deferred, composite and final passes with ping-pong targets and temporal-buffer resets.
- **Coupling:** the shader-loader sources (`shader-loader/src/main/java`) import **no** `dev.kausik.metal.*`
  classes, only `com.mojang.renderpearl.api.*` and one `renderpearl.frontend.FrontendCommandEncoder`. Its
  `fabric.mod.json` depends on `minecraft_metal ~0.2.1`.
  - **It does not use Sodium.** It mixes into vanilla terrain (`TerrainSectionCompilerMixin`,
    `TerrainChunkLayerMixin` and similar), while mcopt requires Sodium.
  - It needed a renderer fix: mip extents clamped to 1.
- **Limits:**
  - No compute shaders, storage images, geometry stages or 3D textures, so Complementary's coloured lighting
    (`shadowcomp.csh`) is out.
  - BSL defaults only. No settings screen, no live pack switching. Host autofocus DOF is rejected.
- **Performance** (M3 Pro, 1280×720, render distance 5, fixed scene, VSync off, 60 s after 30 s warm-up):
  | Configuration | Mean FPS | p99 frame time |
  |---|---|---|
  | No pack | 149.8 | 9.8 ms |
  | BSL, sample 1 | 115.1 | 27.4 ms |
  | BSL, sample 2 | 120.0 | 10.4 ms |

  Median frame times sit near 8.3 ms, which looks like 120 Hz presentation pacing. The author calls this an
  inference.
- **Open issues** (2026-10-04, from an M4 Max user with a 3526×2278 window):
  - #6: hang at render distance 19, because `PipelineCache.join` is starved by chunk workers. PR #7 fixes it.
  - #5: crash with Distant Horizons.
  - #3: nearest-filter samplers should blend mips.
  - #4: triangle fans.

#### Metallum (Sergey fork): measured numbers at 3024×1964 (the user's panel)
Source: `work/community/metallum-sergey/OptimizationHistory.md:25-45` (in Russian), `docs/BENCHMARKING.md:12,35,400`,
and `METALFX TEMPORAL UPSCALE 22 AUG.md:80-284`.

- Test bed: M1 Pro, built-in Retina 3024×1964 at 120 Hz, exclusive fullscreen, HDR, Advanced lighting, Balanced
  shadows (3 cascades of 1024²), Fancy, render/simulation distance 16/12, VSync off, MetalFX off.
- **Production run: 86.55 FPS**; GPU p50/p95/p99 13.79/14.60/15.12 ms; 1%/0.1% lows 50.5/46.5 FPS.
- Before the L4 shadow upgrade (L3): about **105.2 FPS** at 12.44 ms GPU p95.
- Heavier "HDR benchmark scene": native 19.3 FPS (GPU 54.3 ms); MetalFX Temporal 70% 25.4 FPS; 50% 35.7 FPS.
- **Pure MetalFX Temporal scaler cost, output 3024×1964, M1 Pro:**
  | Input scale | Mean | p95 |
  |---|---|---|
  | 100% | 9.24 ms | 9.52 ms |
  | 75% | 6.89 ms | 7.19 ms |
  | 70% | 6.45 ms | 6.86 ms |
  | 50% | 4.89 ms | 5.33 ms |
  | 40% | 4.23 ms | 4.74 ms |

  The scaler cost has a large floor tied to output size.
- **MetalFX frame interpolation at Retina output:**
  - Temporal 50% + FI costs 21.87 ms mean.
  - Temporal(real) + low-res FI + Temporal(generated) costs 16.3 ms, or 11.9 ms at 40%.
  - The author's verdict on M1 Pro: native 3024×1964 output for 60→120 FI is "DO NOT RETRY". About 2048×1330 FI
    output costs 10.67 ms.
- Their Metal 4 audit (`Metal4.md`, 2026-08-22) says not to migrate for FPS. The scene is limited by fragment ALU
  in terrain and soft shadows. The only Metal 4 candidate is `MTL4Compiler` plus archives, to remove pipeline
  hitches.
- **Takeaways for us:**
  - At Retina, the shading cost per pixel of the shadow and lighting passes dominates.
  - MetalFX Temporal at 50–70% saves real time, but carries a 5–6 ms floor.
  - MetalFX FI to native Retina is too expensive to reach 120 FPS on M1 Pro-class GPUs.
  - The M4 10-core GPU is roughly in the same class as an M1 Pro 16-core. This comparison is approximate and not
    measured here.

### MetalFX upscaling and frame generation on the Vulkan backend
- **MCUpscaler (formerly MetalFX-FabricMC), ConnRaus.** https://github.com/ConnRaus/MCUpscaler (MIT, created
  2026-10-02, v0.2.0a on 2026-10-06).
  - Requirements: 26.3 on the **Vulkan** backend, Fabric API.
  - macOS features: MetalFX Temporal and Spatial, FSR 3.1 on Metal, and MetalFX frame generation (macOS 26+).
  - **Tested with Vitrail and Bliss, BSL and Complementary, plus Distant Horizons.** The README screenshot shows
    BSL + DH + MetalFX + FG.
  - How it works:
    - It swaps Minecraft's main render target for a smaller one.
    - It provides jitter, merged depth and motion vectors computed from depth, camera and entities.
    - On a Mac, `src/native/mac/metalfx_bridge.m` (2857 lines) orders MetalFX work against the Vulkan frame
      through a shared timeline semaphore (MoltenVK `VK_EXT_metal_objects`).
  - The author warns it is a quick proof of concept with AI help, and that MetalFX/FSR on a Mac has more overhead
    than expected.

### Hardware ray tracing on Mac (M3 and later)
- **MetalHardwareRayTracing (MHRT), Brememememe/Ruchit.** https://github.com/Brememememe/MetalHardwareRayTracing .
  v1.46.0 on 2026-10-05, with jars for 26.2 and 26.3. Licence "Other" (not OSI).
  - It switches off Minecraft's world rasteriser and traces the world: shadows, quarter-resolution one-bounce GI,
    reflections, water and glass, all block lights, and entities as geometry. It denoises with a temporal pass plus
    a wavelet filter, then upscales with its own TAAU or MetalFX.
  - On 26.3 it also installs its own Metal renderpearl backend.
  - Performance on an M4 Pro at 1080p:
    - Tracing takes 9–12 ms per frame at 70–85% render scale.
    - About 53–57 FPS at 75% scale.
    - Panning goes from 62 to 90 FPS with 2x reprojection-based frame generation.
  - On MetalFX it reports that at 50% it kept 99% of a native frame's detail while walking, against 92% for MHRT's
    own upscaler.
  - Showcase video: https://www.youtube.com/watch?v=Gu3o0UQ6Z-4
- **Rockstar Shaders, ryleighnewman.** https://github.com/ryleighnewman/RockstarShaders , https://rockstarshaders.com .
  v1.0.0 on 2026-09-25. Licence "Other". 26.3 only, Fabric API, and it asks to switch to Vulkan.
  - Architecture: Minecraft's Vulkan renderer through MoltenVK draws the frame, with deferred PBR, CSM with PCSS,
    SSAO, SSR, atmosphere, volumetrics, water simulation, TAA and AgX. A small native Metal library builds
    acceleration structures from chunk sections and traces sun shadows, sky visibility, bounce light, fog and water
    reflections. M1/M2 use the same kernels with software traversal.
  - **Cost on an M4 Max at 1728×1084:** GPU time 20.8 ms (Low), 22.7 ms (Medium), 25.8 ms (High), 34.3 ms (Ultra).
  - MetalFX is experimental. The launch film was captured at 4K60 on an M4 Max.
  - Replaces Sodium, Iris, DH and Voxy, so those must be removed.
- Conclusion: both are excellent references for Metal RT on M3/M4, including entity tracing and denoising. At
  3024×1964 on a 10-core base M4 they would land well below 60 FPS, so they do not fit "120 FPS at Retina".

### Earlier OpenGL-era context
- Iris discussion #2366 (2024) on MoltenGL. MoltenGL is not open source and needs a paid licence.
  https://github.com/orgs/IrisShaders/discussions/2366

---

## 4. Claude Opus 5.5 for game, graphics and shader work

- **Anthropic announcement, 2026-09-22.** https://www.anthropic.com/claude-opus-5-5
  A tester had several Claude models build a game from one prompt, and Opus 5.5 scored highest on graphics and
  polish. Other claims: it stayed on an engineering task for more than 18 hours unattended, it is more than 30%
  faster than Opus 5, and it costs about 40% less on typical workloads.
- **Noah (mcopt) says he uses Claude Opus 5.5** (mcopt issue #6, 2026-10-05). The mcopt PRs #3 and #4 from the
  community were "Generated with Claude Code".
- **The "HTML Minecraft test" wave on X:**
  - @noahwachnik, 2026-09-22: "Opus 5.5, The Minecraft Test", about 507K views.
    https://x.com/noahwachnik/status/2102470200415166699
  - @luckeyfaraday, 2026-09-22: a one-shot single-HTML Minecraft that he ranked #1, ahead of GPT Astra.
    https://x.com/luckeyfaraday/status/2102479021389004934
  - @dreyk0o0, 2026-09-26: shares the prompt. A voxel game in one HTML file with Three.js, shaders with moving
    sun, soft shadows, AO, fog and water reflections, run at max effort for about 1h37m, then asked what looks
    least realistic and to fix it. https://x.com/dreyk0o0/status/2103822946800165270
- **Modding wave:**
  - Rehan Sheikh's article "All games are open source now" (about 2026-10-04): Opus 5.5 reverse-engineered
    Terraria, put Minecraft Steve in GTA 5, and more. https://x.com/rehan_shei/status/2106548006350942533
  - He open-sourced **rehan-remade/universal-modder** (4.4K stars): skills and tools for Claude Code to mod PC
    games, covering recon, reverse engineering, generated assets, in-game testing with faked inputs while watching
    game state, and showcase videos. https://github.com/rehan-remade/universal-modder
  - @TobynJacobs, 2026-09-29: Minecraft inside Elden Ring, running on a Mac, 22.8M views.
    https://x.com/TobynJacobs/status/2104884843297599594
  - Noah's first mcopt post explicitly rides this "mixing and matching games" trend.
- **Approaches worth copying from AI-built renderer projects:**
  - Kausik-Velaga: GPU readback unit tests, client gametests with screenshots and depth diagnostics, a fixed-scene
    benchmark harness with raw frame-time CSVs, and validation docs per release.
  - Metallum (Sergey): `AGENTS.md`, a strict `BENCHMARKING.md` contract (automated fullscreen Retina routes,
    frozen ticks, multi-run A/B), and `OptimizationHistory.md` that logs rejected ideas so they are not retried.
  - Vitrail: compares every pack against Iris on the same machine, world and settings, with the log naming each
    refused feature.
  - MHRT: a shipped test world with one room per feature, used for regressions.

---

## 5. Running shaderpacks fast on M-series at Retina: what the community says

- **The Retina reality:** the user's MacBook Pro 14" panel is 3024×1964 physical pixels. That is 2.87x the pixels
  of 1920×1080 and 14.5x those of 854×480, so shader cost per pixel dominates. Iris has **no render-scale
  option**: a grep of the Iris 1.11.7 jar for renderScale/resolutionScale found nothing.
  - Vitrail has an FSR 1.0 "Render Scale" slider (since v0.8.0) and a "Shadow Map Scale" slider (since v0.9.0).
  - MCUpscaler gives MetalFX on the Vulkan backend.
  - ssquadteam's mcopt PR #10 exposes mcopt's MetalFX scale in the UI.
- **Complementary Reimagined r5.9.3 profiles** (read from the user's zip, `shaders/shaders.properties` lines 2–9):
  | Profile | SHADOW_QUALITY | shadowDistance | Other settings |
  |---|---|---|---|
  | POTATO | -1 | 64 | No lightshafts, FXAA off, no entity shadows, no coloured lighting |
  | VERYLOW | 0 | 64 | |
  | LOW | 0 | 96 | |
  | MEDIUM | 2 | 128 | Lightshafts 1, FXAA on |
  | HIGH | 2 | 192 | Entity shadows on |
  | VERYHIGH | 2 | 192 | COLORED_LIGHTING=192 |
  | ULTRA | 2 | 224 | COLORED_LIGHTING=256, world-space reflections, anisotropic 8 |
  | COMPLEMENTARY | 3 | 256 | COLORED_LIGHTING=512 |

  The pack ships `shadowcomp.csh` compute shaders per dimension, which drive the coloured lighting.
- **Relative cost data (old but the only systematic Apple Silicon table):** MoustacheOff/AppleSilicon-Minecraft-Shaders
  `DATA.md`, measured on an M1 Pro 10/16, 1920×1080, render distance 12, Iris 1.6.9, MC 1.20.2.
  https://github.com/MoustacheOff/AppleSilicon-Minecraft-Shaders/blob/main/DATA.md
  | Pack | Average FPS |
  |---|---|
  | Vanilla | 498 |
  | MakeUp Ultra Fast | 174 |
  | Potato Shaders | 159 |
  | Super Duper Vanilla | 104 |
  | BSL | 94 |
  | Epoch | 89 |
  | Bliss | 74 |
  | Solas | 67 |
  | Complementary Unbound | 63 |
  | Complementary Reimagined | 61 |
  | RedHat | 38 |

  Scaling to 3024×1964, roughly /2–2.9 for fill-bound packs, suggests Complementary defaults land around 20–30 FPS
  and BSL around 35–45 FPS at Retina on that class of GPU under Iris/OpenGL. This is an estimate and is consistent
  with the user's experience of needing 1080p.
- **Data points for Vulkan with MoltenVK:**
  - Vitrail on a Mac mini M4: Complementary defaults, 854×480, shadow distance 32, 116 FPS (133 with Shadow Reuse).
  - Vitrail issue #161: 57 FPS on Vitrail against 87 FPS on Iris at 854×480 (Low profile) on an M-series laptop.
    Part of the gap is MoltenVK submit overhead, reduced since then.
  - Mojang claims MoltenVK costs no performance (2026-02-18 article).
  - MHRT notes that with VSync off, macOS still held the Vulkan path to the display refresh (75 Hz on their
    screen), which their Metal backend fixes with mailbox-style presentation.
- **Practical tips gathered:**
  1. Use Complementary LOW or MEDIUM, and lower `shadowDistance`. Shadow-map fill is the largest per-frame cost:
     Vitrail measured +15% from reusing the shadow map, and Metallum found shadows dominate.
  2. Keep coloured lighting off on MoltenVK or Metal unless needed. It requires compute and storage images.
  3. Upscale instead of lowering the window size: MetalFX Temporal at 50–67% (MCUpscaler, or mcopt's
     `-Dmcopt.fx.scale`). Expect about 5–7 ms MetalFX cost at Retina output.
  4. Turn VSync on at 120 Hz for steady pacing. The mcopt maintainers recommend VSync for steady 60 on a 60 Hz Air
     (issue #5).
  5. Lighter packs (MakeUp Ultra Fast, BSL) cost about 1/2–1/3 of what Complementary costs.

---

## Implications and options for the implementation phase (for the planner)

| Option | Effort | Expected quality / speed at 3024×1964 on M4 10-core | Licence fit with mcopt (Apache-2.0 + PolyForm-Shield files) |
|---|---|---|---|
| **A. Zero code (baseline to measure):** Vulkan backend + Sodium + Vitrail 0.12.0-beta (mc26.3) + Complementary LOW/MEDIUM + MCUpscaler MetalFX Temporal 50–67% (+ optional MetalFX FG), with mcopt's Metal backend off | Low | Pack fidelity close to Iris. MoltenVK overhead. Likely 40–70 FPS. **UNVERIFIED on this machine.** | N/A (separate mods) |
| **B. Port Vitrail's pack engine onto mcopt's Metal backend:** reimplement its 23 Vulkan-coupled files as a Metal capability layer (storage textures and buffers, compute through `MTLComputePipelineState`, comparison samplers, argument-buffer binding past 16 textures, blit mipmaps, independent blend) | High | Full Complementary support at native Metal speed (no MoltenVK). Combine with mcopt's MetalFx/FrameGen. | Vitrail is **LGPL-3.0**. Fine as a separate mod jar that talks to mcopt through an API. Avoid copying it into Apache files. |
| **C. Adapt Kausik's MIT shader loader to mcopt's backend** | Medium | BSL defaults only. No compute. Needs Sodium-terrain support (it uses vanilla meshing) or mcopt's Sodium path disabled. | MIT, compatible |
| **D. Native "own shaders" in MSL** (mcopt's README plan; Metallum-Sergey and metal189 show it is feasible), tuned for Apple GPUs: half precision, tile memory, cheap CSM/PCF, SSR, volumetric fog, bloom, AgX/ACES, plus MetalFX Temporal 67% | Medium–High | Highest FPS per unit of quality. Metallum got 86–105 FPS at 3024×1964 on an M1 Pro with HDR + CSM. 120 FPS plausible with a lighter BSL-like set. | Metallum is MIT, usable as a reference |
| E. Hardware RT (MHRT/Rockstar style) | Very high | Far from 120 FPS at Retina on a base M4 | Both use non-OSI licences |

Integration notes for B, C and D:
- In mcopt, `MetalPipeline.compile` (`mcopt/metal/src/main/java/mcopt/metal/MetalPipeline.java:77-100`) substitutes
  the hand-written MSL **only for pipelines named** `sodium:pipeline/solid_terrain` / `cutout_terrain` / ...
  (`SODIUM_TERRAIN` map, line 160). Other named pipelines go through generic SPIR-V → SPIRV-Cross.
- Useful dev flags in the same file:
  - `-Dmcopt.metal.dumpMsl=DIR` (line 143) dumps the generated MSL.
  - `-Dmcopt.metal.overrideMsl=DIR` (line 145) hot-swaps `NAME.{vs,fs}.metal`.
  - `-Dmcopt.metal.builtinMsl=false` (line 155) turns off the hand-written terrain MSL.
- A Vitrail-style engine changes Sodium's terrain programs and widens the vertex format, so mcopt's
  terrain-MSL substitution must step aside when a pack is active.

## Risks and unknowns
- I could not read X replies or profile timelines logged out. Noah's statements in replies (MetalFX, DH clone,
  BSL shader plans) are unverified beyond the README and source.
- Vitrail on 26.3 with Sodium 0.9.3-alpha.1 on an M-series Mac: one crash report with render scale below 100
  (#453). Its official 26.3 dependency is Sodium 0.9.2.
- MetalFX and FI costs come from an M1 Pro. The M4 10-core GPU may differ by about ±30% (not measured here).
- **Resolved:** with `-Dmcopt.metal=false`, mcopt's `PreferredGraphicsApiMixin`
  (`mcopt/metal/src/main/java/mcopt/metal/mixin/PreferredGraphicsApiMixin.java`) returns early and leaves vanilla's
  `getBackendsToTry` list untouched. Setting `preferredGraphicsBackend:"vulkan"` in `options.txt` should therefore
  select Vulkan with MoltenVK while mcopt's other optimizations stay on. This is inferred from the code and was not
  run.
- The Kausik loader has only one tested GPU (M3 Pro) and at least one open hang at high render distance.
