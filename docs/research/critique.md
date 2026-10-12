# Completeness critique and approach decision (report `critique`)

Scope: I read the five research reports in full (`mcopt-architecture.md`, `mc-renderer.md`, `iris-internals.md`,
`community.md`, `apple-gpu.md`), checked their disputed or decision-critical claims against the code, answered the open
questions I could answer offline, and compared approaches A–E. Nothing was launched, and no user instance was touched.

Path shorthands (all under `…/scratch-2026-10-06-836a24/`):

| Shorthand | Path |
|---|---|
| `MCOPT` | `mcopt/metal/src/main/java/mcopt/metal` (read-only clone) |
| `DECOMP` | `work/decomp/mc-26.3` (Vineflower output of the 26.3 client) |
| `IRIS` | `work/src/iris/common/src/main/java/net/irisshaders/iris` |
| `VITRAIL` | `work/community/vitrail` |
| `BENCH` | `work/bench-apple-gpu` |
| `RS` | `work/community/renderscale` (**new**, cloned by me: `github.com/Zolo101/RenderScale`, branch `stonecutter`, `aca1201`, 2026-09-20, MIT) |

---

## 0. Decision summary

1. **Build approach A, staged, as a fork of mcopt.** That means native, original MSL shaders in the Metal backend: sun
   shadows, lit terrain, sky, water, volumetrics, bloom, AgX, plus our own temporal upscaler with dynamic resolution.
   - It is the only approach that can reach about 120 fps at full Retina output with good-looking shaders on an M4 10-core.
   - Two independent sources agree:
     - the synthetic benchmark measured on this machine (`BENCH/results-frame.txt`);
     - Metallum-Sergey's real game, 86–105 fps at exactly 3024×1964 on an M1 Pro.
   - Both put **rich native shading at native resolution at about 80–100 fps**. 120 fps needs either a 0.67–0.75×
     internal resolution with a cheap custom TAAU, or a leaner feature set at native.
2. **Running Complementary on Metal (B) is not the way to 120 fps.**
   - Complementary is fill- and bandwidth-bound at Retina. Even with Metal and MetalFX, my estimate is about 40–70 fps
     at Retina output.
   - If the user later insists on the exact Complementary look, the realistic B path is a **port of Vitrail**, not Iris.
     Vitrail is LGPL and renderpearl-based. On 26.3 it has 20 Vulkan-coupled files totalling about 5.1k lines. Iris is
     GL to the core (671 GL call sites). So B is an optional later track behind a decision gate.
3. **There is a zero-code stopgap the reports missed.** RenderScale 1.4.0-alpha.6 for Fabric 26.3 (MIT, about 1.9M
   downloads) has a separate *Iris* render scale, FSR 1.0, and dynamic scaling (`RS/…/config/RenderScaleConfig.java:115,
   162-163`).
   - With Iris on GL and a scale of about 0.6–0.7, the HUD and text render at full Retina while the world renders at
     about 1080p cost. It does not add fps over today, but it looks better than lowering the display resolution.
   - We can offer it on day 1 in a *new* test instance.
4. **Build order:** first a harness and a separate test instance, then the stopgap. After that come the milestones:
   - **M1:** sun shadows + lit terrain + sky;
   - **M2:** water + HDR + post + TAAU/dynamic resolution + settings;
   - **M3:** deferred TBDR, entities, clouds, EDR;
   - **M4:** hardware ray-traced shadows and reflections.

   Each milestone ships to the user as a separate Prism instance (§5).

---

## 1. Contradictions between the reports, and how I resolved them

| # | Topic | What the reports say | Resolution (checked in code) |
|---|---|---|---|
| C1 | **Can we use an MRT G-buffer in the main pass?** | `mc-renderer` says no: "impractical, every pipeline in the pass would need matching colour targets". `mcopt-architecture` and `apple-gpu` say yes: TBDR with a memoryless G-buffer through `MetalHooks.Redirect` + `PassDelegate` | **Both are right, at different layers.** See C1 below. |
| C2 | **Role of MetalFX** | `mcopt-architecture` and `iris-internals`: MetalFX at 0.5–0.75 is the path to 120 fps at Retina. `community`: 4.9 ms at 50% and 9.2 ms at 100% (M1 Pro). `apple-gpu`: 3.3–4.3 ms standalone and 4.5–6 ms marginal inside a frame (M4, measured); custom TAAU 0.62–0.84 ms | **Use the `apple-gpu` numbers; they were measured on the user's GPU.** See C2 below. |
| C3 | **Is 120 fps at Retina with shaders feasible?** | `community`: "unlikely; 60–100 realistic". `apple-gpu`: "reachable at 0.5–0.67x + TAAU + RT shadows" | **They agree once resolution is made explicit.** See C3 below. |
| C4 | **Baked face shade in Sodium vertices** | `mcopt-architecture`: dividing it out is approximate, and a Sodium mixin is "cleaner but more invasive" | **Not invasive.** See C4 below. |
| C5 | **Base for running existing packs (B)** | `iris-internals` evaluates only an Iris port (10–15k LOC) or a new runtime that uses Iris as a library (8–12k LOC). `community` proposes porting Vitrail | **Vitrail is the better base for B.** See C5 below. |
| C6 | **MetalFX depth bug** | `mcopt-architecture`: MetalFX gets cleared depth (hand pass) unless a post chain is active | **Confirmed, with a cheap workaround nobody mentioned.** See C6 below. |
| C7 | **Fullscreen size** | `mcopt-architecture`: fullscreen is 3024x1890 (notch excluded). `mc-renderer`: 3024x1964 | **Unresolved, and irrelevant to the decision.** See C7 below. |
| C8 | **HDR main target** | `mcopt-architecture`: "every PSO bakes RGBA8, so HDR needs per-format PSO variants" (sounds expensive). `mc-renderer`: render into a mod-owned RGBA16F target, then tonemap | **On Metal, variants are cheap.** See C8 below. |
| C9 | **Shadow casters** | Camera list (`mcopt-architecture` option A), a full section list (B), or RT instead of a shadow map (`apple-gpu`) | **A proven recipe for the full list exists.** See C9 below. |

