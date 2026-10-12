# M1 "Shaders on Metal v0.1": mcopt-shade-M1.jar

Branch `m1` of `work/mcopt-shade` (worktree `work/wt/m1`, head `46b1210`; jar SHA-256 `39ecef41…4f86e23`, byte-identical to the jar the final benches ran), on top of `shade` (= upstream mcopt
0.2.0-alpha.3). The jar replaces mcopt's `metal-0.2.0-alpha.3.jar` in a Prism instance (Minecraft 26.3, Fabric 0.19.5,
Sodium 0.9.3-alpha.1). Nothing has been installed into any of the user's instances.

## Resumen (ES)

- **Qué es:** shaders propios escritos en Metal (MSL nativo) dentro de mcopt. Sombras del sol y la luna, terreno
  iluminado por píxel, cielo atmosférico, nubes iluminadas, lluvia, agua con reflejo del cielo y brillo del sol, y bajo
  el agua un color verde azulado en vez del azul eléctrico de vanilla. Todo a la resolución Retina completa
  (3024×1964), sin escalar.
- **Rendimiento:** unas **215 fps** girando y **185 fps** volando a 3024×1964 (con la GPU del Mac
  ocupada 50–98 % por otras apps durante las pruebas; en una máquina tranquila se estima ~230 / ~195). El objetivo era
  120 fps. Iris + Complementary a la misma resolución da 22 fps: M1 es unas **10 veces más rápido**. Los 1 % lows
  (tirones) mejoraron mucho: 85–129 fps girando.
- **Cómo se ve:** de día ahora hay sombras reales bajo los árboles, caras al sol cálidas y sombras azuladas, cielo
  azul con horizonte claro; atardecer dorado sin el amarillo "neón" de antes; noches oscuras con antorchas que hacen
  charcos de luz; bajo el agua color verde azulado. Capturas en `work/release/M1-screenshots/`.
- **Cómo activarlo:** en la instancia, archivo `config/mcopt.properties`, añadir la línea `mcopt.shade=true` (o
  `-Dmcopt.shade=true` en los argumentos de Java). Para apagarlo: quitar la línea o poner `mcopt.shade=false`. Apagado,
  el mod se comporta igual que mcopt normal.
- **Qué falta (M2):** agua de verdad (reflejos del terreno, refracción), antialiasing temporal, mobs/jugador/partículas
  iluminados como el terreno, y el visto bueno del usuario sobre el estilo (colores, brillo de noche, atardecer).

## How to enable / disable

