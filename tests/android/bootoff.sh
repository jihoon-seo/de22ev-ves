#!/bin/bash
# Host test of vesd's BootOff (plain Java): VES off at head-unit boot, the writer's retries, the owner's changes.
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
mkdir -p "$T/cls"
mapfile -t S < <({ echo "$ROOT/core/src/ves/core/BootReset.java"; echo "$ROOT/core/src/ves/core/Settings.java";
    echo "$ROOT/android/src/ves/android/BootOff.java"; echo "$HERE/java/ves/android/BootOffTest.java"; } \
    | while IFS= read -r f; do native "$f"; done)
"$JAVAC" --release 8 -Xlint:-options -nowarn -encoding UTF-8 -sourcepath "$(native "$ROOT/core/src")" \
    -d "$(native "$T/cls")" "${S[@]}" || { echo "FAIL bootoff: compile"; exit 1; }
"$JAVA" -cp "$(native "$T/cls")" ves.android.BootOffTest
