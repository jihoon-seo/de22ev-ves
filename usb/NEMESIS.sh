#!/system/bin/sh
USB=${0%/*}
[ "$USB" != "$0" ] || USB=$PWD
exec /system/bin/sh "$USB/VES_MOD/run.sh" "$USB"
