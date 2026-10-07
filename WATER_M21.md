# M2.1 water fixes (branch `m2.1` from `m2` e95f339)

Gael's feedback on M2 (2026-10-07): the water looks like **clay ("plastilina") and choppy ("entrecortada")**; our
reflections are a bit softer than Complementary's (fine), but they **get cut off toward the sides of the screen** while
Complementary's reach the edges. Constraint: keep only what is cheap (budget for all water changes: <= +0.3 ms GPU at the
balanced preset in the water scenes).

Release: `work/release/mcopt-shade-M2.1.jar` (SHA-256 `396b71bd6c3d00e8fbba9ddfb9b5f9207bba8b6b2f6f25947ac8d90d1dba178c`,
code commit `9c834b3`), notes `work/release/M2.1_NOTES.md`, images `work/release/M2.1-screenshots/`.

All changes are in the water module + three flags (all default on; `mcopt.shade=false` and `mcopt.shade.water=false` are
untouched):

| Flag | Default | What |
|---|---|---|
| `mcopt.shade.waterDetail` | true | Fine ripples (their own baked layer, faster), pixel-grid ripples + faint pixel "texture" near the camera, calmer long waves, small refraction shift, fine ripples on the surface seen from below too. `false` = M2's surface. |
| `mcopt.shade.ssrEdge` | true | Reflections reach the left/right screen edges; far-end fade by distance. `false` = M2's SSR edges. |
| `mcopt.shade.waterClock` | true | Waves (and foam, caustics, flow) run on a real-time clock (`ShadeFrame.sunPath.w`). `false` = game time (M2). |

Dev knobs (pricing/tuning only): `mcopt.shade.waterDetailSize` (256 | 512, ripple bake size), `waterDetailExtras` (bake
the 7 extra waves into the ripple layer too), `waterDetailFar` (96: no ripple sample beyond, blocks), `waterPatternFar`
(0.075: footprint in blocks/pixel where the pixel pattern ends).

## Diagnosis (evidence)

Runs: `bench-results/20261007-045718-w21-m2-rec` (M2 jar, water shots + a 1512x982 recording), Complementary references
`bench-results/20261006-220207-wref-iris` (same poses).

1. **Clay look.** In `waterFull` the vanilla water texture is not used at all, and the normal comes from one sample of the
   baked field whose energy sits in the long waves (8.4, 5.9, 3.7 blocks) at 8 texels/block (balanced: 512^2 over 64 blocks):
   the surface is a few large, smooth undulations with no fine structure. The refraction shift was `0.4 x depth (<= 6)` x the
   tilt of those long waves, so every underwater block edge bent into big wobbling curves (jelly), see
   `M2.1-screenshots/diag_noon_crop_m2_vs_complementary.jpg` (top M2, bottom Complementary: straight block edges, a fine
   pixel-art texture).
2. **Choppy.** The waves ran on `gameTimeSeconds = (level.getGameTime() % 72000 + partialTick) / 20`. The client game time
   is tick-based and `ClientLevel.setTimeFromServer` overwrites it with the server's time every second
   (`ClientboundSetTimePacket`): when the integrated server falls behind (chunk generation, autosave) the water stalls,
   jumps or runs backwards. The bench world (no generation during the shots) shows no such stall in the M2 recording, so
   this is a code-level diagnosis. A second cause of a "slow-motion gel" feel: the first fine-ripple version sampled the
   long-wave texture 3.3x smaller, so its ripples crawled 3.3x slower in world space (deep-water waves s times shorter
   should move sqrt(s) times faster).
3. **Reflections cut at the sides.** Three things in `shadeSsrMarch` / `shadeWaterSkyRefl`:
   - the reflection segment was clipped at the left/right screen edges; a ray leaving the side found nothing and fell back
     to the sky LUT (the trees on the left of `water_shore_noon` mirror as sky blue near the left edge);
   - every hit faded over 7 % of the screen at each side (`smoothstep(0, 0.07, min(uv, 1 - uv))`);
   - the far-end fade used the screen-space parameter (`1 - smoothstep(0.75, 1, hi)`): with 512-block rays the screen
     segment is crushed toward its far end, so a shore 100-200 blocks away lost 35-65 % of its reflection. That is why the
     left shore at sunset hardly mirrored (`water_sunset`).

## Fixes (final)

