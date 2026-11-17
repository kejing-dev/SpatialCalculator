# Changelog

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
