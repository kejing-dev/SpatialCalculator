# Spatial Calculator (空间计算器) · io.embodit.spatialcalc · v0.2.0

A plain desktop-style calculator in a PICO OS 6 (SDK 6.1.9) system window. Its one spatial extra: a result with a
length unit can be drawn in the air next to the window as a 1:1 line with ticks (📏), which you can drag, turn and
stand up. Design package: factory `apps/spatial-calc/design-package.json` v2.0 (gate B 2026-11-15: skin A frosted
round keys, history kept, line drag / turn / stand, name 空间计算器). v0.1.x (measuring, fit box, templates, records,
water blocks) is gone.

## Structure

```
Main.kt                DefaultStage (Mixed) + one Planar WindowContainer "CalcMain", 400 × 664 dp fixed
domain/calc            Calculator (BigDecimal 34 digits, precedence, %, ±, ⌫, = =), Fmt, LenUnit / Units, Ruler (ticks)
domain/app             AppController: the only place state changes (keys, unit row, 📏, line turn / stand, pages,
                       history, settings); HistoryCodec (files/history.json)
domain/place           LevelGate (D9), LinePlace (window estimate 0.82 m ahead, line default spot, sight cone, plate spot)
platform               Graph (process store), SoundPlayer, WindowCtl (level-gated open / reopen), HeadTracker, AppLog
ui/window              MainWindowUi (calc / history / settings / how-to pages, exit), Widgets (keys, rows, slider)
ui/stage               StageScreen (SpatialView + plates), StageScene (the line, per frame), input/BoardInput.kt (factory
                       board-input template with DragTotal)
app/src/debug          DebugCommandReceiver
tools                  shots.sh (QA screenshot walk), loudness.py (rendered loudness), build_icon.py, sc.sh
```

The window is opened from the Stage through the level gate. The line is a procedural tube + ticks (abstract scale
line, no model). Its grab area is a 60 mm bar collider; drags go through the board-input template (per-event deltas
summed), grip + stick turn it 15° a notch or stand it up.

## Debug broadcast

`adb shell am broadcast -a io.embodit.spatialcalc.DEBUG -p io.embodit.spatialcalc --es cmd <cmd> --es a "<arg>"`
(or `tools/sc.sh cmd <cmd> [arg]`). Logs: tag `SpatialCalc` (prefixes STATE KEY UNIT LINE HISTORY SETTINGS WINDOW
DRAG STICK CMD DUMP OVERFLOW), mirrored into files/input-log.txt.

| cmd | what | needs first |
|---|---|---|
| `goto calc\|history\|settings\|howto\|exitConfirm` | pages (S01 / S06 / S07 / S08 / S09) | – |
| `type 12*3+4=` | keys, same path as tapping them (`c` = C, `<` = ⌫, `~` = ±) | calc page (type switches to it) |
| `unit none\|mm\|cm\|m\|in\|ft` | unit row | – |
| `ruler on\|off\|toggle` | 📏 | a result (`type …=`) with a length unit |
| `ruler drag dx,dy,dz` | drag the line (start → move → end, same as a pinch drag) | line on |
| `ruler turn ±n` · `ruler stand` · `ruler lay` | grip + stick | line on |
| `seedHistory n` · `recall i` · `clearAsk` · `clearHistory` | history | – |
| `lang zh\|en\|system` · `mute on\|off` · `volume 0\|1\|2` · `bring` | settings | – |
| `wipe` | forget history + settings (first-run hint on next start) | restart after |
| `dump` | state, line ends in Stage metres, measured length, angles | – |

## Screenshot list (design states[])

`DEV=emulator-xxxx tools/shots.sh OUT zh|en` walks: S01-first (after wipe) · S01 · S02 (`type 12*3+4`) · S03 (`=`) ·
S04 (`5/0=`) · S03-converted (cm → m) · S05-line (`c1.2=`, unit m, `ruler on`) · S05a-turned / S05a-standing ·
S05b-clipped (`23.5=`) · S05-too-short (`0.4` mm) · S05-negative · S06-empty · S06 (seedHistory 8) ·
S06-clear-confirm · S07 · S08 · S09.
