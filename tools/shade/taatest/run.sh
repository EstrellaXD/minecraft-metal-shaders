#!/bin/sh
# Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
# Headless checks of the TAAU passes (no game): builds taatest.m against the jar build's libmcmetal.dylib.
#   tools/shade/taatest/run.sh                 correctness: plain vs TAAU error against a 4x4 supersampled truth
#   tools/shade/taatest/run.sh perf [W H S]    GPU time of the TAAU passes (default 3024 1964 0.67)
#   tools/shade/taatest/run.sh compile         both shader variants build and run (validate.sh calls this)
# Needs: ./gradlew :metal:build (or :metal:compileNative) first, clang.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="${TMPDIR:-/tmp}/mcopt-taatest"
mkdir -p "$OUT"
LIB="$ROOT/metal/build/natives/natives"
clang -fno-objc-arc -O1 -arch arm64 -I"$ROOT/metal/src/main/native" -framework Foundation -framework Metal "$HERE/taatest.m" "$LIB/libmcmetal.dylib" \
	-Wl,-rpath,"$LIB" -o "$OUT/taatest"
if [ "$1" = compile ]; then
	exec "$OUT/taatest" "$ROOT/metal/src/main/resources/mcopt/shade/taa.metal" compile
fi
if [ "$1" = perf ]; then
	exec "$OUT/taatest" "$ROOT/metal/src/main/resources/mcopt/shade/taa.metal" perf "${2:-3024}" "${3:-1964}" "${4:-0.67}"
fi
exec "$OUT/taatest" "$ROOT/metal/src/main/resources/mcopt/shade/taa.metal" correct
