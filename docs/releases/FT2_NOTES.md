# FT2 — Distant Horizons directo en la imagen HDR + auroras frecuentes

Jar: `mcopt-shade-FT2.jar` (rama `ft1` d3f7d7d; SHA-256 en `mcopt-shade-FT2.jar.sha256`). Base: OPT1 (incluye sus
optimizaciones del agua).

## Instancias nuevas (2026-10-08, noche)
- **"mcopt Shaders Metal 26.3 (FT2 + DH)"**: con Distant Horizons 3.3.4. Aquí está casi todo lo nuevo.
- **"mcopt Shaders Metal 26.3 (FT2)"**: sin DH. Igual a OPT1, más las auroras frecuentes.

Tus mundos no se copian (tu mundo "lero" sigue en "(FT1)").

## Con Distant Horizons: ~5 % más FPS, misma imagen
Mac conectada, balanced, resolución fija 0.80, DH a 40 chunks. Tres pares de corridas alternadas OPT1 / FT2: FT2 ganó en los 3.

| Escena (con DH) | OPT1 | FT2 | Mejora |
|---|---|---|---|
| Giro, promedio | 55.1 fps | 58.0 fps | **+5.3 %** (−0.93 ms por cuadro) |
| Vuelo, promedio | 56.4 fps | 59.6 fps | **+5.6 %** |
| Bajones (1 % más lento), giro / vuelo | 45.7 / 51.3 | 48.1 / 54.6 | +5–6 % |

Qué cambió (en simple):
- **Sin "puente".** Antes, para que DH pudiera dibujar, la imagen HDR se convertía a una imagen normal y, cuando DH
  terminaba, se regresaba a HDR. Eran dos pasadas a pantalla completa, 0.7 ms. Ahora el desvanecido lejano y la copia final
  de DH usan shaders nuestros, y los LODs se mezclan directo sobre el cielo HDR.
- **DH ya no dibuja terreno lejano donde lo tapan los chunks normales** cerca de ti: ajuste `overdrawPrevention` de DH en 0.5.
  Antes estaba en 0, así que DH iluminaba LODs que nunca se veían. Ahorra 0.5–0.7 ms.
  - DH advierte que pueden aparecer huecos si vuelas muy rápido sobre terreno que todavía no carga. Con 0.5 el riesgo es
    bajo, y DH lo reduce solo cuando te mueves rápido. En las pruebas no apareció ninguno.
  - Si ves huecos, pon `mcopt.shade.dh.overdraw=0` en `config/mcopt.properties`.

Comprobado con capturas (DH): mundo normal de día, atardecer, noche, lluvia, agua, Nether (5 biomas), End (isla, vacío,
mobs). Las diferencias son ruido: lava animada, partículas, bordes de hojas.
- Un error que encontré en el camino y quedó arreglado: en el End, mirando al vacío, aparecía una isla "fantasma" de una
  escena anterior. En el End no hay cielo normal, y la imagen no se limpiaba antes de que DH dibujara.

Lo que DH todavía cuesta (~4–6 ms por cuadro a 40 chunks) es sobre todo dibujar y iluminar sus LODs:
- 1.4 ms solo en la geometría (la cantidad de LODs);
- 1.9 ms en la luz.

Bajar eso requiere cambiar la calidad de DH (menos detalle lejano), y eso sí se nota. Dime si quieres probarlo.

## Auroras frecuentes (pediste "Frecuentes")
- **Todas las noches despejadas en biomas fríos**: nieve, picos de hielo, océano congelado. En los templados-fríos, como la
  taiga, un poco más tenues.
- **1 de cada 3 noches despejadas en cualquier otro bioma**, a media intensidad.
- Con lluvia no hay (las nubes de lluvia las tapan), y las nubes normales tapan una parte.

Antes solo salían en biomas fríos y ~1 de cada 3 noches: por eso no las veías. Para cambiarlo, en `config/mcopt.properties`:
- `mcopt.shade.aurora=always`: todas las noches, en cualquier bioma;
- `mcopt.shade.aurora=rare`: como antes;
- `mcopt.shade.aurora=off`: apagadas.

Alpha: respalda tus mundos.
