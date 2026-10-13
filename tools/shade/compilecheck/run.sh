#!/bin/sh
# Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
# Headless checks of native shading's parallel compile (mcopt.metal.shade.ShadeCompile), no game, in a process with a Metal
# shader cache of its own (jvmrun.m), so "cold" never touches the game's cache:
#   tools/shade/compilecheck/run.sh modules [cold|warm]   shade's module setup compiles: one after another (the render
#                                                         thread before) vs after the pool's warm-up (the render thread now)
#   tools/shade/compilecheck/run.sh prewarm [cold|warm]   the Sodium terrain's 48 prewarm variants (tools/shade/compilebench.sh
#                                                         writes them): one after another vs through the prewarm runner
# Needs: ./gradlew :metal:build first, clang, the JDK in work/tools (or JAVA_HOME), LWJGL 3.4.3 jars (Prism's libraries
# folder, or LWJGL_DIR). Extra JVM switches: CHECK_PROPS="-Dmcopt.shade.compile.threads=4 ...".
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
JDK="${JAVA_HOME:-$ROOT/../../tools/jdk-25.0.4.1+1/Contents/Home}"
LW="${LWJGL_DIR:-$HOME/Library/Application Support/PrismLauncher/libraries/org/lwjgl}"
MC="${MC_JAR:-$(ls "$HOME"/.gradle/caches/fabric-loom/26.3/minecraft-client.jar 2>/dev/null || true)}"
OUT="${COMPILECHECK_OUT:-${TMPDIR:-/tmp}/mcopt-compilecheck}"
mkdir -p "$OUT/classes"
cat > "$OUT/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>CFBundleIdentifier</key><string>org.mcoptshade.compilecheck</string><key>CFBundleName</key><string>compilecheck</string></dict></plist>
PLIST
clang -fobjc-arc -O1 -I"$JDK/include" -I"$JDK/include/darwin" -framework Foundation "$HERE/jvmrun.m" -o "$OUT/jvmrun" -sectcreate __TEXT __info_plist "$OUT/Info.plist"
CP="$ROOT/metal/build/classes/java/main:$ROOT/metal/build/resources/main:$LW/lwjgl/3.4.3/lwjgl-3.4.3.jar:$LW/lwjgl-natives-macos-arm64/3.4.3/lwjgl-natives-macos-arm64-3.4.3.jar"
[ -n "$MC" ] && CP="$CP:$MC"
"$JDK/bin/javac" -nowarn -d "$OUT/classes" -cp "$CP:$(find "$HOME/.gradle/caches" -name 'jspecify-*.jar' | grep -v sources | head -1)" "$HERE/CompileCheck.java"
WHAT="${1:-modules}"
TEMP="${2:-cold}"
run() { # run PROPS... -- ARGS...
	if [ "$TEMP" = cold ]; then export JVMRUN_COLD=1; else unset JVMRUN_COLD; fi
	"$OUT/jvmrun" "$JDK/lib/server/libjvm.dylib" mcopt.metal.CompileCheck "-Djava.class.path=$OUT/classes:$CP" --enable-native-access=ALL-UNNAMED \
		-Dmcopt.shade=true $CHECK_PROPS "$@"
}
if [ "$WHAT" = modules ]; then
	run -- modules seq
	run -- modules par
else
	V="${VARIANTS:-$OUT/lean}"
	[ -d "$V" ] || { echo "no terrain variants in $V: run tools/shade/compilebench.sh first (it writes them there), or set VARIANTS"; exit 1; }
	run -- prewarm seq "$V/terrain.vs.metal" "$V"/*_*.fs.metal
	run -Dmcopt.shade.compile.warm=false -- prewarm par "$V/terrain.vs.metal" "$V"/*_*.fs.metal
fi
