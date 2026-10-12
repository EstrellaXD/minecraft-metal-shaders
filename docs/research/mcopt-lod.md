# mcopt's native far terrain (`mcopt.lod`) compared with Distant Horizons (report `mcopt-lod`)

Scope: everything in `mcopt/metal/src/main/java/mcopt/metal/lod` (26 files, 9.7k lines), the native files `mclod.m`,
`lodmesh.c/.h`, `lodnoise.c` and `lodseam.m`, the shaders `mcopt/lod/columns.metal` (3759 lines) and `seam.metal`, the
mixins in `mixin/lod`, and the profiles. Compared with Distant Horizons 3.3.4 (sources at `work/src/distant-horizons`,
tag `3.3.4`, commit `eb1007c`) and with the user's own DH setup (read-only). I launched nothing and ran no benchmarks.

Paths below are relative to `mcopt/metal/src/main/` unless noted. `DH/` means `work/src/distant-horizons/`.

---

## 0. Summary

**Resumen (ES).** El "look DH" que le gusta al usuario es Distant Horizons + Photon (Iris) en el servidor multijugador
"Pollones" (MC 1.21.1). El far terrain propio de mcopt (`mcopt.lod`) es muy rápido y está hecho para Metal, pero tiene
cuatro límites para ese caso:
- solo funciona en un solo jugador, porque necesita el servidor integrado y la semilla;
- es un mapa de alturas de 2.5D: no hay voladizos, interiores, puentes ni agua translúcida;
- no tiene luz de bloques, así que de noche las aldeas y bases lejanas no brillan;
- no ve estructuras ni construcciones que nunca se hayan cargado.

DH 3.3.4 para 26.3 ya dibuja a través de Blaze3D (la API de render de Mojang), que es justo lo que implementa el backend
Metal de mcopt. El PR #4 de mcopt (abierto, no fusionado) lo hace funcionar en Metal y reporta 160–300 fps frente a 60–70
en OpenGL. **Recomendación: (a) DH sobre Metal como proveedor del terreno lejano, iluminado por nuestros shaders**
mediante overrides MSL de sus pipelines y eventos de su API. `mcopt.lod` queda apagado en esa instancia y como referencia
técnica. Un híbrido opcional para más adelante: el generador de ruido de mcopt como `IDhApiWorldGenerator` de DH, solo
para acelerar la generación en un solo jugador.

**English summary.**
1. **What `mcopt.lod` is.** A GPU-driven clipmap heightfield, written for Apple GPUs.
   - It samples the world generator's own density functions without generating chunks. The noise primitives are
     bit-exact, levels 0–1 follow the game's own surface, and coarser levels are approximate.
   - It runs the game's tree features on level 0 and replaces the estimate with real chunks once the client has seen them.
   - It draws everything either as a per-screen-column compute walk or as per-tile meshes with a GPU cull and indirect
     draws. The recommended profile uses the meshes.
   - Reach is 8192 blocks with 5 clipmap levels. The clipmap takes about 240 MB of shared memory, plus a mesh arena of
     at least 64 MB.
   - Lighting is vanilla-style only: a fixed face shade times the lightmap's sky-15 colour, neighbour AO on level 0, and
     the game's fog moved out to the reach.
   - A deferred G-buffer variant exists but is hard-coded off (`Lod.java:455,495`).
2. **The user's "DH look"** (from `instances/Pollones server`, read-only):
   - DH 3.2.0-b (LGPL-3), Iris 1.8.8 and **Photon v1** on MC 1.21.1, on a **multiplayer** server
     (`servers.dat`: "Pollones").
   - DH LOD radius **40 chunks** (640 blocks), horizontal and vertical quality MEDIUM, complete transparency, SSAO,
     noise texture, exponential-squared DH fog, height fog.
   - Photon lights DH terrain in its deferred pass with atmosphere, clouds, AO and `dh_water`, and **`dhShadow.enabled =
     false`**, so no shadows are cast from DH terrain. Parity therefore needs lighting, aerial perspective, AO and water
     on the far terrain, not far shadows.
3. **`mcopt.lod` cannot give that look**, for four reasons:
   - It does nothing without an integrated server (`Lod.java:338`), so it is useless on Pollones-type multiplayer.
   - One surface per column, plus at most one floating tree crown, cannot show overhangs, cave mouths at distance,
     bridges, roofs over open space or translucent water.
   - It has no block light.
   - Structures, villages and player builds appear only for chunks the client has actually loaded, then from the
     per-world disk cache.
4. **DH on Metal is now realistic.**
   - DH 3.3.4-26.3 exists on Modrinth (2026-09-29, LGPL-3.0-only).
   - Since 3.0.0 DH has a Blaze3D renderer, and since 3.1.0 a "Vulkan support via Blaze3D" path.
   - mcopt PR #4 answers DH's `getMcRenderingApi()` with `VULKAN` while Metal is active, and fixes the Sodium-terrain
     fog range.
   - DH pipelines are named `distanthorizons:opaque_terrain` and `distanthorizons:transparent_terrain` (plus SSAO, fog,
     fade, TAA, copy and apply). The fork's per-pipeline MSL override (`MetalPipeline.compile`) can replace them with
     our lit or G-buffer versions without touching DH's code.
5. **Recommendation: (a) DH on Metal, lit by our shade pipeline.**
   - Phase it with the M1–M3 milestones (§6).
   - Keep `mcopt.lod` off in the shader instance, and add a guard that makes DH and `mcopt.lod` mutually exclusive.
   - Optional later: hybrid (c), mcopt's `LodNoise` as a DH world generator for singleplayer generation speed only.

---

## 1. What the user's "DH look" actually is (evidence)

Read-only, from `~/Library/Application Support/PrismLauncher/instances/Pollones server`:

