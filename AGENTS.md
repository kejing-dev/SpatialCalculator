# Spatial Calculator (空间计算器) · io.embodit.spatialcalc

MR calculator for PICO OS 6 (SDK 6.1.9): measure a tank / wall / shelf by tapping twice, the reading goes into the
formula, press = and the answer stands next to the real thing at 1:1 (water block, tiled wall, paint band, ghost boxes
in the shelf, rug). Design package: factory `apps/spatial-calc/design-package.json` (v1.2, gate B 2026-11-04).
Started from Spatial Ruler (github.com/kejing-dev/SpatialRuler): Vec3/Quat4, HitSurface, PlaneTracker,
WorldAnchorGateway, SystemPointer, the tap-on-backstop → analytic plane rule. Its body panel, PlaneColliders,
fine-tune and pin drag were not taken (design referenceRepo.deleteFirst).

## Structure

```
Main.kt                 DefaultStage (Mixed) + one Planar WindowContainer "CalcMain" (560 × 560 dp)
domain/calc             Q (value ± tol, length power 0–3), templates + slots, Evaluate (tank/tile/paint/fit/rug/free),
                        Paste (product-size strings), Tolerance per channel, Units / Fmt
domain/place            PlaceBody (drop chain: support → right of object / out → clearance → out of sight cone),
                        ResultPlate (plate pose, ≤ 40° below the eye), HeadSampler (D29), LevelGate (D9)
domain/session          CalcFlow: the whole state machine S00–S26; every input calls it (window, Stage, debug)
data/CardCodec          files/cards.json (cards + user templates)
platform                Graph (process store), SoundPlayer, WindowCtl (level-gated open / reopen), HeadTracker,
                        PlaneTracker, WorldAnchorGateway, SystemPointer
ui/window               MainWindowUi (TabBar 算/记录/设置, Toolbar 量一下/拟合盒/清空), CalcPage, Records, Settings
ui/stage                StageScreen (SpatialView + plates), StageScene (entities, per-frame), Visuals (1:1 bodies from
                        the shared unit cube), SceneResources, input/BoardInput.kt (factory board-input template)
app/src/debug           DebugCommandReceiver
tools                   sc.sh (harness), loudness.py, build_icon.py
```

Input: one `boardInput` on the SpatialView. Our colliders: a backstop shell 9 m out (taps that hit nothing of ours →
line from the eye / controller through the tap point → analytic plane hit, else the virtual table height), the result
body (drag to compare, tap: fit turns the boxes, others open the result menu), fit-box face handles (drag along the
face normal). No collider on any real plane (D24). Names come only from `Names`; `HitReplayTest` replays them.

## Debug broadcast

`adb shell am broadcast -a io.embodit.spatialcalc.DEBUG -p io.embodit.spatialcalc --es cmd <cmd> --es a "<arg>"`
(or `tools/sc.sh cmd <cmd> [arg]`, DEV=emulator-xxxx). Logs: tag `SpatialCalc`, prefixes STATE MEASURE CHIP CALC BODY
PLACE ANCHOR WINDOW TAP DRAG HINT CMD DUMP; also mirrored into files/input-log.txt (`run-as … cat files/input-log.txt`).

| cmd | what |
|---|---|
| `goto S01…S26` | S01 welcome, S02 grid, S03 tank, S04 aim, S07 typing, S08/S09 typed tank → place, S12 saved, S13 records, S14 next unit, S15 settings, S16 exit ask, S17 no planes, S18 lost, S19 no permission, S22 fit doesn't fit, S23 clear ask, S26 fit box |
| `template tank [round]` / tile / paint / fit / rug / free | pick a job |
| `measure` | Toolbar [量一下] |
| `ray yaw,pitch` (deg) or `ray x,y,z` | set the debug pointing line from the eye |
| `tap` | tap along it (same path as a system tap on the backstop) |
| `type 62cm` · `key 7` · `paste "60x30x35 cm"` · `typing on/off` | keyboard / paste |
| `equals` · `unit` · `resultmenu on` · `collapse` · `bring` | result |
| `chip i set 60` / `remeasure` / `delete` / `menu` | chips |
| `drag dx,dy,dz` · `stick x` · `turn` | compare: move / rotate the body, fit boxes 90° |
| `fitbox` · `face axis,sign,metres` · `use` | fit box |
| `save` · `pin` · `justsave` · `template-save NAME` · `next` · `clear` · `confirm` · `cancel` | cards |
| `page calc/records/settings` · `expand i` · `replay i` | records |
| `lang zh/en/system` · `imperial on` · `opacity 0-2` · `volume 0-2` · `mute on` | settings |
| `noplanes on` · `lost on` · `noPermission` · `anchorFail on` · `err paste/cardCap/chipCap/pinsClose/dim/boxThin/unitMismatch` | error states |
| `freeze on` · `recall` · `exitask` · `wipe` · `dump` · `evidence` · `sound pin` | misc |
| `bringcard i` · `closewindow` | records [带到面前] on card i (same call as the button) · close the window like the system × (Stage HUD [打开面板]) |

