#!/usr/bin/env bash
# Builds the VYRN in-game mod and the Fabric loader stack it runs on, using only
# GitHub and Maven Central (no Mojang or fabricmc.net servers), then stages everything
# into the launcher's resources so the launcher can install it fully offline.
#
# Requirements: Git Bash, git, curl, unzip, a JDK 21 at $JAVA_HOME, and a local
# Minecraft 1.21.11 client jar (VYRN's game folder already has one).
#
# Usage: JAVA_HOME=/path/to/jdk21 ./mod/build-mod.sh [path/to/1.21.11.jar]
set -euo pipefail

MOD_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(dirname "$MOD_DIR")"
WORK="$MOD_DIR/.work"
OUT_RESOURCES="$REPO_DIR/src/main/resources/vclient-fabric"
MC_VERSION="1.21.11"
LOADER_VERSION="0.19.5"
TINY_REMAPPER_VERSION="0.15.0"
CLASS_TWEAKER_VERSION="0.3.0"
MIXIN_VERSION="0.17.4+mixin.0.8.7"
MIXINEXTRAS_VERSION="0.5.5"
ASM_VERSION="9.10.1"
MAPPING_IO_VERSION="0.9.1"
SAT4J_VERSION="2.3.6"
MOD_VERSION="1.0.0"
PROFILE_ID="VYRN $MC_VERSION"

# Pinned source revisions.
LOADER_REF="0.19.5"
TINY_REMAPPER_COMMIT="4c71cd62a92ffe600049ba6814a2178a2ed728de"
FABRIC_TOOLING_COMMIT="b01b36ab9f89bb6b2a34482cd5a4f5bab27353e0"
YARN_COMMIT="ee98590897c919286b96b7c7f2c89ae4ac737762"
INTERMEDIARY_COMMIT="916868752f6935dda70c4490404641a701335d81"

: "${JAVA_HOME:?Set JAVA_HOME to a JDK 21}"
J="$JAVA_HOME/bin"
APPDATA_DIR="$(cygpath -u "${APPDATA:-$HOME/AppData/Roaming}")"
MINECRAFT_DIR="$APPDATA_DIR/VYRN/minecraft"
# Before a VYRN launcher has started once, the game still lives in the old V-Client folder.
[ -d "$MINECRAFT_DIR" ] || MINECRAFT_DIR="$APPDATA_DIR/V-Client/minecraft"
CLIENT_JAR="${1:-$MINECRAFT_DIR/versions/$MC_VERSION/$MC_VERSION.jar}"
[ -f "$CLIENT_JAR" ] || { echo "Minecraft $MC_VERSION client jar not found: $CLIENT_JAR" >&2; exit 1; }

win() { cygpath -m "$1"; }
mkdir -p "$WORK/lib" "$WORK/out"
cd "$WORK"

clone() { # clone <dir> <url> <ref>
  if [ ! -d "$1/.git" ]; then
    git -c core.longpaths=true clone -q --filter=blob:none "$2" "$1"
  fi
  git -C "$1" -c core.longpaths=true -c advice.detachedHead=false checkout -q "$3"
}

echo "== Fetching sources (GitHub)"
clone fabric-loader https://github.com/FabricMC/fabric-loader.git "$LOADER_REF"
clone tiny-remapper https://github.com/FabricMC/tiny-remapper.git "$TINY_REMAPPER_COMMIT"
clone fabric-tooling https://github.com/FabricMC/fabric-tooling.git "$FABRIC_TOOLING_COMMIT"
clone yarn https://github.com/FabricMC/yarn.git "$YARN_COMMIT"
[ -f intermediary.tiny ] || curl -sSfL -o intermediary.tiny \
  "https://raw.githubusercontent.com/FabricMC/intermediary/$INTERMEDIARY_COMMIT/mappings/$MC_VERSION.tiny"

echo "== Fetching libraries (Maven Central)"
central() { # central <group/path> <artifact> <version>
  local file="lib/$2-$3.jar"
  [ -f "$file" ] || curl -sSfL -o "$file" "https://repo1.maven.org/maven2/$1/$2/$3/$2-$3.jar"
}
for artifact in asm asm-analysis asm-commons asm-tree asm-util; do central org/ow2/asm "$artifact" "$ASM_VERSION"; done
central net/fabricmc mapping-io "$MAPPING_IO_VERSION"
central net/fabricmc sponge-mixin "$MIXIN_VERSION"
central io/github/llamalad7 mixinextras-fabric "$MIXINEXTRAS_VERSION"
central org/ow2/sat4j org.ow2.sat4j.core "$SAT4J_VERSION"
central org/ow2/sat4j org.ow2.sat4j.pb "$SAT4J_VERSION"
central org/jetbrains annotations 23.0.0

