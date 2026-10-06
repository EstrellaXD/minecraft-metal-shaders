#!/bin/sh
# Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
#
# Validates every MSL variant native shading compiles at runtime, assembled exactly like MetalPipeline does (slot defines +
# "#define MCOPT_SHADE" + shade_common.metal + stage, for the plain and pulled terrain vertex stages, all three terrain
# fragments, the sky pipelines with two uniform orders, the sky LUT pass), plus the upstream terrain MSL:
#   1. compile each with the Metal runtime compiler (MSL 3.0, fast math), like mc_library_new;
#   2. build a real MTLRenderPipelineState per vertex/fragment pair with mcopt's vertex layouts (catches stage interface
#      mismatches that compiling alone doesn't) and print the bound slots.
# Needs a built metal module (./gradlew :metal:build). Usage: tools/shade/validate.sh   (JAVA_HOME = a JDK 25)
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
METAL="$HERE/../../metal"
OUT="${TMPDIR:-/tmp}/mcopt-shade-validate"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
clang -fobjc-arc -O1 -framework Foundation -framework Metal "$HERE/pipecheck.m" -o "$OUT/pipecheck"
CP="$METAL/build/classes/java/main:$METAL/src/main/resources:$(find ~/.gradle/caches -name 'jspecify-*.jar' | grep -v sources | head -1)"
"${JAVA_BIN}javac" -d "$OUT/classes" -cp "$CP" "$HERE/ShadeMslDump.java"
"${JAVA_BIN}java" -cp "$OUT/classes:$CP" ShadeMslDump "$OUT/variants"
cp "$HERE"/gen_*.fs.metal "$OUT/"
cd "$OUT"
fail=0
for f in variants/*.metal; do
	./pipecheck compile "$f" >/dev/null || { echo "FAIL compile $f"; ./pipecheck compile "$f"; fail=1; }
done
for k in solid cutout translucent; do
	for p in up shade; do
		./pipecheck sodium variants/${p}_$k.vs.metal variants/${p}_$k.fs.metal || fail=1
		[ -f variants/${p}_$k.pulled.vs.metal ] && { ./pipecheck none variants/${p}_$k.pulled.vs.metal variants/${p}_$k.fs.metal || fail=1; }
	done
done
for i in 0 1; do ./pipecheck pos variants/sky$i.vs.metal variants/sky$i.fs.metal || fail=1; done
./pipecheck postex variants/celestial.vs.metal gen_position_tex.fs.metal || fail=1
./pipecheck poscolor variants/sunrise.vs.metal gen_position_color.fs.metal || fail=1
./pipecheck lut variants/sky_lut.metal variants/sky_lut.metal shade_lut_vs shade_lut_fs || fail=1
# clouds / weather against vanilla's generated stages, when a dump dir is given (MCOPT_GEN_DIR=<-Dmcopt.metal.dumpMsl dir>)
if [ -n "$MCOPT_GEN_DIR" ]; then
	./pipecheck none "$MCOPT_GEN_DIR/minecraft_pipeline_clouds.vs.metal" variants/clouds.fs.metal || fail=1
	[ -f variants/weather.fs.metal ] && { ./pipecheck compile variants/weather.fs.metal >/dev/null && echo "OK   variants/weather.fs.metal (compile)" || { ./pipecheck compile variants/weather.fs.metal; fail=1; }; }
fi
echo "validate: $([ $fail = 0 ] && echo ALL OK || echo FAILURES) ($OUT)"
exit $fail
