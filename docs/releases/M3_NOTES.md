# M3: mcopt-shade-M3.jar

Entregado el 2026-10-08 (madrugada). Instancia de Prism nueva: **"mcopt Shaders Metal 26.3 (M3)"** (copia de la de M2.2 con
este jar; preset balanced, estilo epic). Incluye todo lo de M2.2 (godrays estables) y M2.1 (agua).

Rama `m3-int` de `work/mcopt-shade` (worktree `work/wt/m3-int`; la rama `m3` queda igual a ella), código en el commit
`25c6ef9`. Jar SHA-256 `d34c002ba65e11ee0ec8375536f12d4a073a8d31a9e0dac59f5f5e73abdcc7e5`. Reemplaza al jar de M2.2 en una instancia de Prism (Minecraft 26.3, Fabric 0.19.5, Sodium
0.9.3-alpha.1). Todo va detrás de `mcopt.shade=true` (sin eso el mod es el mcopt original) y cada parte tiene su propio
interruptor (abajo).

## Qué trae (ES)

**Luz dinámica** (lo que pediste): la antorcha, linterna, glowstone, etc. que tengas en **cualquiera de las dos manos**
ilumina alrededor mientras caminas, con su color (la linterna de alma, celeste; la de redstone, roja). También dan luz los
mobs y jugadores que cargan antorchas, los ítems luminosos tirados, los mobs en llamas, los blazes, los cubos de magma, los
calamares brillantes, las flechas en llamas, la TNT encendida, los fuegos artificiales y los rayos. La luz no atraviesa
paredes (se calcula con los bloques reales) y se ve en el terreno, el agua (con su brillo en las olas) y los mobs.

**Mobs, ítems y partículas con nuestra luz**: antes los mobs se veían "planos", con la luz vieja del juego. Ahora reciben el
sol con sombras de verdad (cada mob proyecta su sombra, como en Iris), el cielo, las antorchas y la luz dinámica, igual
que el terreno. La mano en primera persona también.

**Nubes volumétricas**: nubes 3D de verdad (cúmulos esponjosos, cirros altos), con sombras que pasan sobre el terreno y
los mobs, reflejadas en el agua, con clima que cambia cada día, y los godrays saliendo entre ellas. Sigue habiendo un
estilo "por bloques" opcional.

**Lluvia de verdad**: el suelo se moja y se oscurece, se forman **charcos que reflejan como espejo** (antorchas, cielo,
árboles), gotas que hacen ondas en los charcos y en el agua, la lluvia se inclina con el viento y la ilumina el sol o las
antorchas, **rayos** que iluminan todo y se reflejan, nieve que cubre el suelo y nieve que cae. **Sombras de colores**: la
luz que pasa por vidrio de colores pinta el suelo (como un vitral).

**Cielo y dimensiones**: **aurora boreal** algunas noches en biomas fríos; el **Nether** con luz propia por bioma (neblina
que brilla, la lava ilumina desde abajo, calor que ondula a lo lejos en calidad alta); el **End** con cielo propio
(nebulosa, estrellas, rayos que suben del vacío, una luz violeta con sombras); efectos dentro de lava y nieve polvo; y
**salida XDR** para la pantalla de tu MacBook (el sol, la lava y las antorchas brillan más que el blanco normal).

**Atardecer nuevo** (lo que me dijiste: "se ve puro color naranja, plano"): el color del atardecer ahora depende de dónde
está el sol, como en el cielo real y en Photon / Complementary / BSL: degradado dorado-durazno ancho alrededor del sol
(con un halo cálido), durazno-rosa a los lados y del lado contrario un cielo lila sin el anillo naranja. La banda del
horizonte ya no es un amarillo-ámbar saturado (antes 227,176,36; ahora 215,175,134 a los lados y casi blanco-dorado junto
al sol). Los godrays se ven igual de fuertes que antes (medido). Interruptor: `mcopt.shade.sunsetArt=false` vuelve al de M2.

**Las líneas raras del mar que viste** (atardecer, cámara alta, nubes volumétricas): (1) las rayas finas de luz y las
columnas brillantes venían de la niebla de las nubes, que leía el color de la nube en la dirección equivocada: arreglado;
(2) los rectángulos brillantes en el agua eran el brillo del sol, que el agua de M2.1 ensanchaba con la distancia y las
sombras de los cerros cortaban en bloques: ahora solo se ensancha mirando el agua de canto (el camino de destellos desde
cerca queda igual); (3) las rayas horizontales de las nubes del horizonte eran pasos demasiado largos al mirar de lado:
casi desaparecen. Antes/después: `work/notes/m3-2026-10-07/gael-report-sunset-lines/` (README con la explicación).

## Costo en FPS

Medición final limpia (Mac conectada, M2.2 y M3 alternados en la misma sesión A-B-B-A dos veces, balanced, escala fija
0.80, pantalla completa 3024×1964, escena de prueba de día con cielo; corridas `bench-results/*fp1-*`):