- **Real-time clock** (`ShadeFrameUpdater`: `waterClock += min(dt, 0.25)`, wrapped at 4096 s, packed into
  `ShadeFrame.sunPath.w`; water.metal reads `shadeWaterTime(f)` everywhere it used `f.time.y`). The wrap is seamless:
  every angular speed is rounded to a whole number of turns per 4096 s (`shadeWaterOmega`, < 0.1 % change) and the flow
  drift is 1.40625 blocks/s (5760 blocks = 90 wave tiles per wrap).
- **Fine ripples**: a second bake pass per frame (`shade_water_bake_detail_fs`, texture(22), 256^2 RGBA16F + mips, the 7
  main waves only) runs the same wave set 1.82x faster (= sqrt(3.3)) with a phase offset. It is sampled once per water
  pixel in the terrain fragment (`shadeWaterDetailIn` -> `ShadeWaterIn.detail`), 3.3x smaller and turned 53 degrees (slope
  `s R^T grad h`), no anisotropy (the long-wave sample keeps its 8x), long waves x0.6, detail height x0.3. No sample beyond
  96 blocks (faded from 58; the wave strength is ~0.35 there and the mips have averaged the ripples away).
  Near the camera (footprint < 0.03-0.065 blocks/pixel) the sample point is pulled 70 % toward the centre of its 1/16-block
  cell: pixel-grid ripples like the block texture, but every cell changes smoothly with time.
- **Pixel texture**: the same sample's Laplacian (crest-ness) lightens crests / darkens troughs by 13 % and turns the
  sharpest cells into light specks (+35 % in the water's own hue, from 0.9 sd), out to a footprint of 0.075 (ends with the
  cell snap; see "lattice" below).
- **From below**: `shadeWaterBelow` uses the ripples at 0.6x; the terrain fragment computes them for the underside
  (`n.y < -0.5` with the camera under water). Snell's-window edge is now finely broken (pixel steps) instead of large blobs.
- **Refraction**: shift `0.16 x depth (<= 4)` instead of `0.4 x depth (<= 6)`: underwater block edges stay straight.
- **SSR to the sides**: the segment only ends at the top/bottom edges (sides at NDC +-2); past a side the march reads the
  clamped edge column, which stands in for what is just off screen; hits have no side fade (only top). Past a side the hit
  weight falls to 0.5 at NDC +-2 (`1 - 0.5 * smoothstep(0, 1, |x| - 1)`), and a hit where the edge column is much NEARER
  than the ray (a trunk or shore block touching the edge) is faded out (`saturate((wh / wr - 0.5) * 4)`), so near objects
  don't smear sideways or flicker as they enter the edge. The far-end fade uses the hit's view depth vs the ray end's. The
  rendered-sky fallback clamps sideways too, halving by NDC +-2.

## Tried / rejected

| Idea | Result |
|---|---|
| Detail sample with its own per-pixel time drift (`+ dir * t`) | Crashed the Metal compiler service (XPC_ERROR_CONNECTION_INTERRUPTED) on `hdr_translucent`. Replaced by the faster baked ripple layer (no per-pixel time math). |
| Detail sample inside `shadeWaterNormal` (any form) | Same compiler crash. Computed in the terrain fragment and passed in (`ShadeWaterIn.detail`): compiles. |
| Separate `noinline` detail helper (m21c/m21d) | +0.4-0.5 ms spin: the call (register spills) cost more than the samples. |
| Pixel pattern from the cell's *height* | Large lilac blotches and pink specks: replaced by the Laplacian and hue-preserving specks. |
| Ripple layer at 512^2 with all 14 waves (m21h) | +0.40 ms spin (w22f, w22b): over budget. 256^2 with the 7 main waves (m21i) +0.29; plus far cutoff 96 (final) +0.22-0.28. |
| Ripple far cutoff 140 blocks | ~0.06 ms more than 96 (w24) for no visible difference. |
| Pixel pattern out to footprint 0.12 (reviewer suggested widening) | Past the cell snap the unsnapped Laplacian of the two shortest plane waves reads as a regular diamond lattice on mid-distance water seen from a hill (`noon_overview`, `motion_overview_strafe` of fin21-bal). Ends at 0.075 now; only a much fainter regular texture remains in the reflections. |
| SSR sides at NDC +-3 | No measurable difference vs +-2; kept +-2 (denser steps for side rays). |
| Use the vanilla water texel (as Complementary does) | Not done: the atlas animates it at 10 fps (frametime 2, no interpolation): exactly the "choppy" steps. |

## Cost (GPU ms/frame, 3024x1964 fullscreen, balanced, `-Dmcopt.shade.drs=false` = fixed 0.80 scale, perf route)

