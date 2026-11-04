#!/system/bin/sh
# USB kit for the HMG-API mod de22ev-ves (virtual engine sound in the cabin).
#   MODE.txt=install    install/update the signed mod and enable it (active after the next boot)
#   MODE.txt=status     copy HMG-API's log, recent logcat and the mod state to USB
#   MODE.txt=uninstall  disable and remove the mod (stock behaviour after the next boot)
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
umask 077
USB=$1
KIT="$USB/VES_MOD"
[ -d "$KIT" ] || exit 1
OUT="$USB/VES_MOD_$(date +%Y%m%d_%H%M%S)_$$"
mkdir "$OUT" || exit 1
exec > "$OUT/run.log" 2>&1
echo RUNNING > "$OUT/STATUS.txt"
echo "$OUT" > "$USB/VES_MOD_LAST.txt"
trap 'sync' EXIT
fail() { echo "STOPPED: $*"; echo "STOPPED: $*" > "$OUT/STATUS.txt"; exit 1; }
done_status() { echo "$1"; echo "$1" > "$OUT/STATUS.txt"; }

ID=de22ev-ves
UUID=3c503596-94a4-8f10-901e-d6da35861d6a
ZIP=de22ev-ves-0.1.0.zip
PRODUCT=DE22EV.KOR.0000.V039.001.260902
H=/system/bin/hmgapid
LOCAL=/data/nemesis/ves-mod

EUID_=
while read -r LABEL REAL EFFECTIVE REST; do
    if [ "$LABEL" = Uid: ]; then EUID_=$EFFECTIVE; break; fi
done < /proc/self/status
[ "$EUID_" = 0 ] || fail NEMESIS_ROOT_REQUIRED
[ "$(getprop sys.boot_completed)" = 1 ] || fail ANDROID_NOT_READY
[ "$(getprop ro.build.product)" = "$PRODUCT" ] || fail "NOT_THIS_CAR $(getprop ro.build.product)"
[ -x "$H" ] || fail HMGAPI_NOT_INSTALLED

MODE=install
[ -f "$KIT/MODE.txt" ] && read -r MODE < "$KIT/MODE.txt"
case "$MODE" in
    status*) MODE=status ;;
    uninstall*) MODE=uninstall ;;
    *) MODE=install ;;
esac
echo "mode=$MODE"
{ getprop ro.build.product; getprop ro.build.version.incremental; "$H" version 2>&1; date; } > "$OUT/VEHICLE.txt"

dump_state() {
    "$H" engine status > "$OUT/$1-engine.txt" 2>&1
    "$H" mods > "$OUT/$1-mods.txt" 2>&1
}

if [ "$MODE" = status ]; then
    dump_state now
    "$H" log > "$OUT/hmgapi-log.txt" 2>&1
    logcat -d -v time > "$OUT/logcat-all.txt" 2>&1
    grep -E "HMG-API|MicomAudio|AudioServiceHelper|AudioFlinger|AudioTrack|de22ev-ves|vesd" "$OUT/logcat-all.txt" > "$OUT/logcat-ves.txt"
    cp -r /data/hmgapi/logs/de22ev-ves "$OUT/service-logs" 2>/dev/null
    "$H" services > "$OUT/services.txt" 2>&1
    done_status STATUS_WRITTEN
    exit 0
fi

if [ "$MODE" = uninstall ]; then
    dump_state before
    "$H" disable "$ID" > "$OUT/disable.txt" 2>&1
    "$H" engine remove "$ID" > "$OUT/remove.txt" 2>&1
    dump_state after
    done_status UNINSTALLED_RESTART_THE_CAR
    exit 0
fi

# install
[ -f "$KIT/$ZIP" ] || fail "NO_PACKAGE $ZIP"
mkdir -p "$LOCAL" || fail INTERNAL_DIRECTORY
cp "$KIT/$ZIP" "$LOCAL/$ZIP" || fail COPY_PACKAGE
dump_state before
"$H" engine install "$LOCAL/$ZIP" --uuid "$UUID" > "$OUT/install.txt" 2>&1 || fail "INSTALL_FAILED (see install.txt)"
"$H" enable "$ID" > "$OUT/enable.txt" 2>&1 || fail "ENABLE_FAILED (see enable.txt)"
dump_state after
done_status INSTALLED_RESTART_THE_CAR
exit 0
