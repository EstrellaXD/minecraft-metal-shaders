# M3.3 misc: god rays at sunset, dynamic resolution, rain budget (branch `m3.3-misc` from `m3-int` 397e241)

## M3.3 Status (2026-10-08 09:55)

Plan (in this order; nothing of M4 / FT1):

1. **God-ray contrast at sunset.** Re-measure the crepuscular-ray contrast in the `rose_*_sky` view (M3_INTEGRATION.md
   "Sunset art": 7.23 -> 5.94 high-pass std of luma, art off vs on, before the volumetric clouds) on the current code vs
   M2.2 at the same frozen pose. Metric script `work/notes/m33-2026-10-08/misc/rays_metric.py` (std of luma minus a
   Gaussian blur over the sky part) plus a ray-only metric (the same view with the sky's dark rays switched off in the
   same session: the difference image is the rays alone). If still clearly lower: a golden-hour gain on the sky's dark
   rays while the sunset art is on (`mcopt.shade.vl.sunsetRays`, read every frame, 1 = M3.2), checked for noise/flicker.
2. **Dynamic resolution dead zone** (M2.1 pending). Log the controller's view (scale, its GPU average, the target) once a
   second with `mcopt.shade.drsLog=true`, run the perf route with DRS on, find why it sits below 120 without stepping, fix
   behind a flag read every frame (off = M3.2's controller), A/B both controllers in one session (mcbench phases).
3. **Rain 0.56 vs 0.5 ms** (WEATHER.md "What is left" 2). Price the listed options in one session; apply one on balanced
   only if puddles with rain look the same in the game (before/after shots), else leave it and say so.

Tools: mcbench `MiscShots` (new, mine): generic recorded A/B perf phases `ab1..ab12` with system properties per phase, and
variant shots at one frozen pose. Runs and evidence: `work/notes/m33-2026-10-08/misc/`.

Done so far (10:05):
- Tools: mcbench `MiscShots` (mcbench 0060f94, 1acb3de): `-Dmcbench.ab=<scene>@<sec>@<label>:<k=v>,...;...` recorded phases
  `ab1..ab24` with system properties per phase (scenes `spin`, `route` = spin + flight, `wxrain`, `wxdry`), and
  `-Dmcbench.variantShots=<tag>@<time>@<pose>@<weather>@<label>:<k=v>;...` (one frozen pose, ticks frozen, a shot per variant;
  `-Dmcbench.variantPair=<s>` adds `<shot>_b` that long after, for frame-to-frame noise). Scripts in
  `work/notes/m33-2026-10-08/misc/`: `run1.sh`..`run3.sh`, `ab_table.py`, `drs_phases.py`, `rays_ab.py`, `streaks.py`.
- Runs: `bench-results/20261008-094026-mx1-base` (jar 933844d = M3.2 behaviour + diagnostics), `20261008-094646-mx2-m22`
  (M2.2 release jar), both AC.
- **DRS diagnosis:** M3.2 balanced "Auto" sits **at its floor 0.60 for the whole perf route** with 8.1-10.1 ms of GPU per frame
  (status line once a second, `drsLog`): spin 109.6 fps / flight 115.6 fps. M2.2 does the same today (pinned at 0.60, 7.3-8.6 ms,
  131 / 136 fps). The "dead zone" of M2.1's notes (M2 at ~116 fps "without stepping down") was this: the controller was
  already at its lowest allowed scale. Two more defects: (a) ~60 % of the M3 frame does not shrink with the render scale
  (M3.2 spin 11.3 ms at 0.80 vs 9.1 ms at 0.60: T = 6.3 + 7.8 s^2): the s^2 model under-steps; (b) its target 0.86 x 8.33 ms
  (139 fps, up only under 6.6 ms = 152 fps) spends resolution on frames mcopt's pacer never shows (it presents at most one
  per 120 Hz refresh). Fix (abb535d): the **hold controller** (`mcopt.shade.drsHold`, below).