**C1 detail.**
- `FrontendRenderPass.setPipeline` throws when the attachment count or format differs from the pipeline's colour targets
  (`DECOMP/com/mojang/renderpearl/frontend/FrontendRenderPass.java:110,118`). Portable renderpearl code therefore
  cannot do MRT with vanilla pipelines.
- mcopt applies its redirect **inside the backend, after frontend validation**: `MCOPT/MetalEncoder.java:236-334`
  (`redirector.redirect(descriptor)`, the swapped colour handles, then `Native.renderBegin` at :308).
- From then on, `MetalRenderPass.setPipeline` hands every game pipeline to `delegate.setPipeline(...)`, which binds its
  own PSO and remaps uniform slots (`MCOPT/MetalRenderPass.java:84-93`).
- **So MRT/TBDR is possible only Metal-side, inside the fork.** Two catches nobody reported:
  - (a) With a delegate, `setPipeline` returns before `record = terrain && split` (:94). The delegated pass therefore
    loses MetalTerrain's pulled/hi-Z occlusion path, and Sodium draws go through plain multi-draws. At 5.9 Mpx this is
    probably harmless: `MetalOccPick` javadoc says split is 9–13% *slower* at 3456x2234 on the M4 mini.
  - (b) `MetalPipeline` keeps neither `CreateInfo` nor the MSL. Libraries are released right after PSO creation
    (`MCOPT/MetalPipeline.java:53-68, 129`). A delegate cannot build variants without a small fork change (§6.3).

**C2 detail.**
- Measured whole frames (`BENCH/results-frame.txt`): native + custom TAA = 8.05 ms, versus 0.5x + MetalFX = 8.34 ms
  and 0.67x + MetalFX = 10.80 ms.
- MetalFX's fixed cost takes 40–55% of a 120 Hz frame. It pays off only when it removes more than about 5 ms of
  per-pixel work. That fits B (Complementary-class packs) and a 60 fps "ultra" tier, not a lean 120 fps native
  pipeline.
- **Not noted anywhere:** the user's complaint is *blurriness at ~1080p*. 0.5x internal (1512x982 = 1.48 Mpx) has
  **fewer** pixels than today's 1080p (2.07 Mpx). The "quality" floor should be 0.67x (2.64 Mpx); see §3.

**C3 detail.**
- Native resolution with a rich set:
  - `BENCH` gives 8.0–8.9 ms core, plus an estimated 2–4 ms for water, entities, clouds and GUI, so 11–13 ms
    (77–90 fps) with cascaded shadows and 10–12 ms (83–100 fps) with RT shadows.
  - Metallum-Sergey measured 86.6 fps (L4 shadows) and 105 fps (L3) at 3024x1964 on an M1 Pro
    (`community.md` §3).
  - Both agree: about 80–100 fps.
- At 0.67x + TAAU: 4.6–5.6 ms core, so about 7–9.5 ms (105–140 fps).
- Conclusion: **120 fps is reachable at 0.67–0.75x internal with our TAAU, or at native with a "lite" set.** It is
  not reachable at native with everything on.

**C4 detail.**
- Sodium 0.9.3's `SmoothLightPipeline` and `FlatLightPipeline` both call vanilla `CardinalLighting.byFace`. I checked
  the class constant pools in `sodium-fabric-0.9.3-alpha.1+mc26.3.jar`: `cardinalLighting`, `byFace`,
  `shadeDirectionOverride`.
- Iris disables face shade with one `@ModifyVariable(method="byFace")` returning `Direction.UP`
  (`IRIS/mixin/vertices/block_rendering/MixinClientLevel.java:14-16`).
- mcopt's own chunk code does not touch face shade. Only far terrain uses `CardinalLighting.DEFAULT`
  (`MCOPT/lod/LodColors.java:277-278`).
- So a tiny mixin plus a chunk rebuild on toggle gives unshaded vertex colours. AO stays baked in colour, which is
  acceptable.

**C5 detail.**
- I checked Vitrail myself:
  - 526 Java files, 116.6k lines.
  - Only **20 files (≈5.1k lines)** are Vulkan-coupled for the 26.3 build: 13 in `common/src/main` and 7 in
    `common/src/mc26.3`. They cover compute, storage images and buffers, compare samplers, wide sampler sets, mip
    blits, descriptor pools and the device-feature mixin. `community.md` said 23 because it also counted 26.2 files.
  - No GL coupling, except one NeoForge early-window file.
- **Important:** Vitrail refuses to draw unless `DeviceInfo.backendName()` is exactly `"Vulkan"`
  (`VITRAIL/common/src/main/java/dev/vitrail/HostReport.java:48,137`; `platform/EngineStages.java:156,212`). On
  mcopt the name is `"Metal"`, so today Vitrail would load and do nothing.
