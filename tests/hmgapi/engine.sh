#!/bin/bash
# The built package through HMG-API's own engine on the desktop (HMGAPI_HOST=1 on a fake
# root): signature + author uuid accepted, mod.json parsed with no errors (apps, services,
# usb, vehicle reads, launcher/star key/panel), settings defaults readable, enable, remove.
# Needs HMGAPI_SRC = an HMG-API checkout (core/runtime/java/hmgapi/engine) and dist/ from build.sh.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
if [ -z "${HMGAPI_SRC:-}" ]; then echo "SKIP engine: HMGAPI_SRC not set"; exit 0; fi
ZIP=$(ls "$ROOT"/dist/de22ev-ves-*.zip 2>/dev/null | grep -v USB | head -1)
[ -n "$ZIP" ] || { echo "FAIL engine: no dist/de22ev-ves-<version>.zip (run build.sh)"; exit 1; }
native() { if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
T=${KEEP_T:-$(mktemp -d)}; [ -n "${KEEP_T:-}" ] || trap 'rm -rf "$T"' EXIT; mkdir -p "$T"
mkdir -p "$T/cp" "$T/root/data/hmgapi/mods" "$T/root/data/hmgapi/settings"
mapfile -t S < <(find "$HMGAPI_SRC/core/runtime/java/hmgapi/engine" -name '*.java' | while IFS= read -r f; do native "$f"; done)
javac --release 8 -Xlint:-options -nowarn -encoding UTF-8 -d "$(native "$T/cp")" "${S[@]}" 2>/dev/null || { echo "FAIL engine: HMG-API engine does not compile"; exit 1; }
UUID=$(tr -d '\r\n' < "$ROOT/keys/uuid.txt")
ID=de22ev-ves
FAILED=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1: $2"; FAILED=$((FAILED+1)); }
printf "ro.build.product=DE22EV.KOR.0000.V039.001.260902
ro.mobis.onebindir=de22ev_kr
ro.board.platform=tcc897x
ro.build.version.release=4.4.2
" > "$T/root/props.txt"
PROPS="ro.build.product=DE22EV.KOR.0000.V039.001.260902 ro.mobis.onebindir=de22ev_kr ro.board.platform=tcc897x ro.build.version.release=4.4.2"
eng() { out=$(HMGAPI_ROOT="$(native "$T/root")" HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PROPS="$PROPS" java -cp "$(native "$T/cp")" hmgapi.engine.Engine "$@" 2>&1); rc=$?; }

eng install "$(native "$ZIP")" --uuid "$UUID"
[ $rc = 0 ] && pass "install accepted (signature, uuid)" || fail "install" "rc=$rc $out"
echo "$out" | grep -qi "error" && fail "install output has errors" "$out" || pass "install: no errors"
M="$T/root/data/hmgapi/mods/$ID"
# the built-in packs travel inside vesd.jar: the service (user system) can read only its own jar
J=$(mktemp -d); unzip -q -o "$ZIP" vesd.jar -d "$J" 2>/dev/null
# exactly one pack.txt per PackStore.BUILT_IN slot (히릿's packs); the CC0 renders (i4t, v6, v8, i6) are gone
mapfile -t B < <(grep -E 'static final String\[\] BUILT_IN = ' "$ROOT/android/src/ves/android/PackStore.java" | grep -oE '"[a-z0-9_]+"' | tr -d '"')
want=$(printf 'packs/%s/pack.txt\n' "${B[@]}" | sort | tr '\n' ' ')
have=$(unzip -Z1 "$J/vesd.jar" 2>/dev/null | grep -E '^packs/[^/]+/pack\.txt$' | sort | tr '\n' ' ')
[ "${#B[@]}" -gt 0 ] && [ "$have" = "$want" ] && pass "vesd.jar holds one pack.txt per built-in slot (${B[*]})" || fail "packs in vesd.jar" "want '$want', found '$have'"
n=$(unzip -Z1 "$J/vesd.jar" 2>/dev/null | grep -cE '^packs/(i4t|v6|v8|i6)/')
[ "$n" = 0 ] && pass "vesd.jar holds no CC0 pack (i4t, v6, v8, i6)" || fail "CC0 packs in vesd.jar" "$n entries"
unzip -l "$ZIP" | grep -q " packs/" && fail "no loose packs/" "the package still has a packs/ folder" || pass "no loose packs/ folder"
rm -rf "$J"
for f in mod.json settings.json vesd.jar app/EngineSound.apk tile.png THIRD_PARTY.md; do
    [ -f "$M/$f" ] && pass "installed $f" || fail "installed $f" "missing"
done
grep -q "히릿" "$M/THIRD_PARTY.md" 2>/dev/null && pass "installed THIRD_PARTY.md names 히릿 (built-in sounds)" || fail "THIRD_PARTY.md" "no 히릿 notice"
# what the install published for hmgapid (the engine writes these when the mod list changes)
S="$T/root/data/hmgapi/state"
grep -qx "$ID vesd system android yes jar vesd.jar ves.android.Vesd" "$S/services.txt"     && pass "service: vesd, user system, starts with Android, restarts, jar" || fail "service" "$(cat "$S/services.txt")"
grep -qx "io.github.jihoonseo.ves $ID" "$S/apps.txt" && pass "app io.github.jihoonseo.ves" || fail "apps" "$(cat "$S/apps.txt")"
grep -qx "$ID VES" "$S/usb.txt" && pass "usb folder VES" || fail "usb" "$(cat "$S/usb.txt")"
[ "$(grep -c "^$ID " "$S/vehicle.txt")" = 6 ] && pass "6 vehicle readings" || fail "vehicle" "$(cat "$S/vehicle.txt")"
[ -z "$(grep "^$ID " "$S/commands.txt")" ] && pass "no vehicle commands" || fail "commands" "$(cat "$S/commands.txt")"
python -I -c "
import json,sys
m=json.load(open(sys.argv[1],encoding='utf-8'))['mods']['$ID']
assert m['settings']['enabled'] is False and m['settings']['volume']==40, m['settings']
assert m['panel'][0]['widget']=='io.github.jihoonseo.ves/ves.app.VesWidget', m['panel']
" "$T/root/data/hmgapi/public/state.json" && pass "state: off by default, volume 40, panel widget" || fail "state.json" "see above"
eng remove "$ID"
if [ $rc = 0 ] && [ ! -d "$M" ]; then pass "remove"
elif echo "$out" | grep -q "rename"; then echo "SKIP remove: the engine's rename-over doesn't work on Windows"
else fail "remove" "rc=$rc $out"; fi

[ "$FAILED" = 0 ] && echo "engine: all checks passed" || { echo "engine: $FAILED check(s) failed"; exit 1; }
