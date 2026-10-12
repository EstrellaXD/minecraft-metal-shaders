# OPT1 — FT1.3 optimizado (la misma imagen, más FPS)

Jar: `mcopt-shade-OPT1.jar` (rama `ft1` 251731c; SHA-256 en `mcopt-shade-OPT1.jar.sha256`). Base: FT1.3.

## Instancias nuevas (2026-10-08, noche)
- **"mcopt Shaders Metal 26.3 (OPT1)"**: sin Distant Horizons.
- **"mcopt Shaders Metal 26.3 (OPT1 + DH)"**: con Distant Horizons 3.3.4.

Tus mundos no se copian (tu mundo "lero" sigue en "(FT1)").

## Cuánto más rápido
Mac conectada, preset balanced, resolución fija (0.80), 3024×1964. Tres pares de corridas alternadas FT1.3 / OPT1 en la
misma noche. La optimizada ganó en los 3 pares.

| Escena | FT1.3 | OPT1 | Mejora |
|---|---|---|---|
| Giro sobre el lago (mucha agua), promedio | 84.2 fps | 89.0 fps | **+5.7 %** (−0.65 ms por cuadro) |
| Giro sobre el lago, bajones (1 % más lento) | 65.5 fps | 72.7 fps | **+11 %** |
| Vuelo, promedio | 91.2 fps | 94.4 fps | **+3.5 %** (−0.37 ms) |
| Vuelo, bajones | 77.9 fps | 82.6 fps | **+6 %** |

Tus instancias usan resolución automática. Ahí la ganancia se nota como un poco más de nitidez (la resolución baja menos)
o más FPS, según la escena.

## ¿Se ve igual? Sí
Comparé capturas de las dos versiones con el agua y las nubes congeladas: lago, atardecer, mirando al sol, lluvia, noche,
orilla y bajo el agua. Las diferencias son del mismo tamaño que entre dos sesiones de FT1.3 contra sí misma:
- bordes de hojas: el antialiasing tiembla distinto en cada sesión;
- partículas;
- algo que se movía en la aldea.

Por ejemplo, en el lago cambia más de un poquito el 1.2 % de los píxeles, contra 1.7 % de FT1.3 contra FT1.3.
Hoja de comparación: `notes/opt-2026-10-08/look/ft13-vs-opt1.jpg`.

## Qué se hizo (en simple)
El agua era lo más caro: 3.6 de los ~12 ms de cada cuadro mirando el lago. Su programa es enorme. Cada núcleo de la GPU
tiene pocos "cajones" de memoria rápida (registros). Cuando un programa necesita muchos, la GPU procesa menos píxeles a la
vez y espera más. Por eso quitar código que ni siquiera corre lo acelera.

- **Agua vista desde arriba con su propia versión del programa**, sin el código de "agua vista desde abajo" (solo corre con
  la cámara bajo el agua). Se cambia de versión al instante al meterte al agua.
- **El agua ya no lee la textura del bloque de agua.** Nunca la usaba: dibuja sus propias olas.
- **La búsqueda de reflejos compara profundidades sin hacer divisiones** en cada paso, y se hace al principio del programa.
- **Neblina en las orillas del terreno:** 1 lectura en vez de 5 (mismos valores).
- **Interruptores de medición** que puse para encontrar todo esto: fuera del juego normal (costaban ~0.09 ms).

**Bajo el agua** se usa el programa completo, que quedó igual al de FT1.3. Al principio mis cambios también lo hacían más
lento (+1.5 ms). Lo detecté midiendo y lo regresé al código de FT1.3: bajo el agua, mismo rendimiento que antes.

## Probado y descartado (no ayudaba o empeoraba)
- Compartir entre 4 píxeles el valor de profundidad del reflejo: **+0.13 ms**, peor.
- Pedir todas las lecturas al principio del pase final: +0.03 ms, peor.
- Copiar la imagen entera para el agua en vez de solo la parte visible: 0.44 contra 0.34 ms, peor.

## Con Distant Horizons
Probado (`*opt1-dh-smoke`): funciona bien, ~56–58 fps con DH a 40 chunks. DH todavía cuesta ~6 ms por cuadro, porque su imagen pasa
por un "puente" de ida y vuelta a la imagen HDR. El siguiente paso, **FT2**, lo dibuja directo en la imagen HDR. Ahí está el
ahorro grande cuando juegas con DH.

## Lo que queda (para más FPS hay que aceptar cambios mínimos)
Lo que se podía ganar sin cambiar ni un pixel ya está hecho, o son ahorros de ~0.03 ms. Lo que sigue tiene un pequeño costo
visual. Los números son estimados, sin medir todavía. Si quieres alguno, dime y lo mido con capturas antes/después:
- **nubes lejanas calculadas a menos resolución** (~0.2–0.3 ms);
- **neblina con una tabla precalculada** por cuadro en vez de calcularla en cada píxel (~0.3 ms; cambios mínimos de color);
- **godrays con menos pasos o menos resolución** (hoy cuestan ~0.5 ms).

Alpha: respalda tus mundos.
