# Far-terrain decision and plan (report `far-terrain-plan`)

Inputs: `research/dh-internals.md` and `research/mcopt-lod.md` (read in full), `critique.md` §5/§7, `apple-gpu.md`,
`mc-renderer.md`, `mcopt-architecture.md`. Every disputed claim was re-checked in code: DH 3.3.4 at
`work/src/distant-horizons`, the fork at `work/mcopt-shade` (branch `shade`, still clean alpha.3), Iris at `work/src/iris`, the
26.3 decompile, and the M1 worktrees `work/wt/m1-shade` and `work/wt/m1-shadow`, which I **read only**. Pollones and the other
Prism instances were read only. Nothing was launched, built or benchmarked. Any number marked **(est.)** has not been measured.

Path aliases: `DH/` = `work/src/distant-horizons`, `CORE/` = `DH/coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core`,
`COMMON/` = `DH/common/src/main/java/com/seibel/distanthorizons/common`, `FORK/` = `work/mcopt-shade/metal/src/main`,
`M1/` = `work/wt/m1-shade/metal/src/main` (read only).

---

## 0. Resumen para el usuario (ES)

- **Decisión:** usar **Distant Horizons 3.3.4 en el backend Metal de mcopt, iluminado por nuestros shaders nativos**.
  - No hace falta "traducir" DH. En 26.3, DH ya dibuja con la API de render del juego (la que mcopt implementa en Metal), y
    mcopt ya convierte sus shaders a Metal solo.
  - Nosotros solo reemplazamos los **dos shaders de terreno** de DH por los nuestros: el mismo sol, cielo y niebla que el
    terreno cercano.
  - También apagamos los efectos propios de DH que nuestros shaders ya hacen: su AO, su niebla, su antialiasing y sus fundidos.
  - El terreno lejano propio de mcopt (`mcopt.lod`) no sirve para tu caso: solo funciona en un jugador, no tiene luz de
    antorchas y el agua es opaca. Se queda apagado.
- **Por qué se verá "como ya se ve":**
  - Tu look actual es DH + Photon en el modpack de Pollones (1.21.1).
  - En ese look, Photon ilumina el terreno de DH y apaga el AO, la niebla y los fundidos de DH. Además, el terreno de DH no
    proyecta sombras.
  - Es exactamente lo que vamos a replicar.
- **Por qué irá bien en el Mac:**
  - El PR #4 de mcopt midió 160–300 fps con DH en Metal frente a 60–70 en OpenGL, en un M4 Pro con ajustes que no publicó.
  - Además quitamos unas 14 pasadas a pantalla completa que DH hace a resolución Retina (≈3–6 ms por frame, estimado).
  - Y DH sigue la escala de render de nuestros shaders.
- **Cosa honesta:**
  - Todo esto es solo para 26.3.
  - **Pollones (1.21.1, multijugador) no gana nada** hasta que ese servidor pase a 26.3 con su modpack.
  - Para Pollones solo te puedo dar consejos de ajustes (§6.1), y los cambias tú.
- **Lo rápido (quick win):** una instancia nueva de 26.3 con DH en Metal, sin shaders todavía (§6.3), en ≈1 día de trabajo.
  - Se ve como DH "normal", pero con mucha más fluidez.
  - Cuando salga M1, esa misma instancia pasa a tener el terreno lejano iluminado por nuestros shaders.
- **Prioridad:** los shaders siguen primero. FT0 es pequeño y no toca los worktrees de M1. FT1–FT3 van **después** de M1 y M2.

---

## 1. Recommendation

**Far-terrain provider: Distant Horizons 3.3.4-26.3 (unmodified jar, LGPL-3.0-only), drawn through mcopt's Metal backend, lit
by our native shading.** `mcopt.lod` stays off in every shader instance. It remains a code reference, plus an optional
singleplayer generator back end later (FT4).

Why, in one table (details in `mcopt-lod.md` §4 and `dh-internals.md` §7):

| Requirement | DH 3.3.4 on Metal | `mcopt.lod` |
|---|---|---|
| Matches the loved look: DH geometry lit by a shader pack, no LOD shadows, lit water | Yes: same geometry, plus our MSL overrides of its 2 terrain pipelines | No: 2.5D heightfield, no block light, opaque water |
| Multiplayer, which is where the user plays | Yes | No: needs the integrated server (`Lod.java:338`) |
| Runs on Metal at Retina | Yes, with the PR #4 shim; its full-screen chain is removable (FT2) | Yes, natively |
| Integration effort with our shaders | Small: pipeline-name MSL overrides plus the DH config API | Medium: G-buffer path exists but is off; new lighting needed |
| Maturity | Mature mod. Its renderpearl path is new, and DH releases every 4–7 days | Experimental, ~80 flags |

