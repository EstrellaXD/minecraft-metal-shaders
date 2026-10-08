# M3.3 robustness: never-tested paths and frame generation (branch `m3.3-robust`)

## M3.3 Status (2026-10-08 09:35)

Branch `m3.3-robust` from `m3-int` 397e241 (M3.2 + aurora-lean sky). Plan (in this order):

1. Check the never-tested paths with the current code (one game run, mcbench `RobustShots`, `-Dmcbench.robust=...`), then fix
   what breaks (each fix behind or compatible with the flags), then re-run:
   - (a) leave the world and join it again 3 times in one game session: targets/resources rebuilt, no crash, no black or
     garbage frames, Metal `currentAllocatedSize` and Java heap per rejoin (leak check);
   - (b) overworld -> Nether -> overworld -> End -> overworld with a shot in each;
   - (c) vanilla "Improved Transparency" (26.3: wavelet OIT) on together with TAAU: water, rain, clouds;
   - (d) GUI over the world (HUD + hand, inventory, chat, F3, pause menu);
   - (e) leaving fullscreen / windowed resize / back;
   - (f) a shader that fails to compile: dev-only `-Dmcopt.shade.dev.failCompile=<list>` injects the failure; does the
     game keep running on a clean fallback with a log line?
2. Frame generation (`-Dmcopt.metal.framegen=true`, upstream MetalFX frame interpolation) with our shaders: does it run,
   artifacts (framegen dumps), perceived fps, latency (its own once-a-second report). If acceptable: a pause-menu button next
   to "Resolución" persisted in config/mcopt.properties (`mcopt.metal.framegen`), "(al reiniciar)" if not live; off by default.
   Otherwise document why and leave it off.

No M4 (ray tracing) or FT1 (DH lit by our shaders) work here.
