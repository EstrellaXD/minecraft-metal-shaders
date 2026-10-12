# FT2.8 — Revisión completa del código: DH +10 % FPS, LODs mojados y con niebla, y muchos arreglos pequeños

Jar: `mcopt-shade-FT2.8.jar` (rama `ft1` db40ecc; SHA-256 5e50493ca634… en `mcopt-shade-FT2.8.jar.sha256`). Base: FT2.7.
Instancias nuevas: **"mcopt Shaders Metal 26.3 (FT2.8)"** y **"(FT2.8 + DH)"** (con tu configuración de FT2.7, sin mundos).

Con esto quedan revisados **los 118 hallazgos** de la auditoría de código: 78 arreglados (+1 en parte), 8 resultaron no ser errores,
4 duplicados, 1 no afecta, 4 quedan como opción apagada (se probaron y no mejoraban lo que se ve), 1 no se pudo arreglar desde el mod
(DH en DOUBLE_PASS: queda un aviso) y 21 pospuestos con su motivo
(tabla completa: `work/notes/audit-2026-10-09/ESTADO.md`).

## Rendimiento (pantalla Retina 3024×1964, preset balanced, Mac conectada, FT2.7 y FT2.8 alternando en la misma sesión)

| | FT2.7 | FT2.8 |
|---|---|---|
| Con Distant Horizons, girando | 52–54 fps | **57–60 fps** (−1.6 a −1.7 ms por cuadro) |
| Con Distant Horizons, volando | 53–55 fps | **59–62 fps** (−1.8 a −2.0 ms) |
| Sin DH, girando sobre el lago | 82–84 fps | **86–87 fps** (−0.4 a −0.5 ms) |
| Sin DH, volando | 87–87.5 fps | **89.5 fps** (−0.3 ms) |

(Jar final: 2 pares sin DH y 1 con DH; todos los pares a favor de FT2.8. Las mediciones de cada cambio por separado están en
`work/notes/ft28-2026-10-09/`.) Hoja comparativa: `FT2.8-comparacion.jpg` (FT2.7 a la izquierda, FT2.8 a la derecha: DH al mediodía,
DH con lluvia —los LODs ya se mojan—, y tu taiga p7).

De dónde sale (todo con la misma imagen):
- **DH:** los LODs mandaban 7 datos "planos" por vértice (luz, cara, textura…); ahora van en uno solo. La composición de DH ya no
  guarda y vuelve a cargar la imagen HDR completa (se junta con el pase del mundo), y dos pases de DH ya no cargan una imagen que
  van a sobrescribir. La conversión de color de DH hace 3 pasos de cálculo en vez de 4 (el resultado es idéntico).
- **Reflejos del agua:** la búsqueda del reflejo hace sus lecturas en 2 rondas en vez de 4 seguidas (−0.06 a −0.09 ms).
- **Sombras:** el mapa de sombras usa 16 bits en vez de 32 (−0.1 ms; se comparó con el sol bajo, al mediodía y en tu taiga: igual)
  y las hojas se dibujan de arriba hacia abajo en él (−0.03 ms).
- **TAA (suavizado):** una sola matriz para reproyectar y una versión sin el código de "animales en movimiento" cuando no hay
  ninguno (−0.06 ms medido aparte).
- Varios recortes pequeños en nubes, niebla, godrays, histograma de exposición y en el procesador (menos llamadas por cuadro).

## Lo que vas a notar

- **Distant Horizons con lluvia:** el terreno cercano se oscurecía mojado y los LODs no → se veía la costura. Ahora los LODs también se
  mojan (y se cubren de nieve donde nieva).
- **Nether / End con DH:** los LODs no tenían niebla mientras el terreno cercano sí se perdía en el humo (un "anillo" al revés).
  Ahora la niebla cubre parejo cerca y lejos.
- **DH con su configuración de fábrica (problema conocido, no te afecta):** si el "vanilla fade" de DH está en DOUBLE_PASS (el valor
  que DH trae de fábrica), el mundo cercano desaparece. Cambiarlo desde el mod con el juego abierto no sirve (DH lo decide al
  arrancar), así que ahora el juego escribe un aviso en el registro: ponerlo en SINGLE_PASS o NONE y reiniciar. **Tu instancia usa
  NONE: se probó desde el arranque y funciona bien.**
- **LODs cercanos:** se veía una rejilla fina en los bordes de los bloques que temblaba. Arreglado.
- **Mar lejano de DH:** ya no se dibuja el sol dos veces en el reflejo, y las siluetas de las colinas cercanas no "sangran" en él.
- **Nubes en los reflejos** (fuera de la pantalla y en el mar de DH): ya no "hierven" ni brillan como chispitas.
- **Catalejo:** las nubes se ven nítidas en cuanto haces zoom (antes tardaban 1–2 s en enfocar).
- **Luna:** ya no oscurece el cielo que tiene delante (un disco oscuro sobre el cielo del atardecer).
- **Sombras suaves:** ya no hay un anillo a 40 bloques donde de golpe se volvían más duras.
- **Mobs, jugadores, caballos y botes moviéndose con el sol bajo:** ya no se sombrean a sí mismos con su "copia" del cuadro anterior.
- **Charcos al secarse:** el último poquito ya no desaparece de golpe; se desvanece.
- **Luces dinámicas** (antorcha en la mano): la sombra que proyectan estaba corrida un bloque. Arreglado.
- **Exposición automática:** el medidor ignoraba sin querer la esquina superior derecha de la imagen (casi siempre cielo). Ahora
  cuenta toda la imagen: en vistas con cielo arriba a la derecha la imagen sale ~2 % más oscura (casi no se nota; menos velo).
- **Macs con chip M1:** una opción de compresión que solo existe desde el M2 se pedía sin revisar; ahora se revisa (para cuando lo
  subamos a GitHub).

## Probado y dejado apagado (no mejoraba lo que se ve)

- Giro del patrón de las sombras suaves en cada cuadro: hacía **temblar las hojas** en tomas quietas (se queda el patrón fijo de FT2.7;
  `-Dmcopt.shade.pcssNoise=true` para probarlo).
- Filtro del TAA en espacio comprimido contra anillos alrededor del sol: no se vio ningún anillo que quitar y costaba +0.08 ms
  (`-Dmcopt.shade.taaCompressTaps=true`).
- Sombra "de contacto" en el agua junto a orillas altas: no se vio la franja oscura en las orillas de tu lago
  (`-Dmcopt.shade.waterShadowContact=0.125`).
- Borde brillante en hojas cercanas hacia el sol (FT2.7, `mcopt.shade.vl.edgeFix`): no apareció en tu bosque de otoño.

## Pospuestos (los más importantes)

- Sombras de nubes más allá de ~470 bloques con DH; godrays que "ven" los LODs de DH (necesita la profundidad de DH en los godrays).
- TAA con la distancia real de los LODs (un poco de estela en las siluetas lejanas al volar muy rápido con élitros).
- Velocidad de la auto-exposición al salir de una cueva: **es decisión tuya** (se adapta lento al pasar de oscuro a claro; te puedo
  mostrar un video antes y después).
- Optimizaciones que cuestan más trabajo que lo que ahorran (~0.01–0.05 ms cada una).

Si ves algo raro, mándame captura con F3 y te digo qué es.
