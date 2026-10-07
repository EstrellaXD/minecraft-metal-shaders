# M2.1 water fixes (branch `m2.1` from `m2` e95f339)

Gael's feedback on M2 (2026-10-07): the water looks like **clay ("plastilina") and choppy ("entrecortada")**; our
reflections are a bit softer than Complementary's (fine), but they **get cut off toward the sides of the screen** while
Complementary's reach the edges. Constraint: keep only what is cheap (budget for all water changes: <= +0.3 ms GPU at the
balanced preset in the water scenes).

All changes are in `water.metal` + three flags (all default on; `mcopt.shade=false` and `mcopt.shade.water=false` are
untouched):

| Flag | Default | What |
|---|---|---|
| `mcopt.shade.waterDetail` | true | Fine ripples (second, smaller, rotated sample of the baked waves), pixel-grid ripples near the camera, faint pixel "texture", calmer long waves, small refraction shift. `false` = M2's surface. |
| `mcopt.shade.ssrEdge` | true | Reflections reach the left/right screen edges; far-end fade by distance. `false` = M2's SSR edges. |
| `mcopt.shade.waterClock` | true | Waves (and foam, caustics, flow) run on a real-time clock (`ShadeFrame.sunPath.w`). `false` = game time (M2). |

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
   this is a code-level diagnosis; with the real-time clock the M2.1 recording has no frozen runs and an even frame-to-frame
   change (`work/.../scratchpad/rec/freeze.py`).
3. **Reflections cut at the sides.** Three things in `shadeSsrMarch` / `shadeWaterSkyRefl`:
   - the reflection segment was clipped at the left/right screen edges; a ray leaving the side found nothing and fell back
     to the sky LUT (the trees on the left of `water_shore_noon` mirror as sky blue near the left edge);
   - every hit faded over 7 % of the screen at each side (`smoothstep(0, 0.07, min(uv, 1 - uv))`);
   - the far-end fade used the screen-space parameter (`1 - smoothstep(0.75, 1, hi)`): with 512-block rays the screen
     segment is crushed toward its far end, so a shore 100-200 blocks away lost 35-65 % of its reflection. That is why the
     left shore at sunset hardly mirrored (`water_sunset`).

## Fixes

- **Real-time clock** (`ShadeFrameUpdater`: `waterClock += min(dt, 0.25)`, wrapped at 4096 s, packed into
  `ShadeFrame.sunPath.w`; water.metal reads `shadeWaterTime(f)` everywhere it used `f.time.y`).
