#!/usr/bin/env bash
# Host checks for the offline engine-sound renderer (tools/render, ves.render; a Java port of
# DasEtwas/enginesound, MIT). Compiles tools/render/src with --release 8 into build/render/classes,
# renders every engine's loop grid into build/render/<engine>/ and checks it (PASS/FAIL lines):
# determinism (two renders, same sha256), loop seams, firing-frequency peak, on/off spectral
# centroid, on-load RMS spread, no NaN/inf, crank-aligned whole-cycle loops, and that the runtime's
# ves.core.PackLoader (core/src, compiled alongside) loads each pack; prints the render time per pack. Also renders the
# demo sweeps into build/render/demo/ and, if ENGINESOUND_SRC points at an upstream checkout, an
# informational parity render of its example1.esc at 3000 rpm. Exit 1 on any FAIL.
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
native() { if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
now_ms() { echo $(( $(date +%s%N) / 1000000 )); }

# relative paths only: the repo path has spaces/non-ASCII and javac on Windows wants native paths
OUT=build/render
CLS=$OUT/classes
rm -rf "$OUT"
mkdir -p "$CLS" "$OUT/rerun" "$OUT/demo"

FAILED=0
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; FAILED=$((FAILED + 1)); }

find tools/render/src -name '*.java' > "$OUT/sources.txt"
if "$JAVAC" --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -d "$CLS" @"$OUT/sources.txt"; then
    pass "javac --release 8 tools/render"
else
    fail "javac tools/render"
    echo "RENDER TESTS FAILED"
    exit 1
fi

# the runtime's pack loader (ves.core.PackLoader) checks every pack below; compiled into the same
# class dir, separately, so tools/render itself never depends on core
CORE_OK=0
if "$JAVAC" --release 8 -encoding UTF-8 -Xlint:-options -nowarn -d "$CLS" core/src/ves/core/*.java; then
    CORE_OK=1
    pass "javac --release 8 core/src/ves/core (for PackLoader)"
else
    fail "javac core/src/ves/core: cannot check packs with PackLoader"
fi

sums() { # sums <dir>: sha256 of every file, sorted by name
    (cd "$1" && find . -type f | LC_ALL=C sort | while IFS= read -r f; do sha256sum "$f"; done)
}

ENGINES=(i4t v6 v8 i6)
for e in "${ENGINES[@]}"; do
    cfg="tools/render/engines/$e.txt"
    t0=$(now_ms)
    if "$JAVA" -cp "$CLS" ves.render.Grid "$cfg" "$OUT/$e" > "$OUT/$e.grid.log" 2>&1; then
        t1=$(now_ms)
        cat "$OUT/$e.grid.log"
        echo "TIME $e: pack rendered in $((t1 - t0)) ms (JVM start included)"
        grep -q "nonfinite=0" "$OUT/$e.grid.log" && pass "$e: no NaN/inf in any loop" || fail "$e: NaN/inf"
        ph=$(sed -n 's/.*start_phase_max=\([0-9.]*\).*/\1/p' "$OUT/$e.grid.log")
        [ -n "$ph" ] && awk "BEGIN{exit !($ph < 1)}" \
            && pass "$e: every loop starts at crank phase 0 (within $ph of a sample)" || fail "$e: loop start phase ($ph)"
    else
        cat "$OUT/$e.grid.log"
        fail "$e: Grid failed"
        continue
    fi
    "$JAVA" -cp "$CLS" ves.render.Grid "$cfg" "$OUT/rerun/$e" > /dev/null 2>&1 || fail "$e: second Grid run failed"
    if [ "$(sums "$OUT/$e")" = "$(sums "$OUT/rerun/$e")" ]; then
        pass "$e: deterministic (two renders, identical sha256 for $(ls "$OUT/$e" | wc -l | tr -d ' ') files)"
    else
        fail "$e: renders differ"
    fi
    "$JAVA" -cp "$CLS" ves.render.check.Check pack "$OUT/$e" > "$OUT/$e.check.log" 2>&1
    rc=$?
    cat "$OUT/$e.check.log"
    [ $rc = 0 ] || FAILED=$((FAILED + 1))
    if [ "$CORE_OK" = 1 ]; then
        "$JAVA" -cp "$CLS" ves.render.check.CoreLoad "$OUT/$e" || FAILED=$((FAILED + 1))
    fi
