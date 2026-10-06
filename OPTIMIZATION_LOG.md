# M1 optimization log (native shading, branch `m1`)

Everything here was measured in game. Setup:
- Runner: `work/bin/mcbench-run`, instance "mcopt DEV".
- Display: 3024x1964 exclusive fullscreen on the M4 14".
- Route: bench route (spin + flight), RD 16. Metrics come from results.json.
  - "GPU ms" is `gpu.{spin,flight}.gpuMsPerFrame`.
  - fps is CPU frames per second.

The machine was in use during every run (Safari, Chrome, Zoom, the Claude app, other agents' builds), so treat any
single number as ±10-15 %. Differences under about 0.3 ms GPU need several runs to be believed.

## Tools added for pricing

- **`-Dmcopt.shade.dev=<bits>`** (`ShadeFrame.sunPath.z`, `SHADE_DEV_*` in `shade_common.metal`): switches shading
  features off at runtime through uniform branches, with no rebuild.

  | Bit | Switches off |
  |---|---|
  | 1 | fog LUT lookup |
  | 2 | shadow lookups |
  | 4 | sky shading (sky becomes a flat colour) |
  | 8 | water reflection |
  | 16 | all terrain lighting (albedo only) |

- **`-Dmcopt.metal.trace=spin+N`** (`TracePhase`): mcopt's per-encoder GPU profile now starts N submits after the bench
  enters a phase. Before this, the profile needed a fixed submit index, which varied from 11k to 72k between runs.
  - The profile lines are now written with `println`. The game's log drops `printf` output, so before this change the
    profile never reached latest.log.
- **Encoder numbering:** the far cascade is drawn only every 4th frame, so it shifts the profile's encoder numbers.
  Use `-Dmcopt.shade.shadowFarEvery=1` when reading per-encoder numbers.

## Where the time goes

Shade-off reference: `m1-off`, `m1-off2`, `tr-off`.

| Config | Spin GPU ms | Flight GPU ms | Spin fps | Flight fps |
|---|---|---|---|---|
| shade off (upstream path) | 1.72 | 2.02–2.20 | 578 | 452–493 |
| shade on, `dev=31`, no shadows: everything off except the passes themselves | 2.17 | 2.76 | 460 | 358 |
| shade on, `dev=16`: no terrain lighting maths | 2.41–2.84 | 2.97 | 330–414 | 331–335 |
| shade on, no shadow map (`shadows=false`) | 3.32 | 3.52 | 298 | 281 |
| shade on, everything (final defaults) | 3.75 | 4.45–4.65 | 263 | 212–220 |

Per-encoder profile (`trd*` runs, far cascade every frame, µs averaged over 100 submits, under profiling overhead):

| Encoder | Vertex µs | Fragment µs | Notes |
|---|---|---|---|
| Near cascade | 10–20 | 40–50 | ~3–25k quads. Light-space casters push it to ~25k |
| Far cascade | 90–130 | 75–115 | ~35k quads with camera casters. Light mode: 250–390k quads every 4 frames, about +0.15 ms per frame on average |
| Sky + Main, shade off | 1010 | 1783 | |
| Sky + Main + its second encoder, shade on | 450–880 | 2450–2770 | |

Reading:
- **Terrain lighting** (shadeLit + tone curve + fog per pixel) is the largest item, ~0.7–1.3 ms.
- **Shadow lookups** cost ~0.3 ms. The shadow passes themselves cost ~0.25 ms.
- **Sky shading** costs 0.2–0.4 ms, because the full-screen sky triangle's pixels behind cutout leaves still get shaded.
- **Water reflection** costs ≤0.2 ms.

Total: shade on is about +2.0–2.4 ms of GPU per frame over upstream. That is ~4 ms per frame, or ~250 fps on the
route: twice the 120 fps target.

## Done (kept)

| Change | Effect |
|---|---|
| **Sky LUT height mapping `dir.y = v²`** instead of `elevation = v²·π/2` | Every sky and fogged-terrain pixel now looks up with a sqrt instead of an `asin`. Not separable from noise in game; the headless skytime test showed no regression |
| **No fog LUT lookup when the fog weight is under 0.002** (pixels within ~10 blocks) | Small, free |
| **No shadow lookups where sky light is below ~4** (the direct-light gate is 0 there: caves, under overhangs) | Saves the whole shadow cost underground. Not on the bench route |
| **Light-space caster culling by default** (`shadowCull=light`) | +0.0 ms spin, +0.15 ms flight, against a large stability win: no shadow popping when turning |
| **Blit-based debug tiles and opaque far cutout** (shadow branch, carried over) | |

## Tried / rejected / deferred

| Idea | Status |
|---|---|
| Lower Mie (haze) in clear weather to make the horizon bluer (`mie 1 → 0.55`) | **Rejected**: the horizon colour barely changed (the whitening is Rayleigh optical depth, not Mie), and sunsets lost glow. Used a sky saturation factor (1.3) instead |
| Water fog colour darkened under water | **Superseded in the review round**: `ShadeFogMixin` now changes the game's fog colour itself under water (which is also the clear colour), so the clear colour and the terrain fog agree: no band |
| Display-space sky LUT (bake exposure, tone curve and vanilla fog-mix into the LUT, so a sky pixel is one sample) | **Deferred to M2**: saves at most ~0.2 ms. The M2 HDR target and post tonemap change this code anyway |
| Per-vertex fog/sky colour (move the LUT lookup to the vertex stage) | **Deferred**: terrain is vertex-heavy on Apple, and an extra half3 varying costs every vertex. Not worth it at ~4 ms |
| Split the far cascade redraw over 4 frames (even cost per frame) | **Deferred**: with light-space casters the far redraw is ~0.6–0.8 ms every 4th frame. Frame-time data shows no 4-frame periodicity in the slow frames (they come in clusters at specific view directions), so it isn't the cause of the 1% lows |
| Depth16 shadow map | **Not needed**: same speed in the headless bench, and memory is not tight |

## 1% lows (diagnosed in the M1 review)

- The earlier reading ("slow frames are long on the GPU, in clusters at fixed view directions; suspects PSO compiles and
  Sodium uploads") was wrong. The bench CSVs show render-thread stalls instead: CPU frames of 15-22 ms while GPU frames
  stay at 3-6 ms, followed by GPU idle gaps of 7-12 ms (m1-final4: 41 of 70 slow spin frames sit next to an idle gap
  over 4 ms, only 6 are long on the GPU). The spin turns once in 20 s, so a "fixed view direction" is never revisited.
- Mechanism: the drawable is acquired on the render thread and presented from the frame's own command buffer. With
  ~4 ms GPU frames, 2 frames in flight and more presents than the 120 Hz panel shows, the drawables stay held long
  enough that `nextDrawable` blocks the render thread. At 1.7 ms (shade off) they free up sooner.
- Ruled out: GC (about 10 G1 pauses in 40 s against ~150 slow frames), shadow caster listing (0.07-0.10 ms CPU per
  frame), the ShadeFrame ring.
- **Fix (kept): `-Dmcopt.metal.presentQueue=acquire` is the default while `mcopt.shade=true`** (Shade.init sets it when
  the user didn't set the property; mcopt's own opt-in second-queue present that also acquires the drawable on the
  present side). Same-session A/B on the previous jar (occ pinned off): spin 1% low 93/71 -> 152 fps, flight 59 -> 111
  fps; it costs ~0.25 ms of GPU per frame (the staging copy).

## M1 review round (look and cost)

Same-session A/B, `-Dmcopt.metal.occ=false` pinned, perf scenes only (ab-old*/ab-new*/tr-* under
work/bench-results/20261006-15*/16*):

| Build | Spin GPU ms/frame | Flight GPU ms/frame | Notes |
|---|---|---|---|
| m1-final4 jar | 3.74-3.75 | 4.51-4.52 | presentQueue off |
| review build, first cut | 4.48-4.60 | 5.19-5.22 | presentQueue acquire (+~0.25) |
| review build, presentQueue off | 4.26 | 5.04 | |
| review build, cheaper tone curve, presentQueue off | 4.04 | 4.78 | trace run |

Per-encoder (trace, main pass): vertex 1389 -> 1085 us (no quad-normal corner fetches: the face normal now comes from
the position's screen derivatives in the fragment stage; vertex outputs 56 -> 52 B), fragment 2681 -> 2802 us (the
hue-preserving tone curve, emissive and foliage terms; the per-channel part of the curve now runs only for pixels
above 0.6 output). Net cost of the new look: ~+0.2 ms per frame; with the present queue ~+0.45 ms.

Other changes: 32-quad shadow chunks (fewer padding vertex launches). Not done: the far-cascade 4-frame spike (see
SHADOW.md section 9).

Method notes for future pricing: pin `-Dmcopt.metal.occ` (MetalOccPick alternates split and plain blocks during its
trials and the profile otherwise compares different render paths; its pick is now logged with println), interleave
A/B runs, and check `ioreg -r -c IOAccelerator | grep "Device Utilization"` before a run: during this review other apps
kept the GPU 50-90 % busy with no game running, which doubled shade-off frame times in some runs.
