# FT2.14 (2026-10-12 00:35) — bloques mojados más naturales con lluvia

Instancias: **"mcopt Shaders Metal 26.3 (FT2.14)"** y **"(FT2.14 + DH)"** (config de las FT2.13, jar nuevo y tu
"Mundo principal prueba shaders" más nuevo, el de la FT2.13 guardado a las 23:52, en las dos). En GitHub: versión
**1.0-alpha.4**. Jar: `mcopt-shade-FT2.14.jar` (ft1 67a669a).

## Arreglos

- **Paredes mojadas sin velo blanco.** Antes las paredes (un hoyo, los escalones de un cerro) se veían blancuzcas con lluvia,
  "como si reflejaran algo que no está ahí": el reflejo que apuntaba hacia abajo tomaba el cielo del horizonte. Ahora las
  paredes solo se oscurecen un poco (como en Photon, BSL y SEUS) y lo que se refleja hacia abajo es la luz tenue del suelo.
  Esto también quita la "esfera" oscura con cuadrícula sobre los valles (un cerro escalonado con sus caras verticales claras).
- **Pasto y hojas mojadas:** ya no se ponen casi negros: 72 % del brillo del pasto seco (antes 32 %). La piedra, la tierra y
  la madera sí se siguen oscureciendo (son porosas). También en el terreno lejano de Distant Horizons.
- **Menos charcos sobre el pasto** (~1/3, menos espejo): de lado ya no parecen losas de vidrio. En piedra, tierra y caminos
  los charcos siguen igual, con sus reflejos.

Comparación: `lluvia-paredes-comparacion.jpg` (izquierda FT2.13, centro FT2.14, derecha seco).

## Números

- FPS con lluvia (misma sesión, alternando): FT2.13 99.0 / 96.4 → FT2.14 100.4 / 98.7 (GPU 10.24 → 10.05 ms por cuadro).
- Con Distant Horizons y lluvia: sin costura entre lo cercano y lo lejano.

## Opciones (en config/mcopt.properties; se aplican al abrir el juego)

- `mcopt.shade.wetWalls=false`: paredes como en FT2.13.
- `mcopt.shade.wetVegLight=false`: el pasto mojado tan oscuro como antes.
- `mcopt.shade.wetVegPuddles=false`: tantos charcos en el pasto como antes.
