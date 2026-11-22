#!/usr/bin/env bash
# Fast rebuild of just the VYRN mod jar. Needs mod/.work from a previous ./build-mod.sh run
# (it holds the remapped Minecraft jar and the Fabric/Mixin libraries); nothing is downloaded.
# Compiles mod/src, remaps it to intermediary names and copies the jar into the launcher resources.
#
# Usage: JAVA_HOME=/path/to/jdk21-or-newer ./mod/rebuild-mod.sh
set -euo pipefail

MOD_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(dirname "$MOD_DIR")"
WORK="$MOD_DIR/.work"
MC_VERSION="1.21.11"
LOADER_VERSION="0.19.5"
TINY_REMAPPER_VERSION="0.15.0"
MAPPING_IO_VERSION="0.9.1"
MOD_VERSION="1.0.0"
OUT_JAR="$REPO_DIR/src/main/resources/vclient-fabric/libraries/com/vclient/vclient-mod/$MOD_VERSION/vclient-mod-$MOD_VERSION.jar"

: "${JAVA_HOME:?Set JAVA_HOME to a JDK (21 or newer; javac, jar and java are used)}"
J="$JAVA_HOME/bin"
[ -d "$WORK/lib" ] || { echo "Run ./mod/build-mod.sh once first so mod/.work exists." >&2; exit 1; }
win() { cygpath -m "$1"; }
cd "$WORK"

find "$MOD_DIR/src/main/java" -name "*.java" | while read -r file; do win "$file"; done > sources.txt
printf -- '-cp\n"%s"\n' "$( (win "minecraft-$MC_VERSION-named.jar"; win "fabric-loader-$LOADER_VERSION.jar"; for jar in lib/*.jar; do win "$jar"; done; cat mclibs.txt) | tr '\n' ';')" > cp.arg
rm -rf out/mod; mkdir -p out/mod
"$J/javac" -nowarn --release 21 -encoding UTF-8 -proc:none "@cp.arg" -d out/mod "@sources.txt"
cp -r "$MOD_DIR/src/main/resources/." out/mod/
rm -f vclient-named.jar
"$J/jar" cf vclient-named.jar -C out/mod .

TR_CP="$(for jar in lib/asm-*.jar "lib/mapping-io-$MAPPING_IO_VERSION.jar" "tiny-remapper-$TINY_REMAPPER_VERSION.jar"; do win "$jar"; done | tr '\n' ';')"
rm -f "vclient-mod-$MOD_VERSION.jar"
"$J/java" -cp "$TR_CP" net.fabricmc.tinyremapper.Main vclient-named.jar "vclient-mod-$MOD_VERSION.jar" \
  "mappings-$MC_VERSION.tiny" named intermediary "$(win "minecraft-$MC_VERSION-named.jar")" --mixin
cp "vclient-mod-$MOD_VERSION.jar" "$OUT_JAR"
echo "== Rebuilt $OUT_JAR"
