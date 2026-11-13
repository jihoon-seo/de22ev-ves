#!/bin/bash
# Built-in sound packs (packs/<slot>/: sounds by 히릿, processed by ves.resynth; see THIRD_PARTY.md):
# BP-I*  tools/import_packs.py on synthetic folders (mktemp; never the private packs)
# BP2-BP6 what git tracks under packs/ (notice, THIRD_PARTY.md bullets, exact file set, size)
# BP1/BP5 PackStore loads every BUILT_IN slot from a vesd.jar made of packs/; packs/ folders == BUILT_IN
# Prints "PACKS TESTS PASSED".
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
cd "$ROOT" || exit 1
PY=${PYTHON:-python}
native() { if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
FAILED=0
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; FAILED=$((FAILED + 1)); }

# ---- BP-I: the import tool, on synthetic packs
mkwav() { # mkwav <path> <rate> <channels> <seconds>
    "$PY" -I -c '
import math, struct, sys, wave
p, rate, ch, sec = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4])
n = int(rate * sec)
w = wave.open(p, "wb"); w.setnchannels(ch); w.setsampwidth(2); w.setframerate(rate)
w.writeframes(b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 100 * i / rate))) * ch for i in range(n)))
w.close()' "$(native "$1")" "$2" "$3" "$4"
}
mkpack() { # mkpack <dir> [rate]: a private-style pack with 3 loops and a readme
    mkdir -p "$1"
    printf '# rendered by ves.resynth (private: never commit)\nname Test (resynth)\ncylinders 4\nidle 800\nredline 6500\naligned yes\n' > "$1/pack.txt"
    for r in 0800 2000 4000; do
        mkwav "$1/rs_${r}_on.wav" "${2:-22050}" 1 0.2
        echo "loop rs_${r}_on.wav rpm=$((10#$r)) load=on" >> "$1/pack.txt"
    done
    echo "notes" > "$1/readme.txt"
}
tree_sum() { (cd "$1" && find . -type f | sort | while IFS= read -r f; do echo "$f $(cksum < "$f")"; done); }
imp() { "$PY" -I tools/import_packs.py "$@" > "$T/imp.out" 2>&1; }

HEADER='# de22ev-ves built-in pack vex4: sound by 히릿 (engine physics simulator), processed by ves.resynth; used with permission in this mod only, not MIT. See THIRD_PARTY.md.'

# BP-I1: header and name rewritten, only the referenced files copied, no temp folder left
mkpack "$T/i1/src/vex4"
if imp "$(native "$T/i1/src")" --dest "$(native "$T/i1/dest")" vex4; then
    D="$T/i1/dest/vex4"
    l1=$(sed -n 1p "$D/pack.txt" | tr -d '\r'); l2=$(sed -n 2p "$D/pack.txt" | tr -d '\r')
    files=$(ls -A "$D" | tr '\n' ' ')
    same=1; for w in "$T"/i1/src/vex4/*.wav; do cmp -s "$w" "$D/$(basename "$w")" || same=0; done
    if [ "$l1" = "$HEADER" ] && [ "$l2" = "name VEX 4-cyl" ] && [ "$files" = "pack.txt rs_0800_on.wav rs_2000_on.wav rs_4000_on.wav " ] \
        && [ "$same" = 1 ] && [ "$(ls -A "$T/i1/dest")" = vex4 ] && ! grep -q "never commit" "$D/pack.txt"; then
        pass "BP-I1 header + name rewritten, only referenced files copied"
    else
        fail "BP-I1 import result: line1='$l1' line2='$l2' files='$files' same=$same dest='$(ls -A "$T/i1/dest" | tr '\n' ' ')'"
    fi
else
    fail "BP-I1 import failed: $(cat "$T/imp.out")"
fi

# BP-I2: a source holding models/tracks/segments or unreferenced WAVs (or not a private pack) is refused,
# exit 1, the destination untouched
for bad in model.vesmodel run.track.txt run.segments.txt extra.wav header; do
    rm -rf "$T/i2"; mkpack "$T/i2/src/vex4"
    case $bad in
        header) sed -i '1s/.*/# some other pack/' "$T/i2/src/vex4/pack.txt" ;;
        extra.wav) mkwav "$T/i2/src/vex4/extra.wav" 22050 1 0.2 ;;
        *) echo x > "$T/i2/src/vex4/$bad" ;;
    esac
    mkdir -p "$T/i2/dest/vex4"; echo old > "$T/i2/dest/vex4/pack.txt"
    before=$(tree_sum "$T/i2/dest")
    imp "$(native "$T/i2/src")" --dest "$(native "$T/i2/dest")" vex4; rc=$?
    if [ "$rc" = 1 ] && [ "$(tree_sum "$T/i2/dest")" = "$before" ]; then
        pass "BP-I2 refused ($bad): exit 1, destination untouched"
    else
        fail "BP-I2 $bad: rc=$rc, dest changed: $(tree_sum "$T/i2/dest" | tr '\n' ' ') out: $(cat "$T/imp.out")"
    fi
done