- **Enable:** `mcopt.shade=true` in `<instance>/minecraft/config/mcopt.properties` (mcopt's Profile reads it at start),
  or `-Dmcopt.shade=true` in the instance's JVM arguments.
- **Disable:** remove it or set `mcopt.shade=false`. With the flag off the game is upstream mcopt: no shade mixin
  applies, the shader registry is empty and every hook is behind a static final `false`.
- Needs mcopt's Metal backend (`mcopt.metal` not `false`) and Sodium. Keep `mcopt.lod` (far terrain) off and vanilla's
  Improved Transparency off: neither is shaded in M1.

## Flags (system properties, also read from `config/mcopt.properties`)

| Flag | Default | Meaning |
|---|---|---|
| `mcopt.shade` | `false` | The switch |
| `mcopt.shade.shadows` | `true` | Sun and moon shadows |
| `mcopt.shade.shadowRes` | `2048` | Texels per cascade side (512-8192) |
| `mcopt.shade.shadowDist` | `128` | Far cascade half-size in blocks (16-1024) |
| `mcopt.shade.shadowNear` | dist/4 | Near cascade half-size |
| `mcopt.shade.shadowCull` | `light` | `light`: every loaded section in the light box casts (no popping); `camera`: cheaper |
| `mcopt.shade.shadowFarEvery` | `4` | Far cascade redraw interval (frames) |
| `mcopt.shade.shadowFarCutout` | `opaque` | Far cascade leaves drawn opaque; `test` alpha-tests them |
| `mcopt.shade.shadowDepth` | `32` | Shadow depth bits (16 or 32) |
| `mcopt.shade.shadowBias` / `shadowBiasTexels` | `1` / `1` | Raster bias (slope, constant) |
| `mcopt.shade.sunTilt` | `30` | Sun path tilt in degrees (shadows at noon); 0 = vanilla path |
| `mcopt.shade.debug` | `0` | 1 shadow tint + cascade tiles, 2 normals, 3 lighting only |
| `mcopt.shade.dev` | `0` | Feature-off bits for pricing: 1 fog LUT, 2 shadow lookups, 4 sky, 8 water, 16 terrain lighting |
| `mcopt.shade.shadowStats` | `false` | Shadow caster statistics in the log every ~5 s |
| `mcopt.metal.presentQueue` | `acquire` while `mcopt.shade=true` | mcopt's second-queue present; set `false` to opt out |

## Numbers (3024x1964 exclusive fullscreen, RD 16, M4 14", AC power; bench route: 20 s spin + 20 s flight)

| Config | Spin fps | Spin 1% low | Flight fps | Flight 1% low | GPU ms/frame (spin / flight) |
|---|---|---|---|---|---|
| **mcopt-shade-M1, shade ON** (median of 6 runs, contended GPU) | **214** (196-224) | **127** (85-129) | **184** (166-194) | **74** (64-92) | 4.65 / 5.39 |
| same jar, shade OFF, same contended session | 349-386 | 69-70 | 166-288 | 54-65 | 2.39-2.56 / 3.1-4.5 |
| M1 integration build (before review), shade ON, quiet machine | 249 | 67 | 209 | 65 | 3.94 / 4.69 |
| mcopt upstream baseline (M0), quiet | 528 | 109 | 407 | 87 | 1.85 / 2.36 |
| Iris + Complementary Reimagined, OpenGL, 3024x1964 (M0) | 22.4 | 14.0 | 21.4 | 10.5 | n/a |
| Iris + Complementary Reimagined, OpenGL, 1920x1080 window (M0) | 43.3 | 20.1 | 41.9 | 21.3 | n/a |

- **Read the shade-on rows against the shade-off row from the same session.** During the final runs other apps (Claude
  desktop, Chrome GPU processes) kept the GPU 50-98 % busy with no game running, which also pulled shade OFF down from
  its quiet 578 / 460 fps. On a quiet machine the M1 build is expected at about 230 / 195 fps (integration build plus the
  measured +0.45 ms per frame).
- Shade ON costs ~4.0-4.6 ms of GPU per frame at full Retina (~2.4 ms over shade off): terrain lighting and tone curve,
  2 shadow cascades of 2048², their lookups, the atmosphere LUT, sky, clouds, water. ~0.25 ms of it is the present queue,
  which buys the 1% lows (same-session A/B: spin 1% low 71-93 -> 152 fps, flight 59 -> 111).
- Result dirs (work/bench-results/): `20261006-154634-fx-c`, `20261006-161304-m1r-on1`, `20261006-161516-m1r-on2`,
  `20261006-161733-m1r-on3`, `20261006-164250-i-on1`, `20261006-164609-i-on2` (on); `20261006-164427-i-off1`,
  `20261006-164752-i-off2` (off); A/B and traces `20261006-1549*`..`20261006-160738-tr-new0b`.

## Best screenshots (full 3024x1964 PNG)

Copies in `work/release/M1-screenshots/`:
- `M1_forest_closeup.png`: tree shadows on grass, cool shadows on the sand steps, lit canopy.
- `M1_sunset_water.png`: golden-hour sky, lit cloud undersides, sun glint path on the water.
- `M1_noon_overview.png`: bright horizon, transparent shallow water, sun and shade on the sand.
- `M1_night_torches.png`: dark moonlit field with warm torch pools.
- `M1_underwater.png`: teal water fog instead of vanilla's vivid blue.
- `M1_vs_vanilla_vs_complementary.jpg`: 5 scenes, columns = mcopt shade off | M1 | Iris + Complementary.
- `M1_extra_shots_before_after_review.jpg`: morning, afternoon and underwater, integration build | this build.

All 8 shots of the final run (full size): `/Users/gaelquintanilla/Documents/Minecraft Metal Shaders/work/bench-results/20261006-161304-m1r-on1/screenshots/` (`noon_overview`, `forest_closeup`, `sunset_water`, `night_torches`,
`rain`, `morning_forest`, `afternoon_overview`, `underwater`).

## Known issues

- **Art sign-off pending.** Knobs: `ShadeSky` (`AMBIENT_BOOST` 4, `AMBIENT_DESATURATE` 0.2, `SKY_SATURATION` 1.3, `KEY`
  0.61, `ADAPTATION` 0.5, `EXPOSURE_MAX` 1.6, `MOON_INTENSITY`), `ShadeFrame.blockLight`, `shadeGoldenHour`, `sunTilt`.
  The sunset is golden at the horizon but the upper sky stays blue (Complementary paints the whole sky warm).
- **Not shaded:** entities, block entities, particles, the hand (vanilla lighting, no shadows cast); translucent blocks
  don't cast; Improved Transparency (OIT) is not shaded (a log line warns); far terrain (`mcopt.lod`) is not shaded.
- **Water is a stand-in:** sky-only reflection (no SSR, refraction or depth fog); the surface seen from below is lit like
  terrain; a faint edge of the far shore's shadow can show in the sunset glint.
- **Leaves:** alpha-tested leaf shadows can shimmer slightly in motion (no TAA until M2).
- **Far cascade spike:** the far shadow cascade redraws every 4th frame (+0.3-0.5 ms on that frame).
- **Untested in game:** Nether and End (vanilla fallback paths), caves, GUI/hand, the half-failed-setup fallback.
  A Metal API validation run could not confirm the occlusion-split binding fix (upstream trips the validator first).
- **Bench numbers** were taken on a contended GPU (see above).
- The user's existing "mcopt Shaders 26.3" Iris instance crashes under mcopt's alpha profile (see HARNESS.md); it needs
  `mcopt.chunk.mesh=air,bounds,vis,biome` or `profile=none`. Not related to this jar.

## What changed in the review round (this jar vs the integration build)

Correctness:
- Shade globals are bound right after mcopt's occlusion split reopens the render encoder (the newly visible terrain drew
  with unbound buffer 16, textures 16/17, samplers 12/13 before).
- Staged setup: if the sky LUT pass fails, valid resources stay bound and the game is drawn the vanilla way; a total
  failure turns vanilla face shade back on.
- Vanilla face shade is removed only in Sodium's chunk meshing and only while Metal runs (falling sand and pistons keep
  it; an OpenGL fallback keeps vanilla's look).
- One parser for the gate flags; a malformed `mcopt.metal.trace` no longer breaks the backend; a shade shader that fails
  to build (e.g. against a resource pack) falls back to the game's shader; OIT warning; license headers.

Performance:
- `mcopt.metal.presentQueue=acquire` by default with shade on: the 1% lows were render-thread present stalls.
- Terrain normal from screen derivatives (no corner fetches in the vertex stage); cheaper tone curve; 32-quad shadow
  chunks.

Look:
- Hue-preserving tone curve (no neon-yellow sunset, no clipped channels), sun about 4-5x the sky fill (visible shadows,
  warm light and cool shade), golden-hour sky grade, exposure darker at sunset, clouds with real per-face contrast and a
  silver lining, darker nights with steeper torch pools and glowing torch flames, brighter backlit leaves, water with a
  tinted body / lower alpha / normalized glint, teal underwater fog matching the clear colour, thicker rain haze.
