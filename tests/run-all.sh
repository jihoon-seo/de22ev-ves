#!/bin/bash
# All host tests: the sound core (synth, signals, audio policy), the "no writes" allowlist, the resynthesis
# tool (synthetic fixtures only), mod/app/settings consistency, the USB kit (mock hmgapid) and the package
# through HMG-API's engine.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
RC=0
echo "== core"; bash "$HERE/core/run.sh" || RC=1
echo "== allowlist"; bash "$HERE/android/allowlist.sh" || RC=1
echo "== sockets"; bash "$HERE/android/sockets.sh" || RC=1
echo "== packstore"; bash "$HERE/android/packstore.sh" || RC=1
echo "== render (packs)"; bash "$HERE/render/run.sh" || RC=1
echo "== resynth (recordings to packs)"; bash "$HERE/resynth/run.sh" || RC=1
echo "== mod"; python -I "$HERE/mod/check.py" "$HERE/.." || RC=1
echo "== kit"; bash "$HERE/kit/run.sh" || RC=1
echo "== hmgapi engine (needs HMGAPI_SRC and dist/)"; bash "$HERE/hmgapi/engine.sh" || RC=1
[ "$RC" = 0 ] && echo "ALL TESTS PASSED" || echo "SOME TESTS FAILED"
exit $RC