"spin" = the bay view (water ~half the screen, SSR on the shore): the water worst case. All runs with `mcbench-run
--direct` (offline, no Prism/Microsoft login). The Mac was on **battery** for all final runs (Gael unplugged it); runs
contaminated by other GPU use (Claude app, wake from lid close: 10-17 ms in both configs) are left out and listed.

Final ABBA (`*-fin21q-*`, final jar, same session): M2 8.79 (first run, cold), 9.15, 9.15, 9.10; M2.1 9.37, 9.37, 9.39, 9.27.
Blocks: +0.40, +0.21 -> **+0.30 ms**; without the cold first run **+0.22 ms**. Flight: M2 8.67, M2.1 8.78 (+0.11).
FPS at fixed scale, spin: 110.7 -> 107.0.

Pooled with the same code earlier (`w24` f96 config, `fin21p` j; all clean M2 runs of w23/w24/fin21p/fin21q): M2 mean 9.08
(n 10), M2.1 mean 9.33 (n 9): **+0.25 ms** (median +0.24). Flight about +0.05 to +0.11.

| Config (spin GPU ms, clean runs) | Runs | Mean |
|---|---|---|
| M2 (release jar) | w23 9.14 9.00 9.15; w24 9.18 9.07; fin21p 8.97 8.93; fin21q 9.15 9.15 9.10 | 9.08 |
| M2.1 final (= f96/j/fin) | w24 9.36 9.31 9.39 9.19; fin21p 9.35; fin21q 9.37 9.37 9.39 9.27 | 9.33 |
| m21i (ripple far 140) | w23 9.32 9.61 9.33; w24 9.37 9.38 | 9.40 |
| m21i, `waterDetail=false` | w23 9.21 | (side fix + clock ~+0.13) |
| m21h (512^2 + 14 waves) | w22f 9.47 9.45 9.45 9.57 vs M2 8.98 9.11 9.14 | +0.40 |

Contaminated, left out: w23-i-2 (11.31), w24 runs 7, 8, 10, 12 (10.1-11.2, both configs), fin21p runs 3, 4, 6, 8
(9.65-10.17), w22c (lid closed / wake).

Other presets / DRS (same session as fin21p):
- **performance** (DRS on): M2 139.5 fps / 7.17 ms (a second M2 run was contaminated: 132.4, 1 % low 53); M2.1 137.8 and
  138.0 fps / 7.25 ms: **+0.08 ms, -1.5 fps**.
- **balanced with DRS on** (the real default): M2 116.5 / 116.3 fps (8.59 ms), M2.1 133.6 / 136.0 fps (7.42 ms). The
  controller has a dead band: M2 sat at ~116 fps without stepping down, the slightly heavier M2.1 fell below the band and
  stepped to a lower scale. So M2.1 is not faster; it trades a bit of resolution in that view. The full shot run
  `fin21-bal` (DRS on): 125 / 122 fps.

## In-game verification and images

- Final shots: `bench-results/20261007-103317-fin21-k` (water + standard + overview motion shots; code = final except one
  comment), `20261007-101029-fin21-bal` (pattern band 0.12, otherwise final: all 18 shots incl. god rays and motion; every
  non-water scene matches M2's `20261007-020438-fin-bal`), recording `20261007-101336-fin21-rec` (`scratchpad rec/m21j.mp4`,
  1512x982): no frozen runs, 2-frame/1-frame change ratio 1.6-1.8 (M2: 1.70), consecutive frames progress smoothly; the
  per-frame change follows the recorder's own capture jitter (13-35 ms), not the shader.
- `work/release/M2.1-screenshots/` (M2 = fin-bal, M2.1 = fin21-k, Complementary = wref-iris): `M2_vs_M2.1_vs_Complementary.jpg`
  (5 water scenes) and one file per scene, `reflections_left_edge_*` / `reflections_right_edge_*` (the side cut-off fixed),
  `water_texture_closeup_*` (native pixels: shallows, shore, Snell's-window edge), `diag_noon_crop_m2_vs_complementary.jpg`
  (the diagnosis).

## Known issues / next

- Mirrored clouds at sunset are softer than M2's but still flat patches (sky fallback from last frame).
- Past a side edge the reflection is the edge column: for things far off screen it is an approximation.
- Distant water has no pixel glitter road like Complementary's (the pixel pattern stops at 0.075 blocks/pixel to avoid
  shimmer and the lattice). A faint regular ripple texture remains on mid-distance water seen from high up.
- Snell's-window edge from below is now made of small pixel steps (the 1/16-block cells), not smooth curves.
- With DRS on, balanced may sit at a lower render scale in heavy water views than M2 did (see Cost).