**Instance**
- `mmc-pack.json`: MC 1.21.1, Fabric Loader 0.18.4, 95 mods (Cobblemon modpack, TerraBlender, BetterNether/BetterEnd
  (wover), Moog's structures).
- Mods: `DistantHorizons-3.2.0-b-1.21.1.jar` (`fabric.mod.json` license "LGPL-3"), `iris-fabric-1.8.8`, `sodium 0.6.13`,
  `sodium-extra`, `indium`, `lithium`. The last `latest.log` (Aug 8) shows DH 2.4.5-b running; the 3.2.0-b jar has the same
  mtime as that session, which points to DH's auto-updater (`enableAutoUpdater = true`).
- `config/iris.properties`: `shaderPack=photon_v1`, `maxShadowRenderDistance=32`. `options.txt`: render distance 10,
  simulation 12, `maxFps 260`.
- `servers.dat`: one server, "Pollones". `Distant_Horizons_server_data/Pollones` (212 MB) is DH's multiplayer LOD cache.

**`config/DistantHorizons.toml`**
- `lodChunkRenderDistanceRadius = 40`.
- `horizontalQuality = MEDIUM`, `verticalQuality = MEDIUM`, `maxHorizontalResolution = BLOCK`.
- `transparency = COMPLETE`, `lodBiomeBlending = 3`, `grassSideRendering = FADE_TO_DIRT`, `vanillaFadeMode = DOUBLE_PASS`,
  `ditherDhFade = true`.
- SSAO on (6 samples, radius 4, strength 0.2). Noise texture on (intensity 5, 4 steps, dropoff 1024).
- DH fog on: EXPONENTIAL_SQUARED, density 2.5, start 0.4. Height fog below 80. Vanilla fog off.
- `distantGeneratorMode = FEATURES`, 5 threads.

**Photon** (`shaderpacks/photon_v1`, "All rights reserved", Benjamin Stott; used here only for reference)
- `shaders.properties` has a DH section with **`dhShadow.enabled = false`**, a `dh_water` program, and `combined_far =
  dhRenderDistance`.
- DH depth is read in `d3_ao.fsh`, `d4_deferred_shading.fsh`, `c0_vl.fsh`, `c1_blend_layers.fsh` and `c4_taa_exposure.fsh`,
  plus `include/misc/distant_horizons.glsl` and `NOISE_ON_DH_TERRAIN`.
- So the "look" is: DH geometry and colours, lit by Photon's deferred sun/sky/SH ambient, with Photon AO, atmospheric
  fog and aerial perspective, volumetric light and clouds, and Photon water on DH water. There are no shadows from DH
  terrain.

**Consequences**
- This look lives on a **1.21.1 multiplayer modpack**. mcopt and our Metal shaders are **26.3-only**, so the user keeps
  Iris+Photon+DH on GL for Pollones until that server moves to 26.x. Tell the user this up front.
- On 26.3, the target is DH 3.3.x with our Metal shaders standing in for Photon.

**Other instances**
- "mcopt Metal 26.3" runs `profile=alpha` with empty JVM args, so `mcopt.lod` is **off**. The user has probably never
  seen mcopt's far terrain.
- "mcopt Shaders 26.3" runs `-Dmcopt.metal=false` (Iris on GL).

---

## 2. `mcopt.lod` in depth

### 2.1 Switches, profiles and lifecycle
- **Enable:** `-Dmcopt.lod=true`, or any `mcopt.lod.radius` (`LodConfig.java:8`).
  - `LodMixinPlugin` rejects every far-terrain mixin without it, so the game is "byte-for-byte" unchanged.
  - The `alpha` profile lists the far-terrain settings but **does not** set `mcopt.lod`.
  - `recommended.properties` sets `mcopt.lod=true` plus the far-terrain flags below.
  - The first-launch `config/mcopt.properties` carries `#mcopt.lod=true` and "for Macs with 10 or more GPU cores"
    (`Profile.java` `writeDefault`).
- **Recommended far-terrain flags:**
  `render=mesh`, `fade=instant`, `handoff=drawn`, `prio=screen`, `publish=gpu`, `handoffPush=0.9995`,
  `nativeNoise=neon`, `yield=missing:0`.
- **Defaults** (`LodConfig`):

  | Flag | Default | What it does |
  |---|---|---|
  | `RADIUS_CHUNKS` | 512 | Reach 8192 blocks |
  | `N` | 2048 | Cells across a level's window; 512 with `lod.small` |
  | `THREADS` | cores − 4 | Generation workers; 6 on the user's 10-core CPU |
  | `CROWN_LEVELS` | 1 | Levels whose crowns float over their ground |
  | `TREE_LEVELS` | 1 | Levels that get the game's own trees |
  | `NEAR_DETAIL`, `TEXTURES`, `PLANTS` | on | Level-0 AO, textures and plants |
  | `FINE_DENSITY` | levels 0–1 | Final density with caves |
  | `DISK_CACHE` | on | `<gameDir>/mcopt-lod/<save>/<dim>` |
  | `HAZE` | 1 | Environmental fog stretched with the reach |

- **Hooks** (`mixin/lod`):
  - `LodLevelRendererMixin`: `LevelRenderer.render` HEAD → `Lod.beginLevel` (camera and fog snapshot);
    `executeSolid` RETURN → `Lod.drawSolid` (the draw, inside the open level pass, after real opaque terrain);
    `render` RETURN → `Lod.endLevel` (TAA tile and seam probe).
  - `LodCameraMixin`: far plane = max(vanilla, 1.5·reach + 1024). This is safe because depth is reversed-Z on a float
    buffer.
  - `LodFogMixin`: the fog colour is computed as for min(32, reach/16) chunks, `skyEnd` moves to the reach, and
    `Lod.adjustFog` (`Lod.java:176`) puts the render-distance fade over the last tenth of the reach and stretches
    environmental fog when it ends at 512 or more.
  - `LodClientLevelMixin`: chunk load and unload → `Lod.chunk`; `sendBlockUpdated` → `blockChanged` (occluder re-snapshot,
    `realOcc` only).
  - `LodSodiumFadeMixin`: Sodium's section fade-in is off while far terrain draws.
  - `LodMaterialContextAccess`: runs the surface rules per column.
  - `LodTruth*`: test-only render distance above 32.
- **World binding** (`Lod.ensureWorld`, `Lod.java:330-385`):
  - `owner = level == null || server == null ? null : level`. **No integrated server means no far terrain.**
  - It needs `server.getLevel(dim)` for the `RandomState`, which carries the seed.
  - `LodNoise.of` returns null for non-noise generators and for dimensions with a ceiling, so the **Nether gets
    nothing** (`LodNoise.java:199`).
- **Backend:** Metal only. `Lod.get()` disables itself when `MetalBridge.encoder(...)` is null (the GL backend).

### 2.2 Generation: how far terrain is made (`LodNoise`, `LodNativeNoise`, `lodnoise.c`, `LodForest`, `LodTrees`)
- **Density from the game's own router**, sampled in batches over a tile's columns, with no chunk generation.
  - Overworld levels ≥ 2 use `overworld/sloped_cheese` (shape before caves) plus the final density's top slide
    (`LodNoise.java:200-210`). A coarse search finds the top: one batched volume every 32 blocks brackets the top
    crossing, two point samples narrow it to the game's 8-block cell, and linear interpolation follows, as the game's
    cells do.
  - Levels 0–1 use the **final density split as the game evaluates it**: `min(squeeze(interpolated inner), carvers…)`,
    found by `split()`, which drops the beardifier because "the far terrain has none". It interpolates bilinearly
    between 4-block corners and linearly in y, so cave mouths and pits break the surface.
  - Water comes from the **game's aquifers** through the reflected `globalFluidPicker` at levels 0–1, and from sea
    level beyond.
  - Biomes come from the climate sampler and the biome source's resolver.
  - The top block comes from the dimension's **data-driven material (surface) rules** through `MaterialRuleContext`
    invokers.
  - **Consequence:** modded or datapack worldgen that stays inside the noise router and surface rules is followed
    (TerraBlender biomes, Terralith-style routers). The Nether is skipped. The End (noise-based, no ceiling) is drawn as a
    heightfield, with a special case for thin floating lips.
- **"Bit-exact":** `LodNativeNoise` with `neon`, `java` or `vector` replaces only the Perlin/`NoiseStack` and
  `BlendedNoise` leaves.
  - The class comment says all three backends give the game's values bit for bit, checked offline over millions of
    inputs. `-Dmcopt.lod.genBench` checks whole tiles, and `-Dmcopt.lod.nativeNoise.verify=N` spot-checks volumes.
  - `GenServerNoise` lets `-Dmcopt.gen.nativeNoise` speed up the integrated server's own chunk generation with the same
    kernels.
  - **So the noise is exact; the surface is exact only where the algorithm is the game's** (levels 0–1, final density,
    aquifers).
  - Coarser levels are approximations: no caves, 32-block brackets, one sample per cell.
  - Every level misses structures (villages, temples, Moog's structures), lakes, rocks and other non-tree features,
    and terrain adaptation around structures.
  - `-Dmcopt.lod.verify=true` measures agreement against real chunks (surface exact or off by 1, crowns, plants, top
    and side block, colour). I found no published results.
- **Trees.**
  - Level 0 (`TREE_LEVELS=1`, out to about 956 blocks): `LodForest` runs the **game's own vegetal-decoration tree
    features** with the game's decoration and feature seeds, in its order, against a stand-in level whose ground is the
    level-0 surface. It records every block set.
  - It differs from the real chunk in three ways: the ground ignores caves and earlier features, neighbouring chunks'
    trees don't see each other, and tree features are **chosen by vanilla name heuristics** (`trees_*`, `birch_tall`,
    `dark_forest_vegetation`, …, `LodForest.java:105-108`). Modded trees under other names are mostly missed.
  - Plants (grass, ferns, flowers) come from features matched by name and are drawn as crossed quads on level 0.
  - Levels ≥ 1 get `LodTrees`: a per-biome **impostor canopy** (cover fraction, height, leaf block), hashed per 4-block
    patch. From 16-block cells it becomes a raised tinted layer.
