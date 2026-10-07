#!/bin/sh
# Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
# GPU cost of each volumetric light pass outside the game (vlbench.m). Usage: tools/shade/vlbench.sh [w h scale steps taps]
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
RES="$HERE/../../metal/src/main/resources/mcopt/shade"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/mcopt-vlbench.XXXXXX")"
clang -fobjc-arc -O1 -framework Foundation -framework Metal "$HERE/vlbench.m" -o "$OUT/vlbench"
{ echo "#define MCOPT_SHADE"; cat "$RES/shade_look.metal"; echo; cat "$RES/shade_common.metal"; echo; cat "$RES/shade_m2.metal"; echo; cat "$RES/vl.metal"; } > "$OUT/vl_full.metal"
"$OUT/vlbench" "$OUT/vl_full.metal" "$@"
rm -rf "$OUT"
