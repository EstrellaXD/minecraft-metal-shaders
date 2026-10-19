#!/bin/sh
# Copyright 2026 mcopt-shade contributors. Apache-2.0.
# Build the mod first. Pass the MaLiLib jar and optionally an output directory.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../../.."
MALI="${1:?Pass the MaLiLib mod jar to test}"
OUT="${2:-$(mktemp -d "${TMPDIR:-/tmp}/mcopt-outlines.XXXXXX")}"
mkdir -p "$OUT/classes"
python3 - "$HERE" "$ROOT" "$MALI" "$OUT" <<'PY'
import os,pathlib,subprocess,sys
here,root,mali,out=map(pathlib.Path,sys.argv[1:])
lw=pathlib.Path(os.environ.get('LWJGL_DIR',str(pathlib.Path.home()/'Library/Application Support/PrismLauncher/libraries/org/lwjgl')))
jars=[str(p) for name in ['lwjgl','lwjgl-natives-macos-arm64','lwjgl-shaderc','lwjgl-shaderc-natives-macos-arm64','lwjgl-spvc','lwjgl-spvc-natives-macos-arm64'] for p in (lw/name/'3.4.3').glob('*.jar')]
mc=pathlib.Path(os.environ.get('MC_JAR',str(pathlib.Path.home()/'.gradle/caches/fabric-loom/26.3/minecraft-client.jar')))
cp=':'.join([str(root/'metal/build/classes/java/main'),str(root/'metal/src/main/resources'),str(mc)]+jars)
java=pathlib.Path(os.environ['JAVA_HOME'])/'bin' if 'JAVA_HOME' in os.environ else pathlib.Path('')
subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(out/'classes'),str(here/'OutlineCheck.java')],check=True)
cp+=':'+str(out/'classes')
subprocess.run([str(java/'java'),'--enable-native-access=ALL-UNNAMED','-Dmcopt.startup.mslCache=verify','-Dmcopt.startup.mslCacheDir='+str(out/'cache'),'-cp',cp,'mcopt.metal.OutlineCheck',str(mali),str(mc),str(out)],check=True)
PY
clang -fobjc-arc -O2 -framework Foundation -framework Metal "$HERE/check.m" -o "$OUT/check"
"$OUT/check" "$OUT"