- **Colours:**
  - `LodColors` takes per-block average top and side colours from the baked model and texture, and the tint kind
    (grass, foliage, water, dry foliage, constant). Its probe level uses `CardinalLighting.DEFAULT`
    (`LodColors.java:277`).
  - `LodPalette` holds up to 1024 surface block states with atlas sprite positions, so level 0 draws real textures at
    the distance's mip.
  - Biome tint is baked into the cell colour.
- **Scheduling:**
  - `LodField` has workers ordered by screen importance (`prio=screen`: the coarsest fallback first, then projected
    area, then predicted entry within a second).
  - With `LodYield` (`yield=missing:0`), generation waits while the client lacks chunks within render distance. The
    code notes that unthrottled far generation doubles missing near chunks in a 60 b/s flight (36–40% vs 19% on the
    mini).
  - `LodGenQos` sets QoS classes.
  - There is a disk cache per save and dimension (`FORMAT = 10`, palette stored by block-state name).

### 2.3 Real chunks: what happens to player builds (`LodChunks`, `LodField.chunk`)
- On every **client chunk load and unload**, `LodChunks.snapshot` copies, per column:
  - the top block, the block under it, the height, the water surface (with the floor under the water), the biome;
  - a floating crown (leaves over air: lowest y, top y, tier runs, trunk);
  - up to 3 decoration blocks above the ground (plants).

  A worker summarises these into clipmap words: level 0 cell for cell, coarser levels at their sample points. The real
  data **replaces the generated estimate** and is written back to the disk cache every few seconds.
- **So player builds and structures do appear, but only:**
  - as a **top-surface heightfield**: the top non-decoration block's height and colour. A house is a solid block of its
    roof colour, a bridge becomes a wall down to the ground, and an arch or overhang is filled.
  - for chunks this client has loaded at least once, kept in this client's cache.
  - in singleplayer, because the whole field depends on the integrated server.