- Its terrain pipelines are named `…:pipeline/gbuffers_terrain_solid` and similar (`pack/program/TerrainPass.java:31-58`,
  `render/GeometryProgram.java:743`). They do not collide with mcopt's name-based Sodium MSL substitution (`SODIUM_TERRAIN`
  map), but mcopt's 20-byte `MetalTerrain` path must still be off.
- **Verdict:** B means porting about 20 Vitrail files to Metal capabilities in mcopt and lifting the gate, which I
  estimate at 4–7k LOC. That is much less than an Iris rewrite.

**C6 detail.**
- `GameRenderer.render3dHud` clears depth at `DECOMP/net/minecraft/client/renderer/GameRenderer.java:687`. It clears the
  *main* depth only when `consistentDepthRequired == false`, and `consistentDepthRequired = !appliedPostEffects.isEmpty()`
  (:668).
- `GameRenderer.update` always requests `minecraft:end_of_frame` (:429).
- **Any present `post_effect/end_of_frame.json`**, even a trivial passthrough, therefore routes the hand to
  `hud3DTarget` and keeps world depth in main depth, which fixes the bug. It costs one depth-integrate pass. The proper
  fix is still the `render3dHud` wrap that FrameGen already uses.

**C7 detail.**
- 26.3 supports both exclusive fullscreen (video mode) and borderless fullscreen through SDL3
  (`DECOMP/com/mojang/blaze3d/platform/Window.java:72-73,162-173`). Whether the notch strip is used depends on the mode.
- The difference is at most 3.8% of pixels. Read the real size in the test instance (F3, or the `mcopt.metal.stats`
  surface size).
- If the user also plays on an external 4K monitor (8.3 Mpx, 1.4x the panel), every budget below shrinks
  proportionally. Use dynamic resolution.

**C8 detail.**
- `describe()` (`MCOPT/MetalPipeline.java:204-234`) is a pure function of `CreateInfo`.
- MSL fragment outputs are `float4`/`half4` and valid for any float colour format. An RGBA16F variant is the same
  library with a different `pixelFormat` in the descriptor.
- Once `MetalPipeline` keeps `CreateInfo` and the two MSL strings, it is about 80 lines (§6.3).
- That makes "redirect Sky+Main to RGBA16F" cheap. MRT G-buffer variants are different: they need new fragment code.

**C9 detail.**
- Iris for Sodium 0.9 swaps `RenderSectionManager.renderLists` and `renderTree` to a shadow tree, swaps each region's
  render list, and calls Sodium's update with a light-frustum viewport (`IRIS/compat/sodium/mixin/MixinRenderSectionManagerShadow.java:119,148,183`).
- That is the recipe for correct off-screen casters. Re-implement it, don't copy it (it is LGPL).
- **Conflict risk nobody flagged:** mcopt's default `alpha` profile enables `mcopt.cpu.lists=true` and
  `mcopt.cpu.cullRecover=true`. These mix into the same `RenderSectionManager` and `readRenderListFromTree` paths
  (`MCOPT/mixin/cpu/ListAbMixin.java`, `ListRegionCacheMixin`, `CullReuseManagerMixin.java:51-121`), so the shadow scope
  must be tested with those flags on.
- RT (`apple-gpu`) avoids frustum lists entirely but needs section-lifecycle tracking and 230–500 MB of acceleration
  structures. See §4/E.

---

## 2. Blocking questions: answered and still open

### Answered here (with evidence)

1. **Can a new pipeline draw MRT/HDR inside mcopt without fighting renderpearl?** Yes, through
   `MetalHooks.Redirect`/`PassDelegate` (C1). Two fork changes are needed: keep `CreateInfo` + MSL in `MetalPipeline`,
   and accept that the delegated pass loses the occlusion split.
2. **Can we remove baked face shade cleanly?** Yes, with a one-line mixin on `CardinalLighting.byFace` (C4). Far
   terrain (`LodColors`) needs its own handling.
3. **Can we bind extra global resources to Sodium terrain draws?** Yes. Free slots are buffers 16-19, textures 16-19
   and samplers 12/13/15 (`mcopt-architecture` §3.2).
   - They must be rebound after every new encoder: `Native.renderBegin` returning 0 in `MetalEncoder.createRenderPass:308`,
     after the split resume (`MetalRenderPass.rebind`, `:148-162`), and after far-terrain draws (`reapplyPipeline`, `:110-118`).
   - I confirmed `rebind()` restores only the pipeline's own uniforms, vertex buffers and push constants. So our slots
     **must** get their own rebind call there.
4. **Is there an existing way to get the full shadow-frustum section list from Sodium 0.9?** Yes. Iris's shadow-tree
   swap is the proven recipe (C9). Interaction with mcopt's culling mixins is still to be tested.
5. **Is the B path more realistic via Vitrail or Iris?** Vitrail (C5). On mcopt today it is inert: the backend gate.
6. **Does a quick fix exist for "full-res HUD with shaders at ~1080p cost" on GL?** Yes: RenderScale 1.4.0-alpha.6,
   Fabric 26.3.
   - Required dependencies (Modrinth API): Fabric API and Cloth Config.
   - It is built against Iris 1.11.6 and Sodium 0.9.2 (`RS/stonecutter.properties.toml`, `[fabric."26.3"]`).
   - Dedicated Iris scale (`irisScale`), FSR 1.0 (EASU+RCAS), dynamic scaling.
   - FSR Reloaded, the other upscaler mod found, stops at 26.1.2 (Modrinth API).
   - **Not verified** with Iris 1.11.7 + Sodium 0.9.3-alpha.1 + mcopt (`-Dmcopt.metal=false`).
