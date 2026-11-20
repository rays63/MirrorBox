#!/bin/sh
# Installs mirrorbox.apk on a TV and lets it bring its player to the front when a phone starts casting.
#   ./install.sh 192.168.1.77:5789      (find the NetTV port with: adb mdns services)
set -e
cd "$(dirname "$0")"
DEV="${1:?usage: install.sh <ip:port>}"
adb connect "$DEV" >/dev/null 2>&1 || true
adb -s "$DEV" install -r mirrorbox.apk
# Android 10+ blocks activity starts from the background unless the app may draw over other apps.
adb -s "$DEV" shell appops set tv.local.airreceiver SYSTEM_ALERT_WINDOW allow
# Start it now (it also starts by itself after every boot and update).
adb -s "$DEV" shell am start -n tv.local.airreceiver/.MainActivity >/dev/null
echo "Installed. It shows up as an AirPlay and DLNA target under the name on its screen."