- `realOcc` (opt-in) also keeps the solid-run depth under the top (≤127 blocks) and re-snapshots edited chunks after
  150 ms of quiet. This feeds the horizon occlusion cull only, not the drawn geometry.

### 2.4 Storage: the clipmap (`LodClip`, `LodTile`)
- Level L is an N×N toroidal window of cells 2^L blocks wide, filled with 64×64-cell tiles. Shared `MTLBuffer`s mean
  CPU writes are GPU reads with no upload. There are 5 levels for the 8192-block reach at N = 2048.
- Switch distance: `(N/2 − 64 − 4)·2^L`, which is 956, 1912, 3824, 7648 and 15296 blocks.
- **Words per cell:**
  - geometry: top y + 512 (12 bits), wet, valid, crown, crown thickness, fringe, plant height, clear, solid depth;
  - colour: top and side as RGB565;
  - crown word (level 0): the ground under the crown;
  - level-0 texture words (top, side, under-top palette ids) and two plant words.

  An 8×8 and a per-tile max mip let the walk skip whole blocks.
- **Memory at the defaults:**

  | Part | Formula | Size |
  |---|---|---|
  | geometry + colour | 8·N²·5 | 160 MB |
  | crowns | 8·N² | 32 MB |
  | textures and plants | 12·N² | 48 MB |
  | max mips | | < 1 MB |
  | **total** (`LodClip.bytes()`) | | **≈ 241 MB shared** |

  Add the walk's out buffer, W·H·8 = **47.5 MB at 3024×1964**, or the mesh arena: 64 MB at first, doubling up to 2 GB
  (`LodMesh.java:59,129`). With `lod.n=1024` the clipmap drops to about 60 MB.

### 2.5 Rendering
**(a) Column walk** (`render=walk`, the code default).
- `lod_columns` (compute, `columns.metal:876`) has one thread per screen "column", meaning a line through the vertical
  vanishing point.
- It walks the clipmap front to back, level by level, with a y-buffer, writing each pixel of the far band once (8 B per
  pixel).
- Variants: `lod_rows` and `lod_seg_*`.
- `lod_comp_vanilla` and `lod_comp_gbuffer` (fragment, `:1318`/`:1353`) then composite inside the level pass with
  early-Z at the nearest possible far depth.
- **Cost scales with columns × cells crossed**, not triangles.

**(b) Mesh** (`render=mesh`, used by the recommended profile).
- `lodmesh.c` meshes each resident tile on CPU workers into greedy-merged quads per 16×16-cell block, in 16 groups: tops,
  undersides, inner walls, border walls toward the neighbour, skirts for level and real-terrain borders, plants, and
  block info. The meshes live in one arena buffer.
- Each frame `lod_mesh_cull` (compute in the pre command buffer, `mclod.m:395`) does the following:
  1. picks per block the finest resident level whose ring holds it;
  2. drops masked blocks (real terrain draws them), back-facing groups and frustum-culled blocks;
  3. applies the **horizon occlusion cull**: 4096 azimuth bins × 128 log distance bands, built from the terrain itself,
     optionally raised by real chunks with `realOcc`;
  4. appends 64-quad instances to two **indirect draws**, opaque and plants (`mclod.m:457-505`).
- The vertex stage pulls quads. The fragment stage intersects the pixel ray with the face plane and shades through the
  same `compShade` as the walk, so **it is GPU-driven, with no CPU draw calls per tile**.
- `LodPk` (opt-in) caches the cull's view-independent decisions in 128 sectors, which helps a still or rotating camera
  ("spin +27% on the mini").
- `LodPublish` (`publish=gpu`) copies staged tile words on the GPU in the frame their mesh is installed, so frames in
  flight never mix old meshes with new words.

**Shading** (`compShade`, `columns.metal:1194-1300`)
- Level 0: atlas textures at the distance's mip, tinted to the cell's colour.
- Vanilla-style smooth-lighting AO from neighbour columns on tops and walls, the grass-side fringe, the block under the
  top on deeper walls, and crown and under-crown colours.
- Plants unshaded.
- Coarser levels: flat cell colours.

**Lighting** (vanilla path)
- `albedo × faceShade × AO × crownShade × skyLight`, then `compFog`.
- `faceShade` is the constant (1.0, 0.5, 0.8, 0.6) (`Lod.java:799-802`).
- `skyLight` is the lightmap texel for sky 15 / block 0, recomputed on the CPU from `LightmapRenderState`
  (`Lod.java:1316`).
- **No block light, no sun direction, no shadows.**

**Water**
- An **opaque** top whose colour mixes the floor and the biome water colour by depth (`LodChunks.summarize`; the same
  happens for generated water). It is flagged `wet`.
- No translucency, reflection or waves.

**Fog**
- The game's linear fog (`compFog`): max of the cylindrical render-distance fade and the spherical environmental fade,
  both moved out to the reach. There is no aerial perspective.

### 2.6 Hand-off with real chunks (the seam)
- A chunk mask (≤128×128 chunks) says where real terrain draws. The far terrain skips masked cells and draws skirts at
  the borders.
- `handoff=drawn`: a chunk is handed off only after Sodium's render lists contain its surface section for 2 walks and
  every section from the surface up is built (`builtColumn`). An unload takes it back the same frame.
- `handoffPush 0.9995` pushes far depth back slightly so real terrain wins coplanar tops.
- `fade=instant` stops Sodium's fade-in from drawing fog-coloured patches in front of far terrain.
- `chunkHold` and `handoffUnseen` are defaults on the small tier.
- `LodSeam` is a measurement probe only: flashes, pops and holes logged to CSV, with crops.

