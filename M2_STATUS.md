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

## Known issues / next

- Sunset horizon reads a bit lemon-yellow (M1 was more orange): tune golden-hour WB/saturation and the sky's golden grade.
- Noon water reads lighter/cyan in HDR (M1 water shader untouched; the water module replaces it).
- Stars show through the semi-transparent vanilla clouds at night (clouds alpha); fixed by the M3 cloud system or by
  dimming stars by cloud coverage.
- DH LODs keep DH's own lighting/fog (bridge); FT2 should redirect `distantHorizons:TerrainRenderer` into HDR with a shade
  override and provide a merged scene-distance input (haze/VL currently treat DH pixels as sky: depth 0).
- Bloom/TAAU order: with a resolver, bloom is built from the resolved (output-res) image; the bible's internal-res L0 is
  cheaper — revisit when TAAU lands.
- Translucent surfaces that don't write depth get the haze of what is behind them (final-pass haze).
- The HDR world target defaults to RG11B10Float for the performance/balanced presets (no visible banding at 2.5x contrast in the sunset sky and night gradients, `m2b9-rg11` vs `m2b9-onb`); RGBA16Float for quality/ultra. Modules must not rely on destination alpha in it.
- Not done from the bible in this milestone: LUT sky (M2b), material atlases (M2c), GTAO, XDR output.
