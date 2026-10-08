# M2.2 — god rays that no longer flicker (branch `vl-stable`, from `m2.1`)

Report (Gael, 2026-10-07): the god rays "titilan, tambalean" in the forest-morning trunk scene and the next one
(`vl_forest_morning`, `vl_overhang`), especially at the corners/edges of the leaves, and under water; seen in the test
windows since M2.

## How it was measured

Static-camera recordings with mcopt's recorder (`-Dmcopt.rec=…`, 1512x982, 80 Mbps, 60 fps) of five fixed poses held for
~8 s each (mcbench `extraShots`): **fm** forest trunk, **fm2** second forest view (canopy against the sky, sun behind the
trunk), **fs** forest side view, **ov** overview, **uw** under water looking up. Preset balanced (DRS on unless noted).
Metric per hold, last 4 s, 8-bit luma: **q** = mean over pixels of the 75th percentile over time of |Y_t − Y_t−1| (flicker
present on most frames counts, a passing bird/particle does not). Scripts: the session scratchpad `vls/` (rec.sh,
runs.sh, flick2.py, flick3.py, qmap.py, frame.py, crop.py, sbs.py, vlgpu.py); copies in `work/notes/` (see the end).

Reference without god rays (`mcopt.shade.vl=false`, M2.1): fm 0.53, fm2 0.67, fs 0.44, ov 0.15, uw 0.38 — the floor
(clouds, leaves, water surface).

## Bisect

| build | fm | fm2 | uw |
|---|---|---|---|
| `d9e2396` VL integrated, before TAAU | 0.51 | 0.52 | 1.43 |
| `90c6f5c` after TAAU | 0.94 | 0.99 | 1.26 |
| M2 release (`e95f339`, after the review round) | 1.24 | 2.26 | 1.39 |
| M2.1 release | 1.33 | 2.22 | 1.43 |
| M2.1 with `mcopt.shade.taa=false` | 0.87 | 1.77 | 1.63 |

TAAU made it a bit worse; the M2 **review round** (`8a1d16c`: the canopy mist's own near march, carve 0.6, canopy gate
x1.5, the 5-tap plus filter, dark sky rays) made it much worse, with or without TAAU. M2.1's water changes did not.

## Causes (largest first) and fixes

The march jitters its step offset **once per frame for the whole screen** (R1 sequence + a quarter-step IGN spread): that
keeps neighbouring rays' shadow fetches in cache (full per-pixel jitter was 2–3x slower on the main march). But the
temporal pass clamps the history to the 3x3/plus neighbourhood of *this frame*, and with a shared offset the whole
neighbourhood rises and falls together, so the clamp box moves with the noise and drags the history onto it.

1. **Canopy mist near march (review round)** — 6–12 linear steps over the first ~2.5 ranges with the shared offset, then the
   carve `max(lit − 0.6·all, 0) / 0.4` (2.5x gain on the noise). The beams under the trees *and the veil over the sky
   between the leaves* flickered (fm2's whole top-left sky). **Fix `mcopt.shade.vl.nearDither`**: each texel its own offset
   (IGN + R1) for these steps; the spatial filter and the clamp now see the noise and the history averages it. Its steps
   stay within ~20 blocks, but per texel they lose some cache coherence: +0.07–0.13 ms of march in the forest views (the
   biggest cost of M2.2, and its biggest gain). fm2 1.27 → 0.92; with it off (vls11) fm2 0.74 → 1.10.
2. **History clamp too tight** (review round's plus) and blind to the shared jitter. **`mcopt.shade.vl.box3`** (box over
   the 3x3 again) and **`mcopt.shade.vl.still`** (0.6: on a still view the box widens by 0.6 x its mean; under motion the
   plain box, no ghosting). Without `still` under water: 1.01 → 1.28. box3 costs +0.02 ms (temporal), fm 0.62 → 0.52.
3. **History fetched with one bilinear sample** — at a leaf edge it mixed the leaf's and the sky's distance into one that
   matched neither, so the texel lost its history and showed the raw march; under TAAU the jitter moves leaf edges by a
   sub-pixel every frame, so the texels along them alternated. **`mcopt.shade.vl.histDepth`**: the footprint's texels at
   this point's distance only (bilinear weights among them); one gather of the 4 distances first, and when all match (most
   texels) a single hardware-filtered sample. fm2 1.50 → 1.31 (vls11: 0.85 → 0.74 at fixed scale); +0.015 ms.
