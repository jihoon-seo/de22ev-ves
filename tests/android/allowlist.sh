#!/bin/bash
# S7 "no writes": the service only reads the car and only makes the five MICOM audio calls.
#  - the only binder transactions are MicomAudio's T_REGISTER/T_UNREGISTER/T_START/T_END/T_HAS
#  - nothing is ever written to the hscan_mux socket (no getOutputStream in HscanReader)
#  - no HMG-API vehicle command (vehicle-do / "commands" in mod.json), no setVolume, no ASD/VESS calls
set -u
for d in android/src app/java; do [ -d "$(dirname "$0")/../../$d" ] || { echo "FAIL missing $d"; exit 1; }; done
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT="$HERE/../.."
FAILED=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1: $2"; FAILED=$((FAILED+1)); }

SRC=("$ROOT/android/src" "$ROOT/app/java")

# every .transact( call names one of the allowed codes
bad=$(grep -rn "\.transact(" "${SRC[@]}" | grep -vE "transact\((T_REGISTER|T_UNREGISTER|T_START|T_END|T_HAS|code)," || true)
[ -z "$bad" ] && pass "binder: only allowed transactions" || fail "binder" "$bad"
# 'code' is only allowed inside callBool, which only gets T_START/T_END/T_HAS
calls=$(grep -rn "callBool(" "$ROOT/android/src" | grep -v "private synchronized boolean callBool" | grep -vE "callBool\((T_START|T_END|T_HAS)," || true)
[ -z "$calls" ] && pass "binder: callBool only with start/end/has" || fail "callBool" "$calls"
codes=$(grep -oE "T_(REGISTER|UNREGISTER|START|END|HAS) = 0?x?[0-9a-f]+" "$ROOT/android/src/ves/android/MicomAudio.java" | tr '\n' ' ')
[ "$codes" = "T_REGISTER = 3 T_UNREGISTER = 4 T_START = 0x17 T_END = 0x18 T_HAS = 0x7e " ] \
    && pass "binder: codes are registerListener/unregisterListener/startAudio/endAudio/hasStreamTypeInAudioStack" \
    || fail "codes" "$codes"

grep -qE "\.getOutputStream\(" "$ROOT/android/src/ves/android/HscanReader.java" \
    && fail "hscan" "HscanReader writes to the socket" || pass "hscan: read only"

hits=$(grep -rnE "vehicle-do|\.command\(\"|setVolume|setASD|SetVESS|sendEcoVehicle|sendPacket" "${SRC[@]}" || true)
[ -z "$hits" ] && pass "no vehicle commands, volume or ASD/VESS calls" || fail "forbidden calls" "$hits"

# AL2: only the navigation-effect channel 0x45. 0x46 (system alarm) kept the stock navigation's voice
# prompts from playing at all (drive 2026-11-05); 0x39 took the music source; 0x3 reports a prompt.
if grep -qE "case MicomAudio\.(SYSTEM_ALARM|TTS_EFFECT_SOUND|NAVI):" "$ROOT/android/src/ves/android/Vesd.java"; then
    fail "channel" "vesd accepts a channel other than 0x45"
else pass "vesd plays on channel 0x45 only"; fi

# AL1: files are written only by the car check (USB logs) and the pack store (imports into the mod's data)
w=$(grep -rln "new FileOutputStream\|new RandomAccessFile\|\.renameTo(" "$ROOT/android/src" | grep -vE "/(Probe|PackStore)\.java$" || true)
[ -z "$w" ] && pass "files written only by Probe and PackStore" || fail "file writes" "$w"
grep -q "PackLoader.load" "$ROOT/android/src/ves/android/PackStore.java" 2>/dev/null     && pass "PackStore validates packs with PackLoader" || fail "PackStore" "missing or doesn't validate"

python -I -c "
import json,sys
m=json.load(open(sys.argv[1],encoding='utf-8'))
v=m.get('vehicle',{})
sys.exit(1 if 'commands' in v else 0)" "$ROOT/mod/mod.json" && pass "mod.json: no vehicle commands" || fail "mod.json" "declares vehicle commands"

[ "$FAILED" = 0 ] && echo "allowlist: all checks passed" || { echo "allowlist: $FAILED check(s) failed"; exit 1; }
