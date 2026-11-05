#!/usr/bin/env bash
# Host test runner for the platform-independent VES core (ves.core + ves.tools).
# Compiles core/src + core/test with --release 8 into build/core-tests, runs the
# TestMain harness, then generates every Sweep scenario and replays it through
# every profile, asserting the printed metrics. Prints "CORE TESTS PASSED".
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
OUT=build/core-tests
CLS=$OUT/classes
WORK=$OUT/work
rm -rf "$OUT"
mkdir -p "$CLS" "$WORK"

fail() {
    echo "CORE TESTS FAILED: $*"
    exit 1
}

find core/src core/test -name '*.java' > "$OUT/sources.txt"
"$JAVAC" --release 8 -encoding UTF-8 -Xlint:-options -d "$CLS" @"$OUT/sources.txt" || fail "javac"

# Language-level guard for D8 --no-desugaring / API 19 (lambdas, streams, java.util.function, nio.file, Android/org.json in core)
if grep -nE -- '->|::|java\.util\.function|java\.util\.stream|java\.nio\.file|String\.join|try *\(|import android\.|import org\.json' \
        $(find core/src -name '*.java') | grep -vE '^\S+:[0-9]+:\s*(//|\*|/\*)'; then
    fail "forbidden language/API construct in core/src"
fi

"$JAVA" -cp "$CLS" ves.core.TestMain || fail "unit tests"

# ---- Sweep + Replay end-to-end
num() { # num <json> <key>
    echo "$1" | sed -n "s/.*\"$2\":\(-\{0,1\}[0-9.eE+-]*\).*/\1/p"
}
assert() { # assert <awk-expr> <msg>
    awk "BEGIN{exit !($1)}" || fail "$2 ($1)"
}

for scen in city accel cruise navi ready_off; do
    "$JAVA" -cp "$CLS" ves.tools.Sweep "$WORK/$scen.csv" "$scen" > /dev/null || fail "sweep $scen"
    for prof in ice_smooth ice_shift ev_future; do
        M=$("$JAVA" -cp "$CLS" ves.tools.Replay "$WORK/$scen.csv" "$WORK/$scen-$prof.wav" \
            profile=$prof volume=100 mode=idle_regen | tail -n 1) || fail "replay $scen $prof"
        echo "METRICS $scen $prof $M"
        peak=$(num "$M" peak); cap=$(num "$M" peak_cap); delta=$(num "$M" max_delta)
        silent=$(num "$M" silent_fraction); opens=$(num "$M" opens); closes=$(num "$M" closes)
        rms=$(num "$M" rms); nan=$(num "$M" nan_blocks)
        [ -n "$peak" ] && [ -n "$cap" ] && [ -n "$silent" ] || fail "metrics missing for $scen $prof"
        assert "$peak <= $cap + 1" "$scen/$prof peak above gain cap"
        assert "$peak <= 30000" "$scen/$prof peak above full scale"
        # steady full-pedal sound steps up to ~41800 per sample at volume 100 (S1d steadyMaxDelta); like S1d, more
        # than 1.1x that + 300 is a click
        assert "$delta < 46300" "$scen/$prof sample-to-sample jump"
        assert "$nan == 0" "$scen/$prof NaN gain"
        [ -s "$WORK/$scen-$prof.wav" ] || fail "no wav for $scen $prof"
        case $scen in
            ready_off)
                assert "$silent >= 0.999" "ready_off must be silent"
                assert "$opens == 0" "ready_off must never open the path"
                ;;
            navi)
                ymsc=$(num "$M" yield_closed_ms); yaud=$(num "$M" yield_audible_ms)
                assert "$ymsc > 3000" "navi: path not closed during the yield window"
                assert "$yaud == 0" "navi: audible during yield"
                assert "$opens >= 2 && $closes >= 2" "navi: expected close for the prompt and a reopen"
                assert "$rms > 0" "navi audible outside the prompt"
                ;;
            *)
                assert "$rms > 0" "$scen/$prof should be audible"
                ;;
        esac
        if [ "$scen" = accel ] && [ "$prof" = ice_shift ]; then
            assert "$(num "$M" max_gear) >= 4" "accel/ice_shift should reach 4th gear"
        fi
    done
done

echo "CORE TESTS PASSED"
exit 0