**Mac performance strategy** (the "estudio y optimización"):
1. **Kill the OpenGL tax.** Run DH's renderpearl ("Blaze3D") renderer on Metal (FT0). Apple GL stops at 4.1, and DH's GL
   path uses pre-4.3 vertex attributes, which carry heavy driver overhead.
2. **Kill DH's full-screen chain at Retina.**
   - DH runs about 14 full-screen draws and keeps ~16 screen-sized targets, ≈380 MB at 3024×1964 (est.).
   - We remove SSAO, fog, TAA, sharpen, the vanilla fades and the far fade plus its copies, because our pipeline does
     each of those once for the whole frame.
   - What's left: DH depth clear + opaque terrain + transparent terrain + at most one composite (FT1/FT2).
3. **Ride our render scale.**
   - DH sizes its targets from `gameRenderer.mainRenderTarget()` (`COMMON/wrappers/minecraft/MinecraftRenderWrapper.java:524-530,
     704-713`). mcopt's render scale swaps exactly that field for the world part of the frame
     (`FORK/java/mcopt/metal/mixin/GameRendererFxMixin.java`, `beginWorld`).
   - So at 0.67× internal resolution DH's per-pixel work shrinks by about 2.2× with no DH changes. *(Verified in code.)*
4. **Keep DH's workers off the render core.**
   - The M4 has 4 performance cores and 6 efficiency cores.
   - Use 3–4 DH threads, run at utility QoS through mcopt's `Qos` helper, hooked at `DhThreadFactory.newThread`
     (`CORE/util/threading/DhThreadFactory.java`).
   - Use generational ZGC and a 6 GB heap.
5. **Spend the savings on distance, not on DH effects.**
   - Render sections grow only with log(distance) (`dh-internals` §2.2), so going from 40 to 64–96 chunks costs little GPU.
   - The real cost of more distance is generation CPU and disk. It does not touch per-frame cost.

---

## 2. Contradictions between the two reports, resolved in code

