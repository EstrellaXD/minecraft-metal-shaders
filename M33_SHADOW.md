# M3.3 shadow passes (branch `m3.3-shadow` from `m3-int` 397e241)

## M3.3 Status (2026-10-08 09:40)

Plan (in this order; every new behaviour behind a flag whose "off" = M3.2 exactly):

1. **Far cascade spike** (SHADOW.md §9 "Far cascade spike": 250-460k quads redrawn every 4th frame, +0.3 / +0.5 ms on
   that frame). New flag `mcopt.shade.shadowFarSpread` (read every frame, so mcbench can A/B it inside one session): the
   far map is double-buffered (a second 2-slice map; receivers keep binding "the" map at texture(16), which swaps once per
   cycle); each frame draws one quarter of the far casters (sections split 2x2 by section x/z parity) into the back map,
   all four quarters with the same fitted cascade (one light direction, one snapped origin: no seams, no swimming); after
   the 4th quarter the back map becomes the front. A teleport / big light jump still redraws in one frame (as M3.2).
   Price: per-frame GPU (gputimes.csv, end-to-end per submit, mod-4 phase), whole-frame GPU ms, CPU p99 / 1 % low, all
   in one session (mcbench `ShadowShots` phases with the flag off/on/off/on), plus per-pass GPU timers
   (`mcopt.shade.shadowTimes`, counter samples per shadow pass). Shots: same pose on/off, moving.
2. **Coloured-shadow cost near glass** (WEATHER.md "What is left" 1: ~0.18 ms dry, budget ~0.1). First split it with
   per-frame dev bits (`mcopt.shade.tintDev`: lookup off / pass off) and the GPU timers, then pick the best look per ms
   among: a tight caster rectangle (block-level, so far fewer pixels look the map up), a cheaper map, folding, off on
   balanced. Before/after shots of `wx_glass_noon`.
3. **Blob shadow in caves**: log `EntityCasters.dropBlob` decisions; A/B against `release/mcopt-shade-M2.1.jar` at the same
   poses (`-Dmcbench.entities=cave,noon,night`): the blob must be exactly M2.1's where our shadows can't reach, gone
   where they do.

Not in this round: M4 (ray tracing), FT1 (DH lit by our shaders).
