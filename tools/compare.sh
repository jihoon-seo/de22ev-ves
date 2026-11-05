#!/bin/bash
# Renders comparison WAVs into build/compare/: the procedural synth and each built-in pack, through the
# whole runtime chain (EngineModel, PackVoice/Synth, OutputStage) for the same drives, so they can be
# listened to on the PC before going to the car. Needs a JDK (JAVA_HOME or PATH) and the packs from
# tests/render/run.sh (build/render/<engine>/).
#   tools/compare.sh [volume=70] [scenario ...]   (default scenarios: showcase city)
set -eu
cd "$(dirname "$0")/.."
JDK_BIN="${JDK_BIN:-${JAVA_HOME:+$JAVA_HOME/bin}}"
if [ -n "$JDK_BIN" ] && { [ -x "$JDK_BIN/javac" ] || [ -x "$JDK_BIN/javac.exe" ]; }; then
    JAVAC="$JDK_BIN/javac"; JAVA="$JDK_BIN/java"
else
    JAVAC=javac; JAVA=java
fi
native() { if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
VOL=70
case "${1:-}" in volume=*) VOL=${1#volume=}; shift ;; esac
SCEN=("$@")
[ ${#SCEN[@]} -gt 0 ] || SCEN=(showcase city)
OUT=build/compare
CLS=build/compare/classes
rm -rf "$OUT"
mkdir -p "$CLS"
mapfile -t S < <(find core/src -name '*.java' | while IFS= read -r f; do native "$f"; done)
"$JAVAC" --release 8 -Xlint:-options -nowarn -encoding UTF-8 -d "$(native "$CLS")" "${S[@]}"
for sc in "${SCEN[@]}"; do
    "$JAVA" -cp "$(native "$CLS")" ves.tools.Sweep "$(native "$OUT/$sc.csv")" "$sc" > /dev/null
    for prof in ice_smooth ice_shift; do
        "$JAVA" -cp "$(native "$CLS")" ves.tools.Replay "$(native "$OUT/$sc.csv")" "$(native "$OUT/$sc-synth-$prof.wav")" \
            profile=$prof volume=$VOL > /dev/null
    done
    for e in i4t v6 v8 i6; do
        [ -f "build/render/$e/pack.txt" ] || { echo "no build/render/$e (run tests/render/run.sh)"; exit 1; }
        "$JAVA" -cp "$(native "$CLS")" ves.tools.Replay "$(native "$OUT/$sc.csv")" "$(native "$OUT/$sc-$e.wav")" \
            profile=ice_shift volume=$VOL "pack=$(native "build/render/$e")" > /dev/null
    done
done
rm -rf "$CLS"
ls -1 "$OUT"/*.wav