| # | Topic | `dh-internals` | `mcopt-lod` | Resolution (evidence) |
|---|---|---|---|---|
| R1 | DH SSAO | Off under our shaders | Keep in M1 "because the user liked it" | **Off.** The user never saw DH SSAO in the loved look: Iris turns it off while a pack overrides DH (`work/src/iris/.../compat/dh/LodRendererEvents.java:91-92, 273-274`, `ambientOcclusion().enabled().setValue(false)`, fog `FOG_DISABLED`). Photon's own AO is what they saw, and our M2 AO over merged depth replaces it. Keep it on only in the no-shader FT0 instance. |
| R2 | Vanilla fade | Off, or SINGLE_PASS | Keep DOUBLE_PASS | **Off (NONE) under our shaders.** With an Iris pack DH never fades (`CORE/api/internal/ClientApi.java:734-741`, `shouldRenderFade()` returns false), so the loved look has no DH fade. It also samples Minecraft's main colour and depth, which breaks once M2 redirects them. In FT0 (no shaders) use SINGLE_PASS: one pass plus copy instead of two. |
| R3 | Face shade on LODs | AUTO follows `CardinalLighting.byFace`, so M1's mixin covers it | Set `lodShading` to NONE or divide it out | **Both work.** `ClientLevelWrapper.getShade` AUTO calls `level.cardinalLighting().byFace(dir)` (`COMMON/wrappers/world/ClientLevelWrapper.java:763-799`). Use the **public API** `graphics().lodShading()` = `DISABLED` (`IDhApiGraphicsConfig.java:171`) while shading is on, then call `renderProxy.clearRenderDataCache()`. That way it does not depend on the mixin. |
| R4 | Removing far fade | "far fade off" as a config switch | (not covered) | **No config exists in 3.3.4.** Far fade runs whenever Iris shaders are not in use (`CORE/render/renderer/LodRenderer.java:279-287`), and `dhFadeFarClipPlane` is not read anywhere in 3.3.4 (grep). The copy into Minecraft's colour is unblended (`COMMON/render/blaze/apply/BlazeDhCopyRenderer.java:97`), because far fade is what puts the sky under LOD gaps. So removing it takes two things: a `require=0` mixin that cancels `BlazeDhFarFadeRenderer.render`, and cancelling DH's copy through the public `DhApiBeforeApplyShaderRenderEvent` (`LodRenderer.java:333-338`), replaced by our own alpha-blended composite (or by the FT2 redirect). |
| R5 | Fog off | DhApi `fog().drawMode` | `enableDhFog=false` or cancel `DhApiBeforeFogRenderEvent` | **Use the API value, as Iris does.** `LodRenderer.java:190-204` honours the API override and otherwise forces fog on under water, blindness and similar. With the API override set, **our** LOD shader must handle those fog types; `ShadeFrame.fogColor.a` and `flags.y` already carry them (`M1/resources/mcopt/shade/shade_common.metal`). |
| R6 | Jitter | DH's AA jitter stacks on MetalFX jitter | Turn DH TAA off under TAAU | **Both, made precise.** DH builds its matrix from Minecraft's final projection (captured in `MixinProjectionMatrixBuffer`), so **LODs inherit our TAAU jitter for free** as long as the jitter stays inside the projection matrix. DH's own jitter (`uFrameMod8`, `terrain/blaze/vert.vsh:115-118`) must be off: `enableAntiAliasing` = false, internal `Config...setApiValue`, version-gated. |
| R7 | D0/(a) effort | 1.5–3 agent-days | 0.5–1 | **Split.** FT0, the minimum to run (port the PR, change the Profile rule, exclude `mcopt.lod`), is about 1 day. FT0.5 hardening (QoS, Iris guard, DH version gate, upstream merge request) adds 1–1.5 days and can trail. |
| R8 | Do we need mcopt.lod/DH exclusion today? | Yes | Yes | **Not today, required from FT0.** Today `Profile.distantHorizons()` (`FORK/java/mcopt/metal/Profile.java:44-60`) forces GL with DH loaded, and `mcopt.lod` turns itself off on GL. Once FT0 keeps Metal with DH, both would draw and both would hook fog, so FT0 must force `mcopt.lod=false`. |
| R9 | LOD water reflections | No SSR at first | SSR on DH water in D2 | **Sky reflection first, SSR optional.** At LOD distances most reflected rays hit sky. M1's sky-view LUT (`texture(17)`) plus Fresnel gives most of the Photon water look for almost no cost. Add SSR on DH water pixels only if FT2 is under budget. |
| R10 | Override mechanism | File overrides (`-Dmcopt.metal.overrideMsl`) | Same, "turned into a registry in M1" | **Use M1's in-jar registry.** `M1/java/mcopt/metal/MetalShaderRegistry.java` (uncommitted in the M1 worktree) is keyed by pipeline name and has a `required` uniform list: if a pipeline lacks one, the variant is skipped and the stock shader compiles. Register `distanthorizons:opaque_terrain` and `distanthorizons:transparent_terrain` with `required = [vertSharedUniformBlock, vertUniqueUniformBlock, fragUniformBlock, uLightMap, uBlockAtlas]` (names verified in `BlazeDhTerrainRenderer.java:124-132,149,156`). A DH update that renames anything then falls back safely. |
| R11 | Shadows on LODs | Skip in v1 | Skip, sample far cascade in overdraw band | **Skip entirely in the DH shader.** DH's near clip is `vanilla render distance × overdrawPrevention`, clamped to ≥ 1 block (`CORE/util/RenderUtil.java:130-220`). The user has `overdrawPrevention = 0.0`, so LODs are drawn from about 1 block out, but vanilla terrain overwrites them wherever chunks are loaded (DH draws first and is not in Minecraft's depth). LOD pixels inside the M1 shadow range (≤ 96–128 blocks) therefore show only in briefly unloaded chunks. Photon has `dhShadow.enabled=false` anyway. **Perf corollary:** at 0.0, every LOD fragment under vanilla terrain is shaded and then overwritten, which wastes fill at Retina. FT0 should A/B `overdrawPrevention` 0.5–0.7 (watch for holes while flying). |
| R12 | GL engine on Mac (instancing) | Instanced generic rendering crashes with Sodium | DH 3.0.0 forces instancing off on Mac GL | **Consistent.** The warning in the user's log is about GL. On the Metal/Blaze path instancing is untested; keep it in the FT0 test matrix, with `enableInstancedRendering=false` as the fallback. |

Also confirmed:
- The terrain render-pass label is `distantHorizons:TerrainRenderer`: one pass for opaque, one for transparent
  (`BlazeDhTerrainRenderer.java:282-288, 367`). The copy pass is `distantHorizons:CopyRenderer`. These are the keys for FT2's
  redirect.
- DH draws inside `LevelRenderer.prepareTranslucents()` (`DECOMP/.../LevelRenderer.java:437`). That is after the Sky pass and
  **before** the "Solid" pass opens (:444-451).
- DH's lightmap is still bound (`uLightMap`). Our override ignores it and lights from the raw `meta` nibbles
  (`vert.vsh:102-105`).
- The shipped fork still has the unguarded fog math `(d − start)/(end − start)` at `FORK/resources/mcopt/metal/msl/sodium_terrain.vs.metal:55-56`.
  That is the bug PR #4 fixes.
- DH needs **Fabric API** (`DH/fabric/src/main/resources/fabric.mod.json` depends). The user's "mcopt Metal 26.3" instance has
  none (mods: mcopt + Sodium only), so a DH instance must add it.

---

## 3. What M1 and M2 must leave room for

