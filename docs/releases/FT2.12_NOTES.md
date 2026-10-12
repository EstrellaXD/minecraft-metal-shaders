# FT2.12 — El brillo del atardecer ya no atraviesa las montañas

Jar: `mcopt-shade-FT2.12.jar` (rama `ft1` 9cf4084 / dd4586e; SHA-256 en `mcopt-shade-FT2.12.jar.sha256`). Base: FT2.11 (ver `FT2.11_NOTES.md`).
Instancias nuevas: **"mcopt Shaders Metal 26.3 (FT2.12)"** y **"(FT2.12 + DH)"** (con tu configuración de FT2.11, sin mundos).
Comparación: `FT2.12-comparacion.jpg` (izquierda FT2.11, derecha FT2.12). Pruebas y números: `work/notes/ft212-2026-10-11/README.md`.

## Qué pasaba (tu captura del cañón nevado)

Con el sol ya detrás de la montaña, sobre la pared se veía una mancha naranja-rosa, como si la luz del atardecer la atravesara.

Lo reproduje en el mundo de prueba con un muro de piedra de 90 bloques de alto y el sol detrás, y probé apagando cada parte de la
neblina por separado. La culpable era una mezcla antigua (de M1): para que el terreno lejano se funda con el cielo, cada bloque se mezcla
un poquito (1–4 %) con **el color del cielo que hay detrás de él**. Hacia el sol, ese cielo es el resplandor del atardecer, que es
muchísimo más brillante que una pared en sombra, así que ese poquito se notaba como una mancha. Y esa mezcla **no sabía nada de
sombras**: la ponía igual aunque una montaña tapara el sol.

(La neblina normal, la que hace los godrays, sí respetaba las sombras; aun así le encontré un segundo límite: las sombras solo podían
quitar hasta el 70 % de la luz de un píxel. También quedó arreglado, por si en algún lugar ese brillo era más fuerte que la pared.)

## Qué hice

- La luz volumétrica (la de los godrays) ya recorre cada rayo de la cámara al terreno revisando qué partes del aire están en sombra.
  Ahora también le quita a esa mezcla el brillo del sol **en la parte del rayo que está en sombra**. Si todo el aire entre tú y la
  montaña está en su sombra, el resplandor desaparece por completo.
- Donde el aire sí está al sol (por ejemplo, mirando al atardecer sobre una llanura o el mar), **el brillo dorado se queda igual**.
- Las sombras ahora quitan exactamente el brillo que se agregó a ese píxel, ni más (nunca oscurecen la pared misma) ni menos.

## Cómo se ve (ver `FT2.12-comparacion.jpg`)

1. Muro con el sol detrás: la mancha rosa desaparece; la pared queda gris azulada, como una pared en sombra.
2. De más lejos: el agua en la sombra del muro ya no brilla rosa; la tierra y el agua soleadas a los lados conservan su brillo.
3. Colina abierta hacia el sol: casi igual; solo las laderas que de verdad están en sombra pierden un poco del velo dorado.
4. Con Distant Horizons al atardecer: igual (godrays y brillo intactos; ≤ 1.2 % de los píxeles cambian un poco).

Bosques de día y al atardecer, la cueva, la noche y tu bosque de otoño al amanecer: prácticamente iguales (≤ 3 % de píxeles, cambios
mínimos).

## Rendimiento

~0.03–0.05 ms por cuadro (≈ 0.3 fps a ~85 fps), medido prendiendo y apagando el arreglo dentro de la misma sesión (girando y mirando al
atardecer). Solo se calcula en los píxeles hacia el lado del sol donde hay sombra que quitar.

## Si quieres volver a como era

`-Dmcopt.shade.aerialSun=true -Dmcopt.shade.vl.airClamp=0` = FT2.11 exacto (se pueden cambiar en vivo).

Ojo: esto lo hace la luz volumétrica (prendida en todos los presets). Si algún día la apagas (`mcopt.shade.vl=false`), se ve como FT2.11.
