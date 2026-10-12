# FT0: Distant Horizons on mcopt's Metal backend

Branch `ft0` of `work/mcopt-shade`, worktree `work/wt/ft0`. It has 2 commits on top of `shade` (0.2.0-alpha.3):
- `c4e94fe`: the DH compatibility work and the Profile rule;
- `96b5eae`: Metal validation fixes, README, NOTICE.

## Resumen (ES)

- **Funciona.** Distant Horizons 3.3.4 dibuja sus LODs en Metal a resolución Retina nativa (3024×1964): montañas y océano
  más allá del render distance normal. No hubo crashes y la validación de la API de Metal no da errores.
- **Rendimiento con tu configuración de DH** (la de Pollones: 40 chunks, MEDIUM/MEDIUM, agua COMPLETE, SSAO, niebla de DH):

  | Backend | Spin | Vuelo |
  |---|---|---|
  | Metal | 95–98 fps | 85–87 fps |
  | OpenGL | 36 fps | 33 fps |

  Metal es **≈2.7× más rápido**, y sus 1 % lows son 3.5–5× mejores.
- **Con 3 ajustes de DH** (SSAO off, AA off, vanilla fade NONE): **142–154 fps**, sin diferencia visible en las capturas.
  Es la configuración que recomiendo.
- **Bonus:** en OpenGL, la niebla de DH casi no aparece en Mac, porque el OpenGL 4.1 de Apple no tiene clip control. En
  Metal la niebla sale como DH la calcula.

## What changed (code)

