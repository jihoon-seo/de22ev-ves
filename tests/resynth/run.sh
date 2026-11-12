#!/usr/bin/env bash
# Host tests for the offline resynthesis tool (tools/resynth, ves.resynth): WAV reader, resampler,
# cycle-frequency tracker, order spectra, segments, model fit, pack render, A/B tools.
# Every fixture is synthetic and made inside the tests with known parameters; the tests write only
# to the system temp folder (outside any git worktree). Also checks that git tracks no audio, packs,
# models, tracks or segment files (RS-P2). Compiles with --release 8 into build/resynth/classes.
# Optional argument: a test-name filter (substring, e.g. RS-T). Prints "RESYNTH TESTS PASSED".
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1

JDK_BIN="${JDK_BIN:-${JAVA_HOME:+$JAVA_HOME/bin}}"
if [ -n "$JDK_BIN" ] && { [ -x "$JDK_BIN/javac" ] || [ -x "$JDK_BIN/javac.exe" ]; }; then
    JAVAC="$JDK_BIN/javac"
    JAVA="$JDK_BIN/java"
else
    JAVAC=javac
    JAVA=java
fi

# relative paths only: the repo path has spaces/non-ASCII and javac on Windows wants native paths
OUT=build/resynth
CLS=$OUT/classes
rm -rf "$OUT"
mkdir -p "$CLS"

FAILED=0
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; FAILED=$((FAILED + 1)); }

# RS-P2: nothing derived from private recordings is tracked (audio, pack manifests, models, tracks, segments)
if command -v git > /dev/null && git rev-parse --is-inside-work-tree > /dev/null 2>&1; then
    BAD=$(git ls-files | grep -E '\.wav$|(^|/)pack\.txt$|\.vesmodel$|\.segments\.txt$|\.track\.txt$|^private/')
    if [ -z "$BAD" ]; then
        pass "RS-P2 git ls-files lists no wav, pack.txt, vesmodel, track or segments files"
    else
        fail "RS-P2 tracked derived data: $BAD"
    fi
else
    echo "SKIP RS-P2: not a git checkout"
fi

find core/src/ves/core tools/resynth/src tests/resynth/java -name '*.java' > "$OUT/sources.txt"
if "$JAVAC" --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -d "$CLS" @"$OUT/sources.txt"; then
    pass "javac --release 8 tools/resynth"
else
    fail "javac tools/resynth"
    echo "RESYNTH TESTS FAILED"
    exit 1
fi

# RS-R8 compares against a built-in pack: render one with tools/render (skipped by the test if absent)
BUILTIN=$OUT/builtin
mkdir -p "$OUT/render-classes" "$BUILTIN/i4t"
find tools/render/src -name '*.java' > "$OUT/render-sources.txt"
if "$JAVAC" --release 8 -encoding UTF-8 -nowarn -Xlint:-options -d "$OUT/render-classes" @"$OUT/render-sources.txt" \
    && "$JAVA" -cp "$OUT/render-classes" ves.render.Grid tools/render/engines/i4t.txt "$BUILTIN/i4t" > /dev/null; then
    :
else
    echo "NOTE: could not render the built-in i4t pack; RS-R8 will skip"
    rm -rf "$BUILTIN/i4t"
fi

"$JAVA" -Xss8m -cp "$CLS" -Dresynth.builtin="$BUILTIN/i4t" ves.resynth.ResynthTest ${1:+"$1"} || FAILED=$((FAILED + 1))

if [ "$FAILED" = 0 ]; then
    echo "RESYNTH TESTS PASSED"
    exit 0
fi
echo "RESYNTH TESTS FAILED ($FAILED)"
exit 1
