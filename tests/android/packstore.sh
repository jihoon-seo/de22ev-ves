#!/bin/bash
# Host test of vesd's PackStore (plain java.io): USB import into user slots, validation, refusals.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
JDK_BIN="${JDK_BIN:-${JAVA_HOME:+$JAVA_HOME/bin}}"
if [ -n "$JDK_BIN" ] && { [ -x "$JDK_BIN/javac" ] || [ -x "$JDK_BIN/javac.exe" ]; }; then
    JAVAC="$JDK_BIN/javac"; JAVA="$JDK_BIN/java"
else
    JAVAC=javac; JAVA=java
fi
native() { if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/cls" "$T/run"
mapfile -t S < <({ find "$ROOT/core/src/ves/core" -name '*.java'; echo "$ROOT/android/src/ves/android/PackStore.java";
    echo "$HERE/java/ves/android/PackStoreTest.java"; } | while IFS= read -r f; do native "$f"; done)
"$JAVAC" --release 8 -Xlint:-options -nowarn -encoding UTF-8 -d "$(native "$T/cls")" "${S[@]}" || { echo "FAIL packstore: compile"; exit 1; }
"$JAVA" -cp "$(native "$T/cls")" ves.android.PackStoreTest "$(native "$T/run")"