| | M2.2 | M3 | Diferencia |
|---|---|---|---|
| Balanced, girando | 9.27 ms (~108 fps) | 13.02 ms (~77 fps) | **+3.8 ms** |
| Balanced, volando | 8.80 ms (~114 fps) | 11.66 ms (~86 fps) | **+2.9 ms** |

**M3 cuesta bastante más de lo que sumaban las partes medidas por separado** (~1–1.5 ms). Lo investigué apagando cada
parte (corridas `*bd1-*`) y comparando versiones intermedias con todo apagado (`*bs1-*`):
- Con **todas** las partes de M3 apagadas sigue costando **+1.2 ms** más que M2.2: el código de los módulos está dentro del
  shader del terreno (el que se ejecuta en cada píxel del mundo) aunque no se use, y eso lo hace más pesado. La mayor parte
  viene del primer paso de la integración (base + luces + cielo/dimensiones: +0.95 ms), nubes +0.26, lluvia +0.09.
- Con todo prendido, apagar una parte ahorra: luces ~0.6 ms girando, nubes ~0.5–0.7, lluvia ~0.4–0.5 (aunque no llueva:
  el código de lluvia ya pesa), cielo/dimensiones y mobs ~0 en esta escena.
- El arreglo de verdad es compilar versiones del shader del terreno sin el código que no se usa en ese momento (sin lluvia
  cuando no llueve, sin el Nether en el mundo normal, sin luces cuando no hay ninguna), en lugar de "si no se usa, se salta":
  es el **siguiente paso (M3.1, rendimiento)**. No se pudo terminar esta noche porque la pantalla de la Mac se bloqueó a las
  00:00 y el juego no puede abrirse con la pantalla bloqueada.

Mientras tanto, si quieres más FPS: apaga las nubes y/o la lluvia en `config/mcopt.properties` de la instancia (vienen
anotados), o usa el preset `performance`.

Costo de cada parte según la medición de cada módulo por separado (con su interruptor dentro de una sesión, balanced; no
incluye el peso del código en el shader, ver arriba):

| Parte | Costo | Cuándo |
|---|---|---|
| Base (mobs con nuestra luz y sombras, mano) | ≈ 0 en la escena de prueba | siempre |
| Luz dinámica | ~0.2 ms | con luces cerca |
| Nubes volumétricas | ~0.7–0.8 ms | de día con nubes (la parte más cara) |
| Lluvia | ~0.56 ms con lluvia fuerte; 0.18 ms en seco cerca de vidrio de colores | cuando llueve |
| Aurora | ~0.15–0.25 ms | noches con aurora |
| Nether | +0.6 ms vs M2 | en el Nether |
| Godrays M2.2 | +0.04 ms | siempre |

## Interruptores (en `config/mcopt.properties` o `-D...` en Prism)

`mcopt.shade.dynLights` (luz dinámica), `mcopt.shade.entities` / `entityShadows` / `handLight` (mobs, sombras de mobs,
mano), `mcopt.shade.clouds` (nubes volumétricas), `mcopt.shade.weather` (lluvia; `weatherLite` = recortes invisibles en
balanced), `mcopt.shade.colorShadows` (sombras de colores), `mcopt.shade.skydims` (aurora, Nether, End, XDR; por
separado `aurora`, `dims`, `media`, `xdr`), `mcopt.shade.sunsetArt` (atardecer nuevo), `mcopt.shade.skyRose` (rosa del
cielo al atardecer), `mcopt.shade.vl.waterM3` (godrays bajo el agua de M3), `mcopt.shade.waterGlintGraze` (arreglo del
brillo del agua). Todos en `false` = M2.2.

## Pendientes conocidos

- Nubes: un "peine" fino en los bordes suaves de las nubes con viento (se ve de cerca a resolución completa); cuestan el
  doble de lo planeado (ideas en CLOUDS.md §7.3).
- Lluvia: 0.56 ms (meta 0.5) y 0.18 ms en seco cerca de vidrio de colores (opción: apagar sombras de colores en balanced).
- Aurora algo más cara que lo planeado; el Nether cuesta +0.6 ms.
- Distant Horizons funciona con M3 pero el terreno lejano de DH todavía no usa nuestra luz (pálido de día, oscuro al
  atardecer): es el trabajo FT1/FT2. End + DH sigue roto (ya pasaba en M2.1).
- XDR no probado en un monitor externo SDR real.

Detalle técnico: `work/wt/m3-int/M3_INTEGRATION.md` y los docs de cada módulo (`BASE.md`, `DYNLIGHTS.md`, `CLOUDS.md`,
`WEATHER.md`, `SKYDIMS.md`, `VL_FLICKER.md`). Capturas: `work/release/M3-*-screenshots/`.