### 2.7 TAA (`LodTaa`, `LodTaaTile`)
- These are far-terrain-only temporal filters against shimmer of sub-pixel features. Both are opt-in.
- `LodTaa` (`-Dmcopt.lod.taa=ALPHA`) blits the previous far band into a history texture at the top of the level and
  blends in the mesh's fragment stage with a reprojected, neighbourhood-clamped history.
- `LodTaaTile` (`-Dmcopt.lod.taaTile=ALPHA`) does the same as a **tile function inside the open level encoder**
  (`lodseam.m:213-256`, `seam.metal:365`), with distance coded in alpha. This is the template the critique reuses for
  tile-deferred lighting.
- Both are redundant once our TAAU runs over the whole frame, and they would double-filter.

### 2.8 G-buffer variants and the `ShadeFar` hook (prepared but unused)
- `lod_comp_gbuffer` (`columns.metal:1353`), `lod_mesh_gbuffer` (`:3024`) and `lod_mesh_plant_gbuffer` (`:3208`) write:

  | Attachment | Contents |
  |---|---|
  | att0 | lit colour, left untouched (write mask off) |
  | att1 | albedo (`half4`) |
  | att2 | octahedral normal.xy + AO + material/255 (`MAT_WATER = 5`, `MAT_GENERIC = 0`) |
  | att3 | light (x block = 0, y sky = 1 or 13/15 under crowns) |
  | att4 | depth (`R32Float`) |

  The comment refers to "mcopt/shade/common.metal", which is not in the release.
- `colPipeline` (`mclod.m:175-220`) builds PSOs per attachment layout with `gbuffer` as a key, so G-buffer PSOs exist
  in code. But `Lod.draw` hard-codes `boolean gbuffer = false;` (`Lod.java:455,495`).
- `Lod.shadeFar()` (`Lod.java:277-296`) reflectively looks up `mcopt.metal.shade.ShadeFar.setReach(float)` and calls it
  each frame with the active reach, or 0. This is where a shading pipeline learns the far-view distance for its fog and
  far plane.
- **Meaning:** the author planned a native deferred shader that lights far terrain in the same lighting pass. We can
  enable it by detecting our G-buffer pass and implementing `ShadeFar`.

### 2.9 Current limitations (summary)
1. **Singleplayer only.** It needs the integrated server's `ServerLevel` and `RandomState`. Nothing at all appears on a
   server.
2. **Representation is 2.5D.** One surface per column plus one floating crown (with tiers). There are no overhangs,
   interiors, arches, bridges or roofs over air, and real chunks are flattened to their top surface.
3. **No structures or non-tree features** in generated terrain. Builds show only for loaded and cached chunks.
4. **Lighting is minimal.** There is no block light, sun direction or shadow, and water is opaque. Critique §7.8 says
   this produces an unshaded seam against any shader.
5. **Modded trees** are missed outside the name list. The Nether is unsupported.
6. **Cost.** "On Macs with fewer than 10 GPU cores, it costs most of the fps" (README). The user's M4 has exactly 10.
   N = 2048 was tuned for 5K (cells 2.5–5 px). At 3024×1964 the same N gives about 1.5–3 px cells, which is more
   geometry than needed. `lod.n=1024` is the natural setting for this panel.
7. **CPU contention** with the integrated server's chunk generation (the reason `LodYield` exists). With the
   defaults, 6 workers share the 4P+6E cores.
8. **Experimental and author-centric.** There are about 80 flags, many A/B instruments, and no settings UI.
9. **Licence:** Apache-2.0 with the NOTICE. No `lod` file is listed among the Sodium-derived ones, so we can modify it
   freely.

---

## 3. Distant Horizons 3.3.4 (26.3) in brief, from source and official pages

- **Release.** Modrinth `distanthorizons`: `3.3.4-26.3` (Fabric + NeoForge, 2026-09-29), licence **LGPL-3.0-only**
  (Modrinth API). The repo has `LICENSE.txt` (GPL-3), `LICENSE.LESSER.txt` (LGPL-3) and a separate `LICENSE-LOGOS.txt`.
  Source: https://gitlab.com/distant-horizons-team/distant-horizons.
- **Data model** (`DH/coreSubProjects/core/.../util/FullDataPointUtil.java`):
  - Each column is a **list of vertical data points**: block-state id (32 bits), min Y (12), height (12), block light
    (4) and sky light (4).
  - This represents overhangs, caves near the surface, interiors, bridges and floating islands, and it carries **block
    light** (lit villages and bases at night) and sky light.
  - Detail levels form a quadtree, stored in SQLite per world and dimension.
- **Generation.**
  - Singleplayer: real chunk generation on the internal server. `distantGeneratorMode` ranges from PRE_EXISTING_ONLY to
    FEATURES and INTERNAL_SERVER; since 3.1.0 DH fully generates chunks on the internal server. This gives structures,
    features and modded worldgen at full fidelity, but it is **CPU-heavy and slow**.
  - Multiplayer: LODs are built from chunks the client receives, and, when the server runs DH, synced from the server
    (the `[server]` config: `synchronizeOnLoad`, `realTimeUpdateDistanceRadiusInChunks`, `maxGenerationRequestDistance`).
  - API: `IDhApiWorldGenerator` (`DH/coreSubProjects/api/.../override/worldGenerator/IDhApiWorldGenerator.java`) lets
    another mod supply chunks or **full data sources** (`API_DATA_SOURCES`).
