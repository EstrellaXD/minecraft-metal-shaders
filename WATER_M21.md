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
  (slope `s R^T grad h`), long waves x0.6, detail height x0.3 (slopes ~1x the long waves'), with mips + 4x anisotropy.
  Near the camera (footprint < 0.03-0.065 blocks/pixel) the sample point is pulled 70 % toward the centre of its 1/16-block
  cell: pixel-grid ripples like the block texture, but every cell changes smoothly with time.
- **Pixel texture**: the same sample's Laplacian (crest-ness, dominated by the shortest waves) lightens crests and darkens
  troughs by 6 % and turns the sharpest cells into light specks (+35 % in the water's own hue), near the camera only.
- **Refraction**: shift `0.16 x depth (<= 4)` instead of `0.4 x depth (<= 6)`: underwater block edges stay straight.
- **SSR to the sides**: the segment only ends at the top/bottom edges (sides at NDC +-3); past a side the march reads the
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
| Two detail samples (continuous + cell-snapped, 8x aniso) | +0.4 ms GPU at the balanced scale in the bay view (wp2): over budget. Replaced by one sample with a partly snapped coordinate and 4x aniso. |
| Use the vanilla water texel (as Complementary does) | Not done: the atlas animates it at 10 fps (frametime 2, no interpolation): exactly the "choppy" steps; the other frames live in separate textures. |

## Cost (GPU ms/frame, 3024x1964 fullscreen, balanced preset, `-Dmcopt.shade.drs=false` = fixed 0.80 scale, perf route, same session, interleaved)

(filled in below)
