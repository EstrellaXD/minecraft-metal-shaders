# FT2.9 — Sin esfera de neblina en las cuevas y antorcha que ilumina parejo

Jar: `mcopt-shade-FT2.9.jar` (rama `ft1` 9765f94; SHA-256 en `mcopt-shade-FT2.9.jar.sha256`). Base: FT2.8.1 (ver `FT2.8.1_NOTES.md`).
Instancias nuevas: **"mcopt Shaders Metal 26.3 (FT2.9)"** y **"(FT2.9 + DH)"** (con tu configuración de FT2.8.1, sin mundos).
Comparación: `FT2.9-comparacion.jpg`.

## 1. La "esfera" alrededor del jugador en las cuevas

Lo que veías era la neblina del aire (la que da profundidad a lo lejos), no la antorcha. Tenía tres fallas:
- Empezaba de golpe a **2 bloques** de la cámara (y otra capa a **~5 bloques**): con la exposición alta de las cuevas esos cortes
  se veían como una esfera y "el mundo por capas".
- Dentro de la cueva la neblina seguía sumando la **luz azul del cielo**, aunque ahí no llega cielo: por eso salía niebla azul.

Arreglo: la neblina empieza desde la cámara sin cortes, y la parte que viene del cielo depende de cuánta luz del cielo te llega a
los ojos (como Photon): en una cueva cerrada no hay; afuera, bajo los árboles o en la boca de una cueva queda igual que antes.

| Vista | Cambio |
|---|---|
| Cueva de día | sin niebla azul ni esfera (antes la niebla aclaraba toda la imagen ~2×) |
| Afuera, mediodía / atardecer | igual (0.01 % / 0.23 % de píxeles cambian) |
| Godrays en el bosque (amanecer, de lado, atardecer), boca de cueva | igual (brillo 0.996–0.999; ≤ 1.5 % de píxeles cambian un poco) |
| Con Distant Horizons (lago desde arriba, atardecer) | igual (≤ 0.15 % de píxeles cambian) |

## 2. La antorcha en la mano

Antes la luz salía de la mano (abajo y a un lado), así que en un túnel la pared de ese lado quedaba oscura (la luz le llegaba de
lado) y la otra muy brillante. Ahora, como en Photon, Complementary y BSL, la luz sale de tus ojos con la fuerza de la mano que más
alumbra (una antorcha en cualquiera de las dos manos), y llega igual a las paredes, el techo y el piso.

| Túnel 1×2 con antorcha | pared izquierda ÷ derecha (1.00 = parejo) |
|---|---|
| mano izquierda | 0.44 → **1.00** |
| mano derecha | 2.31 → **1.00** |
| cuarto 3×5 | 1.21 → **1.01** |

## Rendimiento

Igual que FT2.8.1. Giro del lago a 3024×1964, viejo y nuevo alternados en la misma sesión: 86.9 / 85.4 fps (FT2.8.1) contra
85.7 / 85.2 fps (FT2.9). Los FPS bajan un poco en cada fase porque la Mac se calienta; descontando eso, la diferencia es de ±0.5 fps, dentro del ruido.

## Opciones (por si quieres comparar)

`-Dmcopt.shade.caveAir=false` (neblina de FT2.8.1) y `-Dmcopt.shade.dynLights.playerLight=false` (antorcha desde la mano, FT2.8.1).