1. **Backend-name compat** (re-implements the idea of mcopt PR #4 by CazzaCoder).
   - New: `metal/src/main/java/mcopt/metal/mixin/compat/DhRenderApiMixin.java`.
     - DH's `MinecraftRenderWrapper.getMcRenderingApi()` returns `VULKAN` when the renderpearl backend is named "Metal".
     - DH only knows "Vulkan" vs everything-else-is-OpenGL. On OpenGL it casts the lightmap to `GlTexture`, which crashes
       on the first frame.
     - DH is not a compile dependency. The target is named by string (`@Pseudo`, `remap=false`), the enum is looked up at
       run time, and the injector has `require = 0`. A DH update that renames things therefore leaves the game unchanged.
   - `CompatMixinPlugin` plus `mcopt-metal-compat.mixins.json` (`required: false`). The mixin is applied only when all
     three hold:
     - DH is loaded;
     - DH's version is in **3.3.3–3.3.4** (`DhCompat.MIN/MAX`; `-Dmcopt.dh.anyVersion=true` lifts the gate);
     - mcopt Metal is on.
   - New: `DhCompat` (DH loaded, version, range, and the `renderingEngine` value from `config/DistantHorizons.toml`).
     It loads no DH or Minecraft classes.
2. **Fog-span clamp** in `sodium_terrain.vs.metal`. The PolyForm-Shield header is kept; the comment credits PR #4.
   - The pre-normalized `(d - start) / (end - start)` gave ≈1 whenever start ≥ end. DH turns vanilla fog off by setting
     start = 4.2e14 and end = render distance, so every chunk drew in fog colour.
   - Now: `span = max(end - start, 1e-4)`, and the result is clamped to ±HALF_MAX before narrowing to half.
   - This matches Sodium's "d ≤ start → no fog". Normal fog ranges are unchanged.
   - Checked by compiling all 3 variants (vs, vs+PULLED, fs) with `MTLDevice.makeLibrary`, then in game: no fog-coloured
     disc.
3. **`Profile.distantHorizons()`**.
   - With DH loaded, mcopt **stays on Metal**. It falls back to OpenGL (as before) in any of these cases:
     - Iris is loaded (DH then forces its raw-GL engine);
     - `renderingEngine = "OPEN_GL"` in `config/DistantHorizons.toml`;
     - DH's version is outside the tested range.
   - An explicit `mcopt.metal` (command line or `config/mcopt.properties`) still wins.
   - **`mcopt.lod` is always forced to `false`** with DH, and `mcopt.lod.radius` is cleared.
   - Every decision is logged on stdout.
4. **Metal API validation fixes**, found with `MTL_DEBUG_LAYER=1` on this instance. After them the debug layer reports
   **zero errors**; only performance hints remain (redundant state sets, unused bindings, endEncoding without draw).

   | Fix | Debug-layer error it removes |
   |---|---|
   | `MetalBuffer.allocate`: MTLBuffers rounded up to 16 B. The GpuBuffer still reports the size that was asked for. | "argument has a length(16), buffer has space for 12" (~200k per run). Causes: DH's per-container `vec3` uniform block, DH post-process blocks and vanilla's lightmap block are packed shorter than the padded MSL struct. |
   | `MetalRenderPass`: zero-count draws are skipped (DH issues some). | "indexCount(0) must be non-zero" |
   | `MetalEncoder`: no NaN clearDepth on a pass that loads depth. | "clear depth (nan) is outside of the range" |
   | `MetalTerrain`: the private quad-index buffer no longer asks for a CPU address. | Debug-layer assert at startup (0.2.0-alpha.3 can't start with `MTL_DEBUG_LAYER_ERROR_MODE=assert`). |

5. **Docs.**
   - README "Known issues": DH 3.3.3–3.3.4 runs on Metal, plus when mcopt falls back.
   - NOTICE: credits CazzaCoder / PR #4, and states that DH is not included or modified.

Licensing:
- DH (LGPL-3.0-only) is shipped and tested **unmodified** (Modrinth jar, sha512 verified). No DH code was copied.
- Our mixin is Apache-2.0 code that calls into DH at run time.

## Harness changes (backward compatible)

- `bin/mcbench-run` accepts `--instance "mcopt DEV-DH"` and adds `-XX:+UseZGC` for that instance only. The file was
  replaced atomically, so runs already in progress were not affected.
- `bin/mcbench-setup-instances`:
  - New flags: `--only "<instance>"` (repeatable) and `--reset-dh-config`.
  - With no flags it still creates exactly DEV and DEV-IRIS, as before.
- `bin/mcbench-wrap`: opt-in `--env MCBENCH_STDERR=<file>` captures the game's stderr. Metal debug-layer messages go
  there; the unified log redacts them.
- HARNESS.md documents these.
- New instance **"mcopt DEV-DH"**. Its mods:
  - mcopt (FT0 jar);
  - Sodium 0.9.3-alpha.1;
  - DH 3.3.4-26.3;
  - **Fabric API 0.161.0+26.3** (required: DH depends on `fabric-api-base`, `fabric-lifecycle-events-v1` and
    `fabric-resource-loader-v0`).
- Instance settings: Java 25 (Prism's), `-Xmx6144m`, `-XX:+UseZGC` (generational by default on 25).
- Jars and the config template are in `work/packs/dh/`.

## DH settings (`DistantHorizons.toml`, config version 5)

Mirrors the user's Pollones config (DH 3.2.0-b, config v3) wherever the option still exists in 3.3.4:
- radius **40** chunks; horizontal and vertical quality **MEDIUM**; max horizontal resolution BLOCK;
- transparency **COMPLETE**; lodShading AUTO; grass side FADE_TO_DIRT; biome blending 3;
- SSAO on, AA on; noise texture on (4 steps, intensity 5, dropoff 1024);
- DH fog on, vanilla fog off: far fog exp² from 0.4 to 1.0 at density 2.5; height fog below y=80 at density 20;
- cave culling on (height 60); overdrawPrevention 0.0; clouds and beacons on (beacon height 6000);
- generator FEATURES, plan SURFACE_THEN_CHUNKS; compression VISUALLY_EQUAL.

Mac changes (far-terrain-plan §5, FT0 column):
- **4 threads at run-time ratio 0.75** (was 5 / 1.0);
- **vanilla fade SINGLE_PASS** (was DOUBLE_PASS);
- **auto-updater off**;
- renderingEngine AUTO (Blaze3D).

Changes forced by the version:
- The 3.2 SSAO tuning keys (samples, radius, strength…) are gone in 3.3.4; only `enableSsao` remains.
- `enableInstancedRendering` no longer exists.
- `distantGeneratorMode` is now `chunkGeneratorMode`.

DH turns vanilla clouds off itself (`overrideVanillaGraphicsSettings`).

Files: `release/DistantHorizons.toml` (template) and `release/DistantHorizons-lean.toml` (the full file of the lean run).

## Numbers

All runs: MacBook Pro M4 (10-core GPU), on AC power, exclusive fullscreen on the built-in panel at **3024×1964**. The
bench world is seed 20261006, vanilla render distance 16, vsync off. Each perf segment is 20 s:
- **spin** = a 360° pan at y≈96;
- **flight** = 10 blocks/s.

DH was still generating LODs (FEATURES) during every run, using 450–680 % median CPU, which is realistic for the first
hours in a world. GPU ms/frame comes from mcopt's command-buffer timestamps.

| Run (result dir in `work/bench-results/`) | Backend | Spin avg / 1 % low | Flight avg / 1 % low | GPU ms/f spin / flight |
|---|---|---|---|---|
| `…133031-ft0-dh-metal` (default DH config) | Metal | **98.4** / 59.3 | **87.2** / 58.4 | 10.2 / 11.5 (GPU-bound, busy 1.0) |
| `…135300-ft0-dh-metal-final` (release jar, default config) | Metal | **94.6** / 51.8 | **84.6** / 52.5 | 10.6 / 11.8 |
| `…133250-ft0-dh-gl` (same instance, `-Dmcopt.metal=false`) | OpenGL | **36.0** / 14.7 | **33.1** / 10.6 | n/a |
| `…135708-ft0-dh-metal-lean` (SSAO off, AA off, fade NONE) | Metal | **153.6** / 53.0 | **142.0** / 63.2 | 6.3 / 6.9 |
| `…132411-ft0-dh-metal-warmup` (fresh world, 60 s segments) | Metal | 114.4 / 54.7 | 119.8 / 44.4 | 8.7 / 8.2 |
| Reference without DH: `…130746-baseline-mcopt-metal` (mcopt DEV) | Metal | 508 / 96 | n/a | 1.9 |

**Gain: Metal over OpenGL**, with the same instance, world and DH config:
- average fps **2.6–2.7×**;
- 1 % lows **3.5–5×**;
- p99 frame time 16 ms vs 54–63 ms;
- max frame time 19–42 ms vs 103–115 ms.

The lean config adds **+60 %** on Metal: 153.6 / 142.0 fps vs 94.6 / 84.6.

FT0 budget (far-terrain-plan §4):

| Budget | Default config | Lean config |
|---|---|---|
| ≥ 100 fps average and 1 % low ≥ 60 | Just misses (95–98 avg, 1 % 52–59) | Average met (142–154); 1 % low 63 in flight, 53 in spin |
| DH GPU ≤ 6 ms | Not met (≈8.5–10 ms on top of the 1.9 ms scene) | Not met (≈4.5–5 ms) |
| ≥ 1.5× OpenGL | Met (2.6×) | Met |

- In the lean run the spin 1 % low (53) comes from CPU hitches during generation, not from the GPU (busy 0.97).
- As the plan prescribes when the GPU budget fails, the lean config is the recommendation.
- The real fix for the remaining DH cost is FT1/FT2, which drop DH's full-screen chain.

## Visual checks (screenshots in `release/screenshots/`, all looked at)

- **LODs render on Metal.** See `horizon_sunset_noDH_metal_gl.png`: top = no DH, middle = DH on Metal, bottom = DH on
  OpenGL.
  - With DH, a snowy mountain range and hills appear far beyond the 256-block vanilla edge.
  - In `metal_forest_closeup.jpg` and `metal_noon_overview.jpg`, LOD ocean and coast reach the horizon.
- **No crash and no fog-coloured disc.** Near terrain is unfogged with vanilla fog off (the clamp works). This held in every Metal run, 7 that completed including 3 under the Metal debug layer. One more run aborted at
  startup: a debug-layer assert in 0.2.0-alpha.3's own code (`MetalTerrain`, fixed in `96b5eae`), before DH drew anything.
- **Fog.** On Metal, DH's exp² far fog is visible. Hand-computing DH's curve gives ≈90 % fog at ~500 blocks with these
  settings, which matches the Metal frames.
  - On **OpenGL the DH fog barely shows** (crisp distant island and mountains, unfogged DH clouds).
  - Cause, verified in the 26.3 decompile:
    - Apple's GL 4.1 has no `GL_ARB_clip_control`, so MC's GL backend runs −1..1 depth (the log lists only
      `GL_ARB_draw_indirect, GL_EXT_texture_filter_anisotropic`).
    - DH 3.3.4 always tells its post shaders `ZERO_TO_POS_ONE`.
    - Its fog pass therefore reconstructs wrong distances on Mac GL.
  - Metal is native 0..1, so the Metal image is the one DH intends.
- **Clouds.** DH's LOD clouds are fogged and tinted by the sky/fog colour on Metal (big orange slabs at sunset); on GL
  they are unfogged grey. Same cause.
- **Water and transparency.** The near water (vanilla) is identical on both backends. LOD water (COMPLETE transparency)
  draws as a translucent blue sheet to the horizon with no sorting holes in the frames.
- **Night and rain.** Torches, rain and night sky look normal (`metal_night_torches.jpg`, `metal_rain.jpg`).
- **Default vs lean.** `horizon_sunset_default_vs_lean.png` shows no visible difference in stills.
  - SSAO at 0.2 strength is subtle at LOD distances.
  - Fade NONE only matters at the moving vanilla/LOD seam.
- **Small artifacts.** A few floating LOD fragments near the far mountain show in the warm-up frames. They are typical of
  DH sections still being built and fewer in later runs.

## Recommended settings (user)

- Instance "mcopt DEV-DH" layout: mcopt-ft0-dh.jar + Sodium 0.9.3-alpha.1 + DH 3.3.4-26.3 + Fabric API 0.161.0+26.3.
- JVM: `-XX:+UseZGC`, 6 GB heap.
- DH: the user's settings as above, with 4 threads at 0.75 and the auto-updater off. Pin DH: 3.3.5+ falls back to OpenGL
  until it is tested.
- **Plus the lean cuts:** `enableSsao=false`, `enableAntiAliasing=false`, `vanillaFadeMode="NONE"`. That gives 142–154 fps
  vs 85–98 at native Retina, with no visible change in stills.
- Under our shaders (FT1+) these are off anyway.

## Known issues / not covered

- **Not exercised by the bench:**
  - F3+T reload, window resize, dimension change, world rejoin;
  - beacons (no beacons in the world);
  - multiplayer.
  - These are the remaining FT0 exit criteria for a manual session.
- **DH's GPU cost is still high:** ≈8.5–10 ms per frame at native with its default post chain. That is FT1/FT2 work
  (remove DH SSAO/fog/TAA/fades, ride the render scale).
- **The VULKAN answer is global.** DH's API also reports VULKAN to add-ons. The clean fix is upstream ("not OpenGL"
  instead of "== Vulkan"; far-terrain-plan FT0.5-3).
- **Iris + DH** in the same instance stays on OpenGL by design. DH forces its raw-GL engine with Iris.
- **The OpenGL fog/clouds on Mac look wrong:** a DH bug with Apple GL 4.1, outside mcopt. It also means the user's
  current 1.21.1 DH + Photon look does not depend on DH fog (Iris turns it off there anyway).
- **Not done in FT0** (they belong to FT0.5): DH thread QoS (`DhThreadFactory`) and turning off DH TAA when MetalFX jitters.
- **Benchmark noise.** DH generation used 4.5–7 CPU cores in every run, and other agents' builds also ran. Two default-config
  Metal runs agree within 4 %, and the Metal/OpenGL gap (2.6×) is far beyond the noise.

## Independent verification (2026-10-06, after the runs above)

- Code review of `shade..ft0`: mixin target/descriptor match DH 3.3.4 (`MinecraftRenderWrapper.getMcRenderingApi`, instance
  method, called after the device exists), version gate checked offline (3.3.3/3.3.4/`3.3.4-b` in; 3.3.2/3.3.5/3.4.0 out),
  `renderingEngine` parsing checked, DH-absent path unchanged. PolyForm header of `sodium_terrain.vs.metal` intact;
  CazzaCoder credited in both source files, NOTICE, README and commit c4e94fe.
- One fix, commit `3adc56b`: the OpenGL-fallback log line said "(-Dmcopt.metal=true overrides)", but forcing Metal in
  any of those cases (Iris, `renderingEngine = OPEN_GL`, untested DH) crashes DH. Hint removed; an explicit
  `mcopt.metal=true` in one of those cases now logs a warning with the reason.
- `20261006-141302-ft0-verify-stress` (DEV-DH, default DH config, 96b5eae jar, `-Dmcbench.stress=reload,resize,nether`):
  spin 96.1 fps / 1% 45.7, flight 86.7 / 45.9, GPU 10.4 / 11.5 ms. F3+T-equivalent resource reload (5.6 s), fullscreen ->
  windowed 3024x1842 -> fullscreen, and overworld -> Nether -> overworld all passed with no exception; the 5 screenshots
  taken afterwards match the `-final` run. DH logged "Renderer already started" on returning to the overworld (benign).
- `20261006-141639-ft0-verify-nodh` (mcopt DEV, no DH, release jar): spin 558 / flight 477 fps, GPU 1.77 / 2.08 ms,
  vs the `shade` baseline median 528 / 407: no regression; screenshots match the baseline.
- Cloud fog checked against DH's source: DH's clouds sit at max height + 100 (y >= 420), so with radius 40 and far fog
  exp^2 start 0.4 / density 2.5 they are >99 % fogged at the distances seen. The Metal frames are DH's intended look;
  the OpenGL frames (unfogged clouds and horizon) are the wrong ones.

## Files

| Path | What |
|---|---|
| `release/mcopt-ft0-dh.jar` | mcopt 0.2.0-alpha.3 + FT0, commit `3adc56b` (independent verification: `96b5eae` plus a log-message-only change in `Profile.distantHorizons()`). The `-final`, `-lean`, `-validation3` and `ft0-verify-stress` runs used the `96b5eae` jar (sha256 `ecec8421...`); `ft0-verify-nodh` used this one. |
| `release/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar` | Modrinth version `TGgEbP9A`, sha512 verified against the API, unmodified |
| `release/fabric-api-0.161.0+26.3.jar` | Modrinth version `bNnaTiuM`, sha512 verified |
| `release/SHA256SUMS.txt` | sha256 of the three jars |
| `release/DistantHorizons.toml`, `release/DistantHorizons-lean.toml` | DH configs (mirror and lean) |
| `release/screenshots/` | `{metal,gl,lean,nodh}_{noon_overview,sunset_water,forest_closeup,night_torches,rain}.jpg` plus 2 horizon comparisons |