4. **Screen-space (crepuscular) taps** with the shared offset and point reads of the sky mask. **`mcopt.shade.vl.crepSmooth`**:
   per-texel offset, bilinear mask. **Off by default**: +0.03–0.05 ms and no measurable gain once `nearDither` was in (the
   sky's flicker came from the canopy mist, not from these taps).
5. **Under water march** with the shared offset. **`mcopt.shade.vl.waterDither`**: per-texel offset. uw 0.95 → 0.82, cost
   unchanged.
6. **Caustics on the game clock** (jumped when the server corrected the client's ticks): now on the water's real-time clock
   with a seamless 4096 s wrap (`ShadeFrame.waterTime`, M2.1's `mcopt.shade.waterClock`).
7. **The final pass ignored the TAAU jitter** — depth and the VL textures are this frame's jittered raster, but the final
   pass read them at the output uv, so the light (and its depth tests at the leaves) moved by up to half a render pixel
   every frame against the steady resolved image. **`mcopt.shade.vl.unjitter`**: read them at `uv + jitter.xy / 2` (the
   raster position of the pixel's scene point). Small gain at fixed scale (fm2 0.78 → 0.73–0.75), no cost.
8. *(removed)* The previous session's "edge blend" in the final pass (light of a depth edge's near and far surface blended
   by 2x2 depth coverage): no measurable change in q (vls5 vs vls5noedge 1.50 / 1.51) and +0.1 ms spinning / **+0.4 ms
   flying over forests** at full resolution (batch J). Its code is gone (6434a16); even switched off it seemed to weigh on
   the final pass (see Cost).

Tried and dropped: folding the light before the TAAU resolve (exposure and bloom saw it: shafts 60 % weaker); `still` 2
(no gain); `nearSteps=0` (stable, 1.07, but loses the separate beams the review round added); per-texel offsets for the
main march too (`mainDither`, vls11: +0.15–0.2 ms from cache misses of the far steps, as the cloudscapes paper predicts,
and no measurable gain: fm2 0.75 vs 0.74).

## What other packs and games do (studied 2026-10-07; techniques only, no code copied)

| | where the light is integrated | step offset | steps | temporal | upsample |
|---|---|---|---|---|---|
| Photon MIT `26c2906e` (`program/c0_vl.fsh`, `include/fog/air_fog_vl.glsl`) | half res, blended **before** TAA (`c1_blend_layers`) | 512² blue noise **per pixel** + R1 per frame | 8–25 linear, point shadow fetch | the main TAA | smooth (quintic) bilinear, not depth-aware |
| Photon today (study only) | same; crepuscular rays marched through the *cloud shadow map* (smooth, low-frequency) inside the ¼-res checkerboard cloud pass | blue noise + R1 | — | cloud temporal upscaling | — |
| Complementary r5.9.3 (study only, `volumetricLight.glsl`) | full res, before TAA | 128² blue noise **per pixel** + golden ratio per frame | 6/10/15/30 near samples, t² spacing (+50 % under canopies), point shadow fetch | the main TAA | — |
| BSL 10.1.8 (study only) | full res | blue noise per pixel | 7 | 4-tap blur + TAA | — |
| Spectrum (MIT) | full res | per-pixel dither | linear | TAA | — |

Every pack offsets each pixel's steps by its own noise and lets a TAA average it; none uses one offset for the whole
screen. The literature says why: "if an identical random offset is applied to every ray per frame the performance hit
does not occur, however TAA is unable to improve the appearance" (Optimisations for Real-Time Volumetric Cloudscapes,
arXiv 1609.05344: half res, 8 steps, 2.3 ms → 7.5 ms with per-pixel jitter, from texture cache misses). That is exactly
our trade-off: the main march (long range, quadratic steps) keeps the shared offset for its cache; the cheap parts (the
canopy mist's own steps within ~20 blocks, the water march, the screen-space taps over a small mask) got per-texel offsets.
Our light is folded in *after* the TAAU resolve (no full-resolution read-modify-write), so unlike the packs it needs its
own temporal pass, and the final pass must undo the frame's jitter (`unjitter`) where the packs get it for free.
Further ideas noted: min/max checkerboard depth for the VL texels (Black Ops 3's quarter-res volumetrics), variance
clipping of the history (froxel TAA thesis, Godot/Frostbite), Photon's cloud-shadow-map sky rays.

## Results (final ABBA, batch K: same session, balanced, DRS on — how Gael plays)

| build | fm | fm2 | fs | ov | uw |
|---|---|---|---|---|---|
| M2.1 (k-m21, k-m21b) | 1.24 / 1.25 | 2.34 / 2.34 | 0.61 / 0.65 | 0.31 / 0.33 | 1.32 / 1.63 |
| M2.2 (vls13, k-v13, k-v13b) | **0.70 / 0.69** | **0.86 / 0.90** | **0.54 / 0.54** | **0.20 / 0.20** | **0.82 / 0.83** |
| no god rays (floor, old session) | 0.53 | 0.67 | 0.44 | 0.15 | 0.38 |

At fixed scale 0.80 M2.2 is steadier still (fm 0.52–0.65, fm2 0.73–0.77); at fixed 0.67 like DRS (fm2 0.86). In the forest
holds DRS sits at ~0.64 without changing, so the extra is the lower resolution, not scale changes. vls14 (head) renders the
same images as vls13 (only dead code removed). All runs: `work/notes/vl-stable-2026-10-07/results/RESULTS_flicker.md`.

Beam brightness in fm2's shaft region: M2.1 146–147, M2.2 148 (8-bit mean); mid-scale structure (2–20 px band) 25.0 vs
25.4: same strength and sharpness. Side-by-side clip: `work/release/M2.2-screenshots/comparacion_M2.1_vs_M2.2.mp4`.

## Cost

Clean whole-frame ABBA x2 (batch M, balanced fixed 0.80, GPU ms/frame): M2.1 spin 9.21 / flight 8.69; vls13 9.34 /
8.84 → **+0.12 / +0.15 ms** (~1.5–2 fps at ~110 fps). Performance preset (batch K): **+0.05 / +0.09**. Of that, the VL
passes are only +0.04 (march +0.01, temporal +0.03 in these scenes); the rest was in the full-resolution final pass, most
likely the edge-blend code compiled in even though switched off (register pressure). **vls14 (6434a16) removes that code:
batch P (AC, nothing else open, M2.1/vls13/vls14 ABCCBA x2, balanced): M2.1 9.117 / 8.633, vls13 9.322 / 8.794 (+0.21 /
+0.16), vls14 9.158 / 8.689 = +0.04 / +0.06 ms** (VL passes +0.035; VL module off: equal; every M2.2 flag off: equal). On
battery with Safari/audio busy (batch O) the same jars measured +1.1 / +0.8 (balanced) and +1.4 (performance) although the VL
passes only added +0.05-0.09: an environment effect, open question. In the forest shot holds the VL
passes cost +0.17 ms (nearDither +0.1, temporal +0.035). Per flag and all runs:
`work/notes/vl-stable-2026-10-07/results/RESULTS_cost.md`.

## Flags (false = M2.1 behaviour)

Default on: `mcopt.shade.vl.still` (0.6; 0 = off), `box3`, `histDepth`, `nearDither`, `waterDither`, `unjitter`. Default
off (option): `crepSmooth`. Packed as bits in `VlParams.sunPx.w` (1 box3, 2 histDepth, 4 crepSmooth, 8 nearDither,
16 waterDither); `unjitter` is bit 2 of `ShadeFinalParams.r.w` (bit 1 was the removed edge blend).

## Commits (merge `vl-stable` into `m3` before packaging M3)

`2240ba9` WIP (fold experiment, still, box3, water-clock caustics) · `c4ea475` drop the fold, keep still/box3/caustics ·
`40798c3` edge blend (later removed) · `17d83f0` histDepth, crepSmooth, nearDither · `d335c8f` waterDither · `84f5901`
unjitter, history fast path, edge and crepSmooth off · `6434a16` edge-blend code removed (head; jar = vls14). Touches only
`vl.metal`, `shade_post.metal`, `ShadeVl.java`, `VlConfig.java`, `ShadePost.java`.

## Known issues

- fm2 is still ~0.2 above the floor (residual shimmer in the shaft right of the trunk). The main march keeps the shared
  offset; per-texel there cost +0.15–0.2 ms with no measurable gain. Next ideas if Gael still sees it: min/max
  checkerboard depth for the VL texels, variance clipping, fewer/more near steps, VL resolution relative to the output
  instead of the render scale (at DRS 0.64 the VL buffer is only ~0.21 of the output).
- The metric counts intended motion too (the caustic curtains sway under water, clouds drift).
