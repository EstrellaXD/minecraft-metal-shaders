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

Done: (nothing yet)
Next: mcbench MiscShots, DRS diagnostics, baseline run.