These are requests to the shader workstream. None of them costs anything when DH is absent. I did not touch `work/wt/m1*`.

### 3.1 M1 (ShadeFrame, lit terrain, sky, shadows)
1. **A far-terrain slot in `ShadeFrame` without changing its size.** The M1 layout (1008 B) has `reserved0` (offset 928),
   `reserved1` (944) and `flags3` (992). Claim them now:
   - `reserved0` becomes `farTerrain`:
     - x: far reach in blocks (0 = none);
     - y: DH near plane;
     - z: DH far plane;
     - w: distance where the atmosphere must reach full sky at the far edge (≈0.95·reach).
   - `flags3.x` = far provider (0 none, 1 DH, 2 mcopt.lod).
   - Fill them from a `ShadeFar.setReach(float)` class in `mcopt.metal.shade`. `Lod.shadeFar()` already looks that class up
     by reflection (`FORK/java/mcopt/metal/lod/Lod.java:277-296`), and the DH compat calls it from `DhApiBeforeRenderEvent`
     with the DH render distance.
   - DH matrices do **not** go in ShadeFrame. They go in a small second buffer in FT2 (slot 18 or 19 of the reserved 16-19).
2. **Fog that does not assume "render distance = end of the world".**
   - The aerial-perspective and haze term must be a pure function of distance and view ray, valid out to 6000+ blocks.
   - The vanilla render-distance fade must turn off cleanly when `fogRange.z ≥ fogRange.w`. DH sets the start to 4.2e14 with
     the end at render distance (`MixinFogRenderer`), so M1's terrain shader must carry PR #4's clamp
     (`span = max(end − start, 1e-4)`, clamp before narrowing to `half`). Otherwise all near terrain draws in fog colour as
     soon as DH loads.
   - When `farTerrain.x > 0`, the far fog target is `farTerrain.w`, not the vanilla render distance.
3. **Lighting and atmosphere as reusable functions in `shade_common.metal`**, not inlined in `sodium_terrain.fs.metal`:
   - for example `shadeLightSurface(albedo, normal, sky01, block01, ao, worldPos, f)` and `shadeAerial(color, worldPos, dist, f)`;
   - the DH overrides `#include` them, so near and far terrain cannot drift apart. That is the "no seam" exit criterion.
4. **Global bindings on every render pass, not only Sodium's.** Bind slots 16/17 and samplers 12/13 after
   `Native.renderBegin` in `MetalEncoder.createRenderPass`, as planned, so DH's two terrain passes see `ShadeFrame` and the
   sky LUT.
5. **Keep the registry's `required` fallback and per-entry names**, and let a compat class register variants at mixin-plugin
   time only when `distanthorizons` is loaded.
6. **Don't assume only Minecraft writes main colour before "Solid".** DH copies into it inside `prepareTranslucents`.
7. LDR output via `shadeOutput()` stays the single exit for M1. The DH override uses the same call, so tone and exposure match.

### 3.2 M2 (HDR, water, post, TAAU, dynamic resolution)
1. **The HDR redirect must not lose DH.** If M2 redirects only the "Sky"/"Main"/"Solid" passes by label, DH's terrain lands
   in the RGBA8 main texture, which the frame no longer shows. Pick one:
   - (a) redirect by **target texture identity** (anything that writes main colour) and build RGBA16F variants for
     `distanthorizons:*` pipelines too; or
   - (b) preferred: let FT2 redirect `distantHorizons:TerrainRenderer` straight into our HDR target and cancel DH's copy.

   Either way, `MetalHooks.redirector` is a **single global** today (`FORK/java/mcopt/metal/MetalEncoder.java:236-243`). M2
   should make it a small chain, or have the shade redirector delegate DH's passes to a far-terrain sub-redirector.
2. **Depth consumers must take a "scene distance" input, not raw Minecraft depth.** DH depth uses a different projection
   (near ≤ 7.5 blocks, its own far). TAAU reprojection, VL, AO, SSR and fog should read a linear view-distance image that
   FT2 fills as min(MC distance, DH distance). Leave a hook point after DH terrain and before post.
3. **Jitter stays in the projection matrix.** It must not be a post-projection offset that only our shaders apply.
   Otherwise DH LODs will not jitter with the rest of the frame, and TAAU will smear them.
4. **Dynamic resolution must not resize targets every few frames.** DH recreates its screen textures whenever the main
   target's size changes (`BlazeTextureWrapper.tryCreateOrResizeToScreenSize`, `:225-260`). Use a fixed allocation with a
   viewport, or quantized steps with hysteresis. After FT2 only DH depth remains, but it would still be reallocated.
5. **Water shading as a shared include** that the DH transparent override can call. DH marks water as material 12
   (`EDhApiBlockMaterial.WATER(12)`, `irisMaterial`).
6. **The settings UI needs room for a "Far terrain (DH)" group:** distance, quality preset, on/off.