# Minecraft's own libraries (log4j, slf4j, gson, lwjgl, ...) from the local game folder.
find "$MINECRAFT_DIR/libraries" -name "*.jar" | while read -r jar; do win "$jar"; done > mclibs.txt
cpfile() { # cpfile <output arg file> <jar...>: javac classpath via an argument file
  local out="$1"; shift
  printf -- '-cp\n"%s"\n' "$( (for jar in "$@"; do win "$jar"; done; cat mclibs.txt) | tr '\n' ';')" > "$out"
}
javac_all() { # javac_all <classpath arg file> <out dir> <source list file>
  rm -rf "$2"; mkdir -p "$2"
  "$J/javac" -nowarn --release 21 -encoding UTF-8 -proc:none "@$1" -d "$2" "@$3"
}
ASM_JARS=(lib/asm-*.jar)

echo "== Building tiny-remapper"
find tiny-remapper/src/main/java -name "*.java" > sources.txt
cpfile cp.arg "${ASM_JARS[@]}" "lib/mapping-io-$MAPPING_IO_VERSION.jar"
javac_all cp.arg out/tiny-remapper sources.txt
cp -r tiny-remapper/src/main/resources/. out/tiny-remapper/
"$J/jar" cf "tiny-remapper-$TINY_REMAPPER_VERSION.jar" -C out/tiny-remapper .

echo "== Building class-tweaker"
find fabric-tooling/class-tweaker/src/main/java -name "*.java" > sources.txt
cpfile cp.arg "${ASM_JARS[@]}" lib/annotations-23.0.0.jar "tiny-remapper-$TINY_REMAPPER_VERSION.jar"
javac_all cp.arg out/class-tweaker sources.txt
"$J/jar" cf "class-tweaker-$CLASS_TWEAKER_VERSION.jar" -C out/class-tweaker .

echo "== Building fabric-loader $LOADER_VERSION"
L=fabric-loader
find $L/src/main/java $L/src/main/legacyJava $L/src/java17/java \
     $L/minecraft/src/main/java $L/minecraft/src/main/legacyJava -name "*.java" \
  | grep -v -i "launchwrapper" \
  | grep -v "/launch/FabricClientTweaker.java\|/launch/FabricServerTweaker.java" \
  | grep -v "src/main/java/net/fabricmc/loader/impl/mrj/AbstractSecureClassLoader.java" > sources.txt
