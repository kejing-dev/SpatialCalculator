#!/usr/bin/env bash
# Spatial Calculator dev harness (macOS). DEV = emulator serial (default emulator-5564).
#   tools/sc.sh cmd NAME [ARG]   debug broadcast
#   tools/sc.sh shot OUT.png     still capture (pico-cli)
#   tools/sc.sh log [REGEX]      logcat of tag SpatialCalc
#   tools/sc.sh install          install the debug APK and start the app
set -u
DEV="${DEV:-emulator-5564}"
PKG=com.kejing.spatialcalc
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"
case "${1:-}" in
  cmd) "$ADB" -s "$DEV" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$2" --es a "\"${3:-}\"" >/dev/null ;;
  shot) pico-cli capture screenshot --device "$DEV" --out "$2" --replace >/dev/null ;;
  log) "$ADB" -s "$DEV" logcat -d -s SpatialCalc:V | grep -E "${2:-.}" ;;
  install) "$ADB" -s "$DEV" install -r "$HERE/../app/build/outputs/apk/debug/app-debug.apk" && "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null ;;
  *) sed -n 2,7p "$0" ;;
esac