---

## 4. Staged implementation plan

Work happens on `work/mcopt-shade`, in branches off `shade`. Never in `work/wt/m1*`, never in user instances. Every
milestone ships as a **new** Prism instance. Launching or benchmarking needs the user's OK and a free machine.

Common test setup ("DH bench"):
- MC 26.3, Fabric Loader 0.19.5, Fabric API ≥ 0.160.5+26.3, Sodium 0.9.3-alpha.1, DH 3.3.4-26.3 (pinned), the fork jar.
- A fixed world seed, pre-generated to 64 chunks.
- The M0 bench route plus a mountain-top 360° pan and a 60 b/s elytra line (generation stress).
- DH: radius 40, MEDIUM/MEDIUM, transparency COMPLETE, 4 threads, the user's other settings.
- Metrics: fps avg and 1% low, mcopt.metal.trace per-pass GPU µs (DH passes summed), render-thread ms, RSS, Java heap, and
  hitches > 50 ms.

### FT0 — DH runs on Metal (quick win, ≈1 agent-day; parallel-safe with M1)
Branch `ft0-dh`, from `shade`:
1. Port PR #4 (CazzaCoder; credit in NOTICE):
   - the compat mixin plugin and `DhRenderApiMixin` (`getMcRenderingApi` → `VULKAN` while the backend is "Metal",
     `require=0`, applied only if `distanthorizons` is loaded);
   - the 2-line fog-span clamp in `sodium_terrain.vs.metal`.
   - Re-implement the code rather than cherry-picking, since the PR conflicts with main. When merging after M1, the M1 terrain
     shader already carries the clamp (§3.1-2), so the fog hunk disappears.
2. Replace `Profile.distantHorizons()`:
   - with DH loaded, **stay on Metal** unless Iris is loaded or DH's `renderingEngine` is explicitly OPEN_GL (read
     `config/DistantHorizons.toml`);
   - force `mcopt.lod=false` unless set explicitly;
   - log the decision.
3. Version gate: enable the mixin only for DH 3.3.3–3.3.4 (read the `distanthorizons` mod version). Outside that range, log
   it and fall back to today's GL rule.
4. Build the jar only; do not launch. Create the instance "mcopt Metal 26.3 + DH (FT0)":
   - mods and loader as in the test setup;
   - DH config set to the user's values, minus the Mac fixes in §5;
   - JVM `-XX:+UseZGC -Xmx6G`. On Java 25 ZGC is generational by default; `-XX:+ZGenerational` is only for Java 21.

**Exit criteria** (when a launch is allowed):
- Starts with no `ClassCastException`, no fog-coloured disc, and LODs visible.
- Survives F3+T, a window resize, a dimension change and a world rejoin.
- Clouds and beacons: either work, or `enableInstancedRendering=false` documented.
- Screenshots: noon, sunset, night, water.

**FPS budget:** DH radius 40 at native 3024×1964 with DH's full default chain:
- ≥ 100 fps average, 1% low ≥ 60;
- DH GPU ≤ 6 ms in the trace;
- ≥ 1.5× the same instance with `-Dmcopt.metal=true` removed (DH on GL);
- no hitch > 50 ms during the elytra line.

If the GPU budget fails, apply the config-only cuts from FT1 step 3 now: SSAO off, AA off, fade NONE.

### FT0.5 — Hardening (≈1–1.5 agent-days; any time after FT0)
1. **Iris guard.** If Iris + mcopt Metal + DH are all present, show a clear message and fall back to GL. DH forces its
   raw-GL engine with Iris, which cannot run on Metal.
2. **QoS.** Add a `dh` thread role at utility, applied in a mixin on `DhThreadFactory.newThread`. Measure 1% lows while
   generating, with and without it.
3. **Limit the API spoof** to `MinecraftRenderWrapper`, if the version allows. File the upstream DH merge request: one line in
   `getMcRenderingApi`, so that any backend other than OpenGL counts as not OpenGL (or add a `METAL` value). Once it lands,
   delete our spoof.
4. **MetalFX on:** check that DH textures follow the render scale (expected from the code, §1.3), and turn DH TAA off when
   mcopt jitters the projection.