# BP-I3: a 44.1 kHz WAV is refused, exit 1, the destination untouched
mkpack "$T/i3/src/vex4" 44100
mkdir -p "$T/i3/dest"
imp "$(native "$T/i3/src")" --dest "$(native "$T/i3/dest")" vex4; rc=$?
if [ "$rc" = 1 ] && [ -z "$(ls -A "$T/i3/dest")" ]; then
    pass "BP-I3 44.1 kHz WAV refused: exit 1, destination untouched"
else
    fail "BP-I3 rc=$rc dest='$(ls -A "$T/i3/dest" | tr '\n' ' ')' out: $(cat "$T/imp.out")"
fi

# ---- BP2-BP6: what git tracks under packs/ (processed packs only, each with its notice)
if command -v git > /dev/null && git rev-parse --is-inside-work-tree > /dev/null 2>&1; then
    git ls-files packs/ | sort > "$T/tracked.txt"
    mapfile -t PT < <(grep -E '^packs/[^/]+/pack\.txt$' "$T/tracked.txt")
    [ "${#PT[@]}" -gt 0 ] || fail "BP2 no tracked packs/*/pack.txt"
    bad2=""; bad3=""; echo packs/README.md > "$T/want.txt"
    for p in "${PT[@]}"; do
        slot=$(basename "$(dirname "$p")")
        l1=$(sed -n 1p "$p" | tr -d '\r')
        case $l1 in "# de22ev-ves built-in pack $slot:"*) ;; *) bad2="$bad2 $slot(line1)" ;; esac
        case $l1 in *히릿*THIRD_PARTY.md*) ;; *) bad2="$bad2 $slot(notice)" ;; esac
        grep -q "never commit" "$p" && bad2="$bad2 $slot(private header)"
        grep -qF -- "- \`packs/$slot/\`" THIRD_PARTY.md || bad3="$bad3 $slot"
        echo "$p" >> "$T/want.txt"
        grep -v '^[[:space:]]*#' "$p" | tr -s ' \t\r' '\n\n\n' | grep -E '^[a-z0-9_-]+\.wav$' | sort -u | sed "s|^|packs/$slot/|" >> "$T/want.txt"
    done
    if [ "${#PT[@]}" -gt 0 ]; then
        [ -z "$bad2" ] && pass "BP2 every packs/*/pack.txt carries the built-in notice (히릿, THIRD_PARTY.md)" || fail "BP2 notice:$bad2"
        [ -z "$bad3" ] && pass "BP3 every packs/<slot>/ has its THIRD_PARTY.md bullet" || fail "BP3 no THIRD_PARTY.md bullet for:$bad3"
    fi
    sort -u "$T/want.txt" -o "$T/want.txt"
    if diff "$T/want.txt" "$T/tracked.txt" > "$T/bp4.diff"; then
        pass "BP4 git tracks packs/README.md, each pack.txt and exactly the files it references"
    else
        fail "BP4 tracked files under packs/ differ (< wanted, > tracked): $(head -20 "$T/bp4.diff" | tr '\n' ' ')"
    fi
    total=0; while IFS= read -r f; do total=$((total + $(wc -c < "$f"))); done < "$T/tracked.txt"
    [ "$total" -gt 0 ] && [ "$total" -le $((18 * 1024 * 1024)) ] && pass "BP6 packs/ total $total bytes <= 18 MB" || fail "BP6 packs/ total $total bytes (want 1..18 MB)"
else
    echo "SKIP BP2-BP6: not a git checkout"
fi

# ---- BP1/BP5: packs/ zipped like build.sh does (packs/<slot>/ inside vesd.jar), loaded by vesd's PackStore
JDK_BIN="${JDK_BIN:-${JAVA_HOME:+$JAVA_HOME/bin}}"
if [ -n "$JDK_BIN" ] && { [ -x "$JDK_BIN/javac" ] || [ -x "$JDK_BIN/javac.exe" ]; }; then
    JAVAC="$JDK_BIN/javac"; JAVA="$JDK_BIN/java"
else
    JAVAC=javac; JAVA=java
fi
mkdir -p "$T/cls" "$T/jar/packs" "$T/mod"
for d in packs/*/; do
    s=$(basename "$d"); mkdir -p "$T/jar/packs/$s"
    cp "$d"pack.txt "$d"*.wav "$T/jar/packs/$s/" 2> /dev/null
done
"$PY" -I tools/zipdir.py "$(native "$T/jar")" "$(native "$T/mod/vesd.jar")"
mapfile -t S < <({ find core/src/ves/core -name '*.java'; echo android/src/ves/android/PackStore.java;
    echo tests/packs/java/ves/android/BuiltinPacks.java; } | while IFS= read -r f; do native "$f"; done)
if "$JAVAC" --release 8 -Xlint:-options -nowarn -encoding UTF-8 -d "$(native "$T/cls")" "${S[@]}"; then
    "$JAVA" -cp "$(native "$T/cls")" ves.android.BuiltinPacks "$(native "$T/mod")" "$(native "$ROOT/packs")" || FAILED=$((FAILED + 1))
else
    fail "BP1 compile PackStore + BuiltinPacks"
fi

if [ "$FAILED" = 0 ]; then
    echo "PACKS TESTS PASSED"
    exit 0
fi
echo "PACKS TESTS FAILED ($FAILED)"
exit 1
