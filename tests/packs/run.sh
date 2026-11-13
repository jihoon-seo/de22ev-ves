#!/bin/bash
# Built-in sound packs (packs/<slot>/: sounds by 히릿, processed by ves.resynth; see THIRD_PARTY.md):
# BP-I*  tools/import_packs.py on synthetic folders (mktemp; never the private packs)
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

if [ "$FAILED" = 0 ]; then
    echo "PACKS TESTS PASSED"
    exit 0
fi
echo "PACKS TESTS FAILED ($FAILED)"
exit 1
