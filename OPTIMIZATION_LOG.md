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
| Water fog colour darkened under water | **Rejected**: when underwater the frame's clear colour (vanilla fog colour) shows behind distant geometry, so a different fog colour on terrain made a bright band. Kept vanilla's colour; daylight under water is filtered instead |
| Display-space sky LUT (bake exposure, tone curve and vanilla fog-mix into the LUT, so a sky pixel is one sample) | **Deferred to M2**: saves at most ~0.2 ms. The M2 HDR target and post tonemap change this code anyway |
| Per-vertex fog/sky colour (move the LUT lookup to the vertex stage) | **Deferred**: terrain is vertex-heavy on Apple, and an extra half3 varying costs every vertex. Not worth it at ~4 ms |
| Split the far cascade redraw over 4 frames (even cost per frame) | **Deferred**: with light-space casters the far redraw is ~0.6–0.8 ms every 4th frame. Frame-time data shows no 4-frame periodicity in the slow frames (they come in clusters at specific view directions), so it isn't the cause of the 1% lows |
| Depth16 shadow map | **Not needed**: same speed in the headless bench, and memory is not tight |

## Open: 1% lows

- Shade on: spin 1% low 65–120 fps, flight 1% low 64–80 fps. Shade off: 200–230 / 200.
- In the spin, slow frames (>8 ms) come in clusters at fixed view directions (t ≈ 3.6, 15.1–15.3 and 18.0–18.3 s of
  the 20 s spin in m1-e), with long command buffers on the GPU side. They are not periodic with the far cascade.
- Suspects:
  - shader variant compilation the first time a view shows a new pipeline (PSO variants);
  - Sodium uploads coinciding with the heavier GPU frame.
- Next step: run `-Dmcopt.metal.trace=spin+<frame of a spike>` around a cluster.
