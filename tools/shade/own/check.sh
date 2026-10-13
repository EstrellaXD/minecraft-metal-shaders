#!/bin/sh
# Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0. Dev tool, not part of the mod.
# Offline check of mcopt's own terrain library (v12 port): builds the classes first (./gradlew :metal:build), then
#   tools/shade/own/check.sh [OUT_DIR]
# dumps the library as OwnTerrain builds it with and without the shade prefix (OwnDump, -Dmcopt.shade=true) and compiles
# both on this Mac's GPU (owncheck: kernels, base stages, the indie profile's draw kinds against HDR and RGBA8 targets).
# No game is launched.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../../.."
OUT="${1:-${TMPDIR:-/tmp}/owncheck}"
JH="${JAVA_HOME:-$ROOT/../../tools/jdk-25.0.4.1+1/Contents/Home}"
mkdir -p "$OUT/classes"
CP="$ROOT/metal/build/classes/java/main:$ROOT/metal/src/main/resources:$(find ~/.gradle/caches -name 'jspecify-*.jar' | grep -v sources | head -1)"
clang -fobjc-arc -O1 -framework Foundation -framework Metal "$HERE/owncheck.m" -o "$OUT/owncheck"
"$JH/bin/javac" -d "$OUT/classes" -cp "$CP" "$HERE/OwnDump.java"
"$JH/bin/java" -Dmcopt.shade=true -cp "$OUT/classes:$CP" OwnDump "$OUT/src"
"$OUT/owncheck" "$OUT/src/own_plain.metal" | grep -v '^OK   pso'
"$OUT/owncheck" "$OUT/src/own_shade.metal" shade | grep -v '^OK   pso'