Typical walk (emulator, head at 1.65 m looking −Z): `template tank`, then `ray 0,-35`/`tap`, `ray 20,-35`/`tap` (length),
again for width and depth, `equals` → water block right of the measured object, plate on its top edge.

## Screenshot list for QA

S01 first launch (wipe) · S02 grid with the 10 L demo block · S03 tank empty / tile with defaults · S04 aim ·
S05 first pin + reading plate · S07 typing (zh / en) · S08 tank / round tank / tile / paint / fit / rug / free oversize ·
S09 place · S11 tile 30 → 60 · S12 saved · S13 records (empty + expanded) · S14 L / gal · S15 settings (zh / en) ·
S16 exit · S17 noplanes · S18 lost · S19 no permission · S20 pinned card after force-stop · S22 fit doesn't fit ·
S23 clear ask · S24 anchorFail · S25 seven errors · S26 fit box.

## Build

`JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home ./gradlew :app:testDebugUnitTest :app:assembleDebug`

## v0.1.1 (QA round 1 rework)

- Typing a LENGTH slot opens in the typing unit (cm / in), not metres; closing the keyboard unchanged keeps the chip
  exactly (`typingInit`). Round2Test covers paste → [打数] → [✓ 收起].
- Settings: 细瞄 ×4 removed for v1 (needs the tracked-pointer channel); 吸附已有钉 works: a tap within 3 cm of a pin
  lands on it (`CalcFlow.snapped`, log `SNAP`).
- Records [带到面前]: moves the pinned body (re-anchors) / the active body, or loads the card in front; hint「放到你面前了」.
- Fit box: never opens within 35° of the head → window direction (`PlaceBody.besideWindow`); [用这三边] remembers the
  cell (`fitCell`) and = puts the ghost boxes in it.
- Auto placement slides past pinned bodies (10 cm gap, `CalcFlow.obstacles`).
- Chips wrap (FlowRow); welcome cards 320 dp; records list + 「接着量一个」tiles follow each other, 1–2 cards keep one open.
- Default volume 85 % (chip 1.0, tape 0.9): `tools/loudness.py` voiced RMS −16.9 / −18.2 / −18.8 dBFS (−30/−40/−50 dB thresholds).

## v0.1.2 (界面精简, profile B31 — user「界面还需要简洁一点，现在有点太乱」)

- `domain/session/Chrome.kt` decides the Calc page chrome per state: one primary (`Chrome.primary`: Measure <slot> → = Show it here →
  Save → Measure another), Toolbar keys only while measuring and never a copy of the primary (`Chrome.toolbar`), everything
  else in ⋯ More (`Chrome.more`, reuses `CalcState.resultMenu`; a tap on the 1:1 body opens it too). `ChromeTest` counts every
  state (≤ 7 incl. the 3 tabs and the body).
- Formula = one quiet strip; tap → chip edit mode (`chipMenu`) with per-chip buttons + 改数 / 重量 / 删 + ✓ (`closeChipMenu`).
- How-to lines (`Hint.TIP`) show once per job / once for the first body (`Settings.tipsSeen`, prefs `tipsSeen`), auto-hide after
  10 s; then More → How to (`showTip`).