- **Fine ripples** (`shadeWaterDetail`): the baked field sampled a second time, 3.3x smaller and turned 53 degrees
  (slope `s R^T grad h`), long waves x0.6, detail height x0.3 (slopes ~1x the long waves'), with mips + 2x anisotropy.
  One sample per top-face water pixel, taken in the terrain fragment (`shadeWaterDetailIn` -> `ShadeWaterIn.detail`).
  Near the camera (footprint < 0.03-0.065 blocks/pixel) the sample point is pulled 70 % toward the centre of its 1/16-block
  cell: pixel-grid ripples like the block texture, but every cell changes smoothly with time.
- **Pixel texture**: the same sample's Laplacian (crest-ness, dominated by the shortest waves) lightens crests and darkens
  troughs by 6 % and turns the sharpest cells into light specks (+35 % in the water's own hue), near the camera only.
- **Refraction**: shift `0.16 x depth (<= 4)` instead of `0.4 x depth (<= 6)`: underwater block edges stay straight.
- **SSR to the sides**: the segment only ends at the top/bottom edges (sides at NDC +-2); past a side the march reads the
  clamped edge column, which stands in for what is just off screen; hits have no side fade (only top), weight x0.5 at most
  for hits far past the side; the far-end fade uses the hit's view depth vs the ray end's. The rendered-sky fallback also
  clamps sideways instead of fading.
- The surface seen from below keeps M2's normal (no detail), see the compiler note.

## Tried / rejected

| Idea | Result |
|---|---|
| Detail sample with its own time drift (`+ dir * t`) | Crashed the Metal compiler service (XPC_ERROR_CONNECTION_INTERRUPTED) on `hdr_translucent`; the bake's own wave motion is enough at 3.3x. |
| Detail inlined (any form: drift, pixel branch, single sample) | Same compiler crash on the HDR translucent pipeline. `__attribute__((noinline))` on `shadeWaterDetail` compiles (validate.sh all OK). |
| Pixel pattern from the cell's *height* | Large lilac blotches (height is dominated by the swell, several blocks wide) and `lit`-coloured specks turned pink on blue: replaced by the Laplacian and hue-preserving multiplicative specks. |
| Two detail samples (continuous + cell-snapped, 8x aniso), noinline helper (m21c) | +0.4-0.5 ms spin (wp2): over budget. |
| One snapped sample, 4x aniso, still a noinline helper (m21d) | +0.5 ms spin (wp3, vs detail off +0.15): the cost was the call (register spills), not the samples. |
| One sample computed in the terrain fragment and passed in `ShadeWaterIn.detail` (m21e, kept) | compiles inline; +0.33 ms spin (wp4); with 2x aniso and side extent 2 (m21f, final) +0.2-0.3. |
| SSR sides at NDC +-3 | no measurable difference vs +-2 (wp5 `noedge` = m21f within noise); kept +-2 (denser steps for side rays). |
| Use the vanilla water texel (as Complementary does) | Not done: the atlas animates it at 10 fps (frametime 2, no interpolation): exactly the "choppy" steps; the other frames live in separate textures. |

## Cost (GPU ms/frame, 3024x1964 fullscreen, balanced preset, `-Dmcopt.shade.drs=false` = fixed 0.80 scale, perf route, same session, interleaved)

The perf "spin" is the bay view (water ~half the screen, SSR on the shore): the water worst case. Runs in
`bench-results/*-wp2-*` .. `*-wp5-*` (label = config, suffix = round). Runs right after a failed Prism launch (Microsoft
login rate limit, HTTP 429) were ~0.4 ms faster in every config and are left out.

| Config | spin GPU ms (runs) | flight GPU ms (runs) |
|---|---|---|
| M2 (release jar) | 9.16, 9.32, 9.33 (wp4); 8.98, 9.35, 9.35 (wp5): **mean 9.25, median 9.33** | 8.80, 8.85, 8.82; 8.94, 8.93, 8.98: **8.89** |
| M2.1 m21e (detail in the fragment, 4x aniso) | 9.59, 9.61 (wp4) | 8.96, 8.95 |
| M2.1 m21f (final: 2x aniso, sides +-2) | 9.54, 9.55 (wp5) | 8.92, 8.99 |
| m21e with `waterDetail=false` | 9.40, 9.45 (wp4) | 8.89, 9.02 |
| m21f with `ssrEdge=false` | 9.56, 9.52 (wp5) | 9.09, 8.92 |
| (earlier) m21c / m21d (noinline helper) | 9.92, 9.80 / 9.77, 9.78 | 9.13, 9.10 / 9.08, 9.03 |

**M2.1 vs M2: about +0.25 ms in the bay view (m21e/f mean 9.57 vs M2 9.25 mean / 9.33 median: +0.24-0.32) and +0.05 ms
on the flight route**, at the fixed 0.80 scale. Split (noisy, +-0.1): fine ripples ~0.15, SSR to the sides ~0-0.1, the
clock and the refraction change nothing. With dynamic resolution on (the real balanced preset) the extra cost is absorbed
by the resolution controller (wp1, the heavier m21c: spin 125 fps vs 130-137; the final version is ~half that cost).

## In-game verification and images

- Shots: `bench-results/20261007-051210-w21-a` (first version), `*-051458-w21-b` (+ recording `scratchpad rec/m21b.mp4`),
  `*-051659-w21-c` (two detail samples, Laplacian specks: the look of the final version). The final code (commit
  caac611 = jar `m21f/m21g`, built to `metal/build/libs/metal-0.2.0-alpha.3.jar`) ran in game in the perf matrices wp4/wp5 (shader compiled at runtime, no errors), but its
  screenshots could not be taken: from ~06:30 every Prism launch failed (Microsoft login `api.minecraftservices.com/launcher/login`
  HTTP 429, then 403, after ~25 launches in 2 h). Visual difference to w21-c: one detail sample whose point is pulled
  70 % to the cell centre (c: two samples blended 55 %), 2x instead of 8x anisotropy. **Re-shoot the water scenes with the
  final jar before shipping** (`--jvm "-Dmcopt.shade=true -Dmcbench.water=true -Dmcbench.standardShots=false" --scenes shots`).
- `work/release/M2.1-screenshots/`: `M2_vs_M2.1_vs_Complementary.jpg` (5 water scenes; M2.1 = w21-c), one file per
  scene, `reflections_left_edge_M2_vs_M2.1_vs_Complementary.jpg` (the side cut-off: shore and tree reflections now reach
  the left edge like Complementary's), `diag_noon_crop_m2_vs_complementary.jpg` (the diagnosis: jelly refraction, no texture).
- Animation: recordings (1512x982, 60 fps) of M2 and M2.1 b; frame-to-frame change per ms is even in both (the bench
  world's server never lags, so the game-time stalls of real play do not show there); no frozen runs in M2.1.

## Known issues / next

- The surface seen from below keeps M2's long-wave normal (detail only on top faces).
- Mirrored clouds at sunset are still flat peach patches (sky fallback from last frame), unchanged from M2.
- Past a side edge the reflection is the edge column: for things far off screen it is an approximation (weight x0.5 at
  most when the ray went a whole screen width past the side).
- Distant water is smooth (no pixel glitter road like Complementary's): the pixel grid fades out beyond ~0.065
  blocks/pixel to avoid shimmer.