cpfile cp.arg lib/*.jar "tiny-remapper-$TINY_REMAPPER_VERSION.jar" "class-tweaker-$CLASS_TWEAKER_VERSION.jar"
javac_all cp.arg out/fabric-loader sources.txt
cp -r $L/src/main/resources/. $L/minecraft/src/main/resources/. out/fabric-loader/
rm -f out/fabric-loader/fabric-installer*.json
sed -i "s/\${version}/$LOADER_VERSION/" out/fabric-loader/fabric.mod.json
printf 'Manifest-Version: 1.0\nAutomatic-Module-Name: net.fabricmc.loader\nFabric-Loom-Remap: false\n' > loader.mf
"$J/jar" cfm "fabric-loader-$LOADER_VERSION.jar" loader.mf -C out/fabric-loader .

echo "== Building intermediary mappings jar"
rm -rf out/intermediary; mkdir -p out/intermediary/mappings
cp intermediary.tiny out/intermediary/mappings/mappings.tiny
"$J/jar" cf "intermediary-$MC_VERSION.jar" -C out/intermediary .

echo "== Merging Yarn names and remapping Minecraft for compilation"
TR_CP="$(for jar in "${ASM_JARS[@]}" "lib/mapping-io-$MAPPING_IO_VERSION.jar" "tiny-remapper-$TINY_REMAPPER_VERSION.jar"; do win "$jar"; done | tr '\n' ';')"
if [ ! -f "mappings-$MC_VERSION.tiny" ]; then
  "$J/java" -cp "lib/mapping-io-$MAPPING_IO_VERSION.jar" "$(win "$MOD_DIR/tools/MapTool.java")" \
    intermediary.tiny yarn/mappings "mappings-$MC_VERSION.tiny"
fi
if [ ! -f "minecraft-$MC_VERSION-named.jar" ]; then
  # shellcheck disable=SC2046
  "$J/java" -cp "$TR_CP" net.fabricmc.tinyremapper.Main "$(win "$CLIENT_JAR")" "minecraft-$MC_VERSION-named.jar" \
    "mappings-$MC_VERSION.tiny" official named $(tr '\n' ' ' < mclibs.txt)
fi

echo "== Compiling the VYRN mod"
find "$MOD_DIR/src/main/java" -name "*.java" | while read -r file; do win "$file"; done > sources.txt
cpfile cp.arg "minecraft-$MC_VERSION-named.jar" "fabric-loader-$LOADER_VERSION.jar" lib/*.jar
javac_all cp.arg out/mod sources.txt
cp -r "$MOD_DIR/src/main/resources/." out/mod/
rm -f vclient-named.jar
"$J/jar" cf vclient-named.jar -C out/mod .
rm -f "vclient-mod-$MOD_VERSION.jar"
"$J/java" -cp "$TR_CP" net.fabricmc.tinyremapper.Main vclient-named.jar "vclient-mod-$MOD_VERSION.jar" \
  "mappings-$MC_VERSION.tiny" named intermediary "$(win "minecraft-$MC_VERSION-named.jar")" --mixin

echo "== Staging launcher resources"
rm -rf "$OUT_RESOURCES"
stage() { # stage <file> <maven group path> <artifact> <version>
  local target="$OUT_RESOURCES/libraries/$2/$3/$4"
  mkdir -p "$target"; cp "$1" "$target/$3-$4.jar"
}
for artifact in asm asm-analysis asm-commons asm-tree asm-util; do
  stage "lib/$artifact-$ASM_VERSION.jar" org/ow2/asm "$artifact" "$ASM_VERSION"
done
stage "lib/sponge-mixin-$MIXIN_VERSION.jar" net/fabricmc sponge-mixin "$MIXIN_VERSION"
stage "lib/mapping-io-$MAPPING_IO_VERSION.jar" net/fabricmc mapping-io "$MAPPING_IO_VERSION"
stage "lib/org.ow2.sat4j.core-$SAT4J_VERSION.jar" org/ow2/sat4j org.ow2.sat4j.core "$SAT4J_VERSION"
stage "lib/org.ow2.sat4j.pb-$SAT4J_VERSION.jar" org/ow2/sat4j org.ow2.sat4j.pb "$SAT4J_VERSION"
stage "tiny-remapper-$TINY_REMAPPER_VERSION.jar" net/fabricmc tiny-remapper "$TINY_REMAPPER_VERSION"
stage "class-tweaker-$CLASS_TWEAKER_VERSION.jar" net/fabricmc class-tweaker "$CLASS_TWEAKER_VERSION"
stage "fabric-loader-$LOADER_VERSION.jar" net/fabricmc fabric-loader "$LOADER_VERSION"
stage "intermediary-$MC_VERSION.jar" net/fabricmc intermediary "$MC_VERSION"
stage "lib/mixinextras-fabric-$MIXINEXTRAS_VERSION.jar" io/github/llamalad7 mixinextras-fabric "$MIXINEXTRAS_VERSION"
stage "vclient-mod-$MOD_VERSION.jar" com/vclient vclient-mod "$MOD_VERSION"

MOD_JAR='${library_directory}/com/vclient/vclient-mod/'"$MOD_VERSION/vclient-mod-$MOD_VERSION.jar"
EXTRAS_JAR='${library_directory}/io/github/llamalad7/mixinextras-fabric/'"$MIXINEXTRAS_VERSION/mixinextras-fabric-$MIXINEXTRAS_VERSION.jar"
mkdir -p "$OUT_RESOURCES/versions/$PROFILE_ID"
cat > "$OUT_RESOURCES/versions/$PROFILE_ID/$PROFILE_ID.json" <<EOF
{
  "id": "$PROFILE_ID",
  "inheritsFrom": "$MC_VERSION",
  "type": "release",
  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
  "arguments": {
    "game": [],
    "jvm": [
      "-DFabricMcEmu= net.minecraft.client.main.Main ",
      "-Dfabric.addMods=$MOD_JAR;$EXTRAS_JAR"
    ]
  },
  "libraries": [
    { "name": "org.ow2.asm:asm:$ASM_VERSION" },
    { "name": "org.ow2.asm:asm-analysis:$ASM_VERSION" },
    { "name": "org.ow2.asm:asm-commons:$ASM_VERSION" },
    { "name": "org.ow2.asm:asm-tree:$ASM_VERSION" },
    { "name": "org.ow2.asm:asm-util:$ASM_VERSION" },
    { "name": "net.fabricmc:sponge-mixin:$MIXIN_VERSION" },
    { "name": "net.fabricmc:mapping-io:$MAPPING_IO_VERSION" },
    { "name": "org.ow2.sat4j:org.ow2.sat4j.core:$SAT4J_VERSION" },
    { "name": "org.ow2.sat4j:org.ow2.sat4j.pb:$SAT4J_VERSION" },
    { "name": "net.fabricmc:tiny-remapper:$TINY_REMAPPER_VERSION" },
    { "name": "net.fabricmc:class-tweaker:$CLASS_TWEAKER_VERSION" },
    { "name": "net.fabricmc:intermediary:$MC_VERSION" },
    { "name": "net.fabricmc:fabric-loader:$LOADER_VERSION" }
  ]
}
EOF
(cd "$OUT_RESOURCES" && find . -type f ! -name manifest.txt | sed 's|^\./||' | sort > manifest.txt)
echo "== Done. Staged $(wc -l < "$OUT_RESOURCES/manifest.txt") files in $OUT_RESOURCES"