- **Rendering on 26.x.**
  - `BlazeDhRenderApiDefinition` targets reverse-Z with a 0..1 depth range on 26.3.
  - Pipelines are created through `RenderPipeline.builder()` with location `distanthorizons:<name>`. Terrain uses
    `opaque_terrain` and `transparent_terrain`, with GLSL in `assets/distanthorizons/shaders/terrain/blaze/*.vsh|fsh`.
  - Vertex attributes: `vPosition` (ushort3), `meta` (sky and block light nibbles plus micro-offset), `vColor`,
    `irisMaterial`, `irisNormal`, `textureTile`.
  - DH renders into **its own RGBA8 colour and DEPTH32 textures** (`BlazeTextureWrapper`, `BlazeDhMetaRenderer`). It
    then runs SSAO, fog, far-fade, vanilla-fade, an optional TAA (8-sample projection jitter `uFrameMod8`), sharpen,
    and copies or applies the result into MC's target.
  - API events include `DhApiAfterColorDepthTextureCreatedEvent`, `DhApiBeforeRenderPassEvent`,
    `DhApiBeforeApplyShaderRenderEvent`, `DhApiBeforeFogRenderEvent` and `DhApiAfterRenderEvent` (which is how Iris
    integrates).
- **Mac history** (Modrinth changelogs):
  - 3.0.0: instanced rendering is always off on Mac under GL, because of native crashes; GL+Sodium crash fixed; clouds
    and beacons removed on Mac GL; "Blaze3D also fixes generic object rendering on Mac".
  - 3.0.3: a Mac crash on VBO uploads larger than 256 KiB fixed.
  - 3.1.0: "Add Vulkan support via Blaze3D rendering API".
- **On mcopt Metal.**
  - Today mcopt forces OpenGL when DH is loaded (`Profile.distantHorizons()`), because DH casts the lightmap to
    `GlTexture`.
  - **PR #4** (CazzaCoder, 2026-10-04, **open, not merged**) changes this:
    1. It clamps the fog span in `sodium_terrain.vs.metal`. DH's default sets the fog start to about 4.2e14 while the
       end stays near the render distance, and without the clamp every chunk drew in the fog colour.
    2. A compat mixin answers `MinecraftRenderWrapper.getMcRenderingApi()` with `VULKAN` while Metal is active, so DH
       takes its Blaze3D, non-GL path.
  - Reported result: M4 Pro, 26.3, Sodium 0.9.3-a1, DH 3.3.4, **160–300 fps vs 60–70 fps** for DH on GL. The PR text
    does not give the resolution.

---

## 4. Comparison