- Settings first screen: language / units step rows, mute + volume, [More settings] (`CalcState.settingsMore`), Quit.
- Records: open card = [Show it again] [⋯ More] (pin / bring / template / delete / close), one primary「Measure another」.
- New debug cmds: `more on/off` (= resultmenu), `settingsmore on/off`, `tip`, `chipdone`.
- Tools: `tools/walk.sh OUTDIR zh|en` (screens + `pico-cli interact query` control counts), `tools/ws.py` (blank %),
  `tools/ba.py` (factory build/simplify-before-after.html).

## v0.1.3 (QA round 3 rework)

- q3-1 mute always on the Calc page: `MuteKey` (Widgets.kt) in the title row of the job page, the formula page (icon + 静音 / 取消静音)
  and the keyboard (icon only); `CalcFlow.toggleMute()` = the same setting as Settings → Sound. Sound left ⋯ More (no duplicate).
  `Chrome.MUTE` is outside the B31 seven; `Chrome.shownCount` (≤ 8 with a body) is what is on screen.
- q3-2 after [钉在这里] the primary is「接着量一个」: `Chrome.pinnedDone` (saved card pinned, not dirty) → NEXT before the PLACE
  rule; `CalcFlow.equals()` ignores = in that state (log `EQUALS ignored`). Editing a number makes it a new result again.
- q3-3 keyboard slot switch: chips on the keyboard call `CalcFlow.typingChipTap(i)`: the field is committed into the slot it was typed
  for (unchanged → nothing written), then the keyboard opens on slot i with that slot's value. Debug: `chip "i typing"`.
- q3-4 Settings first screen: [重开面板] and [退出应用] side by side again (D44); gone from More settings.
- K6 job page with saved templates: job cards 140 dp, templates as a labelled 64 dp tile row (up to 6 without scrolling).
- K7 template naming row has [取消] (`cancelNaming`, debug `namecancel`). K8 records: one card at a time + a「1 / 7 下一张 ›」row
  (`recordStep`, debug `recordnext`), always 7 controls. K9 the 10 L demo block slides right (then left) off pinned bodies, also
  when they are restored after it appeared (`PlaceBody.clearOf`, log `DEMO 10 L moved clear of pinned`). K10 [✓ 收起] never lands
  in chip edit mode. K3 English count「Cards: n / 200」.
- Tests: `Round3Test` (9). Real taps: `pico-cli interact act --text` fails on digit-only names, click keys by node id
  (`interact perform --node … --action-id 16`).

## v0.1.4 (QA round 4)

- q4-1 keyboard page (`CalcPage.Typing`): title row · [field (slot name inside) + 2-column slot grid + result, scrolls] beside the
  keypad · fixed bottom bar `unit · [Paste] · [✓ Done]` that is never scrolled. Six English slots (How many boxes fit) = 3 rows.
  The chip-edit page uses the same grid with 3 columns (`ChipRow(columns = …)`). `Round4Test.keyboardPageFitsEveryJobWithDoneVisible`
  checks the dp budget for every job and shape; screenshots for every job × zh / en (`template <t>` → `typing on`).
- B31 gates: every button is `BTN_H` = 64 dp, side-by-side gap `BTN_GAP` = 16 dp (Widgets.kt). Keypad keys stay 52 × 48 / 6 dp
  (`gate:allow`, design S07).
- K11 demo block: `PlaceBody.clearOf(..., view)` searches only spots 11°–40° off the view direction at 1.0–2.4 m (`inView`),
  falls back to the plain slide only if none is free. K13 a record card's ⋯ → 存成模板 opens the same naming row (`NameEditor`)
  with [取消] [好]. K14 the name field's accessibility name is the hint text. K12 saved template names stay as typed (user data,
  not translated).

## v0.1.5 (QA round 5)

- q5-1 default template name = short job name + `MM-dd HH:mm` (`domain/calc/DefaultName`, <= 24 chars = the name field limit,
  base <= 12, the date always kept, an old stamp is dropped when saving a template from a template). Short names
  `tpl_short_*`: en Tank / Round tank / Tiles / Paint / Boxes / Rug / Formula; zh = the job names (round tank 圆缸多少升).
  Job page template tile: a name ending in a stamp shows the date on its own line; the tile grows (>= 64 dp), never cuts.
  `DefaultNameTest`. Debug: `template-save default` (the naming row's default), `naming on/off`, `custom i` (pick template i).
