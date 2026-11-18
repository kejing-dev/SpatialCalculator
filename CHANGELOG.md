# Changelog

## v0.3.3 (2026-11-17)
QA #11 on v0.3.2 (q11-1, user: grey them out).
- Once there is a result with a unit, the unit keys lock to its dimension: a length lights only mm cm m in ft, a volume
  only mL L cm³ m³ ft³ gal; the other row is greyed (still readable, ≈ 4.0:1) with a note on which row applies; [—] stays lit.
- Switching within a row converts from the original value (no drift); converted results show ≤ 7 significant digits,
  values under 1 keep plain decimals (0.0042378 ft³).
- The grey preview of a × b × c in a length shows the volume.
- Known: 1 mL → ft³ still shows 3.531e-5 (correct value); the box label has one decimal fewer than the window; to change
  dimension after a result press C, a new number or [—] first.

## v0.3.2 (2026-11-17)
QA #10 on v0.3.1.
- q10-1: a × b × c typed in a length unit is a volume in the data itself (length³), not only on screen. 30 × 20 × 10 cm
  = 6 L with「= 6,000 cm³」above; the volume keys convert from that one value (L 6 · mL 6,000 · cm³ 6,000 · m³ 0.006 ·
  ft³ 0.211888000329 · gal 1.58503231415), back and forth without drift; the length keys are greyed on it with a short
  note「这是体积，可换 mL、L、m³ 等单位」. History keeps「30 × 20 × 10 cm = 6 L」; × 2 = 12 L; a new number is in cm again;
  — gives the plain product 6,000. The box label reads its sides in cm and its volume in the window's unit.
- q10-2: the box label (PlatePlace) stays inside the default view (all 8 corners within ±39°, aimed at ±37°) for any
  size and turn: it slides back along the view and stops in front of the box's near face; it grows with distance so its
  smallest line (28 sp, was 20 / 24 sp) is ≥ 0.6° (64 m³: was 0.27°). Debug `dump` prints `boxPlate={…}`.

## v0.3.1 (2026-11-17)
QA #9 on v0.3.0: the new turn panel.
- The turn panel no longer hangs on the gauge: it stands 0.9 m ahead, 12° below the eye, left of the window (fixed in the
  room), on the first spot that the line / box, its label and the window leave clear seen from the user; it only moves
  when the gauge is dragged onto it. Text 0.64°, keys 2.85°, every corner within ±39° for any volume (1 mL … 64 m³).
- 16 dp between the panel's rows (was 8 dp); round keys.
- a × b × c in a length shows its volume in the window too ("6 L", not "6,000 cm").

## v0.3.0 (2026-11-16)
User 2026-11-16 on v0.2.0: volumes too, and the generated line / volume should drag and turn (line two axes, volume three).
- Volume unit row (mL L cm³ m³ ft³ gal) under the length row; results convert within a dimension. Window 400 × 716 dp.
- 📏 is now [摆出来 / Place it] (one key): a length → the 1:1 line; a volume → a 1:1 glass cube (edge = ∛volume, 1 L =
  10 cm) with edge lines and a label facing you; a × b × c typed in a length → that box (label "30 × 20 × 10 cm = 6 L").
- Drag the line or the box (hand pinch, controller ray, eye-hand; per-event deltas summed, DragTotal).
- Turn: a small panel beside the gauge with [−15°] [+15°] per axis and [Face me]; grip + stick (left / right = Y,
  up / down = X / the line's tilt, trigger + left / right = Z); two-hand twist (detectSpatialRotateGesture) snapping to
  0 / 45 / 90°. The line turns about its vertical axis and tilts lay → slant → stand.
- How-to page: rows for volume, drag, turning the line, turning the box, two-hand twist.
- Fix q8-1: the settings volume slider changes stop on a tap and on a drag (StopSlider + rememberUpdatedState; the
  SpatialUI Slider keeps its first onValueChange).

## v0.2.0 (2026-11-15)
Rebuilt as a minimal spatial desktop calculator (design package v2.0, gate B: skin A, history kept, line drag / turn /
stand, name 空间计算器).
- Cold start is the calculator itself: one 400 × 664 dp system window, no TabBar, no onboarding.
- New engine: BigDecimal, precedence, % in + / − context, ±, ⌫, chained and repeated =, ÷ 0 / too big messages.
- Unit row (— mm cm m in ft) converts results; 📏 draws a 1:1 line with ticks beside the window (≤ 10 m, longer is
  clipped with ≈), drag / grip + stick turn 15° / stand up; tap 📏 again to hide, once more to reset.
- History page (6 shown, 20 kept), settings (volume 0 / 50 / 100, language, bring it back, how to use, clear, quit).
- Removed: measuring, fit box, templates, records, result bodies, anchors, plane tracking and their sounds.

## v0.1.6 and earlier
The measure-and-calculate workflow (removed in v0.2.0; see git history).
