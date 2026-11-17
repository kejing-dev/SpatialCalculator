# Spatial Calculator · 空间计算器

PICO OS 6 (Spatial SDK 6.1.9). Opens straight into a calculator. Get a length, see it in the air at real size.

- 4 × 5 keys, × ÷ before + −, %, ±, ⌫, repeated =, exact decimals (0.1 + 0.2 = 0.3)
- unit rows — mm cm m in ft and mL L cm³ m³ ft³ gal: switching converts the result
- [摆出来 / Place it] puts the result next to the window 1:1: a length as a line with ticks, a volume (mL L cm³ m³ ft³ gal)
  as a glass cube, a × b × c as that box; drag it, turn it (panel ±15° per axis, grip + stick, two hands), [回正] faces you
- recent history (20 kept, 6 shown), mute always on screen, Chinese / English

Build: `./gradlew assembleDebug` (JDK 21). Debug commands and the QA screenshot list: `AGENTS.md`.
