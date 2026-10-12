# FT2.13 (2026-10-11 23:55) — lluvia sin nieve falsa + Distant Horizons de fábrica

Instancias: **"mcopt Shaders Metal 26.3 (FT2.13)"** y **"(FT2.13 + DH)"** (config de las FT2.12, jar nuevo, copia de tu
"Mundo principal prueba shaders" de cada una). En GitHub: versión **1.0-alpha.3**. Jar: `mcopt-shade-FT2.13.jar` (ft1 bbdb7af).

## Arreglos

- **La lluvia ya no pinta de blanco el suelo.** En lugares que no son nevados, la lluvia solo moja: bloques más oscuros,
  reflejantes y con charcos. La capa de nieve del shader solo aparece en biomas nevados (planicies, taigas y laderas nevadas,
  picos helados), y ahora conserva un poco de la textura del bloque.
  - Por qué pasaba: el juego decide lluvia o nieve por la temperatura, que baja con la altura (en cerros pedregosos nieva
    arriba de ~120, en taigas de abetos arriba de ~160). El shader miraba solo la altura de la cámara: con la cámara arriba de
    esa línea creía que nevaba en todo el terreno, y la "nieve" se quedaba minutos aunque después lloviera.
  - Probado (versión anterior contra la nueva, misma sesión): taiga con lluvia vista desde y 175: antes todo blanco, ahora
    mojado; prado con lluvia: antes manchas blancas en el pasto, ahora pasto mojado; planicie nevada: igual. Comparación:
    `lluvia-sin-nieve-comparacion.jpg`.
- **Distant Horizons con su configuración de fábrica** ya no oscurece la mitad de abajo de la pantalla (su opción "Vanilla
  Fade Mode" se pone en NONE al arrancar). Tus instancias ya usaban NONE. Comparación: `1.0-alpha.2-DH-comparacion.jpg`.

## Opciones nuevas (config/mcopt.properties)

- `mcopt.shade.snowLine=false`: como antes (todo el terreno toma lo que cae en la cámara).
- `mcopt.shade.snowLine.cover=true`: la capa de nieve también arriba de la línea de nieve de biomas no nevados.
- `mcopt.shade.snowGrain=false`: nieve blanca lisa, como antes.

No se midieron los FPS: el cambio son dos cálculos pequeños por píxel.