### FT1 — Lit LODs with our shading (after M1; ≈2–4 agent-days)
1. Dump DH's translated MSL (`-Dmcopt.metal.dumpMsl`) to fix attribute and buffer indices and uniform-block layouts.
   Write **clean-room** MSL for both pipelines. Do not translate DH's LGPL GLSL line by line, and copy nothing from Photon,
   which is all-rights-reserved.
   - **Vertex:** decode position and micro offset; normal from `irisNormal` (0–5); sky and block light from `meta & 0xFF`
     (drop the lightmap sample); albedo from `vColor`; pass `irisMaterial`.
     - DH's jitter only when `uFrameMod8 > 0`; it stays off.
     - Earth curvature only if DH's setting is on.
   - **Fragment:**
     - keep DH's dithered near-clip discard (`uClipDistance`, `uDitherDhRendering`) and the texture-tile sampling from
       `uBlockAtlas`;
     - light with `shadeLightSurface(...)`: sun or moon N·L × `sunColor`, sky ambient by sky light, warm block light by
       block light, no shadow lookup (R11);
     - then `shadeAerial(...)` out to `farTerrain.w`, then `shadeOutput()`.
     - DH's noise texture becomes optional (cheap ALU; Photon has an equivalent).
2. Register both variants in `MetalShaderRegistry` only when DH is loaded and `mcopt.shade` is on (the `required` list from R10).
3. Turn DH's own effects off through its config while shading is on, and restore them when it is off:
   - SSAO off, fog `FOG_DISABLED` (both public API, as Iris does);
   - `lodShading` = DISABLED (public API) plus `clearRenderDataCache()`;
   - vanilla fade NONE and AA off (internal `setApiValue`, version-gated);
   - far fade cancelled by mixin, with the copy replaced by one alpha-blended composite into main colour (R4).
4. Fill `ShadeFrame.farTerrain` / `flags3.x` from `DhApiBeforeRenderEvent` (§3.1-1).

**Exit criteria:**
- No lighting, colour or fog seam at the vanilla↔DH boundary at noon, sunset, night (torch-lit village at 600 blocks visibly
  lit) and in rain.
- Underwater and lava fog applied to LODs.
- The user signs off on a side-by-side against their Pollones/Photon screenshots.
- Toggling shading restores DH's defaults.

