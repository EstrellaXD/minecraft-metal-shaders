# M2 base status (branch `m2`)

M2 base = M1 + FT0 (Distant Horizons on Metal) merged, the M1 polish backlog, and the HDR frame (Shade Filmic tonemap,
natural bloom + fog glow, bounded auto-exposure + Purkinje). Everything is gated by `mcopt.shade=true`; every feature has
its own flag (M2_INTEGRATION.md §8). Integration points for the parallel modules (water, god rays, TAAU): **M2_INTEGRATION.md**.

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

## Known issues / next

- Sunset horizon reads a bit lemon-yellow (M1 was more orange): tune golden-hour WB/saturation and the sky's golden grade.
- (fixed by the water module) Noon water read lighter/cyan in HDR with M1's water shader.
- Stars show through the semi-transparent vanilla clouds at night (clouds alpha); fixed by the M3 cloud system or by
  dimming stars by cloud coverage.
- DH LODs keep DH's own lighting/fog (bridge); FT2 should redirect `distantHorizons:TerrainRenderer` into HDR with a shade
  override and provide a merged scene-distance input (haze/VL currently treat DH pixels as sky: depth 0).
- Bloom/TAAU order: with a resolver, bloom is built from the resolved (output-res) image; the bible's internal-res L0 is
  cheaper — revisit when TAAU lands.
- Translucent surfaces that don't write depth get the haze of what is behind them (final-pass haze).
- The HDR world target defaults to RG11B10Float for the performance/balanced presets (no visible banding at 2.5x contrast in the sunset sky and night gradients, `m2b9-rg11` vs `m2b9-onb`); RGBA16Float for quality/ultra. Modules must not rely on destination alpha in it.
- Not done from the bible in this milestone: LUT sky (M2b), material atlases (M2c), GTAO, XDR output.
