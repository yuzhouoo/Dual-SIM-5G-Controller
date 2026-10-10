#!/system/bin/sh
MODDIR=${0%/*}
# Wait for framework/telephony to initialize.
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 5; done
sleep 12
exec /system/bin/sh "$MODDIR/controller.sh" daemon
