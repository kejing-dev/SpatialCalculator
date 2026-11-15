# Changelog

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
