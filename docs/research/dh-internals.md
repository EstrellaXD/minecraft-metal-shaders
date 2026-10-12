# Distant Horizons internals and its Metal path (report `dh-internals`)

Scope: Distant Horizons (DH) on Minecraft 26.3 Fabric, how it renders through renderpearl, the mcopt "DH on Metal"
contribution (GitHub #4, which is a **pull request**, not an issue), how DH performs on Apple Silicon, and what it takes
to run DH on our Metal backend, lit by our native shaders, fast at Retina on the M4.

Nothing was launched or benchmarked. Every number marked **(est.)** is derived from code and from measured M4 figures in
`apple-gpu.md` and `critique.md` §3, not from an in-game run.

Path aliases used below:
- `DH/` = `work/src/distant-horizons` (clone of tag 3.3.4)
- `CORE/` = `DH/coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core`
- `COMMON/` = `DH/common/src/main/java/com/seibel/distanthorizons/common`
- `FABRIC/` = `DH/fabric/src/main/java/com/seibel/distanthorizons/fabric`
- `SHD/` = `DH/coreSubProjects/core/src/main/resources/assets/distanthorizons/shaders`
- `MCOPT/` = `mcopt/metal/src/main/java/mcopt/metal`
- `DECOMP/` = `work/decomp/mc-26.3`
- `POLLONES/` = `~/Library/Application Support/PrismLauncher/instances/Pollones server/minecraft` (read only)

---

## 0. Summary

1. **Version for 26.3:** DH **3.3.4-26.3** (Modrinth, 2026-09-29, one jar for Fabric and NeoForge). Source cloned at
   tag `3.3.4` (`eb1007cb6c`) with core submodule `64410621`.
   - **License:** verified **LGPL-3.0-only**. Source headers say "version 3", with no "or later". The logos are CC BY-SA 4.0.
2. **What the user runs today** (Pollones server, MC 1.21.1):
   - DH **3.2.0-b**. The auto-updater installed it on 2026-08-08, replacing 2.4.5-b.
   - **Iris 1.8.8 with the Photon shaderpack.** So the look the user loves is **DH LODs shaded by Photon** (`dh_terrain`
     and `dh_water` programs), not DH's own shading. Photon sets `dhShadow.enabled = false`, so LODs cast no shadows.
3. **On 26.x DH has a renderpearl ("Blaze3D") renderer, which is its default.**
   - Every DH draw, texture and full-screen pass goes through `GpuDevice`/`RenderPass`. That means it already runs on
     mcopt's Metal backend, apart from two OpenGL-only branches chosen by a backend-name check.
   - DH forces its old raw-OpenGL engine whenever Iris is installed.
4. **mcopt #4 (CazzaCoder) is a small, well-written PR.**
   - It fixes mcopt's hand-written Sodium fog when start ≥ end.
   - It spoofs DH's backend check to `VULKAN` while Metal is active.
   - It reports 160–300 fps on an M4 Pro versus 60–70 fps for DH on OpenGL.
   - **It is unmerged and conflicts with main.** It was branched before alpha.2, and alpha.2 added
     `Profile.distantHorizons()`, which forces `mcopt.metal=false` whenever DH is loaded. So on alpha.3 the PR alone does
     nothing unless `-Dmcopt.metal=true` is also set.
5. **DH's Blaze path costs a lot of bandwidth at Retina.** Per frame it runs terrain passes plus about **14 full-screen
   draws** (SSAO, its apply pass, fog, far fade and copy, TAA, sharpen, copy into MC, and two vanilla-fade passes each
   with a copy). It also allocates about **16 screen-sized targets, ≈380 MB at 3024×1964** (est.). That is about
   **3–6 ms GPU at native Retina** (est.), all of which our own pipeline would redo anyway.
6. **Recommended path:**
   - **(a)** Land a rebased #4 in our fork, with DH detection changed from "force OpenGL" to "Metal unless Iris".
   - **(b)** Light DH LODs with **per-pipeline MSL overrides** of `distanthorizons:opaque_terrain` and
     `distanthorizons:transparent_terrain`. These read our `ShadeFrame` UBO; DH's vertex format already carries the
     normal index, the sky/block light and a material id.
   - **(c)** Switch off DH's SSAO, fog, TAA and fades through its config API, and composite once in our own pass.
   - **Effort:** about 1.5–2.5 agent-weeks in stages after M1 (§5).

---

## 1. Versions, sources, licences

### 1.1 26.3 release and source
| Item | Value |
|---|---|
| Modrinth project | `distanthorizons` (id `uCdwusMi`), licence field `LGPL-3.0-only`, source `gitlab.com/distant-horizons-team/distant-horizons` |
| 26.3 versions | 3.3.0 (09-17), 3.3.2 (09-21), 3.3.3 (09-29), **3.3.4 (09-29)**: `DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`, 28,476,894 B |
| Git tag | `3.3.4` → `eb1007cb6cc3e0419effe2b40ab485cf24e55b43`; submodule `coreSubProjects` → `jeseibel/distant-horizons-core` @ `64410621767ee697e8bc175c2a3ebe4c5e2ce520` |
| Local clone | `work/src/distant-horizons` (69 MB, 925 Java files; core holds 563 of them) |
| 26.3 build props | `DH/versionProperties/26.3.0.properties`: Java 25, fabric-loader 0.19.5, fabric-api 0.160.5+26.3, compiled against Sodium `mc26.3-0.9.2` and Iris `1.11.6+26.3`, `vertigo` marked incompatible |
| API | 7.2.0 (since 3.3.2) |

3.3.x changelog items that matter here:
- 3.3.0: TAA, LOD texture mipmaps and "texture tiles", forward/**reverse-Z** rendering, a high-speed surface generator,
  about 50% less disk IO.
- 3.3.2: "Fix DH disappearing when a glow is on screen for MC 26.3", "Fix vanilla fading using DH's depth range".
- 3.3.3: Iris blend and lightmap fixes. The **config version goes from 4 to 5, which wipes the user's config.**
- 3.3.4: an Iris boat-transparency fix.

### 1.2 What the user runs (read only, `POLLONES/`)
- `mods/DistantHorizons-3.2.0-b-1.21.1.jar` (`fabric.mod.json` version `3.2.0-b`). `logs/latest.log` (2026-08-08) shows
  2.4.5-b running, then "Distant Horizons successfully updated. It will apply on game's relaunch" to 3.2.0-b. The
  auto-updater is on (`enableAutoUpdater = true`).
- Iris 1.8.8 with `shaderPack=photon_v1` and `enableShaders=true`. Sodium 0.6.13, sodium-extra, moreculling,
  entityculling, ImmediatelyFast, dynamic-fps. Java 21 (Microsoft), `MaxMemAlloc=6152` MB, G1 (DH logs a G1 warning).
- MC options: render distance 10, simulation 12, fullscreen `1920x1200@120`, `maxFps 260`, VSync off.
- Latest release for 1.21.1 is **3.3.3-1.21.1**. Updating means the config 4→5 reset: **back up `DistantHorizons.toml`
  first.**
- `Distant_Horizons_server_data/Pollones` holds 212 MB of client-side LOD cache for that server. Whether the server
  itself runs DH (and sends LODs) is unknown.

DH settings the user chose (`config/DistantHorizons.toml`):

| Setting | Value | Note |
|---|---|---|
| `lodChunkRenderDistanceRadius` | **40** (640 blocks) | modest; detail levels 0–1 only with MEDIUM (§2.2) |
| `horizontalQuality` / `verticalQuality` | MEDIUM / MEDIUM | base 2.0, unit 12 chunks; 6/6/6/4… slices per column |
| `maxHorizontalResolution` | BLOCK | |
| `transparency` | COMPLETE | real transparent LOD water pass |
| `vanillaFadeMode` | DOUBLE_PASS | 2 extra full-screen passes (skipped under Iris shaders) |
| `ditherDhFade`, `dhFadeFarClipPlane` | true, true | |
| SSAO | on, 6 samples, blur 2, strength 0.2 | Iris switches it off while a pack overrides DH |
| Noise texture | on, intensity 5, steps 4, dropoff 1024 | Photon has its own `NOISE_ON_DH_TERRAIN` |
| Fog | DH fog on, vanilla fog **off**, far fog exp² density 2.5 start 0.4; height fog on | Iris disables DH fog under a pack |
| `distantGeneratorMode` | FEATURES | generates real chunks off-thread (not saved) |
| `numberOfThreads` / `threadRunTimeRatio` | 5 / 1.0 | 10-core M4 (4P+6E) |
| `enableCaveCulling` | true (height 60) | |
| generic rendering | clouds, beacons, **instanced on** | the log warns: "instanced rendering causing crashes on macOS when Sodium is present" |
| `worldCompression` | VISUALLY_EQUAL | |

### 1.3 Photon (the look to match)
`shaderpacks/photon_v1/shaders`: `program/dh_terrain.{vsh,fsh}`, `dh_water.{vsh,fsh}`,
`include/misc/distant_horizons.glsl`.
- `dh_terrain.fsh` writes Photon's G-buffer: albedo, block id, flat normal and light levels.
- LODs are lit in Photon's deferred pass like normal terrain: sun/sky lighting, GTAO, aerial-perspective fog and clouds.
- `dh_water` blends into colortex0 and colortex13 with reflections.
- `shaders.properties:147 dhShadow.enabled = false`, so **there are no LOD shadows**. `uniform.float.combined_far = dhRenderDistance`.
- **Licence: "All rights reserved"** (`photon_v1/LICENSE`). Use it only as a visual reference and copy no code.

### 1.4 Licences (for any work we ship)
- **DH: LGPL-3.0-only.** Checked in `DH/LICENSE.txt` (GPLv3 text) plus `LICENSE.LESSER.txt` and per-file headers ("as
  published by the Free Software Foundation, version 3"). Of 925 files, 595 carry the LGPL header; the rest are tests,
  generated code or headerless files. `CORE/util/MoreObjects.java` is Apache (Guava-derived).
- Logos: CC BY-SA 4.0 (`LICENSE-LOGOS.txt`); don't use them.
- Bundled libraries: sqlite-jdbc 3.53.2.1 (Apache-2.0), lz4 1.8.0, zstd-jni 1.5.7-12, xz 1.12. Natives are relocated to
  `dh_sqlite` and similar (`DH/relocate_natives`).
- **Consequence:** mixins and compat code in mcopt that call into DH, or patch it at runtime, are a "work that uses the
  library". They stay under mcopt's Apache-2.0. If we ever ship a **modified DH jar**, we must publish its source under
  LGPL. **Prefer runtime mixins plus upstream merge requests over forking DH.**
- mcopt: Apache-2.0. Iris: LGPL-3.0.

---

## 2. Architecture

### 2.1 Layout
- **`api`**: the public `DhApi`. Events, configs, a render proxy and a terrain data repo.
- **`core`**: version-independent. Quadtree, data sources, SQLite, world-gen queues, mesh building, the render
  orchestrator (`LodRenderer`) and networking.
- **`common`**: MC-version wrappers and the two renderer back ends. Uses a Manifold-style `#if MC_VER` preprocessor.
  - `render/openGl/*`: raw LWJGL GL. This is the "native" renderer, and the only one with Iris support.
  - `render/blaze/*`: renderpearl.
- **`fabric` / `neoforge`**: mixins and entry points.

Singletons are wired through `SingletonInjector` (dependency injection). The renderer back end is bound once by
`COMMON/wrappers/DependencySetup.setRenderingApiBindings` (:89).

### 2.2 LOD data model and detail levels
- **Full data point**: one `long` per vertical run in a column (`CORE/util/FullDataPointUtil`).
  - Fields: 32-bit id into a per-section mapping, 12-bit height, 12-bit min-Y, 4-bit block light, 4-bit sky light.
  - The mapping (`FullDataPointIdMap`) stores block state and biome. A column is a list of these runs, top to bottom.
- **Data source**: `FullDataSourceV2` holds **64×64 columns** (`WIDTH = 64`) at one detail level. Detail level *d* means
  each column covers 2^d × 2^d blocks.
  - Section position (`CORE/pos/DhSectionPos`) packs detail level, x and z.
  - The smallest render section is detail level 6, which is 64 blocks or 4×4 chunks.
- **Downsampling**: `FullDataUpdatePropagatorV2` merges four children into a parent, up to
  `HIGHEST_DOWNSAMPLE_DETAIL_LEVEL = SECTION_MINIMUM + 6`.
- **Render data**: `ColumnRenderSource` stores per column a list of `long`s (`RenderDataPointUtil`).
  - Fields: ARGB colour, material id (4 bits), min-Y and max-Y (12 bits each), block light and sky light.
  - It is built by `FullDataToRenderDataTransformer`.
  - Vertical quality caps how many runs per column survive at each detail level: `EDhApiVerticalQuality.MEDIUM` = `{6,6,6,4,4,…}`.
  - Colours come from the block's average texture colour times the biome tint (`ClientLevelWrapper`). Since 3.3, a
    **block texture tile id** per face also points into a "colour ratio" atlas, which gives real texture detail near the
    player.
- **Quadtree** (`CORE/render/QuadTree/LodQuadTree`, 1449 lines): one tree per level, ticked on a worker. The expected
  detail level of a section is:
  `level = clamp(maxRes, floor(ln(dist / unit) / ln(base)), root)` with `unit = horizontalQuality.distanceUnitInBlocks × 16`
  (`LodQuadTree.java:1244-1271`). `EDhApiHorizontalQuality`: LOWEST(2.0, 4), LOW(2.0, 8), **MEDIUM(2.0, 12)**,
  HIGH(2.2, 16), EXTREME(2.2, 20).
  - MEDIUM: 1-block columns to 384 blocks, 2-block to 768, 4-block to 1536, and so on, so each ring doubles.
  - The number of render sections grows with **log(distance)**: about 113 + 85·log2(R/384) at MEDIUM (est.). That is
    about 175 for the user's R = 640 and about 400 at R = 4096.
  - Geometry per frame therefore grows slowly with distance. **World generation grows with R².**
  - Zoom (spyglass) temporarily raises the detail level (`CameraZoom`).

### 2.3 Ingestion: saved chunks, world generation, network
- **Chunks the client sees**: chunk load and save events go to `LodDataBuilder` (on the "chunk to LOD builder" pool),
  then `FullDataSourceV2`, then the update propagator and SQLite. A chunk hash table (`ChunkHash`) skips unchanged chunks.
- **Distant generation** (`generation/WorldGenerationQueue`, `DhWorldGenerator`, `PregenManager`). Modes come from
  `EDhApiDistantGeneratorMode`:
  - `PRE_EXISTING_ONLY`: read existing region files only.
  - `SURFACE`: the new fast surface generator, no features.
  - `FEATURES` (user): runs MC's chunk generator off-thread, features included. Chunks aren't saved, so trees and
    structures can differ from the real ones (DH FAQ).
  - `INTERNAL_SERVER`: generates through the server's chunk system, so results are exact.
  - `DhLightingEngine` (921 lines) computes sky and block light for generated chunks.
- **Multiplayer**: if the server runs DH, LOD data comes over DH's protocol (`multiplayer/*`, server config
  `maxGenerationRequestDistance` and similar). Otherwise only chunks the client receives become LODs.

### 2.4 Storage
- **SQLite** per dimension (relocated `dh_sqlite`), WAL journal, `synchronous=NORMAL` (`sqlScripts/0031`).
- Table `FullData`: primary key `(DetailLevel, PosX, PosZ)`. Columns: `MinY`, `DataChecksum`, `Data` BLOB,
  `ColumnGenerationStep`, `ColumnWorldCompressionMode`, `Mapping` BLOB, `DataFormatVersion`, `CompressionMode`,
  `ApplyToParent`/`ApplyToChildren`, timestamps, and `North|South|East|WestAdjData` (adjacent edges, so a render load
  needs only one data source, `sqlScripts/0090`).
- Compression: UNCOMPRESSED, LZ4, Z_STD_BLOCK, Z_STD_STREAM or LZMA2. Lossy "world compression" (`VISUALLY_EQUAL`, …)
  merges similar blocks.
- `BeaconBeam` and `ChunkHash` tables.

### 2.5 Threads and memory
- Pools (`CORE/util/threading/ThreadPoolUtil`):
  - shared, size-limited: file handler, render-section loading, update propagator, world gen, network compression,
    chunk→LOD builder;
  - single-thread: cleanup, beacon culling, full-data migration.
- `numberOfThreads` defaults to **50% of cores** (`ThreadPresetConfigEventHandler.getDefaultThreadCount`).
  `threadRunTimeRatio` duty-cycles the workers. There's special C2ME/Chunky logic.
- **macOS:** Java thread priority does not map to QoS. DH workers run at default QoS and compete with the render thread
  for P-cores. mcopt already has a QoS helper (`MCOPT/Qos.java`, roles render/server/mesh/cull/worker/io) that DH threads
  could join (§5c).
- Memory:
  - Java heap holds cached data sources, quadtree nodes and pooled arrays (`PhantomArrayListPool`).
  - GPU memory holds VBOs plus screen-size render targets (§2.7). On Apple Silicon both share the 16 GB.
  - The DH FAQ suggests 6–8 GB heap for 4–8 cores and a concurrent GC (ZGC on Java 21+).

### 2.6 Mesh building
- Runs on the render-loading pool: `ColumnRenderBufferBuilder` → `ColumnBox.addBoxQuads` → `LodQuadBuilder`.
  - `ColumnBox`: per column and run, emits the faces not hidden by neighbours. It uses adjacent data, cave culling, and
    the grass-side mode.
  - `LodQuadBuilder`: per direction (6 lists, opaque and transparent separate).
- **Merging is greedy along one axis at a time** (`LodQuadBuilder.mergeQuads`, :257-322). It sorts, merges EastWest,
  then NorthSouth for up/down faces only, plus a pre-merge while adding.
- **Vertex: 16 bytes** (`BYTES_PER_VERTEX = 16`, :57), 4 vertices per quad, shared index buffer:

  | Field | Type | Contents |
  |---|---|---|
  | `vPosition` | ushort3 | section-local position |
  | `meta` | ushort | sky and block light nibbles, plus a 2-bit-per-axis "micro offset" that closes seams between detail levels |
  | `vColor` | RGBA8 | colour |
  | `irisMaterial` | byte | material id `DH_BLOCK_*` (0 unknown … 12 water, 13 grass, 15 illuminated) |
  | `irisNormal` | byte | normal index 0–5 |
  | `textureTile` | ushort | texture tile id |

- Buffers are split at **2 MB** (32,768 quads) "to reduce stuttering during upload" (:572). Per section:
  `LodBufferContainer` with arrays of opaque and transparent VBO wrappers plus a uniform container (`uModelOffset`).
- **Face shading is baked into `vColor`** by `ClientLevelWrapper.getShade` (:761). With `lodShading = AUTO` it calls
  `level.cardinalLighting().byFace(dir)`, the same hook our M1 "face shade off" mixin targets (`critique.md` §5 M1.3),
  so DH follows it automatically. After toggling, rebuild LOD meshes with
  `DhApi.Delayed.renderProxy.clearRenderDataCache()`.

### 2.7 Rendering on 26.3

**Engine selection** (`DependencySetup`:100-150, `VersionConstants.getDefaultRenderingEngine`:114):
- `AUTO` resolves to `BLAZE_3D` on 26.x; `OPEN_GL` was the default up to 1.21.11.
- If Iris is installed, `AbstractModInitializer`:455-484 forces `OPEN_GL`. An explicit `BLAZE_3D` with Iris crashes with
  a dialog.
- `MinecraftRenderWrapper.getMcRenderingApi` (:554-575) returns `VULKAN` only when
  `RenderSystem.getDevice().getDeviceInfo().backendName()` equals "Vulkan"; everything else is `OPEN_GL`.
- `BlazeDhRenderApiDefinition` adopts that value. Two `OPEN_GL` branches then run:
  - `MixinLightTexture` (:120) casts the lightmap to `GlTexture`;
  - `GLProxy` (only created for GL).
- Depth: `getMcDepthDirection` is **REVERSE_Z** on 26.3 unless an Iris pack wants forward-Z. `getDepthRange` is
  **ZERO_TO_POS_ONE**.

**Hooks** (`FABRIC/mixins/client`):
- `MixinGameRenderer`: `renderLevel` HEAD/RETURN arms `MixinProjectionMatrixBuffer`, which captures the final projection
  matrix in `ProjectionMatrixBuffer.writeBuffer` (26.3 doesn't expose it otherwise).
- `MixinLightTexture`: `Lightmap.render` RETURN hands DH the lightmap `GpuTexture`.
- `MixinFogRenderer`: a `@WrapOperation` on the `FogData.renderDistanceEnd` PUTFIELD.
  - When vanilla fog is cancelled it sets environmental and render-distance start to 4.2e14, then writes the
    **original** value into `renderDistanceEnd`.
  - So **start > end**, the case PR #4's fog fix handles.
- `MixinLevelRenderer` (26.3):
  - `prepareTranslucents` HEAD (:275) → `ClientApi.renderLods()` (skipped under an Iris pack);
  - `executeOutline` HEAD (:302) → deferred transparent pass, then `renderFadeOpaque()`;
  - `executeOit` HEAD (:319) → `renderFadeTransparent()`.
- `MixinChunkSectionsToRender` (26.3, `@Pseudo` on Sodium `SodiumChunkSection.renderGroup`) is used only while Iris
  shaders are active.

Where these hooks fall in the 26.3 frame (`DECOMP/.../LevelRenderer.java`:248-273, 383-470):
```
frame graph: clear(main color=fog, depth=0.0 reverse-Z) → Sky pass → "main" pass:
   prepareTranslucents()  ◄── DH: whole LOD frame (own targets) + copy into MC main color
   RenderPass "Solid" (main color+depth): Sodium opaque/cutout, entities  [mcopt far terrain draws at executeSolid RETURN]
   executeOit() (improved transparency)   ◄── DH: vanilla fade #2 (transparent)
   executeOutline()                       ◄── DH: deferred transparent LODs (if deferred) + vanilla fade #1
```
DH's LODs end up only in MC's **color**, never in MC's **depth**. Vanilla chunks draw on top with their own depth.
DH's fragment shader discards LODs within the near-clip radius (dithered), and the vanilla-fade pass blends the seam.

**`LodRenderer.renderTerrain`** (`CORE/render/renderer/LodRenderer.java`), first pass:

| # | Step | Line | Pass name or target |
|---|---|---|---|
| 1 | `buildRenderList` (sort and cull containers, CPU) | 186 | |
| 2 | clear DH colour (sky colour, alpha 0) and depth (far) | 224 | |
| 3 | **opaque LOD terrain** | 235 | `distantHorizons:TerrainRenderer`, into DH colour RGBA8 + DH depth D32F |
| 4 | generic objects (clouds, beacons) with SSAO | | |
| 5 | **SSAO** | 250 | AO pass + apply/blur pass |
| 6 | generic objects without SSAO | | |
| 7 | **transparent LOD terrain** (if COMPLETE and not deferred) | 264 | |
| 8 | **fog** | 275 | far and height fog, one pass |
| 9 | **far fade** | 286 | blends MC's colour (sky) under LOD gaps; fades the far clip; then copies back into DH colour |
| 10 | **TAA** + sharpen | 300 | |
| 11 | **copy DH colour → MC main colour** | 343 | `BlazeDhCopyRenderer`, *no blend*; works because step 9 already holds sky under LOD gaps |
| 12 | later: vanilla fade opaque and transparent | | each renders a mix of MC colour/depth and DH colour/depth into its own texture, then copies into MC colour |

Steps 1–10 are each a separate `RenderPassWrapper` (renderpearl render pass), 20+ per frame.

**Terrain pipeline** (`COMMON/render/blaze/BlazeDhTerrainRenderer.java`, `SHD/terrain/blaze/{vert.vsh,frag.fsh}`):
- **State:** face culling on, depth write on, depth test `GREATER` (reverse-Z, :119), triangles.
- **Pipelines:** `distanthorizons:opaque_terrain` (no blend) and `distanthorizons:transparent_terrain`
  (`BlendFunction.TRANSLUCENT`). The colour target is baked as `RGBA8_UNORM`.
- **Uniforms:** `vertSharedUniformBlock` holds white-world, world-Y offset, micro offset, earth radius, TAA frame index,
  view size, camera position and `uCombinedMatrix`. `vertUniqueUniformBlock` holds `uModelOffset` per container.
  `fragUniformBlock` holds clip distance, noise parameters and the dither flag. Samplers: `uLightMap`, `uBlockAtlas`.
- **Vertex shader:**
  - decodes the micro offset;
  - optionally applies earth curvature;
  - **samples MC's lightmap with (sky, block)** to get `vertexColor`, times `vColor`;
  - applies TAA jitter (8-point pattern).
- **Fragment shader:**
  - optional texture tile from the colour-ratio atlas (UV from `fract(blockPos)` and the face index);
  - near-clip discard, either Bayer-dithered `smoothstep(clip, 1.5·clip)` or a hard cut;
  - optional pseudo-texture noise (`dFdx`/`dFdy` normal, quantized random).
  - There is no sun or N·L lighting; all "lighting" is the MC lightmap plus the baked face shade.
- **Draw loop (:300-360):** for each container, `setUniform(vertUniqueUniformBlock)`; then for each 2 MB VBO,
  `setIndexBuffer` + `setVertexBuffer` + `drawIndexed`, firing `DhApiBeforeBufferRenderEvent` per buffer. Draw count is
  roughly sections × (1–2) opaque plus transparent, about 200–800 at the user's distances (est.).
- **Projection** (`CORE/util/RenderUtil.setDhProjectionMatrix`:78-110): MC's projection with DH's own planes.
  `near = min(overdraw-based near, 7.5)` blocks; far = DH distance. For reverse-Z the planes are swapped and
  `setClipPlanes(…, zZeroToOne = true)` is used. **DH depth values are therefore not comparable with MC's depth.**
  Compare them through each side's inverse matrix, as DH's fade shaders do.
- Uploads: `GpuDevice.createBuffer` + `CommandEncoder.writeToBuffer` per VBO; a global sequential quad index buffer.

**Screen-size targets** (`BlazeTextureWrapper.tryCreateOrResizeToScreenSize`, sized from
`gameRenderer.mainRenderTarget().width/height`): DH colour + depth, far-fade colour + depth, vanilla-fade colour + depth,
copy dummy depth, SSAO ×2, TAA ×3, fog ×2, sharpen ×1, apply ×1. That is **about 16 targets** of RGBA8 or D32F, about
**380 MB at 3024×1964** (est.: 16 × 5.94 Mpx × 4 B).

**Other renderers:**
- `BlazeDhGenericObjectRenderer`: clouds and beacon beams. Instanced or direct.
- `BlazeDebugWireframeRenderer`.
- Post shaders live in `SHD/{ssao,fog,fade,antialias,apply,copy}/blaze/*.fsh`. Full-screen draws are 4-vertex
  `TRIANGLE_FAN`s; mcopt re-indexes fans (`mcmetal.m:83-88, 967`).

### 2.8 Shader API and Iris integration
- **DhApi events** (`api/methods/events/abstractEvents`): BeforeRender/Setup/RenderPass/TextureClear/Buffer/Fog/
  ApplyShader/Deferred/Cleanup, ColorDepthTextureCreated, BlockColor/TextureOverride, level and world load.
  - `DhApiRenderParam` exposes `mc/dh` projection, model-view and inverse matrices, near/far clip and render pass. This
    is everything we need to reconstruct LOD positions.
- **Render proxy:**
  - `getDhDepthTextureBlazeWrapper()`, `getDhColorTextureBlazeWrapper()` and
    `getDhBlockRatioAtlasTextureBlazeWrapper()` expose DH's targets as renderpearl textures. On mcopt they map to
    `MTLTexture` handles via `MetalBridge.textureHandle`.
  - `setDeferTransparentRendering(bool)`, `clearRenderDataCache()`.
- **Config API:** `DhApi.Delayed.configs.graphics()` exposes, among others, ambient occlusion `enabled`, fog `drawMode`,
  render distance, quality and LOD shading. **There is no API for anti-aliasing, vanilla fade or far fade.** Those need
  `Config.Client.Advanced.Graphics.*.setApiValue(...)`, which is internal and brittle.
- **Override interfaces** (`IDhApiShaderProgram`, `IDhApiFramebuffer`, `IDhApiGenericObjectShaderProgram`) are honoured
  **only when `isNativeRenderer()`**, i.e. the GL engine. **They are unusable on the Blaze/Metal path.**
- **Iris** (`work/src/iris/.../compat/dh`, 1578 lines):
  - programs `dh_terrain`, `dh_water`, `dh_shadow`, `dh_generic`;
  - samplers `dhDepthTex0` (all LODs) and `dhDepthTex1` (opaque only);
  - uniforms `dhNearPlane`, `dhFarPlane`, `dhRenderDistance` (`CommonUniforms`:184-186), plus `dhProjection` and friends;
  - macros `DH_BLOCK_*` (`StandardMacros`:82-97); `dhMaterialId` comes from `irisMaterial`.
  - While a pack overrides, Iris sets defer-transparent, **turns DH SSAO off and sets fog to FOG_DISABLED**
    (`LodRendererEvents`:91-92, 273-274). That is the same switch-off we want (§5c).
- **Iris on 26.3 needs DH's raw-GL engine.** So Iris + DH is GL-only, and on mcopt "Iris" and "Metal" stay mutually
  exclusive (`iris-internals.md`).

---

## 3. mcopt #4: "Distant Horizons on the Metal backend"

Source: https://github.com/noahdunnagan/mcopt/pull/4. Author CazzaCoder, opened 2026-10-04 23:20Z. **Open, not merged,
`mergeable_state: dirty`.** Head `CazzaCoder:fix/distant-horizons-metal` @ `045cee4f`, base `b089444e` (before alpha.2).
It is 2 ahead and 2 behind main. No review comments. The body says "Generated with Claude Code".

### 3.1 What it changes (whole diff, 7 files, +99/−5)
1. **`sodium_terrain.vs.metal` fog** (commit `0a301c2b`).
   - mcopt's hand-written MSL pre-normalizes fog per vertex as `(d − start)/(end − start)`.
   - Sodium's `linear_fog_value` checks `d ≤ start` before `d ≥ end`, so the two disagree when start ≥ end. That is
     exactly DH's vanilla-fog-off state (start 4.2e14, end ≈ render distance): every chunk renders as fog colour.
   - The fix: `span = max(end − start, 1e-4)` and clamp to ±`HALF_MAX` before narrowing to `half`.
   - GPU-checked table in the PR: DH range 1.0 → 0.0; vanilla, water and start == end cases unchanged.
   - Our local `mcopt/.../sodium_terrain.vs.metal:54-55` still has the unfixed form.
2. **Backend spoof** (commit `045cee4f`).
   - `mcopt-metal-compat.mixins.json` (required: false, plugin `CompatMixinPlugin` applies `DhRenderApiMixin` only if
     `distanthorizons` is loaded).
   - `@Inject(HEAD, cancellable, require = 0)` on `MinecraftRenderWrapper.getMcRenderingApi` returns
     `EDhApiRenderingApi.VULKAN` (looked up reflectively) when `backendName() == "Metal"`.
   - Rationale: Metal shares Vulkan's 0–1 depth and top-left conventions.
   - The README line is updated.
3. **Tested:** M4 Pro, macOS 27, MC 26.3, Sodium 0.9.3-alpha.1, DH 3.3.4, Fabric API 0.161.0, DH defaults (SSAO, vanilla
   fade, AA on).
   - Before: crash on the first frame. With commit 2 only: a fog-coloured disc.
   - After: both render, **"160–300 fps where DH on OpenGL gave 60–70"**. Without DH: unchanged.

### 3.2 What works and what doesn't (from code)
- **Works, by construction:** DH's Blaze renderer touches only renderpearl objects (`GpuDevice`, `CommandEncoder`,
  `RenderPass`, `GpuTexture`), and mcopt implements them all:
  - SPIR-V → MSL for every DH pipeline (`MetalPipeline.compile`);
  - all vertex formats, including DH's ushort3/ubyte/byte attributes (`MetalConst.vertexFormat`);
  - fans, D32F, RGBA8, clears, `writeToBuffer` with rename-on-busy (`MetalEncoder.writeToBuffer`:457-477);
  - textures created without ShaderWrite, so they stay compressible (`MetalDevice.createTexture`:99-106).
- **Not addressed:**
  1. **Profile conflict.** alpha.2+ `Profile.distantHorizons()` (`MCOPT/Profile.java:44-60`) sets `mcopt.metal=false`
     whenever DH is loaded. The PR predates it and doesn't touch it, so merged as-is on alpha.3 it is inert unless
     `-Dmcopt.metal=true`.
  2. **The spoof is global.** `DhApi.Delayed.renderProxy.getRenderingApi()` also answers `VULKAN` to third-party
     add-ons, and any future DH "Vulkan-only" code path (e.g. native `VkDevice` access) would be taken on Metal. The
     clean fix is upstream: classify "not OpenGL" instead of "== Vulkan", or add a `METAL`/`OTHER` value.
  3. **Iris + mcopt Metal + DH** still crashes: DH forces the GL engine with Iris. A guard is needed (§5a).
  4. **mcopt's own far terrain (`mcopt.lod`) can run at the same time** as DH, drawing far terrain twice. They need to
     exclude each other.
  5. **MetalFX/FrameGen and other depth consumers:**
     - DH pixels never reach MC depth, so mcopt's depth-derived motion and MetalFX treat LODs as sky. Rotation is
       right; translation parallax is lost, which is small at LOD distances.
     - **DH TAA jitter stacks on top of the MetalFX projection jitter**, because DH captures MC's (jittered)
       projection.
  6. **Performance claim is unverifiable.** No resolution, render distance, DH distance or scene is given. Plausible
     given Apple GL overhead (§4.1), but not reproducible.
- **Quality verdict: good.**
  - Minimal, gated (loaded-mod check plus `require = 0`), self-documenting, with a measured fog table.
  - Correct analysis of DH's fog hook. The fog fix is correct and independent of DH.
  - It's a compatibility shim, not an integration: DH's whole post chain runs unchanged, and the API spoof is the
    weakest part.

---

## 4. Performance on Apple Silicon

### 4.1 Known Mac issues (official DH GitLab)
- **Apple's GL stops at 4.1.** DH's raw-GL engine falls back to pre-GL4.3 vertex attributes
  (`GlVertexAttributePreGL43`), with no `ARB_buffer_storage`, high driver CPU cost and no compute.
  - #1190: users resort to the third-party libMGL for GL 4.6.
  - #1258 (open, 2026-05/09, M4 Max, 3.0.3-b): native crash in `IOGPU`/`Metal` during GL renderer setup, labelled
    "Developers Don't Know How To Fix".
  - #1206 (2.4.5-b, closed): SIGSEGV in `AGXMetalG16G_B0::BlitDispatchContext` after "Null phantom checkout" during
    world gen.
  - #1179: JVM crash on 1.21.1 Fabric on Apple Silicon. #1227: crash with Sodium on NeoForge.
  - #871 / #793: generic (instanced) rendering crash with Sodium on M1, the source of the log warning the user gets.
    #932: clouds incorrect on macOS.
- **Thread count:** the default 50% of logical cores is 5 on a 10-core M4 (4P+6E). Without QoS, DH workers take
  P-cores. The user's 5 threads at ratio 1.0 is aggressive while generating; QoS utility or ratio ≈0.5 fits better.
- **Memory:** 16 GB unified. The user's 6 GB heap is right per the DH FAQ. G1 pauses show up as stutter; ZGC is
  recommended.

### 4.2 DH on mcopt Metal (Blaze path): where time goes (est.)
- **CPU (render thread):**
  - `buildRenderList` sort and cull;
  - uniform uploads per container (`tryUpload`);
  - a few hundred `drawIndexed`, each with about 4–6 JNI calls (`MetalRenderPass`:84-330) plus renderpearl frontend
    validation;
  - 20+ render passes per frame.
  - Expected well under 1 ms at the user's distances. Worth a `mcopt.metal.trace` check later.
- **GPU geometry:** 16-B vertices, merged quads, culled. Triangle count is unknown. Measure it via DH's F3 lines and
  mcopt GPU timing. Apple's opaque pass is geometry-bound (`apple-gpu.md` §0.3: 1.78 M triangles ≈ 1.35–1.8 ms).
- **GPU full-screen work:** about 14 full-screen draws (§2.7). Measured on the M4 (`apple-gpu.md` §1.3): an RGBA16F
  read+write pass costs 0.31–0.50 ms on compressible targets at native. RGBA8/D32F passes reading 2–4 textures should
  land around 0.2–0.45 ms each.
  - **About 3–6 ms per frame at native 3024×1964 (est.)**; ÷2.2 at 0.67x.
  - That is most of a 120 Hz budget (8.3 ms) before our own lighting.
- **Memory:** about 380 MB of DH screen targets at native (est.), plus VBOs (tens to a few hundred MB at large
  distances).

### 4.3 Settings that matter most

| Setting | FPS impact | Look impact | Recommendation (M4, Retina) |
|---|---|---|---|
| `lodChunkRenderDistanceRadius` | GPU geometry ~log(R); **worldgen CPU and disk ~R²** | huge (the point of DH) | 64–128 chunks is fine on GPU; limit by generation time |
| `horizontalQuality` | geometry ×(base, unit): MEDIUM→HIGH ≈ 1.5–2× quads (est.) | crisper mid-distance | MEDIUM or HIGH |
| `verticalQuality` | slices per column → overhang and cliff quads | cliffs, caves, floating islands | MEDIUM (user); HIGH if terrain is mountainous |
| SSAO (full-res, 6 samples + 5×5 blur) | ~0.5–1 ms native (est.) | contact darkening | **off under our shaders** (our AO covers LODs) |
| TAA + sharpen | ~0.6–1 ms native (est.) | removes LOD shimmer | **off under our TAAU** (one temporal pass for everything) |
| DH fog / far fade | ~0.5–1 ms (est.) | atmosphere | **ours replaces it** |
| `vanillaFadeMode` DOUBLE_PASS | ~1–2 ms (2 passes + 2 copies, est.) | soft seam at the vanilla edge | SINGLE_PASS or ours (dither in Sodium override) |
| `transparency` COMPLETE vs FAKE | an extra pass over water | real water | COMPLETE (water is where Photon shines) |
| noise texture | ALU only | fake texture | off once 3.3 texture tiles exist, or keep (cheap) |
| cave culling | fewer quads underground | none | on |
| generic instanced rendering | small | clouds and beacons | if crashes, disable instancing (macOS + Sodium reports) |
| `numberOfThreads` / `threadRunTimeRatio` | stutter while generating | none | 3–4 threads or ratio 0.5; QoS utility (§5c) |
| `distantGeneratorMode` | FEATURES is the heaviest | trees and structures | FEATURES (user); SURFACE for fast fill |

---

## 5. What it takes

### (a) Run DH robustly on mcopt's Metal backend (≈1.5–3 agent-days)
1. **Rebase #4** onto our fork (`work/mcopt-shade`, branch `shade`; not `wt/m1*`):
   - keep the fog fix (also needed by our terrain override's fog function);
   - keep the compat mixin plugin.
2. **Replace `Profile.distantHorizons()`.** The rule becomes "stay on Metal with DH unless Iris is loaded, or DH's
   `renderingEngine` is explicitly OPEN_GL" (read `config/DistantHorizons.toml`). Log the decision.
3. **Narrow the spoof.** Prefer an upstream DH merge request (LGPL; a one-line change in `getMcRenderingApi` to
   "non-OpenGL backend → non-GL"). Until then, keep `VULKAN`, but only for `MinecraftRenderWrapper` and only while
   `backendName == "Metal"`. Version-gate the mixin on the DH version range we test (3.3.3–3.3.4 for 26.3); DH ships
   about every 4–7 days.
4. **Exclusions:**
   - `mcopt.lod` forced off when DH is loaded;
   - FrameGen/MetalFX jitter: switch DH TAA off
     (`Config.Client.Advanced.Graphics.enableAntiAliasing.setApiValue(false, "mcopt")`) whenever mcopt jitters the
     projection.
5. **DH thread QoS:** add an mcopt QoS role `dh` (utility) applied from a mixin on `DhThreadFactory.newThread`. Measure
   1% lows while generating.
6. **Test matrix** (when launches are allowed): DH 3.3.4 + Sodium 0.9.3 + mcopt Metal with SSAO on/off, AA on/off,
   fade modes, transparency modes, clouds and beacons (generic instanced), dimension change, F3+T reload, window resize,
   MetalFX on.

### (b) Light, fog and shade DH with our native pipeline

The hook: DH's Blaze pipelines compile through `MetalPipeline.compile`, and mcopt supports **per-pipeline MSL
overrides keyed by pipeline name** (`-Dmcopt.metal.overrideMsl`; M1 turns this into an in-jar registry, `critique.md`
§5 M1.6). Our global binding hook (ShadeFrame at `buffer(16)`, shadow map at `texture(16)`, compare sampler at
`sampler(12)`) is applied in `MetalEncoder.createRenderPass`, so **DH's render passes get it automatically.**

- **b1. Forward-lit LOD terrain** (≈2–4 agent-days after M1's ShadeFrame, binding hook and registry):
  - **Starting point:** dump DH's translated MSL (`-Dmcopt.metal.dumpMsl`) for `distanthorizons:opaque_terrain` and
    `transparent_terrain` to get buffer and texture indices. Write overrides that include `shade_common.metal`.
  - **Normal:** from `irisNormal` (0–5).
  - **Light:** sky and block from `meta` nibbles (not the lightmap texture), so our sky ambient and warm block light
    match Sodium terrain exactly.
  - **Sun/moon:** N·L from ShadeFrame. Shadow lookup only inside the shadow distance; LODs are mostly beyond it.
  - **Fog:** our atmospheric/aerial-perspective fog by distance. Keep DH's dithered near-clip discard and the texture
    tile sampling.
  - **Face shading:** M1's `CardinalLighting.byFace` mixin removes it consistently (DH `lodShading = AUTO`). Call
    `clearRenderDataCache()` on toggle.
  - **Switch DH's own effects off** while our shaders are on, mirroring Iris: `ambientOcclusion().enabled(false)`,
    `fog().drawMode(FOG_DISABLED)`, AA off.
  - **Result:** the Photon look (lit LODs, atmospheric fog, no LOD shadows), at about zero extra GPU cost over DH's
    own terrain pass.
- **b2. LOD water** (≈1–2 days after M2 water): an override of `transparent_terrain` that uses M2's water function.
  - Fresnel and sky reflection.
  - Absorption from (DH depth − LOD water depth) needs DH's opaque depth. Copy it before the transparent pass, or set
    `setDeferTransparentRendering(true)` so DH draws transparent LODs later, at `executeOutline`.
  - No SSR on LODs at first.
- **b3. LOD shadows (optional; Photon has none):**
  - cheapest: **screen-space sun shadows** marched on DH depth at half resolution, 1–2 days;
  - better: draw DH's VBOs into our far cascade (mixin on `BlazeDhTerrainRenderer.render` to capture containers;
    `MetalBridge` handles for the buffers; depth-only PSO; sun-facing faces only), 3–5 days;
  - Skip in v1.
- **b4. HDR and deferred integration (M2/M3):**
  - DH pipelines bake `RGBA8_UNORM` colour targets, so writing into our RGBA16F world target needs mcopt's per-format
    PSO variants (`critique.md` §6.3), or a redirector on pass `distantHorizons:TerrainRenderer` towards our G-buffer.
  - Our VL, SSR, AO and TAAU need **one merged view-distance or depth image**: a small pass taking the nearer of MC
    depth and DH depth, each linearized with its own inverse matrix from `DhApiRenderParam`.
  - About 3–5 days.

### (c) Fast at Retina on the M4 (≈2–3 agent-days, in parallel with b1)
1. **Cut DH's post chain**, saving about 3–6 ms at native (est.). When our pipeline is active:
   - SSAO off, fog off, TAA and sharpen off (our TAAU);
   - vanilla fade off: use a dithered fade at the seam in our Sodium terrain override and in the LOD shader, or
     `SINGLE_PASS` as a fallback;
   - far fade off. It also composites the sky under LOD gaps, so replace it with an **alpha-blended copy** (only DH
     alpha > 0 pixels) into MC colour. One pass instead of far fade + copy + copy; or draw LODs straight into MC's main
     colour target through a redirect.
   - DH then costs: clear + opaque + transparent + one composite.
2. **Render scale for free:** DH sizes its targets from `gameRenderer.mainRenderTarget()`, so mcopt's render-scale and
   MetalFX target swap shrinks DH's work too. Verify that the swap goes through `mainRenderTarget()`.
3. **Threads:** QoS utility for DH workers (§5a.5), `threadRunTimeRatio ≈ 0.5` while the player is moving.
   Generation never on P-cores at user-interactive QoS.
4. **Later, only if measured necessary:** batch DH draws (one argument buffer, multi-draw indexed, per-container offset
   in an instance or buffer index), and GPU culling. Not needed at hundreds of draws.

### Effort and order

| Step | Depends on | Effort (agent-days) | Ships |
|---|---|---|---|
| (a) rebase #4 + Profile rule + exclusions + QoS | fork baseline | 1.5–3 | DH on Metal, vanilla look, instance "mcopt Metal + DH" |
| (c1) DH post chain off + single composite | (a) | 1–2 | faster DH on Metal |
| (b1) forward-lit LOD terrain overrides | M1 ShadeFrame, binding hook, MSL registry | 2–4 | "Photon-like" lit LODs with our sky and fog |
| (b2) LOD water | M2 water | 1–2 | lit LOD water |
| (b4) merged depth + HDR/deferred | M2/M3 | 3–5 | consistent TAAU, VL, AO across LODs |
| (b3) LOD shadows | M1 cascades | 1–5 | beyond Photon |
| **Total to Photon-level parity** (a + c1 + b1 + b2) | | **≈6–11** | |

### Risks
1. **DH churn.** Four 26.3 releases in 12 days. Mixins targeting internals (`MinecraftRenderWrapper`,
   `BlazeDhTerrainRenderer`, `Config`) can break. Pin and version-gate; `require = 0`; upstream the backend
   classification.
2. **The Blaze renderer is young.** Recent fixes: "DH disappearing when a glow is on screen", blend state, the depth
   range for vanilla fade. Expect Metal-only edge cases. Most likely: `TRIANGLE_FAN` passes, D32F sampling,
   texture-view wraps of the main targets, and generic instancing.
3. **Depth mismatch.** DH and MC use different projections, with DH near ≤ 7.5 blocks and its own far. Any effect that
   needs "scene depth" must merge both, or LOD pixels will read as sky.
4. **Iris on the same instance** forces DH's GL engine, which crashes on Metal. Guard it at startup with a clear message.
5. **Memory on 16 GB.** About 380 MB of DH targets at native, VBOs and a 6–8 GB heap. Removing DH's post chain
   (c1) also frees most of those targets.
6. **Pollones is a 1.21.1 multiplayer server.** The mcopt/Metal path is 26.3-only, so it helps the user's server only
   once that server moves to 26.3. For Pollones today the only levers are settings, DH 3.3.3 (config reset) and JVM
   flags (§6).
7. **The PR's 160–300 fps** comes from an M4 Pro with unknown settings. Expect less on the 10-core M4 at Retina until
   (c1) lands.
8. **Licensing:** LGPL is fine for mixins. Shipping a patched DH jar would require publishing its source. Photon is
   "all rights reserved": match its look, not its code.

---

## 6. Notes for the user's current setup (no changes made)
Pollones, 1.21.1, Apple GL, Iris + Photon:
- Back up `config/DistantHorizons.toml` before any DH update. 3.3.x resets the config (version 4→5), and the
  auto-updater is on.
- Under Photon, DH's SSAO, fog and vanilla fade are already bypassed (Iris overrides, `shouldRenderFade` is false with
  shaders). This is per the 3.3.4 source; 3.2.0-b was not inspected. FPS is governed by Photon's per-pixel cost at the window resolution, DH geometry (distance, quality) and
  Apple GL driver overhead.
- If generation stutters: 3–4 DH threads or `threadRunTimeRatio` 0.5. JVM: `-XX:+UseZGC -XX:+ZGenerational` (Java 21),
  heap 6–8 GB.
- If clouds or beacons crash: disable instanced generic rendering.

## 7. mcopt's own far terrain vs DH
| | mcopt `mcopt.lod` | DH 3.3.4 |
|---|---|---|
| Data | clipmap tiles, generated **from the world generator's noise** without generating chunks (`LodNoise`); real chunks overwrite them (`LodChunks`); disk cache | real chunk data (seen, generated FEATURES/INTERNAL_SERVER, or from a DH server), SQLite |
| Accuracy far away | estimated surface + impostor tree canopies (`LodTrees`) | exact blocks; structures depend on mode |
| Rendering | GPU column walk (`columns.metal`) drawn into the level pass; G-buffer variants exist (`gbuffer=false` hard-coded, `Lod.java:455,495`) | renderpearl meshes + DH post chain |
| Mac | native Metal, shared buffers, no upload | through mcopt's renderpearl → Metal (after a), or Apple GL |
| Multiplayer | client only | client + server LOD sync |
| User preference | — | **loved; keeps worlds the user knows** |

**Recommendation:** make DH the supported LOD path for the user (multiplayer, real data, it is what they love). Keep
`mcopt.lod` as an alternative for singleplayer. Never run both at once.

## 8. Open items to verify in game (when launches are allowed)
- DH on Metal: count of draws and render passes per frame, GPU ms of DH passes (`mcopt.metal.trace`), triangle count.
  Compare at native and 0.67x.
- That `MetalFx.beginWorld`'s target swap goes through `gameRenderer.mainRenderTarget()`, so DH targets follow render
  scale.
- MSL dump of `distanthorizons:*` pipelines, to fix the binding indices for the overrides.
- Whether DH instanced generic rendering is stable on Metal.

## Sources
- Modrinth API: `api.modrinth.com/v2/project/distanthorizons` and the `/version` lists for 26.3 and 1.21.1 (changelogs
  quoted above).
- GitLab:
  - repo `gitlab.com/distant-horizons-team/distant-horizons` (tag 3.3.4);
  - core `gitlab.com/jeseibel/distant-horizons-core` @ 6441062;
  - issues #1258, #1190, #1227, #1206, #1179, #932, #871, #793;
  - wiki FAQ "Problems-and-Solutions".
- GitHub: `noahdunnagan/mcopt` PR #4 (body, files, diff, commits `0a301c2b` and `045cee4f`), the compare
  `main...CazzaCoder:fix/distant-horizons-metal`, and the PR list.
- Local:
  - `work/src/distant-horizons` (code citations above);
  - `work/src/iris` (26.3 branch, `compat/dh`);
  - `mcopt/` (alpha.3);
  - `work/decomp/mc-26.3`;
  - `research/{mcopt-architecture,critique,apple-gpu,mc-renderer,community,iris-internals}.md`;
  - Pollones instance files (read only).
