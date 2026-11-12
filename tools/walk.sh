#!/usr/bin/env bash
# usage: walk.sh OUTDIR LANG(zh|en)
set -u
DEV=emulator-5560; PKG=io.embodit.spatialcalc; ADB=/Users/bytedance/Library/Android/sdk/platform-tools/adb
OUT="$1"; L="$2"
c() { "$ADB" -s $DEV shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$1" --es a "\"${2:-}\"" >/dev/null; sleep 1.2; }
shot() { sleep 1.5; pico-cli capture screenshot --device $DEV --out "$OUT/$1-$L.png" --replace >/dev/null 2>&1 || echo "shot fail $1"
  pico-cli interact query --package $PKG --device $DEV --format json > "$OUT/$1-$L.controls.json" 2>/dev/null
  echo "shot $1 $(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(len(d["controls"]), "|", " / ".join(c.get("name","?") for c in d["controls"]))' "$OUT/$1-$L.controls.json" 2>/dev/null)"; }
# fresh start: wipe, restart
c wipe; sleep 1; "$ADB" -s $DEV shell am force-stop $PKG; sleep 1; "$ADB" -s $DEV shell run-as $PKG rm -f files/cards.json; "$ADB" -s $DEV shell run-as $PKG ls files; sleep 1
"$ADB" -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 9
c lang $L; sleep 1
shot S01-welcome
c welcome; shot S02-pick
c page records; shot S13-records-empty
c page calc
c template tank; shot S03-tank-first-tip
c tip; sleep 0.5; c next; c template tank; shot S03-tank-empty
c measure; shot S04-aim
c cancel; c paste "60x30x30 cm"; shot S03-tank-filled
c equals; sleep 1; shot S09-place
c ray "25,-35"; c tap; sleep 2; shot S08-tank-body
c more on; shot S08-more
c more off; c clear; shot S23-clear-ask
c cancel
c more off; c chip "0 menu"; shot S03-chip-edit
c chipdone; c typing on; shot S07-typing
c typing off; c save; shot S12-saved
c justsave; shot S08-after-save
c page records; shot S13-records-1card
c next; c template tile; c paste "300x250 cm"; c equals; sleep 1; c ray "-30,-10"; c tap; sleep 1; c save; c justsave
c page records; shot S13-records-2cards
c next; c template fit; shot S03-fit-empty
c page settings; shot S15-settings
c settingsmore on; shot S15-settings-more
c settingsmore off; c exitask; shot S16-exit
c cancel; c page calc
