# FT2.5 — Agua lejana de Distant Horizons con reflejos

Jar: `mcopt-shade-FT2.5.jar` (rama `ft1` fa0c464; SHA-256 en `mcopt-shade-FT2.5.jar.sha256`). Base: FT2.4 (botón "Salida").

## Instancias nuevas (2026-10-09)
- **"mcopt Shaders Metal 26.3 (FT2.5)"**: sin Distant Horizons (igual que FT2.4 en lo visual).
- **"mcopt Shaders Metal 26.3 (FT2.5 + DH)"**: con Distant Horizons 3.3.4.

Tienen tu configuración (de "(FT2.4…)"), pero no tus mundos. Si quieres uno ahí, dime y lo copio (copia, el original no se toca).

## Qué cambió
- **El agua lejana de DH ahora refleja**: el cielo, las nubes y el sol, como el agua cercana. Antes se iluminaba como si fuera
  tierra: un azul liso con una franja dura contra el cielo en el horizonte.
- Donde el reflejo cae dentro de la pantalla, refleja **la imagen real del cielo** (nubes a resolución completa, colores del
  atardecer, islas lejanas), igual que el agua cercana. Fuera de la pantalla usa el cielo calculado.
- Se comporta como mar con olas, no como espejo plano: refleja ~1/3 en el horizonte (un espejo plano reflejaba casi todo el
  blanco del horizonte y se veía como leche).
- Lo cercano no cambia (medido: diferencia de 2 sobre 255 en el bosque y la orilla).
- Interno: un cálculo de las sombras de vidrio de colores y hielo ya no pide memoria en cada cuadro (menos tirones).

Capturas: `FT2.5-comparacion.jpg` (columnas: FT2.4 | versión intermedia | FT2.5; filas: desde arriba, a media altura, atardecer).

## Costo
Con DH (giro y vuelo, 3 rondas A/B, Mac conectada): rondas 2 y 3 FT2.4 53.0 / 52.6 FPS vs nueva 52.9 / 52.9 = **sin costo
medible** (la ronda 1 salió con la Mac ocupada). Medido con la primera versión; la final solo agrega una lectura de textura en los
píxeles de agua lejana.

## Ajustes de Distant Horizons (importante)
Con nuestro shader:
- **SSAO y antialiasing de DH no hacen nada**: el shader los apaga siempre (tiene su propio antialiasing, más barato).
- **"Vanilla fade" en NONE**: el modo DOUBLE_PASS lee la imagen de Minecraft de una forma que choca con el shader.
- **Distancia de LODs**: 40 chunks va bien. Se puede subir a 64 o 96, pero el terreno a generar crece con el cuadrado: 256
  chunks es ~40 veces más que 40 y en un mundo nuevo tarda horas en aparecer.
- **Hilos de DH**: 4 al 75 %. Con 8 al 100 % DH se queda con el procesador y el juego se traba.

## Volver al agua lejana anterior
Agregar a los argumentos de Java de la instancia: `-Dmcopt.shade.dh.water=false`.
