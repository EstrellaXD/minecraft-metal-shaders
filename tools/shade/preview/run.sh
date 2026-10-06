#!/bin/sh
# Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
#
# Art preview without the game: packs real ShadeFrames (the mod's ShadeFrame/ShadeSky: sun, ambient, exposure) for noon,
# morning, afternoon, sunset, dusk, night, rain and a cave view, renders an analytic test scene (boxes on grass, analytic
# shadows, a torch) with shade_common.metal's own lighting, fog, sky LUT and tone curve, and writes PNGs to
# $OUT/out/*.png. skytime then prints the GPU cost of a full-screen 3024x1964 sky. Usage: tools/shade/preview/run.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../../metal/src/main"
OUT="${TMPDIR:-/tmp}/mcopt-shade-preview"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JOML="$(find ~/.gradle/caches/modules-2 -name 'joml-1.10*.jar' | grep -v sources | head -1)"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/frames" "$OUT/out"
"${JAVA_BIN}javac" -d "$OUT/classes" -cp "$JOML" "$SRC/java/mcopt/metal/shade/ShadeSky.java" "$SRC/java/mcopt/metal/shade/ShadeFrame.java" "$HERE/ShadePreviewFrames.java"
"${JAVA_BIN}java" -cp "$OUT/classes:$JOML" ShadePreviewFrames "$OUT/frames"
cat "$SRC/resources/mcopt/shade/shade_common.metal" "$SRC/resources/mcopt/shade/sky_lut.metal" "$HERE/preview_scene.metal" > "$OUT/preview_full.metal"
clang -fobjc-arc -O1 -Wno-enum-conversion -framework Foundation -framework Metal -framework CoreGraphics -framework ImageIO -framework UniformTypeIdentifiers "$HERE/render.m" -o "$OUT/render"
clang -fobjc-arc -O1 -framework Foundation -framework Metal "$HERE/skytime.m" -o "$OUT/skytime"
cd "$OUT"
for f in frames/*.bin; do ./render preview_full.metal "$f" "out/$(basename "$f" .bin).png"; done
./skytime preview_full.metal frames/noon_up.bin
