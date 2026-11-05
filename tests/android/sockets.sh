#!/bin/bash
# Android 4.4 bug: a LocalSocket returned by LocalServerSocket.accept() is never really
# closed by close() (LocalSocketImpl.accept doesn't set mFdCreatedInternally, so close()
# only drops the fd). The client then never sees the end of the answer and times out
# ("Try again"), and every connection leaks a descriptor in the service. Checked here:
#  - every accepted socket in the service is closed through Sockets.closeAccepted
#  - closeAccepted ends the answer with shutdownOutput and closes the descriptor itself
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT="$HERE/../.."
FAILED=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1: $2"; FAILED=$((FAILED+1)); }

SRC="$ROOT/android/src/ves/android"
H="$SRC/Sockets.java"
if [ -f "$H" ]; then
    grep -q "shutdownOutput()" "$H" && pass "closeAccepted ends the answer (shutdownOutput)" || fail "closeAccepted" "no shutdownOutput"
    grep -q "libcore.io.Libcore" "$H" && grep -q "getFileDescriptor()" "$H" \
        && pass "closeAccepted closes the descriptor itself" || fail "closeAccepted" "does not close the fd"
else
    fail "Sockets.java" "missing"
fi
while IFS= read -r f; do
    n=$(basename "$f")
    grep -q "Sockets.closeAccepted(" "$f" && pass "$n: accepted sockets closed with closeAccepted" || fail "$n" "accepted sockets closed with plain close()"
done < <(grep -l "= *[a-z]*\.accept();" "$SRC"/*.java)
[ -n "$(grep -l "= *[a-z]*\.accept();" "$SRC"/*.java)" ] || fail "accept" "no LocalServerSocket.accept() found (test is stale)"

[ "$FAILED" = 0 ] && echo "sockets: all checks passed" || { echo "sockets: $FAILED check(s) failed"; exit 1; }
