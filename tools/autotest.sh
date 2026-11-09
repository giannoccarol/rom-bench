#!/usr/bin/env bash
# Runs every automatic check of ROM Bench on the connected phone and pulls the report.
#
#   tools/autotest.sh [-s SERIAL] [-p PACKAGE] [-i APK] [-a areas] [-o OUTDIR]
#
#   -i APK     install (or update) this APK first, granting runtime permissions (-g)
#   -a areas   comma-separated area ids (wifi,telephony,...); default: all
#   -p PACKAGE org.rombench (release) or org.rombench.debug (debug build); default: autodetect
#
# The phone must be unlocked with the screen on (camera, display and audio checks need it).
# Nothing on the ROM is changed: the script only installs the app, grants its permissions
# (including DUMP and READ_LOGS for the dumpsys/logcat extras) and reads its files.
set -euo pipefail

SERIAL=""; PKG=""; APK=""; AREAS=""; OUT="reports"
while getopts "s:p:i:a:o:h" o; do
  case "$o" in
    s) SERIAL="$OPTARG" ;; p) PKG="$OPTARG" ;; i) APK="$OPTARG" ;; a) AREAS="$OPTARG" ;; o) OUT="$OPTARG" ;;
    *) sed -n '2,13p' "$0"; exit 0 ;;
  esac
done
ADB=(adb); [ -n "$SERIAL" ] && ADB+=(-s "$SERIAL")

if [ -n "$APK" ]; then
  echo "Installing $APK"
  "${ADB[@]}" install -r -g "$APK" >/dev/null
fi
if [ -z "$PKG" ]; then
  for p in org.rombench org.rombench.debug; do
    if "${ADB[@]}" shell pm path "$p" >/dev/null 2>&1; then PKG="$p"; break; fi
  done
fi
[ -n "$PKG" ] || { echo "ROM Bench is not installed (use -i app.apk)"; exit 1; }

for perm in DUMP READ_LOGS; do
  "${ADB[@]}" shell pm grant "$PKG" "android.permission.$perm" 2>/dev/null || true
done

DIR="/sdcard/Android/data/$PKG/files/reports"
# Reads a report file: through the app's DUMP-protected provider, or Android/data as a fallback.
get() {
  "${ADB[@]}" exec-out content read --uri "content://$PKG.reports/$1" 2>/dev/null ||
    "${ADB[@]}" exec-out cat "$DIR/$1" 2>/dev/null
}
before=$(get latest.done | tr -d '\r' || true)
"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP || true
EXTRA=(--ez autorun true); [ -n "$AREAS" ] && EXTRA+=(--es areas "$AREAS")
"${ADB[@]}" shell am start -n "$PKG/org.rombench.MainActivity" "${EXTRA[@]}" >/dev/null
echo -n "Running automatic checks on $("${ADB[@]}" shell getprop ro.product.device | tr -d '\r')"
for _ in $(seq 1 180); do
  sleep 2; echo -n "."
  now=$(get latest.done | tr -d '\r' || true)
  if [ -n "$now" ] && [ "$now" != "$before" ]; then
    echo
    dev=$("${ADB[@]}" shell getprop ro.product.device | tr -d '\r')
    dest="$OUT/$dev-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$dest"
    for f in latest.txt latest.md latest.bbcode.txt latest.json; do get "$f" > "$dest/$f"; done
    echo "Report saved in $dest"
    grep -E '^(Fail|\[FAIL\]|\[WARN\])' "$dest/latest.txt" || true
    exit 0
  fi
done
echo; echo "Timed out waiting for the report"; exit 2