7. **Does MetalFX work today?** Partly: the depth/motion bug (C6). There is a cheap workaround (end_of_frame) and a
   proper fix (`render3dHud` wrap).
8. **Is 120 fps feasible, and at what internal resolution?** See §3.
9. **What does "translate existing shaders" mean legally for this user?**
   - Personal, on-device runtime translation (what Iris and Vitrail do) is fine.
   - Hand-porting Complementary's code, even locally, is outside its "Normal Usage" (`iris-internals` §6).
   - Approach A must be **original** code from published techniques (Hillaire sky, Jimenez bloom, AgX, PCSS) plus
     MIT references.
   - Note that `work/community/metallum-sergey/LICENSE` is the MIT text with "Copyright (c) Microsoft Corporation",
     an unedited template. Attribute it to the project author if any code is borrowed.

### Still open (and how to close them)

| Open question | Why it matters | How to answer |
|---|---|---|
| Real per-pass GPU time of mcopt vanilla at 3024x1964 in a real world (terrain, entities, clouds, translucents) | Calibrates the +2–4 ms "real scene" estimate | M0 bench in a **separate** test instance with `-Dmcopt.metal.trace=N`, `-Dmcopt.metal.stats=true`, `MTL_HUD_ENABLED=1` |
| User's current fps with Iris+Complementary at their "1080p" setting, and which profile | Baseline to beat; also tells whether C is useful | Ask the user, or measure in a copy of their Shaders instance (with permission) |
| RenderScale + Iris 1.11.7 + Sodium 0.9.3-alpha.1 + mcopt (GL) | Stopgap validity | One launch of a new test instance |
| Shadow-tree swap compatible with `mcopt.cpu.lists` / `cullRecover` / `CullReuse` | Correct casters in M1 | Prototype in M1. Fallback: the camera list (A) first, or turn those flags off when shading is on |
| Sodium arena relocation and section lifecycle events (for BLAS in M4) | RT tier | Read `RenderRegion`/arena code in Sodium 0.9.3 source (clone `CaffeineMC/sodium` at the 0.9.3 tag into `work/src/sodium`) before M4 |
| Thermal/power: sustained 120 fps on MBP 14 M4, on battery vs AC | Real fps after 10+ minutes | Bench a 10-minute route on AC and on battery |
| Image quality of our TAAU on pixel-art textures (shimmer, ghosting, mip bias) | Visual goal | Side-by-side screenshots versus native, on fixed camera paths |
| Whether the mcopt author publishes the private `mcopt.pack` / `mcopt.shade` code | Possible overlap, or a free win | Watch the upstream repo. Keep our code isolated behind the same seams so it can merge |

---

## 3. Converged performance model (M4 10-core, output 3024x1964)

**Pixel counts.** The user's "1080p" today is the reference.

| Internal resolution | Pixels | vs today's 1080p |
|---|---|---|
| native 3024x1964 | 5.94 Mpx | 2.87x |
| 0.75x 2268x1473 | 3.34 Mpx | 1.61x |
| 0.67x 2016x1309 | 2.64 Mpx | 1.27x |
| 0.60x 1814x1178 | 2.14 Mpx | 1.03x |
| 0.50x 1512x982 | 1.48 Mpx | **0.72x (less than today)** |

**GPU frame estimates.**

- "Core" values are measured in `BENCH/results-frame.txt`, with fragment post and double-buffered targets.
- "Real" adds +2–4 ms for water, entities, clouds, particles and GUI. That addition is an estimate.
- Vanilla mcopt alone is about 1.4 ms at 7.7 Mpx (`MCOPT/MetalRenderPass.java:290-291`, "3456x2234 spin 702→714 fps").