| | `mcopt.lod` (recommended profile) | DH 3.3.4 on Metal (PR #4) |
|---|---|---|
| Works in multiplayer | **No** (needs integrated server and seed) | **Yes** (received chunks, plus server sync if the server runs DH) |
| Player builds and structures | Top surface of loaded chunks only; generated terrain has no structures | Full vertical data from real chunks; generation includes structures and features |
| Vertical fidelity | 2.5D heightfield + 1 floating crown | Multiple data points per column (overhangs, interiors, bridges, caves near the surface) |
| Block light at night | No | Yes (per data point) |
| Water | Opaque, colour mixed by depth | Translucent (`transparency = COMPLETE`); Photon reflects and refracts it |
| Trees | Exact vanilla trees on level 0 (≤ about 956 blocks), impostors beyond; modded trees often missed | Whatever the real or generated chunks contain |
| Modded worldgen | Noise router and surface rules yes; structures and modded features no; no Nether | Everything worldgen produces (slower) |
| Max distance | 8192 blocks by default (`lod.radius`), coarse levels 8–16-block cells | Configurable; user runs 40 chunks (640 blocks); thousands of blocks are practical |
| Generation speed | Very fast: noise only, bit-exact native NEON kernels, no chunk pipeline; tiles cached | Slow in singleplayer (full chunk generation on the internal server); fast for existing chunks and server-synced data |
| CPU cost | 6 workers by default, throttled by `yield`; small render-thread mask cost (0.2–0.9 ms spikes noted) | Heavy Java data pipeline and GC (DH warns about G1); 5 threads in the user's setup |
| GPU cost | GPU-driven cull + indirect draws in the level pass; horizon occlusion; no extra full-screen passes. "Costs most fps" under 10 GPU cores | Vertex-heavy LOD buffers plus **several full-screen passes** (own colour/depth targets, SSAO, fog, fades, copy/apply): about 0.3–1.0 ms each at native Retina (`apple-gpu.md` §1.3) |
| Memory | ≈ 241 MB clipmap + ≥ 64 MB arena (N = 2048); ≈ 60 MB at N = 1024 | Large Java heap use plus GPU buffers that scale with distance and quality; SQLite on disk (the user's cache is 212 MB) |
| Depth and composition | Draws in the level pass into MC's depth (reversed-Z); one pass | Separate DH depth texture, composited afterwards; shaders must merge two depths (Photon's `combined_far`) |
| Shader integration hooks | Native: G-buffer PSO variants exist, `ShadeFar` hook, our own MSL | Pipeline-name MSL overrides (our fork) plus DH API events; vertex data already has normal, material and light |
| Correctness risks | Hand-off seam, generated vs real differences (§2.2), 2.5D artefacts | DH bugs on Blaze/Metal (new path, PR not merged); depth merge; double TAA jitter |
| Maturity | Experimental, about 80 flags, single author | Mature mod, active releases; Blaze3D path newer (since 3.0/3.1) |
| Licence | Apache-2.0 (mcopt) | LGPL-3.0-only (use unmodified; never copy code into our Apache files) |

**Verdict on "the DH look":** fidelity, multiplayer and block light decide it. Only DH delivers them. `mcopt.lod`
wins on generation speed and GPU efficiency, but the user does not need 8192 blocks: their DH is set to 640.

---

## 5. Recommendation: (a) DH on Metal, integrated with our shade pipeline; hybrid (c) optional and later

**Why not (b), improving `mcopt.lod`.** Closing its gaps means rebuilding much of DH, in particular:
- multiplayer data;
- multi-layer columns, which change the mesher, the cull and the walk;
- block light;
- translucent water;
- structure-aware generation.

That is weeks of work off the critical path (shaders are priority #1), and it would still lose on Pollones-style
servers.

**Why (a).**
- DH already produces the geometry the user likes, and it already draws through Blaze3D, the API mcopt implements on
  Metal.
- The integration seam is small:
  - one compat mixin (PR #4);
  - MSL overrides by pipeline name, which the fork already has (`MetalPipeline.compile`, `OVERRIDE_DIR`), turned into
    an in-jar registry as planned in critique M1 step 6;
  - DH API events for textures and passes.
- DH's vertex format already carries a normal index, material id and raw sky and block light, which is exactly what a
  lighting shader needs.

**What (c) means here.**
- Do **not** run two far-terrain renderers.
- Optionally implement `IDhApiWorldGenerator` with `API_DATA_SOURCES`, backed by mcopt's `LodNoise`, `LodForest` and
  native NEON noise, so DH fills **singleplayer** LODs much faster than full chunk generation.
- Columns would be built as DH data points: the top block run, the under-block run, stone to min Y, and crown leaves as
  separate points.
- DH then re-reads real chunks as they are generated, which corrects the estimate.
- This is useful only if DH's singleplayer generation feels slow. Gate it on a measurement.

### Integration requirements (lighting, fog and aerial perspective, shadows, water)

Match what Photon does to DH terrain:

1. **Lighting.**
   - Sun and moon N·L from `ShadeFrame` (normal from `irisNormal` index; 0..5 is down, up, N, S, W, E).
   - Sky ambient from the raw sky-light nibble (`meta & 0xF0`) rather than DH's lightmap-multiplied `vertexColor`.
   - Warm block light from the block nibble.
   - Albedo is `vColor` × texture tile, *without* DH's baked lightmap. In the override, replace the
     `texture(uLightMap, …)` multiply in the vertex stage.
   - Face shade off, as for near terrain (the `CardinalLighting.byFace` mixin). DH bakes its own `lodShading`: set
     `lodShading` to NONE, or divide it out.
2. **Fog and aerial perspective.**
   - Disable DH fog (`enableDhFog=false`, or cancel `DhApiBeforeFogRenderEvent`) and vanilla fog for DH.
   - Apply our analytic atmosphere: the same function as the M1 sky, as a function of view ray and distance. Do this in
     the overridden fragment (M1) or in our post pass over a **combined depth** (M2+), so near terrain, far terrain and
     sky use one fog model and no seam appears.
3. **Shadows.**
   - Photon casts none from DH (`dhShadow.enabled = false`), so parity needs none.
   - Inside our cascade range DH terrain is hidden behind real chunks anyway, except DH's overdraw band. Sample the far
   cascade there for continuity.
   - Optional later: half-resolution screen-space sun ray-march against the combined depth for long mountain shadows at
     sunset, or M4 RT shadows against near BLAS only.
4. **Water.** Override `transparent_terrain`:
   - Fresnel against our sky;
   - sun specular;
   - absorption by view distance (DH water has no floor depth in the fragment; approximate with the distance to DH's
     opaque depth when M2 copies it);
   - our water material id in the G-buffer at M3.
   - Disable DH's TAA and sharpen when our TAAU runs. DH jitters its own projection (`uFrameMod8`), and two jitters
     would ghost.
5. **AO.** Keep DH SSAO in M1, because the user liked it. From M2, replace it with our SSAO or GTAO over the combined
   depth, which removes one full-screen pass.
6. **HDR and targets.**
   - DH's offscreen colour is RGBA8 plus a copy, which costs bandwidth and clips HDR.
   - From M2, redirect DH's terrain pass to draw straight into our HDR target, keeping DH's depth (via
     `MetalHooks.setRedirector` keyed on DH's pass, or by overriding the copy/apply pipelines). That needs RGBA16F PSO
     variants for `distanthorizons:*`.
   - From M3, a G-buffer MRT variant of `opaque_terrain`, so tile-deferred lighting lights DH terrain with the same code
     as near terrain.

### Mac optimisation of DH (the user's "study and optimisation" ask)

Apply this to a **new** instance only. Never edit the user's instances.

- **Settings close to the user's:**
  - `lodChunkRenderDistanceRadius` 40 to start, then 64–96 once measured on Metal.
  - Horizontal and vertical quality MEDIUM, `transparency COMPLETE`, SSAO on (M1), noise texture on (or our
    equivalent), `lodBiomeBlending 3`, vanilla fade DOUBLE_PASS, dithered fade.
- **CPU:** 4 DH threads (4P+6E CPU, with the render and server threads to spare).
- **GC:** generational ZGC instead of G1, because DH warns about G1 stutter. Keep the heap at about 6 GB as in the mcopt
  instance.
- **Config reset:** DH 3.3.3 bumped the config version to 5, which **clears the config**, so re-apply after the first
  start.
- **Background:** pre-generate with Chunky in singleplayer worlds, since DH defers its own generation then.
- **Disable for parity work:** DH TAA and sharpen when our TAAU is on; DH clouds when ours exist (M3).

---

## 6. Implementation plan (follows critique §5 milestones; shaders stay first)

**D0, with M0 (about 0.5–1 agent-day).**
1. In the fork (`work/mcopt-shade`, never `work/wt/m1`), port PR #4:
   - the fog-span clamp in `sodium_terrain.vs.metal`;
   - `compat/DistantHorizons.java`, `DhRenderApiMixin`, `CompatMixinPlugin` and `mcopt-metal-compat.mixins.json`.
   - Credit CazzaCoder in NOTICE. The PR targets the Apache-2.0 repo; its licence terms are assumed, not verified.
   - Re-implement rather than copy if needed.
2. Change `Profile.distantHorizons()`: with DH loaded, **keep Metal** and set `mcopt.lod=false` unless explicitly set.
   - This prevents both far-terrain renderers.
   - It also prevents `LodCameraMixin` and `LodFogMixin` from fighting DH's fog hooks.
3. New Prism instance, "mcopt Shaders Metal + DH (D0)": DH `3.3.4-26.3` (unmodified jar from Modrinth), Sodium 0.9.3-a1,
   Fabric API, and the user's DH settings.
4. Exit: starts, no fog disc, LODs draw, no `ClassCastException`. Screenshots. Benchmark only when the machine is free
   and the user agrees.

**D1, with M1 (about 2–3 agent-days).**
1. In-jar MSL overrides for `distanthorizons:opaque_terrain` and `transparent_terrain` (vs and fs), with the attribute
   and uniform-block layout matched to DH's GLSL (see `DH/.../shaders/terrain/blaze/*`).
   - Read `ShadeFrame` at `buffer(16)` (the global binding hook from M1 step 2) for sun, sky colours and fog.
   - Forward lighting as in §5.1–2; DH's own fog off.
   - Write our own MSL. Do not translate DH's GLSL line by line: it is LGPL, so keep our file clean-room.
2. Bind `ShadeFrame` inside DH's passes. Either the M1 hook covers every render pass created through `createRenderPass`,
   which includes DH's, or a `DhApiBeforeRenderPassEvent` handler binds it.
3. Exit: no visible lighting or fog seam at the vanilla-to-DH transition at noon, sunset and night (the fixed bench
   route plus a view from a mountain).

**D2, with M2 (about 3–4 agent-days).**
1. Combined depth: after DH terrain, merge DH depth into our scene depth (reverse-Z: max), or give post passes both.
   Get the handles through `DhApiAfterColorDepthTextureCreatedEvent` and `MetalBridge.textureHandle`.
2. VL, our AO, TAAU and fog read the combined depth. Turn DH TAA, sharpen, SSAO and fog off when ours are on.
3. Water override gets sky reflection plus SSR on DH water pixels only (half resolution, budgeted), with absorption
   from the combined depth.
4. HDR: RGBA16F variants of DH pipelines. Redirect DH's opaque pass into our HDR target to drop its RGBA8 target and
   copy.

**D3, with M3.**
1. G-buffer MRT variant of `opaque_terrain`, matching our layout. Reuse `columns.metal:1338-1375` as the reference
   encoding (octahedral normal, AO, material).
2. DH terrain is then lit in the tile-deferred lighting draw with everything else. Optional far shadows (§5.3).

**D4 (optional, after measuring).**
1. Hybrid generator: `McoptDhGenerator implements IDhApiWorldGenerator` (`API_DATA_SOURCES`), using `LodNoise` and
   `LodForest` at DH detail levels, singleplayer only.
2. Gate it on a measurement: DH's own generation at settle-time vs `LodField` settle-time on the same seed.

**`mcopt.lod` path (if ever wanted, for example singleplayer without DH; about 2–3 days).**
1. Pass `gbuffer = (pass is our G-buffer pass)` at `Lod.java:455,495`.
2. Implement `mcopt.metal.shade.ShadeFar.setReach` to feed the far plane and fog.
3. Match the G-buffer layout, or adapt `meshGBuffer`.
4. In the vanilla path, replace the constant `faceShade` and `skyLight` with `ShadeFrame` N·L, sky ambient and our
   atmosphere.
5. Keep `lod.taa*` off under TAAU.
6. Default `lod.n=1024` at 3024×1964.

**Testing protocol** (as critique §6.4; benchmark only with the machine free and the user's OK)
- Measure the far-terrain cost with DH on and off: the A/B the user cares about.
- Check for seams at the vanilla-to-DH boundary in day, sunset and night.
- Track memory: RSS and Java heap after settling.
- Check for hitches when crossing 16-chunk boundaries.

---

## 7. Risks and open questions

1. **Version gap with the user's server.** The user's DH look is on 1.21.1 multiplayer. Our work runs only on 26.3.
   Expectations must be set.
2. **PR #4 is unmerged and new.** DH's Blaze path on Metal (via mcopt's GLSL→SPIR-V→MSL translation) may hit
   untranslated features in DH's other passes (SSAO, TAA, clouds). Mitigation: override or disable those passes.
3. **DH API and pipeline names can change between versions.** Pin DH 3.3.4-26.3 in the instance and make overrides
   fall back to DH's own shader when a name is missing.
4. **Licensing.**
   - DH is LGPL-3.0-only. Shipping the unmodified jar alongside, plus mixins and API calls from our Apache code, is
     fine.
   - Copying DH source or shader text into our files would make those files LGPL. Keep our MSL clean-room.
   - Photon is "All rights reserved": reference only, no code.
   - `mcopt.lod` is Apache-2.0.
5. **Cost at native Retina.** DH's own offscreen targets plus 4–6 full-screen passes could add about 2–5 ms at native
   (estimate from `apple-gpu.md` §1.3, not measured). The M2 redirect and pass removal recovers most of it.
6. **DH Java heap and GC on 16 GB**, alongside the GPU's unified memory: watch for swap. Use ZGC.
7. **`mcopt.lod` and DH together** would double-draw and fight over fog and the far plane until the D0 guard exists.

---

### Sources

**Local**
- `mcopt/metal/src/main/java/mcopt/metal/lod/*`
- `mcopt/metal/src/main/native/{mclod.m,lodmesh.c,lodmesh.h,lodseam.m,lodnoise.c}`
- `mcopt/metal/src/main/resources/mcopt/lod/{columns,seam}.metal`
- `mcopt/metal/src/main/java/mcopt/metal/mixin/lod/*`
- `mcopt/metal/src/main/java/mcopt/metal/{Profile,MetalPipeline}.java`
- `mcopt/metal/src/main/resources/mcopt/profiles/*`
- `mcopt/README.md`, `NOTICE`, `LICENSE`
- `work/src/distant-horizons` (tag 3.3.4)
- The Pollones instance (read-only)

**Official**
- Modrinth API: `api.modrinth.com/v2/project/distanthorizons` and `/version` (licence, 26.3 builds, changelogs 3.0.0,
  3.1.0, 3.3.3, 3.3.4)
- https://gitlab.com/distant-horizons-team/distant-horizons
- https://github.com/noahdunnagan/mcopt/pull/4 (via `gh pr view 4`)
