# FT2.11 — Antorcha sin sombras duras y cascadas que sí parecen agua cayendo

Jar: `mcopt-shade-FT2.11.jar` (rama `ft1` 66fdcdc; SHA-256 en `mcopt-shade-FT2.11.jar.sha256`). Base: FT2.10 (ver `FT2.10_NOTES.md`).
Instancias nuevas: **"mcopt Shaders Metal 26.3 (FT2.11)"** y **"(FT2.11 + DH)"** (con tu configuración de FT2.10, sin mundos).
Comparación: `FT2.11-comparacion.jpg` (izquierda FT2.10, derecha FT2.11). Pruebas y números: `work/notes/ft211-2026-10-11/README.md`.

Todo lo probé en el mundo de pruebas recreando tus fotos (no abrí tu mundo): un túnel de cueva con bloques sueltos, un pilar y un hueco
de 1x1 en la pared, una antorcha tirada detrás del pilar, una repisa con agua que cae en la cueva, y un acantilado de 7 bloques con una
cascada en una plataforma al aire libre. Todo lo construido se borró al terminar.

## 1. La luz de la antorcha en la mano

**Qué pasaba:** para que la luz no atravesara paredes, el mod calculaba qué bloques "ve" la luz, con rayos de bloque en bloque. Pero la
luz de tu antorcha está en tus ojos, en el mismo punto que la cámara: todo lo que ves, la luz lo alcanza. El cálculo por bloques se
equivocaba en esquinas, nichos y detrás de bloques sueltos y dejaba sombras de un bloque con un corte seco (tus capturas 1–4).

**Cómo lo hacen los otros:** Photon, Complementary y BSL ponen la luz en el jugador y solo la apagan con la distancia, **sin sombras**.
Nuestra caída con la distancia ya era casi igual a la de Complementary; lo único distinto eran esas sombras. Ahora la luz que cargas
tampoco tiene sombras: el túnel queda parejo, el hueco de la pared se ve iluminado por dentro y se oscurece poco a poco hacia el fondo.

**Las demás luces** (antorchas tiradas en el piso, zombis en llamas, blazes, flechas de fuego...) sí necesitan no atravesar paredes,
porque no están en tus ojos. Antes se cortaban igual de seco. Ahora la luz **rodea las esquinas y se apaga poco a poco** detrás de lo que
la tapa, como la luz de una antorcha colocada en Minecraft (y como las luces de colores de Complementary y Photon): detrás de un pilar
queda un poco más oscuro, detrás de una pared larga oscuro, pero sin bordes cortados. Lo comprobé contra un cálculo de referencia: 0
diferencias en un cuarto con pilar, muro y bloques sueltos.

## 2. El agua que cae / en cascada

**Qué pasaba:** las olas del agua solo existían en la cara de arriba. Las caras de los lados (justo lo que ves en una cascada) eran
vidrio liso y transparente: se veía la pared de atrás tal cual, como un panel azul cuadrado (tus capturas 5–6).

**Cómo lo hacen los otros:** Complementary y BSL ponen las olas en todas las caras del agua y muestran la textura de agua fluyendo de
vanilla (vetas que bajan); Photon hace el agua que corre con olas más fuertes que la quieta. Como a ti no te gustó la textura de vanilla
(FT2.10), aquí todo es realista y animado:

- **Olas en los lados de la cascada**, finas, estiradas hacia abajo y bajando (~3 bloques por segundo).
- **Agua blanca**: vetas finas aireadas que caen más rápido que las olas, y la lámina un poco blanquecina, como una cascada de verdad.
  De lejos (30+ bloques) se sigue leyendo como cascada, sin parpadeo.
- **La corriente** (agua que fluye por el piso) con olas más ásperas que un lago quieto.

## Rendimiento

El código de cascadas hacía más lento **todo** el agua (unos 0.2 ms, ~2 fps sobre el lago) aunque no hubiera ninguna cascada, porque
el shader del agua va justo al límite de memoria de la GPU. Por eso ahora el mod revisa, de forma muy barata, si hay agua corriendo o
cayendo a menos de ~96 bloques: solo entonces usa la versión del agua con cascadas (y el efecto se desvanece antes de esa distancia para
que el cambio no se note). Lejos de cascadas y ríos que fluyen, el agua es exactamente la de FT2.10.

Medido en el recorrido de prueba a 3024×1964, lanzamientos alternados FT2.10 / FT2.11 (3 de cada uno), con la Mac conectada. En el
mundo de prueba **sí hay agua corriendo cerca del recorrido** (un manantial natural), así que esto es el caso "con cascadas cerca":

| | FT2.10 | FT2.11 | Diferencia |
|---|---|---|---|
| Girando sobre el lago | 84.6 fps | 83.3 fps | +0.18 ms |
| Volando | 89.1 fps | 88.4 fps | +0.08 ms |

Sin agua corriendo a menos de ~96 bloques, el agua usa exactamente el código de FT2.10 (mismo costo). La antorcha en la mano cuesta un poco
menos que antes (ya no recalcula su volumen de sombras cada cuadro al caminar); otras luces que se mueven (mobs en llamas, etc.) cuestan
~0.05 ms más solo mientras se mueven. Con Distant Horizons: misma imagen (brillo 0.998–1.000). Si algún día prefieres los FPS a las
cascadas: `mcopt.shade.water.falls=0` vuelve al agua de FT2.10.

## Opciones (en vivo, en `config/mcopt.properties` o con `-D`; los valores entre paréntesis = como FT2.10)

| Opción | Normal | Qué hace |
|---|---|---|
| `mcopt.shade.dynLights.playerShadows` | false (true) | sombras de la luz que cargas |
| `mcopt.shade.dynLights.soft` | true (false) | las demás luces rodean esquinas en vez de cortarse |
| `mcopt.shade.water.falls` | 1 (0) | cascadas con olas y agua blanca, corriente más áspera (0 a 2: más o menos fuerte) |

No probado en el juego: la luz que cargas en tercera persona (F5); como en los otros shaders, ahí puede alumbrar un poco a través de una
pared lo que la cámara ve detrás de ti.
