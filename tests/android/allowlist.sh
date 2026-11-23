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

# AL3 (0.2.13): the only child processes are NavReader's read-only logcat (camera lines of ClusterService) and dumpsys,
# and the car check's (Probe) getprop / logcat -d / service list for the USB logs
A="$ROOT/android/src"
ex=$(grep -rnE "\.exec\(|ProcessBuilder" "$A" | grep -vE "/NavReader\.java:[0-9]+:.*\.exec\((LOGCAT|DUMPSYS)\)" \
    | grep -vE '/Probe\.java:[0-9]+:.*Runtime\.getRuntime\(\)\.exec\((new String\[\] \{ "/system/bin/getprop", k \}|cmd)\);' || true)
[ -z "$ex" ] && pass "processes: only NavReader's exec(LOGCAT)/exec(DUMPSYS) and Probe's" || fail "processes" "$ex"
pc=$(grep -nE "[^.]exec\(new String" "$A/ves/android/Probe.java" \
    | grep -vE 'exec\(new String\[\] \{ "/system/bin/(logcat", "-d", "-v", "time"|service", "list") \}, ' || true)
[ -z "$pc" ] && pass "Probe runs only logcat -d and service list" || fail "Probe exec" "$pc"
N="$A/ves/android/NavReader.java"
if [ -f "$N" ]; then
    grep -qF 'LOGCAT = {"logcat", "-v", "time", "-b", "system", "ClusterService:D", "*:S"};' "$N" \
        && pass "NavReader: logcat reads ClusterService on the system buffer only" || fail "NavReader" "LOGCAT command changed"
    d=$(grep -F "DUMPSYS =" "$N" || true)
    { [ -z "$d" ] || echo "$d" | grep -qF 'DUMPSYS = {"dumpsys", "com.hkmc.micom.cluster"};'; } \
        && pass "NavReader: dumpsys of the cluster service only" || fail "NavReader" "DUMPSYS command changed: $d"
    [ "$(grep -c "\.exec(" "$N")" -ge 1 ] && pass "NavReader: starts logcat" || fail "NavReader" "no exec"
else
    fail "NavReader" "android/src/ves/android/NavReader.java missing"
fi

# AL4 (0.2.13, D3): vesd writes the mod's settings only to put VES off at boot: one setting-set call site in
# ves.android, inside Vesd.bootStore(), which is handed only to BootOff.writeOff
VA="$ROOT/android/src/ves/android"
sets=$(grep -rnE "saveSettingsJson\(|Prefs\.save\(|\"setting-set\"" "$VA" || true)
if [ "$(echo "$sets" | grep -c .)" = 1 ] && echo "$sets" | grep -q "/Vesd\.java:"; then
    ln=$(echo "$sets" | cut -d: -f2)
    meth=$(awk -v n="$ln" 'NR <= n && /^    [A-Za-z].*\(.*\).*\{ *$/ { m = $0 } NR == n { print m }' "$VA/Vesd.java")
    echo "$meth" | grep -q "BootOff.Store bootStore()" && pass "settings written only in Vesd.bootStore()" \
        || fail "setting-set" "outside bootStore(): $meth"
else
    fail "setting-set" "expected one call site in Vesd.java, found: ${sets:-none}"
fi
uses=$(grep -rn "bootStore()" "$VA" | grep -v "BootOff.Store bootStore()" || true)
[ "$(echo "$uses" | grep -c .)" = 1 ] && echo "$uses" | grep -q "writeOff(bootStore()" \
    && pass "bootStore() only feeds BootOff.writeOff" || fail "bootStore" "${uses:-unused}"
hs=$(grep -rnE "\"setting-set\"" "$ROOT/android/src" | grep -v "/ves/hmg/HmgClient\.java:" || true)
[ -z "$hs" ] && pass "setting-set spelled only in HmgClient" || fail "setting-set" "$hs"

python -I -c "
import json,sys
m=json.load(open(sys.argv[1],encoding='utf-8'))
v=m.get('vehicle',{})
sys.exit(1 if 'commands' in v else 0)" "$ROOT/mod/mod.json" && pass "mod.json: no vehicle commands" || fail "mod.json" "declares vehicle commands"

[ "$FAILED" = 0 ] && echo "allowlist: all checks passed" || { echo "allowlist: $FAILED check(s) failed"; exit 1; }