**FPS budget:** DH radius 40, native Retina, M1 shading on:
- all DH GPU work ≤ 2.0 ms (terrain ≤ 1.2 ms, composite and clear ≤ 0.4 ms) (est. target);
- DH render-thread CPU ≤ 1.0 ms;
- whole frame ≥ 110 fps average (M1's own exit is ≥ 120 without DH).
- If terrain alone is > 1.5 ms, drop to `horizontalQuality` LOW beyond 1500 blocks, or enable cave culling (on already).

### FT2 — DH inside the HDR / TAAU frame (after M2; ≈3–5 agent-days)
1. **Redirect** `distantHorizons:TerrainRenderer` into our HDR world target:
   - load action instead of DH's sky clear, keep DH's depth;
   - RGBA16F variants of the two overrides;
   - cancel DH's copy (`DhApiBeforeApplyShaderRenderEvent`).

   DH then costs a depth clear plus 2 terrain passes, with zero full-screen passes. DH's RGBA8 colour and the post targets
   are never touched, so they stay at 1×1 or idle (verify).
2. **Scene-distance merge:**
   - one fragment pass after DH terrain and before post, writing linear distance = min(MC, DH);
   - each side is linearized with its own inverse projection, from `DhApiRenderParam`, fed through the far-terrain buffer
     (slot 18/19);
   - TAAU, VL, AO, SSR and fog read it (§3.2-2).
3. **LOD water:**
   - the transparent override calls M2's water include: Fresnel, sky LUT reflection, sun specular;
   - absorption from (DH opaque depth − water depth), using `setDeferTransparentRendering(true)` so opaque DH depth is
     complete first;
   - SSR on DH water pixels only if the budget allows (R9).
4. **AO on LODs** comes from M2's AO over the merged distance. This is the look Photon's AO gave.

**Exit criteria:**
- No LOD ghosting or smearing under TAAU while strafing (LODs inherit the jitter, §3.2-3).
- VL and fog continuous across the boundary.
- LOD water reflects the sky.
- Dynamic resolution causes no DH reallocation hitches.

**FPS budget:** DH radius 40:
- DH ≤ 1.2 ms GPU at 0.67× internal;
- M2 Performance preset still ≥ 120 fps sustained;
- Quality preset (native) ≥ 75 fps with DH;
- at radius 96, DH ≤ 2.0 ms at 0.67×.

### FT3 — Deferred LODs (after M3; ≈1–2 agent-days)
1. A G-buffer MRT variant of `opaque_terrain` in M3's layout. Use `columns.metal:1336-1375` as the encoding reference:
   octahedral normal, AO, material, light.
2. LODs are then lit in the same tile-deferred lighting draw as everything else. Clouds and VL see them via the merged
   distance.
3. Optional extras, each gated on budget:
   - half-resolution screen-space sun shadows on the merged distance (long mountain shadows at sunset), or LOD buffers in
     the far cascade;
   - raise the default radius to 64–96.

**Exit:** pixel-identical lighting between FT2 forward LODs and FT3 deferred LODs (tolerance ±2/255). **Budget:** ≤ +0.2 ms
over FT2.

### FT4 — Optional: mcopt's noise generator for DH (singleplayer; ≈3–5 agent-days; only if measured slow)
- Implement `IDhApiWorldGenerator` (`API_DATA_SOURCES`) on top of `LodNoise`, `LodForest` and NEON noise. DH replaces the
  estimate with real chunks once they exist.
- **Gate:** DH's own time to settle a 64-chunk radius on the bench seed > 2× `LodField`'s time. Multiplayer is unaffected.

**Total to Photon-level parity (FT0+FT1+FT2):** ≈ 6–10.5 agent-days. FT0.5 is +1–1.5 and FT3 is +1–2.

---

## 5. DH settings for the Mac (new 26.3 instance; also the advice basis for Pollones)

| Setting | User today | FT0 (no shaders) | FT1+ (our shaders) | Why |
|---|---|---|---|---|
| `lodChunkRenderDistanceRadius` | 40 | 40 | 40 → 64–96 after FT2 measures | GPU cost grows with log(distance); generation grows with distance² |
| horizontal / vertical quality | MEDIUM / MEDIUM | same | same; HIGH vertical only for mountainous packs | Look parity |
| `transparency` | COMPLETE | COMPLETE | COMPLETE | Water is where the look shines |
| SSAO | on | on (cheap at 40 chunks? measure) | **off** (R1) | Our AO in M2 |
| DH fog / vanilla fog | on / off | on / off | **off** via API / off | Our atmosphere |
| `vanillaFadeMode` | DOUBLE_PASS | **SINGLE_PASS** | **NONE** (R2) | 2 full-screen passes saved |
| anti-aliasing (TAA) | default on | on (off with MetalFX) | **off** (R6) | Our TAAU |
| `lodShading` | AUTO | AUTO | **DISABLED** (R3) | Face shade comes from our lighting |
| noise texture | on | on | optional | ALU only |
| cave culling | on | on | on | Fewer quads |
| `overdrawPrevention` | 0.0 (LODs from ~1 block) | A/B 0.0 vs 0.5–0.7 | best of the A/B | Avoids shading LODs hidden under vanilla terrain (R11) |
| instanced generic rendering | on | on → off if clouds or beacons misbehave | same | Mac reports (R12) |
| `numberOfThreads` / `threadRunTimeRatio` | 5 / 1.0 | **4 / 0.75** | 4 / 0.75, QoS utility | 4 performance + 6 efficiency cores; render thread first |
| `distantGeneratorMode` | FEATURES | FEATURES (singleplayer pre-generation with Chunky) | same | Look parity |
| auto-updater | on | **off** in the test instance | off | 3.3.3 resets config v4→v5; pin tested versions |
| JVM | Java 21, G1, 6 GB | Java 25, `-XX:+UseZGC`, `-Xmx6G` | same | DH warns about G1 stutter |

---

## 6. Quick-win options (honest)

### 6.1 Today, Pollones itself (1.21.1 + Iris + Photon + DH 3.2.0-b on OpenGL); advice only, the user applies it
- **Back up `config/DistantHorizons.toml` first.** The auto-updater is on, and 3.3.x wipes the config.
- If generation stutters: DH threads 5 → 3–4, `threadRunTimeRatio` 1.0 → 0.5–0.75.
- JVM (Java 21): `-XX:+UseZGC -XX:+ZGenerational`, heap 6–8 GB.
- If clouds or beacons crash, turn off instanced generic rendering.
- **Caveat:** these remove stutter, not the OpenGL ceiling. Photon at the window size plus Apple GL overhead still sets the
  fps. **None of the Metal work reaches Pollones** until that server (a ~95-mod Cobblemon pack) exists on 26.3.

### 6.2 Zero code, 26.3, closest to the look: Iris + Photon + DH on OpenGL
- A **copy** of "mcopt Shaders 26.3" (`-Dmcopt.metal=false`, Iris 1.11.7), plus Fabric API, DH 3.3.4-26.3 and the Photon pack
  (the user's own copy, for personal use).
- Optional: RenderScale with Iris scale 0.6–0.7 (critique §2.6).
- **Caveats:**
  - It is OpenGL, so expect Pollones-like fps or lower at Retina.
  - DH 3.3.4 was built against Iris 1.11.6, not 1.11.7.
  - Photon v1 on Iris 1.11.x / 26.3, and RenderScale + DH (which follows the swapped main target), are both **unverified**.
- Good for "the look on 26.3 now", not for speed.

### 6.3 Recommended quick win: FT0, DH on mcopt Metal via the PR #4 path (≈1 agent-day, plus one launch with the user's OK)
- DH's own look (its SSAO and fog, no shader lighting) at Metal speed.
- The PR reports 160–300 fps vs 60–70 on GL, on an M4 Pro with unstated resolution and settings.
- It is the first step of the real plan: the same instance becomes shader-lit at FT1.
- **Caveats:**
  - Built from an **unmerged** PR, re-implemented in our fork.
  - DH's renderpearl path on Metal is new: watch clouds, beacons, resize and F3+T.
  - Not compatible with Iris in the same instance.
  - No Photon lighting until FT1, which follows M1.
  - The fps claim is not ours and was not measured on a 10-core M4 at Retina.

### 6.4 `mcopt.lod` (not recommended for this user)
- Settings, if the user is curious: `profile=recommended` in `config/mcopt.properties` (sets `mcopt.lod=true`, `render=mesh`,
  `handoff=drawn`, …) plus `-Dmcopt.lod.n=1024` for 3024×1964 (≈60 MB clipmap instead of ≈241 MB).
- **Caveats:**
  - Singleplayer only (nothing on servers).
  - 2.5D: no overhangs or bridges.
  - No block light; opaque water; no structures in generated terrain.
  - Vanilla lighting with an unshaded seam against our shaders.
  - It "costs most of the fps" below 10 GPU cores, and the user has exactly 10.
  - Never together with DH.

---

## 7. Risks
1. **Version gap.** The loved look lives on 1.21.1 multiplayer, and our work is 26.3-only. Say so up front (§0).
2. **DH churn.**
   - Four 26.3 releases in 12 days. Internal mixins (render-API check, far fade, AA and fade config) can break.
   - Mitigations: pin DH, version-gate, `require=0`, and the registry's `required` fallback.
   - Upstream the backend check so the spoof can go.
3. **The spoof is global.** It also changes what `DhApi` reports to add-ons, and could route DH into a future Vulkan-only
   path. Remove it once upstream accepts "not OpenGL".
4. **DH's renderpearl path on Metal is young.** Expect edge cases: triangle fans, D32F sampling, texture-view wraps, instanced
   generic objects.
5. **Depth mismatch.** Any depth consumer that reads raw Minecraft depth treats LODs as sky until FT2's merged distance
   exists (§3.2-2).
6. **One redirector slot** (`MetalHooks.redirector`) shared by the shade pipeline and DH: M2 must chain it (§3.2-1).
7. **Memory on 16 GB unified.**
   - DH's screen targets: ≈380 MB at native in FT0 (est.), almost gone after FT2.
   - Plus DH VBOs, a 6 GB heap, and M4 RT acceleration structures (230–500 MB) later.
   - Watch for swap.
8. **Licensing.**
   - DH is LGPL-3.0-only: ship the unmodified jar; mixins and API calls from our Apache-2.0 code are fine; never copy DH
     Java or GLSL into our files.
   - Photon is all-rights-reserved: reference only.
   - The PR #4 fog hunk lives in `sodium_terrain.vs.metal` (PolyForm Shield, Sodium-derived).
   - Credit CazzaCoder; the PR's inbound licence is assumed to be the repo's own.
9. **Unmeasured numbers.** Every budget here is an estimate from code plus earlier M4 synthetic measurements. FT0 is the
   first real calibration, and it needs a launch the user approves.

## Sources
- Local:
  - DH 3.3.4 source: `LodRenderer.java:186-343`, `ClientApi.java:679-741`, `ClientLevelWrapper.java:763-799`,
    `MinecraftRenderWrapper.java:524-575,704-713`, `BlazeDhTerrainRenderer.java:112-160,260-330,366-367`,
    `BlazeDhCopyRenderer.java:97,147`, `BlazeTextureWrapper.java:90-260`, `Config.java:131,309-330,442-470`,
    `IDhApiGraphicsConfig.java:171`, `EDhApiBlockMaterial.java:43`, `terrain/blaze/vert.vsh`, `fabric.mod.json`.
  - Iris: `compat/dh/LodRendererEvents.java:80-100,265-280`.
  - Fork: `Profile.java:44-60`, `MetalPipeline.java:78-95,187-196`, `MetalEncoder.java:236-310`, `sodium_terrain.vs.metal:55-56`,
    `lod/Lod.java:277-296,455,495`, `mixin/GameRendererFxMixin.java`, `MetalFx.java:81-103`.
  - Read only: `M1/resources/mcopt/shade/shade_common.metal` (ShadeFrame layout), `M1/java/mcopt/metal/MetalShaderRegistry.java`.
  - Decompile: `LevelRenderer.java:209,390-461`, `GameRenderer.java:819-821`.
  - Read-only instances: Pollones `config/DistantHorizons.toml`, its mods list, and the other instances' `instance.cfg` and
    mods lists.
- Official (via the two input reports):
  - Modrinth `distanthorizons` (3.3.4-26.3, LGPL-3.0-only);
  - GitLab `distant-horizons-team/distant-horizons` tag 3.3.4;
  - GitHub `noahdunnagan/mcopt` PR #4.