done
rm -rf "$OUT/rerun"

# single-file CLI: determinism and argument forms
"$JAVA" -cp "$CLS" ves.render.Render tools/render/engines/i4t.txt 3000 0.5 1.5 "$OUT/cli_a.wav" 22050 7 > /dev/null \
    && "$JAVA" -cp "$CLS" ves.render.Render tools/render/engines/i4t.txt 3000 0.5 1.5 "$OUT/cli_b.wav" samplerate=22050 seed=7 > /dev/null \
    && cmp -s "$OUT/cli_a.wav" "$OUT/cli_b.wav" \
    && pass "Render CLI deterministic (same args, identical bytes)" || fail "Render CLI determinism"
"$JAVA" -cp "$CLS" ves.render.Render tools/render/engines/i4t.txt 3000 0.5 1.5 "$OUT/cli_c.wav" 22050 8 > /dev/null
cmp -s "$OUT/cli_a.wav" "$OUT/cli_c.wav" && fail "Render: seed has no effect" || pass "Render: seed changes the noise"

# demo sweeps straight from the model
for e in "${ENGINES[@]}"; do
    "$JAVA" -cp "$CLS" ves.render.Sweep "tools/render/engines/$e.txt" "$OUT/demo/${e}_sweep.wav" 12 800 6000 \
        || fail "$e: demo sweep"
done
[ "$(ls "$OUT"/demo/*_sweep.wav 2>/dev/null | wc -l)" -eq ${#ENGINES[@]} ] && pass "demo sweeps in $OUT/demo" || fail "demo sweeps"

# parity (informational): upstream example1.esc, converted, at 3000 rpm
if [ -n "${ENGINESOUND_SRC:-}" ] && [ -f "$ENGINESOUND_SRC/example1.esc" ]; then
    mkdir -p "$OUT/parity"
    esc="$(native "$ENGINESOUND_SRC/example1.esc")"
    "$JAVA" -cp "$CLS" ves.render.EscConvert "$esc" "$OUT/parity/example1.txt" && pass "EscConvert example1.esc" || fail "EscConvert"
    # the .esc schema was tuned at the upstream GUI's 48 kHz: render there
    "$JAVA" -cp "$CLS" ves.render.Render "$OUT/parity/example1.txt" 3000 1 3 "$OUT/parity/example1_3000.wav" 48000 1 > /dev/null
    "$JAVA" -cp "$CLS" ves.render.Render "$esc" 3000 1 3 "$OUT/parity/example1_3000_esc.wav" 48000 1 > /dev/null
    cmp -s "$OUT/parity/example1_3000.wav" "$OUT/parity/example1_3000_esc.wav" \
        && pass "parity: converted .txt and direct .esc load render identically" || fail "parity: .txt vs .esc differ"
    "$JAVA" -cp "$CLS" ves.render.check.Check spectrum "$OUT/parity/example1_3000.wav" 3000 10 | sed 's/^/INFO parity /'
    for c in 2 3 4 5 6; do # every other upstream example must convert and load too
        if [ -f "$ENGINESOUND_SRC/example$c.esc" ]; then
            "$JAVA" -cp "$CLS" ves.render.EscConvert "$(native "$ENGINESOUND_SRC/example$c.esc")" "$OUT/parity/example$c.txt" \
                && pass "EscConvert example$c.esc" || fail "EscConvert example$c.esc"
        fi
    done
else
    echo "SKIP parity: set ENGINESOUND_SRC to a DasEtwas/enginesound checkout (e5fcca5) to render its example1.esc"
fi

if [ "$FAILED" = 0 ]; then
    echo "RENDER TESTS PASSED"
    exit 0
fi
echo "RENDER TESTS FAILED ($FAILED)"
exit 1
