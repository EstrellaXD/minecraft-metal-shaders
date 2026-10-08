#!/bin/sh
# Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
# GPU time of the final pass (shade_final, shade_final_xdr) of this tree vs another worktree's (default: ../m2.1), and the
# XDR compose vs the plain staging copy. Usage: run.sh [other worktree]
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
TREE="$HERE/../../.."
BASE="${1:-$TREE/../m2.1}"
OUT="${TMPDIR:-/tmp}/mcopt-finaltime"
mkdir -p "$OUT"
lib() { # tree -> prelude (whatever shade_*.metal of the prelude it has) + shade_post.metal
	R="$1/metal/src/main/resources/mcopt/shade"
	{ echo "#define MCOPT_SHADE"; cat "$R/shade_look.metal" "$R/shade_common.metal" "$R/shade_m2.metal"; [ -f "$R/shade_dims.metal" ] && cat "$R/shade_dims.metal"; cat "$R/shade_post.metal"; }
}
lib "$BASE" > "$OUT/base.metal"
lib "$TREE" > "$OUT/this.metal"
clang -fobjc-arc -O1 -framework Foundation -framework Metal "$HERE/finaltime.m" -o "$OUT/finaltime"
"$OUT/finaltime" "$OUT/base.metal" "$OUT/this.metal"
