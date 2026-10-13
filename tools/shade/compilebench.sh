#!/bin/sh
# Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
#
# Shader compile times outside the game (compilebench.m), with Metal's shader cache cold (a first launch, a mod update that
# changes the MSL, the first launch after a macOS update) unless WARM=1. Dumps the MSL native shading compiles (ShadeMslDump,
# like validate.sh), then times:
#   1. shade's own module libraries + an estimate of their pipelines (every fragment / kernel of each library): one after
#      another (the render thread at the first world frame, before) vs 10 at once (ShadeCompile's warm-up);
#   2. the Sodium terrain's 48 lean prewarm variants (MetalPipeline.prewarm: 24 translucent, 12 solid, 12 cutout): the
#      translucent ones one after another (the longest chain of the startup reload before) vs all 48 on a pool of 10.
# The terrain variants and the dump stay in $OUT for tools/shade/compilecheck/run.sh. Needs: ./gradlew :metal:build, clang,
# python3, JAVA_HOME = a JDK 25 (default: work/tools's). Usage: tools/shade/compilebench.sh [JOBS]  (default 10)
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
METAL="$ROOT/metal"
JDK="${JAVA_HOME:-$ROOT/../../tools/jdk-25.0.4.1+1/Contents/Home}"
OUT="${COMPILECHECK_OUT:-${TMPDIR:-/tmp}/mcopt-compilecheck}"
JOBS="${1:-10}"
COLD="--cold"
[ -n "$WARM" ] && COLD=""
mkdir -p "$OUT/classes"
cat > "$OUT/compilebench.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>CFBundleIdentifier</key><string>org.mcoptshade.compilebench</string><key>CFBundleName</key><string>compilebench</string></dict></plist>
PLIST
# the bundle id gives the tool a Metal shader cache of its own (--cold empties only that one)
clang -fobjc-arc -O1 -Wall -framework Foundation -framework Metal "$HERE/compilebench.m" -o "$OUT/compilebench" -sectcreate __TEXT __info_plist "$OUT/compilebench.plist"
CP="$METAL/build/classes/java/main:$METAL/src/main/resources:$(find "$HOME/.gradle/caches" -name 'jspecify-*.jar' | grep -v sources | head -1)"
"$JDK/bin/javac" -nowarn -d "$OUT/classes" -cp "$CP" "$HERE/ShadeMslDump.java"
rm -rf "$OUT/variants" "$OUT/lean"
"$JDK/bin/java" -Dmcopt.shade=true -cp "$OUT/classes:$CP" ShadeMslDump "$OUT/variants" >/dev/null
V="$OUT/variants"
# the module libraries as the modules compile them (ShadeMslDump writes most; TAA and shadows from their resources)
{ printf '#define TAA_LINEAR_HISTORY\n'; cat "$METAL/src/main/resources/mcopt/shade/taa.metal"; } > "$V/taa_module.metal"
cp "$METAL/src/main/resources/mcopt/shade/shadow.metal" "$V/shadow_module.metal"
set -- "$V/sky_lut.metal" "$V/post.metal" "$V/water_passes.metal" "$V/weather_noise.metal" "$V/aurora_pass.metal" "$V/vl.metal" \
	"$V/clouds_passes.metal" "$V/dynlight_vis.metal" "$V/taa_module.metal" "$V/shadow_module.metal"
echo "== 1. shade module libraries + pipelines"
"$OUT/compilebench" $COLD --psos "$@" | tail -1 | sed 's/^/one after another: /'
"$OUT/compilebench" $COLD --psos -j "$JOBS" --split --qos initiated "$@" | tail -1 | sed "s/^/$JOBS at once:        /"
# the lean variants MetalPipeline.prewarm builds per terrain pipeline (ShadeMsl.prewarm, leanDefines)
mkdir -p "$OUT/lean"
cp "$V/hdr_solid.vs.metal" "$OUT/lean/terrain.vs.metal"
python3 - "$V" "$OUT/lean" <<'PY'
import os, sys
src, out = sys.argv[1], sys.argv[2]
OW, DRY, NODL, NETHER, ABOVE, NOFALLS = 1, 2, 4, 16, 32, 128
NAMES = [(OW, 'OW'), (DRY, 'DRY'), (NODL, 'NODL'), (NETHER, 'NETHER'), (ABOVE, 'ABOVE'), (NOFALLS, 'NOFALLS')]
for k in ('solid', 'cutout', 'translucent'):
    base = open(os.path.join(src, 'hdr_%s.fs.metal' % k)).read()
    rest = base[len('#define MCOPT_HDR\n'):]
    sets = list(range(8)) + [NETHER | t << 1 for t in range(4)]
    if k == 'translucent':
        sets += [OW | (t & 3) << 1 | ABOVE for t in range(4)] + [OW | t << 1 | NOFALLS for t in range(4)] + [OW | (t & 3) << 1 | ABOVE | NOFALLS for t in range(4)]
    for t in sets:
        defs = ''.join('#define SHADE_LEAN_%s\n' % n for bit, n in NAMES if t & bit)
        open(os.path.join(out, '%s_%03d.fs.metal' % (k, t)), 'w').write('#define MCOPT_HDR\n' + defs + rest)
PY
echo "== 2. terrain prewarm variants"
"$OUT/compilebench" $COLD --qos initiated --terrain "$OUT/lean/terrain.vs.metal" "$OUT"/lean/translucent_*.fs.metal 2>/dev/null | tail -1 | sed 's/^/translucent, one after another: /'
"$OUT/compilebench" $COLD -j "$JOBS" --qos initiated --terrain "$OUT/lean/terrain.vs.metal" "$OUT"/lean/*_*.fs.metal 2>/dev/null | tail -1 | sed "s/^/all 48, $JOBS at once:        /"
