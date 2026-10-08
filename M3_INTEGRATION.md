# M3 integration (branch `m3-int`, worktree `work/wt/m3-int`)

Started 2026-10-07 22:00 from `m3` b32c170, in a separate branch so the integration could run while fix agents were still
working on `m3`, `m3-clouds` and `m3-weather`. When everything is merged and tested, `m3` is fast-forwarded / merged to
`m3-int` (or M3 is packaged from `m3-int` directly). Checklist of collisions: `BASE.md` section 8 (from `m3`).

## Done (each step: build + `MCOPT_GEN_DIR=work/notes/m3-2026-10-07/msl-dump-402 sh tools/shade/validate.sh` ALL OK)

| Step | Commit | Notes |
|---|---|---|
| `vl-stable` (M2.2) | 37da135 | ShadeVl temporal line: m3's under-water history 0.8 kept with the shared water clock |
| `m3-lights` (d1925fc) | 18dc541 | Prelude: `DynLightMsl.prelude()` before m3's seam file. Terrain/water: lights' own calls replace m3's seam call sites (8.5.5). Entities (8.6): vertex = `DynLightMsl.patchVertex` then `ShadeEntity.vertex`; the patched fragment gets buffer 24 / textures 24-25 (`SHADE_ENT_DL_PARAMS`) and `shadeEntityShade` adds the irradiance volume per pixel. Not the hand (hand-space p; `DynLightEntityRendererMixin` raises its packed light). Note: lights' doc proposed ShadeResources slots 4-7, but weather owns 4-6: direct bindings used instead. |
| `m3-skydims` (2d7bfff) | 526a1a5 | `ShadeFinalParams.r.w` = (DH ? 1) + (VL unjitter ? 2); 96 B with `x` (XDR). Prelude: shade_dims after shade_m2. Terrain: dims lighting then the dynamic-light volume. Entities/particles in a dims-lit Nether/End: `shadeLitDims` (End: shadow map), fogged by the final pass (fixes the dark particle specks); hand stays vanilla there. |

## Still to do

0. (DONE eee84e3: m3 75bf608 merged; GPU blob patch gone, CPU EntityBlobShadowMixin. Note: dropBlob needs the sun, so
   in the End the blob stays next to the End key light's entity shadows: minor, check end_island with mobs.)
1. (was) Merge `m3` again (the base fix agent's commits after b32c170: flags `skyRose` / `vl.waterM3`, blob gate by UV2, caster
   resources, hand prewarm...). Then the blob row of 8.1: with End shadows, drop the blob where `flags.z != 0 &&
   flags.x != SHADE_DIM_NETHER` (combine with the base agent's UV2 gate).
2. Merge `m3-clouds` (after its fix agent: Gael's sunset sea lines). Expected conflicts: `vl.metal` / `ShadeVl` (vl-stable's
   temporal changes vs clouds' `vl_march` with ShadeLook 17 + ShadeResources 19), terrain shadow line (+ cloud shadow),
   fog line (`&sr` variant inside `if (!dims)`), `sky.fs.metal` (aurora, flash before the clouds composite), `water.metal`,
   `mcshade.m SHADE_RESOURCES 16`, `ShadeLook` offsets (8.2). Entities: `shadow *= shadeCloudShadow(...)` (8.6).
3. Merge `m3-weather` (after its fix agent). ShadeResources 4-6 (weatherNoise, prevDepth, shadowTint), dev bits
   1024-65536 (skydims moved to 1 << 23), `flash` (weather in the overworld only), terrain chain (8.5.4), shadow pass
   order terrain -> entities -> tint pass (8.6).
4. Test (BASE.md 8.8): every module flag off = M2.1, `mcopt.shade=false` = upstream, entity / lights / weather / dims /
   clouds / VL shots, DH sanity on DEV-DH. Then review, clean measurement, packaging (PROXIMOS_PASOS.md).

## Smoke tests
- `int1-smoke` (jar `work/notes/m3-2026-10-07/jars/m3int-526a1a5.jar`): entities noon/night/hand, dynamic lights
  mobs/nether, dims nether/end. Result (`bench-results/20261007-220548-int1-smoke`, 13 shots, no shader errors): mobs lit
  once and not overexposed at night (lower half +11 % brighter than m3's own ent_night: dynamic lights of the zoo's
  dropped items), the hand with torch + soul lantern fine, Nether particles now light grey ash instead of dark specks (a
  few dark dots left in nether_warped: check which pipeline), End fine, blazes lit in dl_nether.
