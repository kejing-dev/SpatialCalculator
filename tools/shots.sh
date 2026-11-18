#!/usr/bin/env bash
# Screenshot walk for QA evidence (design states[], both languages). Every state is reached with the debug broadcast,
# which calls the same AppController / StageScene entry the real keys do (AGENTS.md). Text overflow lines are collected
# from a logcat stream started before the walk.
#   DEV=emulator-5564 tools/shots.sh OUTDIR zh|en
set -u
DEV="${DEV:-emulator-5564}"; PKG=io.embodit.spatialcalc; ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
OUT="$1"; L="$2"; mkdir -p "$OUT"
c() { "$ADB" -s "$DEV" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$1" --es a "\"${2:-}\"" >/dev/null; sleep 0.8; }
shot() { sleep 1.2; pico-cli capture screenshot --device "$DEV" --out "$OUT/$1-$L.png" --replace >/dev/null 2>&1 || echo "shot fail $1"
  pico-cli interact query --package $PKG --device "$DEV" --format json > "$OUT/$1-$L.controls.json" 2>/dev/null
  echo "shot $1 $(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(len(d.get("controls",[])))' "$OUT/$1-$L.controls.json" 2>/dev/null) controls"; }
start() { "$ADB" -s "$DEV" shell am force-stop $PKG; sleep 1; "$ADB" -s "$DEV" shell am start -n $PKG/.platform.LaunchActivity >/dev/null; sleep 12; }
"$ADB" -s "$DEV" logcat -c
"$ADB" -s "$DEV" logcat -v time -s SpatialCalc:V AndroidRuntime:E > "$OUT/walk-$L.logcat" & LOGPID=$!
# first run (wipe → cold start): the hint line is there
c wipe; start; c lang "$L"; sleep 1.5; shot S01-first
c type 1; c type c; shot S01
c type "12*3+4"; shot S02
c type "="; shot S03
c type "5/0="; shot S04
c type "c"; c unit cm; c type "120*3="; c unit m; shot S03-converted
c type "c1.2="; c ruler on; sleep 1; shot S05-line
c dump
c ruler "turn 2"; c ruler "drag -0.05,0.1,0.15"; sleep 1; c dump; shot S05a-turned
c ruler stand; sleep 1; shot S05a-standing
c ruler off; c type "c23.5="; c ruler on; sleep 1.5; shot S05b-clipped
c ruler off; c unit mm; c type "c0.4="; c ruler on; shot S05-too-short
c type "c"; c unit m; c type "2~="; c ruler on; shot S05-negative
c ruler off; c unit cm; c type "c80="; c ruler on; c turn "z 3"; sleep 1; shot S05a-slanted
c ruler off
# v0.3.0 volumes: a volume unit → a 1:1 cube; a × b × c in a length → that box; turn panel, stick, twist, reset
c unit l; c type "c1.5="; shot S03-volume
c ruler on; sleep 1.5; shot S10-cube
c dump
c turn "x 2"; c turn "y 2"; c stick "right trigger"; sleep 1; shot S10a-turned
c twist "y 40"; sleep 1; c dump; shot S10a-twist-snapped
c turn reset; sleep 1; c dump; shot S10-reset
c ruler "dragdeltas 60 0,0.002,0.001"; sleep 1; c dump; shot S10-dragged
c ruler off; c unit cm; c type "c30*20*10="; c ruler on; sleep 1.5; shot S10-sides
# v0.3.2 q10-1: the same box after [m³] (label second line follows the window unit)
c unit m3; sleep 1; shot S10-sides-m3
c ruler off; c unit m3; c type "c1="; c ruler on; sleep 1.5; c dump; shot S10-1m3
c ruler off; c unit m3; c type "c64="; c ruler on; sleep 1.5; c dump; shot S10b-clipped
# v0.3.2 q10-2: QA #10 turned the 64 m³ box Y +15°, Z −15° and the label left the view
c turn "y 1"; c turn "z -1"; sleep 1.5; c dump; shot S10b-turned
c ruler off; c unit ml; c type "c0.0001="; c ruler on; shot S10-too-small
c unit none; c type "c"
c clearHistory; c goto history; shot S06-empty
c seedHistory 8; c goto history; shot S06
c clearAsk; shot S06-clear-confirm
c goto calc; c goto settings; shot S07
c goto howto; shot S08
c goto settings; c goto exitConfirm; shot S09
c goto calc; c type "c"
kill $LOGPID 2>/dev/null
echo "OVERFLOW lines: $(grep -c OVERFLOW "$OUT/walk-$L.logcat")"