| Configuration (approach A) | Core (ms) | Real (ms) | Approx. fps |
|---|---|---|---|
| native + TAA, cascaded shadows + VL + SSR | 8.87 | 11–13 | 77–90 |
| native + TAA, RT shadows + SSR | 8.05 | 10–12 | 83–100 |
| native "lite" (cached CSM, no SSR/VL, TAA) — est. from the floor 3.3 ms + shadows ~0.8 + TAA 1.26 | ~5.4 | 7.4–9.4 | 105–135 |
| 0.75x + TAAU (interpolated from 0.67x and native) | ~5.5–6.5 | 7.5–10.5 | 95–133 |
| 0.67x + TAAU, RT shadows + SSR | 4.55 | 6.6–8.6 | 115–150 |
| 0.67x + TAAU, cascaded shadows + VL + SSR (0.5x cascaded − RT delta ≈ +1.1 ms) | ~5.6 | 7.6–9.6 | 105–130 |
| 0.67x + MetalFX, RT shadows + SSR | 10.80 | 12.8–14.8 | 68–78 |
| 0.5x + TAAU, RT shadows + SSR | 3.41 | 5.4–7.4 | 135–185 (image below today's pixel count) |

**Cross-check:** Metallum-Sergey at native 3024x1964 on an M1 Pro measured 86.6–105 fps. That is consistent with the
native rows.

**Approach B estimate** (Complementary on Metal via a Vitrail port), using the only systematic Apple Silicon table
(`community.md` §5):
- Complementary Reimagined ran at 61 fps at 1080p on an M1 Pro under Iris/GL, so about 13 ms of per-pixel work.
- Scaled by pixels: native ≈ 40 ms (≈25 fps); 0.67x ≈ 17 ms + MetalFX 4.5–6 ms ≈ 22 ms (≈45 fps); 0.5x ≈ 13 ms +
  MetalFX ≈ 18 ms (≈55 fps).
- Allowing +20–50% for Metal-native compressible targets and no GL overhead gives **about 40–70 fps at Retina output**.
- About 120 fps is not reachable with Complementary defaults.

**Approach C estimate** (Iris/GL + RenderScale): fps ≈ today's fps × (2.07 / (5.94·s²)) minus FSR (≈0.3–0.5 ms). At
s = 0.6 it is the same fps as today with a full-res HUD and FSR edges. At s = 0.7 it is about 0.75x today's fps.

---

## 4. Approach comparison

Scores are 1 (worst) to 5 (best). "fps" means GPU-bound fps at 3024x1964 output on the M4 10-core.

| | A — native MSL in mcopt + own TAAU (MetalFX optional) | B — existing packs on Metal (Vitrail port; Iris port worse) | C — Iris on GL + RenderScale/FSR1 + pack tuning | D — vanilla post chain + core/MSL overrides only | E — hybrid: A staged + HW RT tier (+ optional B track) |
|---|---|---|---|---|---|
| **Visual quality** | 3 at M1 (shadows, sun, sky) → 4 at M2 (water, VL, bloom, HDR) → 4–5 at M3/M4. Art tuning is the risk | **5** (Complementary/BSL/Bliss as authored; coloured lighting possible on Metal) | 4 (Complementary at reduced internal res; FSR1 softness) | 2 (grading, bloom and SSAO on RGBA8; no real shadows or sun) | 5 eventually (RT shadows and reflections crisp at any distance) |
| **fps** | **4–5**: 105–150 at 0.67x TAAU; 80–100 native rich; 105–135 native lite | 2: ≈40–70 with MetalFX | 2: ≈ today's fps (unknown, probably 40–70) at s≈0.6 | 5: 200–400 | 5: same as A, −0.8 to −1.1 ms with RT shadows |
| **Engineering effort** | Medium: M1 ≈2–3.5k LOC, M2 ≈3–4k, M3 ≈3–5k | Very high: ≈4–7k LOC Metal capability layer + gate lift + mcopt terrain-path opt-out + Vitrail drift; Iris route 10–15k | **Very low**: 0 LOC (install + config) | Low: 0–500 LOC (JSON + GLSL/MSL files) | High: A + 2–3k LOC RT (BLAS from Sodium arenas) |
| **Risk** | Medium: hooks exist and the build works; shadow list vs mcopt culling mixins; art direction | High: Vitrail is beta, depends on Vulkan semantics, mac issues #453/#447; MoltenVK-free behaviour untested; LGPL separation | Low–medium: untested with Iris 1.11.7 / Sodium 0.9.3-alpha.1 | Low technically, but does not meet the user's goal | Medium–high: memory (+230–500 MB AS +80–150 MB positions), Sodium arena lifecycle |
| **Existing shaderpacks** | None (our own pack; could later read a simple options file) | **Full** (≈25 packs listed by Vitrail) | Full | None | Optional via B track |
| **Maintainability** | Good if isolated in new files behind mcopt's seams; rebase on mcopt alphas; tied to Sodium's vertex format (pinned `<0.9.4-alpha`) | Poor: two fast-moving upstreams (Vitrail beta and mcopt), LGPL boundary | Good: maintained by others | Good | As A, plus RT code tied to Sodium internals |
| **Matches the user's ask** ("full res, as fast as possible, beautiful, translate or create") | Yes ("create new super-beautiful ones") | Partly (translation, but not fast) | Stopgap only | No | Yes (best long-term) |

**Notes per option**

- **A.** Everything the user asked for is reachable, and A is the only option where 120 fps is plausible.
  - Do it Metal-native in the fork, not as a portable renderpearl mod. Reasons:
    - (1) Sodium terrain on mcopt is hand-written MSL anyway (`MetalPipeline.java:87-91`). A portable mod would need
      `-Dmcopt.metal.builtinMsl=false`, which loses mcopt's tuned terrain shaders and the pulled/occlusion path.
    - (2) Compare samplers, memoryless attachments, programmable blending, RT and MetalFX do not exist in renderpearl.
  - Renderpearl's fragment passes *are* the fast path on Apple: mcopt creates private RT|ShaderRead textures, which are
    compressible. So writing post passes as MSL fragment passes on our own private textures loses nothing.
- **B.** Worth it only for the exact authored look of Complementary or other packs.
  - It cannot make Complementary fast at Retina: the cost is per pixel and bandwidth (`apple-gpu` §0.1).
  - If pursued, it is a **separate LGPL add-on** with three parts: Vitrail with the backend gate lifted; an mcopt-side
    capability API (compute, storage textures/buffers, compare samplers, argument-buffer binding past 16 textures,
    blit mipmaps, independent blend); and mcopt's terrain fast path disabled while a pack is active
    (`-Dmcopt.metal.builtinMsl=false -Dmcopt.metal.occ=false`).
  - Start it after M2, since it reuses the MetalFX fixes and the render-scale infrastructure.
- **C.** Offer it immediately as a stopgap in a new instance (see M0). It needs no code.
  - Also tune Complementary: MEDIUM profile, `shadowDistance` 96–128, coloured lighting off on GL.
  - It does not make shaders faster. It makes the same cost look sharper: native-res GUI/text and FSR1 instead of
    display scaling.
- **D.** Not a solution by itself.
  - Post passes get no camera matrices or sun direction, use RGBA8 intermediates, and cannot render from the sun
    (`mc-renderer` §3).
  - It is still useful as a dev tool: `-Dmcopt.metal.overrideMsl` for quick shader iteration, and the end_of_frame
    passthrough as the MetalFX depth workaround (C6).
- **E.** The recommended long-term shape is A staged with an RT tier.
  - On this exact M4, HW RT shadows saved 0.8–1.1 ms per frame versus cached CSM (`BENCH`: 0.5x TAAU cascaded 4.64
    vs RT 3.56 ms; native TAA 8.87 vs 8.05 ms). They also have no cascade shimmer, no acne, and naturally include
    off-screen casters.
  - The integration cost is BLAS lifecycle against Sodium arenas, so it comes after CSM, not instead of it.
  - MetalFX frame interpolation (11.8–12.9 ms per 3024x1964 frame) and the denoised scaler (11–16 ms) are ruled out
    on this GPU.

---

## 5. Recommended staged plan (each milestone ships to the user)

General rules for every milestone:
- Never touch the user's instances. Ship each milestone as a **new Prism instance**, for example "mcopt Shaders Metal
  (M1)", copied from "mcopt Metal 26.3". Keep worlds separate, or copy a world the user names.
- Launching any instance needs the user's OK, because the user may be playing and GPU benchmarks need the machine free.
- Every milestone ends with a fixed-route benchmark CSV and a screenshot set.

### M0 — Harness, baseline, and the day-1 stopgap (≈1–2 agent-days)
1. **Fork.** Copy `mcopt/` to `work/mcopt-shade/` and run `git init` on a branch `shade`. Build with
   `work/tools/jdk-25.0.4.1+1` using `./gradlew :metal:build`; the baseline build is verified at 1m17s. New native
   files must be added to `metal/build.gradle:23-30` `sources`.
2. **Bench mixin** in the fork, `-Dmcopt.shade.bench=route.json`:
   - auto-join a fixed world (`--quickPlaySingleplayer`), scripted camera spline on fixed ticks;
   - `Screenshot.grab` at fixed frames, then `Minecraft.stop()`;
   - CSV of frame times plus `mcopt.metal.trace=N` per-pass GPU µs.
   - Route: day plains with water, forest, sunset, night with torches, rain, cave.
3. **Baseline numbers** at native Retina: vanilla mcopt Metal, and Iris+Complementary on GL at the user's settings in a
   *copy* of the Shaders instance.
4. **Stopgap (approach C).** A new instance "mcopt Shaders 26.3 + RenderScale": the user's Shaders setup plus
   RenderScale 1.4.0-alpha.6 (Fabric 26.3), Fabric API and Cloth Config. Set the window at native Retina, Iris scale
   0.6–0.7, FSR on, and Complementary MEDIUM.
   - Verify that it starts, the HUD is sharp and fps is within ±10% of the old 1080p.
   - Give it to the user while M1 is built.
   - If it fails with Iris 1.11.7, drop it; nothing else depends on it.

### M1 — "Shaders on Metal v0.1": sun shadows + lit terrain + sky, full Retina (≈4–7 agent-days, ~2–3.5k LOC)
Build these in this order:
1. **`ShadeFrame` UBO** (Java, `mcopt.metal.shade` package):
   - inputs from `GameRenderer.gameRenderState().levelRenderState`: camera view/proj and inverse, previous-frame matrices
     (vanilla keeps none), camera position split into block + fraction, sun/moon direction from
     `SkyRenderState.sunAngle` (`RotY(-90°)·RotX(sunAngle)·(0,1,0)`), rain/thunder, day time, fog colours and ranges,
     dimension/fogType;
   - a ring of 3 shared `MTLBuffer`s (`MetalBridge.newBuffer`);
   - written at `LevelRenderer.render` HEAD (pattern: `LodLevelRendererMixin`).
2. **Global binding hook** (fork patch, about 30 lines): bind `ShadeFrame` at `buffer(16)`, the shadow map at
   `texture(16)` and a **compare sampler** at `sampler(12)`, created natively with `compareFunction=GreaterEqual` for
   reversed-Z. Do it after `Native.renderBegin(...) == 0` in `MetalEncoder.createRenderPass` (:308), inside
   `MetalRenderPass.rebind` (:148), and after far-terrain draws (`reapplyPipeline`, :110).
3. **Face shade off:** a mixin on `CardinalLighting.byFace` (same idea as Iris) behind the shader toggle, with a chunk
   rebuild on toggle.
4. **Terrain MSL** (edit `sodium_terrain.vs.metal`/`.fs.metal` so the plain and pulled variants stay identical):
   - face normal from the quad's corners (`vid & ~3u`, arena at `buffer(27)` or the pulled arena pointer), camera-relative
     world position varying;
   - lighting: sun/moon direct × shadow, sky ambient from `a_LightAndData.y`, warm block light from `.x` (or the
     lightmap), sky-coloured fog;
   - keep the varyings ≤ about 40 B, because the vertex stage bounds terrain on Apple (`sodium_terrain.vs.metal:6-12`).
5. **Shadow pass** in its own encoder via `mc_frame_cmd` before the "Main"/"Solid" pass opens:
   - template: `mcprobe.m` (`probe_vs`/`mc_probe`) with the pulled vertex function;
   - 2 cascades in one Depth32F (or Depth16Unorm) atlas: a near cascade every frame, the far cascade every 4 frames or
     when the sun moves more than 0.1°;
   - **only the 3 sun-facing face directions** (Sodium's per-facing vertex ranges), slope bias via `setDepthBias`.
   - Geometry, step 1: always-record the camera list (`mcopt-architecture` §4.4 A) to get pixels on screen fast.
   - Geometry, step 2: the shadow-tree list (C9), validated with `mcopt.cpu.lists` / `cullRecover` on.
   - Budget: ≤1.0 ms average (`BENCH`: sun-facing ≤96 blocks 0.61 ms; ≤128 blocks 0.90 ms).
6. **Sky:** an MSL override of `minecraft:pipeline/sky`, `sunrise_sunset` and `celestial`:
   - analytic single-scattering atmosphere plus a sun disc and limb glow, stars kept;
   - turn the file-based `overrideMsl` into an in-jar registry keyed by pipeline name (`MetalPipeline.compile:87-95`).
7. **Exit criteria:**
   - ≥120 fps at native Retina on the bench route. The expected cost is about 1.5 ms vanilla + ≤1 ms shadows + lighting.
   - Shadows stable when turning (after step 2).
   - Day, sunset and night screenshots signed off by the user.
8. Far terrain (`mcopt.lod`): in M1 either keep it off in the shader instance, or apply the same sun/sky/fog term in
   `columns.metal` so the seam is not visible.

### M2 — Water, HDR, post, temporal upscaling, settings (≈5–8 agent-days, ~3–4k LOC)
1. **HDR world:**
   - redirect "Sky"+"Main" to an RGBA16F target with per-format PSO variants (fork change §6.3);
   - pre-warm the variants on resource reload to avoid hitches;
   - tonemap into main RGBA8 before the hand.
2. **Atlas material mask** (R8Uint, rebuilt on atlas stitch): water, lava, leaves, plants, glass, emissive. It gives
   waving foliage, emissive glow and water identification without changing Sodium's vertex format.
3. **Water:**
   - at the switch to `sodium:pipeline/translucent_terrain`, suspend, copy scene colour and depth, and resume (the
     `mc_render_suspend` pattern);
   - a translucent fragment override with normal-mapped waves, Fresnel, sky reflection, SSR on water pixels only
     (half-res plus refinement), refraction and depth fog.
4. **Post**, depth-based work in a wrap of `GameRenderer.render3dHud` (as `GameRendererFrameGenMixin` does):
   - VL at half internal resolution (12–16 taps against the far cascade, temporal);
   - optional SSAO (0.16 ms at 0.67x);
   - **custom TAAU** as a fragment pass into compressible history (0.62–0.84 ms);
   - bloom (Jimenez 13-tap, fragment, 0.74 ms);
   - final AgX + grade (0.35 ms).
5. **Render scale + dynamic resolution:**
   - reuse `MetalFx.beginWorld`'s target swap and `CameraFxMixin` jitter, with our TAAU as the default upscaler;
   - MetalFX becomes an option once fixed (capture depth/motion at `render3dHud`, exposure, reactive mask, write in
     place instead of the blit at `mcmetal.m:1466`).
6. **Settings UI** (borrow PR #10's Sodium page). Presets:
   - **Performance:** 0.67x TAAU, dynamic resolution to hold 120.
   - **Balanced:** 0.8x.
   - **Quality:** native + TAA.
   - **Ultra:** native + MetalFX or extras for 60–90 fps.

   **Never default below 0.67x** (C2).
7. **Exit criteria:** Performance preset ≥120 fps sustained on AC; Quality preset ≥80 fps; no ghosting on the water or
   particle test scenes.

### M3 — Deferred TBDR, entities, clouds, XDR (≈1–2 agent-weeks, ~3–5k LOC)
1. **Tile-deferred** through `MetalHooks.setRedirector` + `PassDelegate`:
   - memoryless albedo/normal/material G-buffer, HDR lit colour stored (layout as in `columns.metal:1336-1375`);
   - lighting with a full-screen programmable-blending draw or a tile function (template `lodseam.m:213-256`).
   - Benefit: entities and block entities lit consistently, with shadows.
   - Requires MRT variants of entity pipelines; these are generated MSL, so patch the SPIRV-Cross output by adding
     outputs.
2. Entity shadow casting: a small entity shadow map around the player.
3. Volumetric clouds (quarter to half resolution, 8–16 steps, reprojection).
4. Coloured shadows through stained glass. Rain wetness and puddles.
5. Optional **XDR mode**: `CAMetalLayer` RGBA16Float + `extendedLinearDisplayP3` + `wantsExtendedDynamicRangeContent`,
   with AgX to EDR headroom, in `mc_layer_configure`/`mc_present`.
6. Far-terrain lighting through mcopt's existing `lod_*_gbuffer` shaders (`Lod.java:455,495` hard-coded `gbuffer=false`).

### M4 — Hardware ray-tracing tier (Apple9 / M3+) (≈1–2 agent-weeks, ~2–3k LOC)
1. One BLAS per 16³ section from a heap:
   - positions decoded from Sodium arena vertices by a compute kernel;
   - `half3` section-local positions are exact for 1/16 grids, which saves about half of the 150 MB float3 buffer;
   - PreferFastBuild, compacted when stable;
   - TLAS rebuilt only on change (0.36 ms).
   - Prerequisite: Sodium 0.9.3 arena and section lifecycle study.
2. Inline RT sun shadow in the lighting pass, replacing far cascades; keep a small CSM for VL. RT water reflections. RTAO
   in the Ultra preset.
3. Check the memory budget on 16 GB. Gate the tier on `supportsRaytracingFromRender` and on a free-memory check.

### Optional track P — "Your Complementary on Metal" (decision gate after M2, ≈3–6 agent-weeks, high risk)
- A separate LGPL add-on: Vitrail fork with the backend gate lifted (`HostReport.otherBackend`,
  `EngineStages.java:156,212`), plus Metal implementations of its 20 Vulkan-coupled files through a new mcopt capability
  API, plus mcopt's terrain fast path off while a pack is active.
- Target: Complementary MEDIUM at 0.67x + MetalFX ≈ 45–60 fps at Retina output.
- Pursue it only if the user explicitly prefers Complementary's look over our shaders at M2.
- Do not use Iris as the base (`iris-internals` §4).

---

## 6. Architecture decisions for the implementation agents

### 6.1 Where code lives
- Fork of mcopt, with new code in new files: package `mcopt.metal.shade` (Java), `native/mcshade.m`, and
  `resources/mcopt/shade/*.metal`.
- Patches to existing mcopt files limited to:
  - `MetalEncoder.createRenderPass` (global binds plus the shade redirector install);
  - `MetalRenderPass.rebind` and `reapplyPipeline` (global rebinds);
  - `MetalPipeline` (keep CreateInfo/MSL, a registry, variants);
  - `metal/build.gradle` (`sources`);
  - `sodium_terrain.{vs,fs}.metal` (lighting, normals).
- Gate everything behind `-Dmcopt.shade=…`, readable from `config/mcopt.properties` (`Profile.applyFlags`).
- This keeps rebases on mcopt alphas small, and keeps us compatible with the author's seams in case `mcopt.shade` lands
  upstream.

### 6.2 Licensing
- Our new files are Apache-2.0.
- Edits to `sodium_terrain.*.metal` stay PolyForm Shield (they derive from Sodium).
- No Complementary or BSL code.
- MIT references (Metallum-Sergey MSL `src/main/metal/MetallumHdrEffects.metal` with 1,691 lines, MCUpscaler, Kausik)
  may be adapted with attribution.
- Iris and Vitrail code (LGPL) is reference only. Re-implement the shadow-tree idea.

### 6.3 Fork change list in `MetalPipeline`
- Store `CreateInfo info`, `vmsl`, `fmsl` and the entry names in the instance. They are currently dropped
  (`MetalPipeline.java:53-68`; libraries released at :129).
- Add `long variant(int[] colorFormats, boolean depth)`. It lazily rebuilds the libraries (Metal's shader cache makes
  this fast) and calls `Native.pipelineNew` with a `describe()` that substitutes formats. Cache it per key and close it
  with the pipeline.
- Replace the `OVERRIDE_DIR` file lookup with a `Map<String, ShaderVariant>` registry. Keep `overrideMsl` as a dev
  overlay.

### 6.4 Testing protocol
- Headless:
  - `MetalBridge.createHeadlessContext/Encoder` + `commitAndWait` + `readTexture` for shader unit and pixel tests;
  - `work/spike/iris-msl/tools/mslcheck` to validate MSL;
  - `BENCH` for prototyping pass costs before integrating.
- In-game: the bench route in the test instance, only with the user's OK.
- Keep an `OptimizationHistory.md`-style log of rejected ideas (the Metallum practice).

---

## 7. What the five reports missed

1. **A ready-made GL render-scale mod for 26.3 with an Iris-specific scale** (RenderScale, MIT). It makes C a zero-code
   stopgap (§2.6).
2. **Vitrail as the base for B,** and its hard `"Vulkan"` backend gate (C5). `iris-internals` costed only Iris-based
   routes.
3. **The face-shade fix is a one-line mixin** on `CardinalLighting.byFace`, which Sodium's light pipelines call (C4).
4. **An `end_of_frame` post effect fixes MetalFX's depth** as a side effect, through `consistentDepthRequired` (C6).
5. **0.5x internal is below today's 1080p pixel count.** Quality presets must not default there (C2/§3).
6. **The delegated pass disables MetalTerrain recording.** Variants need retained MSL, because libraries are released
   at `MetalPipeline.java:129` (C1).
7. **mcopt's default `cpu.lists` / `cullRecover` mixins share Sodium's render-list code** with any shadow-tree scope (C9).
8. **Far terrain is on in the "recommended" profile** (`mcopt.lod=true`) and uses its own colouring
   (`LodColors.java:277`). It needs matching lighting, or an unshaded seam will show.
9. **Dimension and fog-type handling** (Nether/End have no sun; underwater and lava fog), and F3+T variant invalidation.
10. Thermals and AC vs battery, an external 4K monitor, and the odd licence header in Metallum-Sergey.

---

## 8. Risks and unknowns (ranked)

1. **Runtime is still unverified.** No research agent could launch the game. Every fps number is either a synthetic
   benchmark on this M4 or another person's machine. M0 must calibrate before M1 claims anything.
2. **Art direction.** "Super bonito" depends on tuning, not only on features. Mitigate with side-by-side references
   (the user's Complementary screenshots) and user sign-off per milestone.
3. **Shadow casters list** versus mcopt's culling mixins and Sodium alpha changes (pinned `sodium >=0.9.3-alpha.1
   <0.9.4-alpha`).
4. **mcopt upstream churn:** 3 alphas in 3 days, and a possible release of the private `mcopt.pack`/`mcopt.shade` code.
5. **TAAU quality on pixel-art textures** (shimmer, ghosting). MetalFX is the fallback at a cost of 4.5–6 ms.
6. **RT tier memory and lifecycle complexity** on 16 GB.
7. **Stopgap C compatibility** with Iris 1.11.7 / Sodium 0.9.3-alpha.1 (RenderScale is built against 1.11.6 / 0.9.2).
8. **Track P (if chosen):** Vitrail beta churn, LGPL separation, and Vulkan-ordering semantics that Metal does not have.
