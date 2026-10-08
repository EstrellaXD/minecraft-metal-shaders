#!/bin/sh
# Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
# GPU cost of the M3 sky/dimension functions at 3024x1964 (every pixel pays). Needs a built metal module (the prelude is
# assembled by tools/shade/ShadeMslDump through validate.sh's variants dir, or here from the resources directly).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
R="$HERE/../../../metal/src/main/resources/mcopt/shade"
OUT="${TMPDIR:-/tmp}/mcopt-dimstime"
mkdir -p "$OUT"
{ echo "#define MCOPT_SHADE"; cat "$R/shade_look.metal" "$R/shade_common.metal" "$R/shade_m2.metal" "$R/shade_dims.metal" "$R/shade_post.metal" "$HERE/dimstime.metal"; } > "$OUT/lib.metal"
clang -fobjc-arc -O1 -framework Foundation -framework Metal "$HERE/dimstime.m" -o "$OUT/dimstime"
"$OUT/dimstime" "$OUT/lib.metal"
