# M2 base status (branch `m2`)

M2 base = M1 + FT0 (Distant Horizons on Metal) merged, the M1 polish backlog, and the HDR frame (Shade Filmic tonemap,
natural bloom + fog glow, bounded auto-exposure + Purkinje). Everything is gated by `mcopt.shade=true`; every feature has
its own flag (M2_INTEGRATION.md §8). Integration points for the parallel modules (water, god rays, TAAU; all merged): **M2_INTEGRATION.md**.

## What is done

| Item | State | Where |
|---|---|---|
| m2 from m1, ft0 merged | done (DH 3.3.4 + shade coexist; DH verified in game) | `bbf283c` |
| Chainable redirect hook | `MetalHooks.addRedirector(order, r)` / `removeRedirector`; `setRedirector` kept | MetalHooks.java |
| PSO format variants | `MetalPipeline.variant/state/pulledState`, CreateInfo + MSL retained (shade on only), pre-warmed on the compile worker | MetalPipeline.java |
| Format adapter | shade stages get `MCOPT_HDR`; the game's own fragments get `shadeLdrToHdr` on their colour(0) (201/201 generated fragments compile, validate.sh) | ShadeMsl.java |
| HDR world | "Sky"+"Main" (+ everything on the main colour texture until render3dHud) → RGBA16F pre-exposed linear target, two alternating (last frame's = `prevScene`) | ShadePost.java |
| Shade Filmic + grade (E21) | inset → Lottes curve solved for mid-grey/white → outset, WB, saturation/vibrance, soft black floor, triangular dither; per-time-of-day keyframes | shade_look.metal, shade_post.metal, ShadeLook.java |
| Auto-exposure (E20) | 64-bin log histogram on bloom L2 (compute), 40-92 percentile mean, λ-blend with the reference, ±1.75 EV (night +0.6 max), τ 0.6/2.2 s; rain −0.7 EV | shade_post.metal |
| Purkinje, vignette | final pass | shade_post.metal |
| Natural bloom + fog glow (E19) | 6 levels, 4-tap Karis first level, 13-tap down, tent up, octave weights i^s, energy-conserving lerp + haze-weighted fog glow (SE Natural Bloom composite idea, MIT, credited in NOTICE) | shade_post.metal |
| ShadeLook art layer (E01) | buffer(17), keyframes by sun elevation (Catmull-Rom, √-space colours), chroma power, morning/evening asymmetry, rain, look presets (epic/storybook/natural), sun-path tilt 30°, sun/moon handover crossfade | ShadeLook.java, ShadeSky.java |
| Sun/moon/stars (E03/E04) | procedural limb-darkened sun disc (1°) + Mie aureole, procedural moon with phases (Lommel-Seeliger, maria, craters, earthshine), hashed star field (blackbody colours, twinkle), baked procedural Milky Way; vanilla sun/moon/stars quads dropped | shade_m2.metal, sky.fs/celestial.vs/stars.vs |
| Haze (E06) | height haze + mist (closed-form layer optical depth), evening glow, ℓ4 border fog into the sky (off when DH moves vanilla fog); in HDR applied once per pixel from depth in the final pass | shade_m2.metal, shade_post.metal |
| PCSS-lite (E08) | near cascade: 2-gather blocker search, 8-tap Vogel PCF, contact sharpening, within 40 blocks (M1 tent beyond), leak gate by sky light past the far cascade | shade_m2.metal |
| Lighting v2 (E09) | wrap, art ambient tint, warm fill bounce, F(b) block curve dimmed by day, cool cave floor, E/W face separation | shade_m2.metal |
| DH under HDR | bridge: any `distantHorizons:*` pass → HDR tone-mapped into main first; DH draws/fades/copies as without shading; changed pixels come back through the inverse tone map | ShadePost.java |
| Module hooks | `ShadePost.addBeforeTonemap` (VL/AO/composites), `ShadePost.setResolver` (TAAU), `ShadeRender` (library/pipeline/texture/pass/dispatch), ShadeResources argument buffer | M2_INTEGRATION.md |
| Validation | `tools/shade/validate.sh` (+ HDR terrain/sky/clouds/weather variants, post library, stars, every generated game fragment converted for HDR when `MCOPT_GEN_DIR` is set) | ALL OK |

## Numbers (3024x1964 fullscreen, M4 10-core GPU, AC, bench route, 15 s segments, same-session A/B)

GPU ms/frame from gputimes (union of command-buffer intervals / frames). Noise between identical runs ≈ ±0.3 ms.

| Config | spin fps | spin GPU ms | flight fps | flight GPU ms | run |
|---|---|---|---|---|---|
| upstream jar (no shade) | 617 | 1.62 | 503 | 1.98 | 20261006-210905-m2b9-upstream |
| **m2, mcopt.shade=false** | 610 | 1.63 | 507 | 1.97 | 20261006-211457-m2b9-off (= upstream: no regression) |
| M1 release jar, shade on | 233-240 | 4.16-4.32 | 203-208 | 4.78-4.91 | m2b-m1ref, m2b6-m1ref, m2b9-m1ref |
| **M2 base, shade on (default)** | 170-176 | 5.68-5.89 | 150-155 | 6.45-6.67 | m2b6-on, m2b6-tr, m2b7-on, m2b9-onb |
| M2, hdrFormat=rg11b10f | 176 | 5.67 | 161 | 6.22 | m2b7-rg11 |
| M2, exposure=fixed | 170 | 5.87 | 150 | 6.69 | m2b7-fixed (histogram cost ≈ 0) |
| M2, hdrFormat=rg11b10f (second run) | 174 | 5.73 | 160 | 6.23 | m2b9-rg11 |
| **final build (rg11b10f default), shade on** | 175 | 5.72 | 153 | 6.55 | 20261006-214715-m2b10-on |
| M2 + Distant Horizons (DEV-DH, DH default config) | 61.5 | 16.3 | 56.1 | 17.8 | m2b9-dh (FT0 measured DH alone, no shade: 98 / 87 fps, 10.2 / 11.5 ms; the lean DH config, FT0_NOTES, is the recommendation) |

M2 over M1 ≈ **+1.4-1.7 ms** at native Retina (all of M2a + polish). The bible budgets M2a at 1.1 ms at 0.67x internal
(≈ 1.6-2 ms at native for the full-screen passes), so this is inside the M2a budget; at native Retina M2 base runs
**150-176 fps** on the route (Performance-preset ≥120 fps holds even before TAAU; TAAU at 0.67x would recover ~3 ms).

Where the time went (first build → now), from same-session flag A/B and the labelled GPU profile
(`-Dmcopt.metal.trace=spin+300 -Dmcopt.metal.profileEncoders=40`, shade passes are now named in the trace):

| Cost | first build | now | how |
|---|---|---|---|
| height haze inline in terrain (overdraw) | 1.1 ms | ≈ 0.1 ms (in the final pass) | moved to one per-pixel evaluation from depth |
| PCSS (12 taps everywhere) | 0.64 ms | ≈ 0.2-0.3 ms | 8 taps, only within 40 blocks |
| bloom (13-tap first level + half-size up pass) | 1.1 ms | ≈ 0.75 ms | 4-tap Karis first level (0.54 ms: the full-res read), up chain stops at level 1 |
| final pass (bloom mix, haze, Purkinje, vignette, Shade Filmic, dither) | – | 0.60 ms | |
| HDR target bandwidth (RGBA16F vs RGBA8) | – | ≈ 0.2-0.4 ms | rg11b10f option saves 0.2-0.45 ms |

Shade off is byte-identical in behaviour to upstream (no redirector, no adapter, no retained MSL, hooks behind static
finals) and measured equal.

## Screenshots

Contact sheets (M1 left, M2 right; noon, forest, sunset over water, night torches, rain) and DH: 
`work/release/M2-base-screenshots/` (m2-vs-m1.jpg, m2-dh.jpg, m2-details.jpg, m2-final-dev-vs-dh.jpg = final build on
DEV and DEV-DH). Full-size PNGs: `bench-results/20261006-214715-m2b10-on/screenshots/` (final), DH:
`bench-results/20261006-215042-m2b10-dh/screenshots/`.

What changed visually vs M1: filmic highlight roll-off with bloom glow around the sun and bright water, a real round sun
with aureole, Milky Way + twinkling coloured stars + phased moon at night, warm torch pools that keep their colour,
height haze/ground mist that makes distance soft (fog glow), punchier forest greens, golden band at sunset, darker grey
overcast. DH: LOD terrain visible and part of the bloom/tone map (sunset mountains, rain hills).

## Water and reflections module (merged from `m2-water`, 2026-10-06 evening)

Merge + HDR wiring + new work on branch `m2` (via `m2-wint`; details: M2_INTEGRATION.md section 3, WATER.md "M2 integration").
Water runs in the HDR translucent fragment: waves, Fresnel, SSR over the sky, refraction, absorption, caustics, foam,
Snell's window from below, all in pre-exposed radiance, so bloom, auto-exposure and Shade Filmic treat the sun glint and
the bright sky reflections like the rest of the frame.

New on m2 (beyond the module as it was on `m2-water`):
- **Reflections read last frame's finished HDR world** (`ShadeResources.prevScene`, `mcopt.shade.ssrPrev`): SSR hit
  colours are reprojected from it, and the sky in reflections is the rendered sky wherever the reflected direction lands
  on screen: clouds, sun disc and aureole, stars and (with Distant Horizons) the far LODs now mirror in open water.
- **Baked waves** (`mcopt.shade.waterBake`): one 1024^2 mipmapped wave texture per frame (14 waves on a 64-block
  lattice) sampled with anisotropy, instead of 7 analytic waves per pixel; less distant shimmer, a less regular glitter road.
- **Faster SSR**: batched depth reads on a half-size coarse depth (−0.6 ms at native in the bay view).
- Water fragments use the 4-tap tent shadow instead of PCSS; the underwater fog radiance is computed only under water.

| Config (3024x1964, bay "spin" view: water ~half the screen) | spin fps | spin GPU ms | flight fps | flight GPU ms | runs |
|---|---|---|---|---|---|
| M2 + `mcopt.shade.water=false` (M1 stand-in water) | 174-182 | 5.51-5.76 | 145-161 | 6.23-6.90 | wi2-off, wi2-off2, wi7-off, wi7-off2 |
| scene copy only (`dev=8`) | 159 | 6.28 | 142 | 7.06 | wi2-copy |
| first integrated build (analytic waves, full-res march) | 108-109 | 9.17-9.24 | 121-122 | 8.19-8.25 | wi2-on, wi2-on2 |
| **final** (`wi-7`: baked waves, coarse-depth SSR) | 108-114 | 8.77-9.24 | 113-118 | 8.50-8.85 | wi7-on, wi7-on2, wi6-on, wi4-on |
| final, `ssr=false` | 126 | 7.91 | 126 | 7.94 | wi4-nossr |
| SSR steps 12 / 24 / 48 (pre-coarse build) | 114 / 104-110 / 98 | 8.73 / 9.1-9.6 / 10.26 | | | wi3-s12, wi3-on*, wi3-s48 |
| `mcopt.shade=false` (this branch) vs upstream jar | 623 vs 615 | 1.60 vs 1.62 | 511 vs 506 | 1.95 vs 1.97 | wi7-noshade, wi7-upstream (no regression) |
| DEV-DH (DH default config) + water | 51.6 | 19.4 | 50.4 | 19.9 | wi7-dh (m2b9-dh without the water module: 16.3 / 17.8) |

Re-verified after the interrupted run (23:24, head 33f6bfd, `wv1-on`, all scenes + water shots): spin 115 fps / 8.70 ms GPU,
flight 122 fps / 8.21 ms GPU, 10 shots identical in look to `wi7-on`; `tools/shade/validate.sh` ALL OK (needs JAVA_HOME = JDK 25).

Cost split in the worst-case view (native Retina, same-session A/B): scene copy + pass split ≈ 0.7 ms, SSR ≈ 1.1 ms
(after the coarse depth; reflections from `prevScene` ≈ 0.3 of it), the rest of the shading ≈ 1.5 ms; ≈ +3.3 ms total
for half the screen water (+2.4 ms on the flight route). Everything scales with pixels: at the Performance preset's
0.67x TAAU (0.45x pixels) and the bible's 30 % water that is ≈ 0.4 ms copy + ≈ 0.7 ms shading, i.e. at the top of the
bible's H6 + water budget (0.7-1.15 ms). Without TAAU (m2 today) the bay view runs 108-118 fps at native Retina; views with
less water lose proportionally less. The session noise is ±0.3 ms (other agents' games and the Claude app share the GPU).

Screenshots (`work/release/M2-water-screenshots/`): `water-vs-complementary.jpg` (Iris + Complementary vs M2 water,
same poses: sunset glitter road, shore at noon and evening, shallows with caustics, underwater toward the sun),
`m2-base-vs-water.jpg` (M2 base with M1 water vs the module: sunset, noon overview, rain), `water-dh.jpg` (DEV-DH:
LOD mountains and clouds mirrored at sunset), single shots `m2-water_sunset.jpg`, `m2-sunset_water.jpg`,
`m2-water_shore_noon.jpg`, `m2-water_shallows.jpg`. Full PNGs: `bench-results/*-wi7-on/screenshots`, `*-wi7-dh/screenshots`,
the Complementary reference `*-wref-iris/screenshots`.

Water known issues: SSR hit/miss dithering per 2x2 quad shows as speckle on sparse leaves at 100 % zoom until TAAU jitters
it; a faint diamond pattern remains in the far glitter road; the hit search uses the opaque depth copy (translucents
are mirrored only through `prevScene` where something opaque is behind them); the TIR region from below is the rippled fog
colour unless SSR hits; DH's own water (LOD oceans) is unshaded (FT2); glossy glass/ice is opt-in and unchecked in game;
the copy runs every frame with translucent terrain even when no water is visible.

## Volumetric light / god rays (merged from `m2-vl`, 2026-10-07 night)

**Merged** (`79f6fc3` merge + follow-ups; module doc `VL.md`, wiring in M2_INTEGRATION.md section 4). What changed on m2:

- **Hook**: VL runs as ShadePost's before-tonemap hook (order 100) whenever the HDR post chain is live; the m1 render3dHud
  mixin only registers it then (it keeps the M1 LDR composite for `mcopt.shade.hdr=false`). Source = `ShadeMsl.prelude` +
  vl.metal; `mc_shade_bind_render/compute` bind slots 16-19; the canopy kernel publishes its factor to `ShadeGpu.scene.y`.
- **Folded into the final pass** (`mcopt.shade.vl.fold`, default on): the temporal pass writes an extra VL-resolution
  RGBA16F target (light before exposure + the canopy mist's transmittance T) and `ShadePost.setVolumetric` hands it to
  `shade_final`, which upsamples it depth-aware (one `gather` of the history's distances; one bilinear sample where all
  four agree, the 4-tap bilateral only at depth edges) and applies `c = (c*T_haze + S_haze)*T_vl + L_vl*exposure` before
  bloom's mix; the fog glow also uses T_vl. This replaces a full-resolution read-modify-write of the HDR target
  (vl_composite_hdr, 0.47-0.55 ms at native, kept for `fold=false` and the debug views) with ≈ 0.13 ms in the final pass.
  Trade-off: bloom and the exposure meter don't see the shafts (they are soft, low-frequency light), and `prevScene`
  (SSR) has no shafts.
- **HDR look**: in HDR nothing compresses the in-scattering the way M1's output curve did, so the first HDR build was a
  milky veil. Tuned in game (vi1..vi7): strength ×0.6 in air and ×0.45 under water, lighter canopy-mist veil (0.1 sky
  colour) and extinction (0.35), 70 % of the open air's unshadowed glow left to the base haze, and a **carve** (new
  `VlParams.carve`): the canopy mist keeps max(lit − 0.4·unshadowed, 0)/0.6 and the caustic water shafts max(lit −
  0.55·uncaustic, 0)/0.45, so rays that cross shadows darken and the beams read as structure. The open-air medium now
  follows the base's E06 haze/mist (`ShadeLook` heights, keyframes, morning/evening asymmetry, rain) ×4.6/×4.2, and the
  strength follows the ShadeLook VL keyframe and look preset (softened to 0.66 at noon .. 1.2 storybook golden hour).
- VL preset default now follows the base (`mcopt.shade.preset`, performance: VL 756x491 at native, 10 steps, 6 taps).

| Config (3024x1964, bay "spin" view + flight, same-session interleaved A/B) | spin fps | spin GPU ms | flight fps | flight GPU ms | runs |
|---|---|---|---|---|---|
| M2 + water, `mcopt.shade.vl=false` | 109-111 | 9.03-9.18 | 116-118 | 8.51-8.62 | vi7-off, vi7-off2 (vi3-off/off2 8.71-9.16 / 8.41-8.56) |
| **M2 + water + VL, performance (default, fold)** | 104 | 9.60-9.66 | 109-111 | 9.03-9.20 | vi7-on, vi7-on2 → **VL ≈ +0.52 ms / +0.55 ms** |
| VL balanced (998x648, 12 steps, 8 taps) | 102 | 9.81 | 108 | 9.22 | vi7-bal → ≈ +0.70 / +0.66 ms |
| VL with a composite pass (`fold=false`, first build) | 99 | 10.07 | 105 | 9.55 | vi2-nofold (vi2-fold same session: 9.84 / 9.07) |
| passes only (`foldSkip`, measurement) | 108-111 | 8.99-9.27 | 112-117 | 8.55-8.94 | vi4-skip, vi4-skip2 |
| DEV-DH (DH default config) + water + VL | 50.9 | 19.65 | 49.7 | 20.13 | vi7-dh (wi7-dh without VL: 19.4 / 19.9) |
| `mcopt.shade=false` | 544 | 1.71 | 409 | 2.17 | vi7-noshade (no shade/VL line in the log; machine shared, ±0.1 ms) |

In-game GPU timer (`-Dmcopt.shade.vl.gpuTimer=true`, ~18 two-second windows): performance march 0.32 + temporal 0.08 ms,
balanced 0.48 + 0.13 ms; the final pass's share ≈ 0.13 ms. Performance VL ≈ 0.5 ms total at native Retina = the top of the
bible's E07 budget (0.40-0.55); with TAAU 0.67x the march/temporal shrink to ≈ 0.2 ms (the fold stays at output res).
The frame is still below 120 fps in this water-heavy view because of the water module (≈ +3.3 ms at native) until TAAU.

Stress (`vi7-stress`: reload, fullscreen→windowed 3024x1684→fullscreen, Nether and back) passed; VL targets follow the
resize, no errors. The `vl_overhang` Complementary reference doesn't exist (Iris run skipped it) and its
`vl_forest_morning` shot has a stray inventory screen (keyboard input during that older run).

Screenshots (`work/release/M2-vl-screenshots/`): `vl-off-vs-on-vs-complementary.jpg` (god-ray poses: VL off, on, Iris +
Complementary), `vl-morning-evening-crops.jpg` (full-resolution crops: golden beams through the canopy, evening shafts by
the birch), `vl-underwater-carve-before-after.jpg` (caustic shaft curtain after the carve), `vl-standard-off-vs-on.jpg`
(sunset over water, noon, forest, rain, night: no regressions; VL subtle in the open), `vl-dh.jpg` (DEV-DH). Full PNGs:
`bench-results/*-vi7-look/screenshots` (god-ray poses), `*-vi6-look/screenshots` (all), `*-vi7-dh/screenshots`.

VL known issues: DH pixels are depth 0 (sky) for VL: at sunset LOD silhouettes toward the sun get the full 160-block
in-scattering and the crepuscular sky mask counts them as open sky (streaks cross LOD hills) until FT2's merged distance;
clouds cast no shafts (no cloud shadow map yet); no tinted shafts through water seen from above or stained glass (M3 tint
map); `vl_forest_side` / `vl_forest_evening` stay subtle (sun behind dense leaves). TAAU: done (see the TAAU section).

## TAA / TAAU + dynamic resolution (merged from `m2-taau`, 2026-10-07)

**Merged** (`095ce42` merge + `5fc507d`; module doc `TAAU.md`, wiring in M2_INTEGRATION.md section 6). The world renders
at a render scale into fixed-size targets (live sub-rect, Halton jitter in the projection), TAAU resolves the HDR world
into an output-size history before bloom and the tone map, and the final pass tone-maps and sharpens it into the game's
main target before the hand. Bloom's first level reads the internal-resolution scene; SSR reads the TAA history as last
frame's image; the history follows auto-exposure on the GPU; VL and water are sub-rect aware; one preset switch.

**Preset switch** (system property or one line in `config/mcopt.properties`, which mcopt's Profile applies):

```
mcopt.shade=true
mcopt.shade.preset=balanced      # performance | balanced | quality | ultra
```

Default **balanced**: in the worst bench view (the bay: half the screen water, SSR, VL) it holds the 120 fps target at
its 0.67 floor (120-125 fps); everywhere lighter the dynamic resolution spends the headroom on resolution (up to 0.90).
`performance` (0.60-0.75) keeps a margin (137-140 fps there); `quality` is native-resolution TAA (DRS 0.85-1.0 for
80 fps).

| Config (3024x1964 fullscreen, bay "spin" view + flight, water + VL on, same-session interleaved) | spin fps | spin GPU ms | flight fps | flight GPU ms | runs |
|---|---|---|---|---|---|
| `mcopt.shade.taa=false` (native, = M2 before the merge) | 99-104 | 9.65-10.07 | 107-109 | 9.21-9.36 | ti2-off1, ti2-off2 |
| **performance** (DRS settles at 0.60) | 137-138 | 7.26-7.30 | 139-140 | 7.16-7.21 | ti2-perf1/2, ti3-perf1 |
| **balanced** (default; DRS settles at 0.67) | 120-122 | 8.18-8.33 | 123-125 | 7.98-8.10 | ti2-bal1/2, ti3-bal1/2 |
| quality (DRS settles at 0.85) | 90-91 | 11.00-11.07 | 93-94 | 10.62-10.74 | ti2-qual1/2 |
| balanced fixed 0.67 (profile run) | 126 | 7.94 | 127 | 7.90 | ti1-prof |
| DEV-DH, DH default config, balanced (fixed 0.80 with DH) | 62.4 | 16.06 | 59.5 | 16.81 | ti4-dh (native without TAA: vi7-dh 19.65 / 20.13) |
| `mcopt.shade=false` vs upstream jar (GPU contended by the Claude app's renderer, ±0.5 ms) | 365 / 264 vs 196 / 497 | 2.18 / 2.69 vs 3.44 / 1.78 | 352 / 379 vs 392 / 339 | 2.41 / 2.28 vs 2.19 / 2.36 | ti4/ti5-noshade-* (no shade line in the log; equal within noise) |

The water scene copy now copies only the live sub-rect (ti3 vs ti2: about -0.13 ms on balanced). 1% lows: performance
94-99 fps, balanced 85-97, quality 65-78.

**Distant Horizons**: the first DEV-DH run with dynamic resolution (`ti3-dh`) was wrong: DH draws its LODs into its own
main-sized targets with a full viewport and copies them 1:1 into the main target, so with a live sub-rect smaller than
the allocation its frame landed at the wrong scale (`release/M2-taau-screenshots/taau-dh-subrect-bug-before-fix.jpg`).
With DH loaded TAAU now uses a fixed scale (the preset's start scale; targets allocated at exactly that size, no
sub-rect): `ti4-dh` matches the native DH frame (LODs, sunset mountains, rain), and strafing shows no ghosting on LOD
silhouettes. DH frames got 3.5 ms cheaper (16.1 vs 19.7 ms). Dynamic resolution with DH needs DH's own passes on the sub-rect
too (FT2).

Stress (`ti4-stress`: resource reload, fullscreen -> windowed 3024x1684 -> fullscreen, Nether and back): TAA and HDR targets
follow the resizes, no errors, shots afterwards correct. `tools/shade/validate.sh` ALL OK, `taatest` unchanged
(still 0.67: TAAU err 3.44 vs plain 4.92).

Look (screenshots `work/release/M2-taau-screenshots/`): `taau-standard-native-vs-balanced.jpg` and
`taau-water-native-vs-balanced.jpg` (same poses, native M2 vs TAAU balanced: same grade and bloom, cleaner leaf
edges and glints, crisper underwater caustic shafts), `taau-godrays-native-vs-balanced.jpg`, `taau-motion-shots.jpg` (forest pan, strafe,
sunset pan mid-motion: no ghosting), `taau-forest_crop.jpg` / `taau-strafe_crop.jpg` (100 % crops), `taau-dh-*.jpg`.
Full PNGs: `bench-results/*-ti3-look/screenshots` (18 shots: standard, water, god rays, motion), `*-ti4-dh/screenshots`.

TAAU known issues: strafing still softens distant pixel-art texture a little (classic TAA; the motion floor and the final
sharpen limit it); no reactive mask yet (particles, rain and water normals rely on the clip); no per-object motion
vectors; DRS off with Distant Horizons (fixed scale); the TAAU resolve (~0.9-1.1 ms at output size) is above the bible's
0.72 ms; the HDR world target, the DH bridge passes and the VL targets are sized from the allocation (scaleMax), a little
more than the live area needs.

## Known issues / next

- (fixed in the review round) Sunset horizon read lemon-yellow; evenings looked like noon.
- (fixed by the water module) Noon water read lighter/cyan in HDR with M1's water shader.
- Stars show through the semi-transparent vanilla clouds at night (clouds alpha); fixed by the M3 cloud system or by
  dimming stars by cloud coverage.
- DH LODs keep DH's own lighting/fog (bridge); FT2 should redirect `distantHorizons:TerrainRenderer` into HDR with a shade
  override and provide a merged scene-distance input (haze/VL currently treat DH pixels as sky: depth 0; VL's march and
  crepuscular sky mask need it too, see the VL section).
- (fixed with the TAAU merge) Bloom L0 is built from the internal-resolution scene.
- Translucent surfaces that don't write depth get the haze of what is behind them (final-pass haze).
- The HDR world target defaults to RG11B10Float for the performance/balanced presets (no visible banding at 2.5x contrast in the sunset sky and night gradients, `m2b9-rg11` vs `m2b9-onb`); RGBA16Float for quality/ultra. Modules must not rely on destination alpha in it.
- Not done from the bible in this milestone: LUT sky (M2b), material atlases (M2c), GTAO, XDR output.

## Review round and final benches (2026-10-07, release `work/release/mcopt-shade-M2.jar`)

Three reviews (correctness, performance, beauty) of `90c6f5c`; every major finding fixed (commits `185df6f`, `8a1d16c`,
`fe02c1d`), release notes in `work/release/M2_NOTES.md`.

- **Correctness**: ShadeResources ring (retained, one slot longer than ShadeFrame's; GPU address faults under Metal API
  validation on resizes and the TAA-history use-after-free are gone), `ShadeLook.ssr.x` = prevScene valid, exposure kernel
  after the final pass, VL LDR path live size under TAAU. `fin-val` (reload, resize x2, Nether, `MTL_DEBUG_LAYER=1`,
  stderr captured): no Metal error.
- **Performance**: VL on the live sub-rect, 512^2 wave bake for P/B (skipped without translucent terrain), one HDR target
  under TAA, balanced DRS floor 0.60 (+ a drsLog line when pinned at the floor).
- **Look**: golden hour (chroma gamma 1.30 at 0 deg, inset 0.20, warm WB/ambient from 25 deg, golden sky gain in the sky
  LUT), VL near march for the canopy mist + carve 0.6 + dark crepuscular rays on the sky, haze-matched reflections, calmer
  LUT direction on ripples, sparse foam, tight Snell glare, brighter cloud undersides, rain streaks.

| Config (3024x1964, bay spin / flight, one interleaved session) | fps | GPU ms |
|---|---|---|
| shade off | 338-457 / 316-324 | 1.88-2.29 / 2.46-2.53 |
| M1 jar | 238 / 212-213 | 4.20 / 4.70 |
| performance | 138.8 / 141.7-142.9 | 7.20 / 7.00-7.06 |
| **balanced** | 134.1-136.1 / 137.3-139.0 | 7.34-7.45 / 7.20-7.28 |
| quality | 92.4 / 95.4-95.8 | 10.82 / 10.45-10.49 |
| ultra | 65.5-65.8 / 71.7-72.3 | 15.20-15.28 / 13.85-13.97 |
| DEV-DH balanced (fixed 0.80) | 60.9 / 59.7 | 16.43 / 16.76 |

Runs `bench-results/20261007-0156..0222-fin-*`; screenshots `work/release/M2-screenshots/`.

Still open: DH LOD ocean unshaded (navy seam) and flat orange clouds with DH (FT2); DRS off with DH; reactive mask;
TAAU resolve ~1 ms; Improved Transparency (vanilla OIT) unverified with TAAU's sub-rect (targets at the allocation size,
full viewport: likely the same issue DH had; keep it off); VL shafts in open-field side views stay subtle.
