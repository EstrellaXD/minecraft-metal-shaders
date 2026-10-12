# FT1 (avance) — Distant Horizons con nuestro shader

Jar: `mcopt-shade-FT1.jar` (rama `ft1` 4574606; SHA-256 en `mcopt-shade-FT1.jar.sha256`). Base: M3.4.

## Instancias nuevas (2026-10-08)
- **"mcopt Shaders Metal 26.3 (FT1 + DH)"**: shaders + Distant Horizons 3.3.4 + Fabric API + Sodium. Ajustes de DH de tu
  instancia "mcopt DH Metal 26.3" (radio 40, como Pollones). Java con ZGC.
- **"mcopt Shaders Metal 26.3 (FT1)"**: los mismos shaders sin DH. Sin DH no hay ningún cambio respecto a M3.4 (todo lo nuevo
  es para DH): es la versión más nueva por si quieres jugar sin DH.

## Qué cambió con DH
- Los LODs (terreno lejano de DH) ahora los ilumina nuestro shader: sol y luna, luz del cielo, antorchas, sombra de las nubes
  y la neblina de la distancia. Antes usaban la luz propia de DH: franja verde oscura al mediodía, siluetas negras al atardecer.
- Mientras corre el shader se apagan SSAO, TAA, niebla y sombreado de caras de DH (nuestro shader hace lo suyo). Tu archivo
  de ajustes de DH no cambia; si quitas el shader, DH vuelve a sus ajustes.
- Rendimiento (batería, DH a 40 chunks, Retina): M3.4 + DH 54 fps → FT1 + DH **58 fps**.

## Pendiente (siguiente versión)
- Revisar de cerca la unión entre chunks normales y LODs, y el borde lejano contra el cielo.
- El agua lejana de DH todavía se ilumina como terreno (sin reflejos).
- Más FPS con DH: dibujar DH directo en la imagen HDR (FT2) ahorraría la mayor parte de los ~6 ms que DH aún cuesta.
- Nether/End con DH: usan la luz normal del juego en los LODs (no probado a fondo).

Si algo falla: con `-Dmcopt.shade.dh.light=false` (argumentos de Java de la instancia) los LODs vuelven al look de DH.
Alpha: respalda tus mundos.
