#!/bin/bash
# tour.sh (2026-10-11): records the preset tour: for every preset and output resolution, one game launch with the three F3
# clips of gen_tour.py (16 chunks, Gael's settings otherwise). Videos in rec/tour-<preset>-<output>.mp4.
S="/Users/gaelquintanilla/Documents/Minecraft Metal Shaders/work/showcase"; cd "$S"
T="${CINE_TMP:-/private/tmp/claude-501/-Users-gaelquintanilla-Documents-Minecraft-Metal-Shaders/a85d8bd1-3bde-4782-a4eb-336263be40d6/scratchpad/cine}"
cp run.sh "$T/run-tour.sh"
for O in ${OUTS:-native 1440 1080}; do
  for P in ${PRESETS:-performance balanced quality ultra}; do
    python3 specs/gen_tour.py "$P" "$O"
    echo "== $P $O"
    bash "$T/run-tour.sh" "specs/tour-$P-$O.json" "tour-$P-$O" --rec --rd=16 --out="$O" -Dmcopt.shade.preset="$P"
  done
done
echo TOUR DONE
