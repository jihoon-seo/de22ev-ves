#!/bin/bash
# Host test of the USB kit (usb/VES_MOD/run.sh) with a mock hmgapid.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
KIT_SRC="$HERE/../../usb"
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
FAILED=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1: $2"; FAILED=$((FAILED+1)); }

setup() {   # setup PRODUCT
    M="$WORK/$RANDOM"; mkdir -p "$M/bin" "$M/usb" "$M/nemesis"
    cp -r "$KIT_SRC"/* "$M/usb/"
    ZIP=$(sed -n 's/^ZIP=//p' "$M/usb/VES_MOD/run.sh")
    echo zip > "$M/usb/VES_MOD/$ZIP"
    printf 'Name: sh\nUid:\t0\t0\t0\t0\n' > "$M/procstatus"
    cat > "$M/bin/getprop" <<EOF
#!/bin/bash
case "\$1" in sys.boot_completed) echo 1 ;; ro.build.product) echo $1 ;; *) echo x ;; esac
EOF
    cat > "$M/bin/hmgapid" <<'EOF'
#!/bin/bash
echo "hmgapid $*" >> "$MOCK/calls"
case "$1" in
    version) echo "hmgapid 1.0.0-beta.5" ;;
    log) echo "I/HMG-API: [de22ev-ves] [de22ev-ves] service vesd started" ;;
    mods) echo "de22ev-wireless-aa	enabled"; [ -f "$MOCK/installed" ] && echo "de22ev-ves	enabled" ;;
    engine) [ "$2" = install ] && touch "$MOCK/installed"; [ "$2" = remove ] && rm -f "$MOCK/installed" ;;
esac
exit 0
EOF
    printf '#!/bin/bash\necho "I/MicomAudioService: startAudio 69"\necho "I/Other: unrelated"\n' > "$M/bin/logcat"
    chmod +x "$M/bin/"*
    sed -i -e "s#/system/bin/hmgapid#$M/bin/hmgapid#g" -e "s#/data/nemesis#$M/nemesis#g" \
        -e "s#/proc/self/status#$M/procstatus#g" -e "s#^export PATH=.*#export PATH=$M/bin:\$PATH#" "$M/usb/VES_MOD/run.sh"
    : > "$M/calls"
}
run_kit() {   # run_kit MODE -> sets STATUS and OUTDIR
    echo "$1" > "$M/usb/VES_MOD/MODE.txt"
    MOCK=$M bash "$M/usb/VES_MOD/run.sh" "$M/usb"
    OUTDIR=$(cat "$M/usb/VES_MOD_LAST.txt")
    STATUS=$(cat "$OUTDIR/STATUS.txt")
}
order() {   # order PATTERN...: the calls appear in this order
    local n=0 p line
    for p in "$@"; do
        line=$(grep -nF -- "$p" "$M/calls" | head -1 | cut -d: -f1)
        [ -n "$line" ] && [ "$line" -gt "$n" ] || return 1
        n=$line
    done
}

setup DE22EV.KOR.0000.V039.001.260902
run_kit install
if [ "$STATUS" = INSTALLED_RESTART_THE_CAR ] && grep -q "engine install .*de22ev-ves-0.2.2.zip --uuid 3c503596-94a4-8f10-901e-d6da35861d6a" "$M/calls" \
    && order "hmgapid engine install" "hmgapid enable de22ev-ves"; then
    pass "first install: signed package, then enable; the car restart attaches the tile, star key and widget"
else fail "install" "status=$STATUS calls=$(tr '\n' ';' < "$M/calls")"; fi

# an update applies at once: hmgapid re-syncs mod apps only when the app list changes (an update
# keeps it) and doesn't restart a running service on enable, so the kit asks for both
: > "$M/calls"
run_kit install
if [ "$STATUS" = UPDATED_IN_USE_NOW ] \
    && order "hmgapid engine install" "hmgapid enable de22ev-ves" "hmgapid engine apps" "hmgapid service-restart de22ev-ves vesd"; then
    pass "update: install, enable, app update, service restart, no car restart"
else fail "update" "status=$STATUS calls=$(tr '\n' ';' < "$M/calls")"; fi

run_kit status
if [ "$STATUS" = STATUS_WRITTEN ] && grep -q "service vesd started" "$OUTDIR/hmgapi-log.txt" \
    && grep -q "MicomAudioService" "$OUTDIR/logcat-ves.txt" && ! grep -q unrelated "$OUTDIR/logcat-ves.txt"; then
    pass "status: HMG-API log and filtered logcat copied"
else fail "status" "$STATUS"; fi

: > "$M/calls"
run_kit uninstall
if [ "$STATUS" = UNINSTALLED_RESTART_THE_CAR ] && order "hmgapid disable de22ev-ves" "hmgapid engine remove de22ev-ves"; then
    pass "uninstall: disable, then remove"
else fail "uninstall" "status=$STATUS calls=$(tr '\n' ';' < "$M/calls")"; fi

setup CN7.KOR.0000.V039.001.260902
run_kit install
case "$STATUS" in "STOPPED: NOT_THIS_CAR"*) pass "other car refused" ;; *) fail "other car" "$STATUS" ;; esac
grep -q "engine install" "$M/calls" && fail "other car" "it installed" || pass "other car: nothing installed"

[ "$FAILED" = 0 ] && echo "kit: all checks passed" || { echo "kit: $FAILED check(s) failed"; exit 1; }
