# FT2.10 — Cuevas sin neblina del atardecer, agua de cueva con olas, linterna sobre nieve, montañas y nubes lejanas

Jar: `mcopt-shade-FT2.10.jar` (rama `ft1` f0f2b48; SHA-256 0e74d89f6dbf… en `mcopt-shade-FT2.10.jar.sha256`). Base: FT2.9 (ver `FT2.9_NOTES.md`).
Instancias nuevas: **"mcopt Shaders Metal 26.3 (FT2.10)"** y **"(FT2.10 + DH)"** (con tu configuración de FT2.9, sin mundos).
Comparación: `FT2.10-comparacion.jpg` (izquierda FT2.9, derecha FT2.10). Pruebas y números: `work/notes/ft210-2026-10-10/README.md`.

Todo lo probé en el mundo de pruebas recreando tus fotos (no abrí tu mundo): una cueva al atardecer y al amanecer, un cuarto sellado
bajo tierra con una zanja de agua de 1 bloque y antorchas, una plataforma con nieve en capas de noche con una linterna en la mano, y
dos muros de piedra altos cerca del borde de la distancia de render como "montaña". Todo lo construido se borró al terminar cada prueba.

## 1. Neblina en las cuevas (y el "bloom" amarillo de la última foto)

Era la neblina del aire (la que da profundidad a lo lejos). En FT2.9 ya no tomaba la luz del **cielo** dentro de una cueva, pero
seguía tomando la luz del **sol**: la neblina más densa está justo a la altura de las cuevas, y al atardecer el sol es naranja y su
brillo hacia adelante es fuerte, así que la cueva se llenaba de neblina roja/naranja, y mirando hacia el poniente salía el resplandor
amarillo. Las sombras solo podían quitar hasta el 70 % de ese brillo.

Cómo lo hacen los otros: Photon quita la neblina del aire bajo el nivel del mar, Complementary y BSL la apagan según cuánta luz del
cielo te llega a los ojos, y los tres dejan en las cuevas, si acaso, una neblina **oscura y gris** muy tenue. Ahora hacemos lo mismo:
dentro de una cueva el aire no recibe sol ni cielo, y la poca neblina que queda es gris neutro y oscura (nunca de color).

| Vista (cueva del mundo de prueba) | "Velo" (0 = nada) | Tinte de color |
|---|---|---|
| Atardecer, mirando al sol | 55 → 21 | 33 → 9.5 |
| Amanecer, mirando al sol | 36 → 19 | 48 → 9 |
| Atardecer con antorcha en la mano | 48 → 34 | 61 → 38 (la luz cálida de la antorcha sigue) |
| Afuera: bosque con godrays, saliente, lago al atardecer | **igual** (brillo 0.999–1.000, ≤ 1.2 % de píxeles cambian) | igual |

## 2. El agua en la cueva (o de una sola fila)

En un lago las olas se notan porque reflejan el cielo (brillante en el horizonte, más oscuro arriba) y el sol. Bajo tierra no hay
ninguno de los dos y el reflejo caía en negro, así que el agua se veía como vidrio liso. Ahora, donde no llega el cielo, el agua refleja
su alrededor: la misma forma del cielo pero con la luz y el color del lugar (las antorchas), así que las **mismas olas del lago** se
ven también en una zanja de un bloque. Además el reflejo de paredes y antorchas se deforma más con las olas, y la luz de las antorchas
hace cáusticas en el fondo (se notan en pozas más hondas). Afuera el agua no cambia (lago y tira al aire libre: brillo 0.998–1.000).

## 3. La luz de la linterna se cortaba sobre la nieve

Para que la luz que cargas no atraviese paredes, el mod revisa qué bloques la tapan. Contaba **cada capa de nieve** (1/8 de bloque) como
un bloque sólido completo, así que la luz que sale de tus ojos, rozando el suelo, se cortaba a 3–4 bloques. El pasto chiquito no
tapaba. Ahora solo tapan los bloques completos; la nieve en capas, alfombras, losas, caminos y tierra de cultivo ya no cortan la luz.

## 4. Las nubes atravesaban las montañas al atardecer

En el borde de la distancia de render el terreno se desvanece en el cielo para que no se vea dónde se acaba el mundo. Ese "cielo" incluía
las nubes desde el principio, así que una montaña medio desvanecida dejaba ver las nubes de atrás (al atardecer se nota mucho por el
contraste). Ahora, como en Complementary, la montaña se desvanece primero en el cielo despejado y las nubes solo aparecen cuando ya casi
no queda montaña (el último 30 %).

## 5. Las franjas en las nubes lejanas (tu recorte)

Todas las nubes tenían la base perfectamente plana y a la misma altura; de lejos, cerca del horizonte, esas bases formaban líneas
horizontales oscuras apiladas como platos. Ahora las bases de las nubes **lejanas** (desde ~400 bloques) quedan a alturas un poco
distintas y más suaves; las nubes cercanas quedan idénticas a FT2.9. Las franjas de "platos" desaparecen; en algunas nubes medianas,
mirando hacia el sol, todavía quedan unos "dedos" horizontales más suaves en los bordes (probé más pasos de cálculo, más pasos de luz y
sin tablero de ajedrez, y ninguno los quita: vienen de la forma de las nubes vistas casi de lado). Si te siguen molestando, sigo con eso.

## Rendimiento

Igual que FT2.9. Recorrido de prueba a 3024×1964, lanzamientos alternados FT2.9 / FT2.10 / FT2.9 / FT2.10: girando 87.2 / 83.0 / 82.0 /
86.8 fps, volando 87.5 / 87.7 / 88.9 / 89.1 fps. Promedios: FT2.9 84.6 y 88.2, FT2.10 84.9 y 88.4 (la variación de 82–87 al girar la tienen
las dos: es ruido de la Mac). Con Distant Horizons las vistas quedan iguales (brillo 0.997–1.005).

## Opciones (por si quieres comparar o ajustar; todas se pueden cambiar en vivo)

| Opción | Normal | Igual que FT2.9 |
|---|---|---|
| `mcopt.shade.caveAir.sun` | true | false |
| `mcopt.shade.caveFog` (brillo de la neblina gris de cueva) | 0.5 | — (0 = hacia negro) |
| `mcopt.shade.water.caveSky` (reflejo del alrededor en agua sin cielo, 0–1) | 0.5 | 0 |
| `mcopt.shade.water.caveRipples` / `torchCaustics` | 1 / 1 | 0 / 0 |
| `mcopt.shade.dynLights.fullOccluders` | true | false |
| `mcopt.shade.clouds.lateFog` | true | false |
| `mcopt.shade.clouds.baseSpread` (bases de nubes lejanas, 0–0.6) | 0.25 | 0 |