- **Rain options**, first in-session pricing (mx1, 10 s pans, DRS paused at 0.80; clean phases only, several had CPU spikes):
  rain cost = base 8.95 - dry 8.38 = **0.57 ms** (as WEATHER.md's 0.56); all three options together 8.88 (-0.07 ms, two pairs);
  single options within noise. Shots at the ground pose: base and all look the same at screen size (full-res crops next).
- **Sunset rays** (rose_sky pose, top 62 % of the frame): the old metric (std of luma minus a sigma-32 blur at 1512 px wide;
  reproduces art3's -17 %: 8.83 -> 7.35) now gives **M2.2 8.87, current 9.26 / 9.62 (art on), 9.19 (art off): not lower than
  M2.2 any more**; the volumetric clouds add structure. Rays alone (current code, same frozen pose, minus the same frame with
  the sky's dark rays off): art on 8.99 / 6.88 (two shots of the same setting, 20 s apart), art off 7.67: the sunset art no
  longer weakens them measurably. But they look different from M2.2's: soft broad shadows of the volumetric clouds instead of
  the thin dark streaks behind vanilla's flat clouds, and the same term darkens the clouds' own bodies (purple undersides):
  a plain golden-hour gain (g1.5-3, `sunsetRays` first version) mostly darkened the clouds (mean -8 -> -14 luma). Second
  version (abb535d): the gain only over the open sky (x the pixel's own cloud transmittance squared). Pending: run 3.

Next: run 3 (`mx3-hold`): hold vs M3.2 controller in one session (route, lighter wxdry pan), rain ABAB again, open-sky ray
gains with frame pairs, rain shots incl. a grazing view; then decide defaults (sunsetRays, rainBudget).

## Flags (M3.3 misc)

| Flag | Default | Off = M3.2 | What |
|---|---|---|---|
| `mcopt.shade.drsHold` | true (read every frame) | false | DRS "hold" controller: floor `mcopt.shade.drsFloor` (0.50) on performance/balanced, cost model with a fixed share `mcopt.shade.drsFixed` (0.5), band: down over 96.5 % of the frame budget (to 90 %), up while the model keeps the next step under 86 %, steps up to -0.08 / +0.03 |
| `mcopt.shade.vl.sunsetRays` | 1 (pending) | 1 or false | golden-hour gain of the sky's dark crepuscular rays over the open sky while the sunset art is on |
| `mcopt.shade.rainBudget` | empty (pending) | empty | rain budget options: `gate` (SSR from F x P 0.06), `ripples` (rings fade out nearer), `steps` (8 SSR steps) |
| dev: `mcopt.shade.drsLog` | false | | + a status line once a second (scale, its range, GPU average, target, fps, changes, at the floor) |
| dev: `mcopt.shade.drs.pause` / `drs.restart` | | | read every frame: hold the start scale / restart the controller (bench A/B) |
| dev: `mcopt.shade.vl.skyRays` | 1 | | gain of the sky's dark rays at any time (0 = none: the ray-only metric) |
| dev: `mcopt.shade.rainBudget.dev` | false | | compiles the rain options switchable by dev bits 262144 (gate), 524288 (ripples), 1048576 (steps) |

## Integration notes (shared files touched)

| File | What | Why |
|---|---|---|
| `shade_common.metal` | 3 dev-bit defines after `SHADE_DEV_WX_SSR_GATE2`: 262144, 524288, 1048576 | rain options priced in one session (weather convention: dev bits count up) |
| `vl.metal` (VL module) | `VlParams.art` float4 at 256 (struct 272 B); dark-ray line x `art.x (1 + art.y T^2)`; the pixel's cloud T (`skyT`) computed before the crepuscular block and reused for `o.sky` | sunset ray gain over the open sky |
| `mcvl.m` | params copy buffer 64 -> 80 floats | VlParams 272 B |
| `ShadeVl.java`, `VlConfig.java` | PARAM_BYTES 272, `art` packed; `sunsetRays()` / `skyRays()` read every frame | |
| `weather_surface.metal`, `ShadeWeather.java` | `SHADE_WX_B_*` options; the SSR march is a template on its step count | rain options |
| `DynamicResolution.java`, `ShadeTaa.java` | hold controller, status line, restart/pause hooks | DRS |
| `tools/shade/vlbench.m` | VlParams 272 B | keep the tool in step |
