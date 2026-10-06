#!/bin/sh
# Headless checks of the sun shadow pass (no game): builds against the jar build's libmcmetal.dylib and the sources.
#   tools/shade/shadowtest/run.sh            correctness: synthetic Sodium arena vs ray-cast ground truth, several sun angles
#   tools/shade/shadowtest/run.sh perf       GPU time of one cascade pass over a 256x256 heightmap (~83k sun-facing quads)
# Needs: ./gradlew :metal:build first (for metal/build/natives/natives/libmcmetal.dylib), a JDK in JAVA_HOME, clang.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="${TMPDIR:-/tmp}/mcopt-shadowtest"
mkdir -p "$OUT/cls"
LIB="$ROOT/metal/build/natives/natives"
NATIVE="$ROOT/metal/src/main/native"
SHADE="$ROOT/metal/src/main/resources/mcopt/shade"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
mkdir -p "$OUT/src/mcopt/metal/shade/shadow"
cp "$ROOT/metal/src/main/java/mcopt/metal/shade/shadow/ShadowCascade.java" "$HERE/GenMatrices.java" "$HERE/Reexpress.java" "$OUT/src/mcopt/metal/shade/shadow/"
"$JAVAC" -d "$OUT/cls" "$OUT"/src/mcopt/metal/shade/shadow/*.java
if [ "$1" = perf ]; then
	clang -fno-objc-arc -O2 -arch arm64 -I"$NATIVE" -framework Foundation -framework Metal "$HERE/perf.m" "$LIB/libmcmetal.dylib" -Wl,-rpath,"$LIB" -o "$OUT/perf"
	for c in 0 1419; do CHUNKS=$c "$OUT/perf" "$SHADE/shadow.metal" "${2:-2048}"; done
	exit 0
fi
clang -fno-objc-arc -O1 -arch arm64 -I"$NATIVE" -framework Foundation -framework Metal "$HERE/harness.m" "$LIB/libmcmetal.dylib" -Wl,-rpath,"$LIB" -o "$OUT/harness"
"$JAVA" -cp "$OUT/cls" mcopt.metal.shade.shadow.Reexpress
status=0
for bits in 32 16; do
	for ang in -20 -30 -45 -60 -70 25; do
		"$JAVA" -cp "$OUT/cls" mcopt.metal.shade.shadow.GenMatrices "$OUT/mat.bin" $ang > /dev/null
		echo "== Depth$bits, sun angle $ang"
		BITS=$bits "$OUT/harness" "$SHADE/shadow.metal" "$OUT/mat.bin" "$SHADE/shade_shadow.metal" || status=1
	done
done
exit $status
