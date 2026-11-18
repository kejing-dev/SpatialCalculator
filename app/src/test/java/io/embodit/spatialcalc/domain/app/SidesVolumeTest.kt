package io.embodit.spatialcalc.domain.app

import io.embodit.spatialcalc.domain.calc.Fmt
import io.embodit.spatialcalc.domain.calc.Key
import io.embodit.spatialcalc.domain.calc.LenUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * v0.3.2 (QA #10 q10-1): a × b × c typed in a length unit is a volume (length³) in the data itself, not only on screen.
 * The window, the unit keys, the converted line, history and the box all read one value with that dimension.
 * QA saw 30 × 20 × 10 cm =「6 L」, then [L] →「6,000 L」, [m³] →「6 m³」, [m] → big「6 L」over「= 6,000 cm」, [L] →「60 L」.
 */
class SidesVolumeTest {
    private class Fx : AppEffects {
        val log = ArrayList<String>(); var saved: List<HistoryItem> = emptyList()
        override fun sound(s: Sfx) {}
        override fun saveSettings(s: Settings) {}
        override fun saveHistory(items: List<HistoryItem>) { saved = items }
        override fun log(line: String) { log += line }
        override fun now() = 1L
    }

    private fun app() = AppController(Fx()).also { it.start(Settings(firstHintShown = true), emptyList()) }
    private fun AppController.type(keys: String) = keys.forEach { c -> press(Key.of(c)!!) }

    /** What the window's big line shows (MainWindowUi.ResultText: number + the unit's symbol). */
    private fun AppController.shown(): String = Fmt.number(s.calc.result!!) + " " + s.unit.symbol

    private fun eq(expected: String, v: BigDecimal) = assertEquals("$expected vs ${v.toPlainString()}", 0, BigDecimal(expected).compareTo(v))

    private fun boxCm(): AppController = app().also { it.unit(LenUnit.CM); it.type("30*20*10=") }

    @Test fun thirtyByTwentyByTenCentimetresIsSixLitres() {
        val a = boxCm()
        assertEquals(LenUnit.L, a.s.unit)
        eq("6", a.s.calc.result!!)
        assertEquals("6 L", a.shown())
        assertTrue(a.s.sidesVolume)
        // the converted line says what it was computed as: 6,000 cm³ (not「= 6,000 cm」)
        assertEquals("= 6,000 cm³", a.s.convertedText)
        // the box over it is 30 × 20 × 10 cm and 0.006 m³ — the same value
        val w = a.s.gaugeWant!!
        assertEquals(0.006, w.volumeM3, 1e-12)
        assertEquals(listOf(0.3, 0.2, 0.1), w.dimsM.map { Math.round(it * 1e9) / 1e9 })
        assertEquals(LenUnit.CM, w.sidesUnit)
    }

    @Test fun everyVolumeUnitIsRight() {
        val want = mapOf(
            LenUnit.L to "6", LenUnit.ML to "6000", LenUnit.CM3 to "6000", LenUnit.M3 to "0.006",
            // 0.006 / 0.028316846592 and 0.006 / 0.003785411784 (exact definitions)
            LenUnit.FT3 to BigDecimal("0.006").divide(BigDecimal("0.028316846592"), java.math.MathContext(34)).toPlainString(),
            LenUnit.GAL to BigDecimal("0.006").divide(BigDecimal("0.003785411784"), java.math.MathContext(34)).toPlainString(),
        )
        for ((u, v) in want) {
            val a = boxCm()
            a.unit(u)
            assertEquals(u, a.s.unit)
            val mc = java.math.MathContext(20)
            assertEquals("$u", 0, BigDecimal(v).round(mc).compareTo(a.s.calc.result!!.round(mc)))
            // the box reads the same value in m³
            assertEquals("$u", 0.006, a.s.gaugeWant!!.volumeM3, 1e-12)
        }
        val a = boxCm(); a.unit(LenUnit.FT3); assertEquals("0.211888000329 ft³", a.shown())
        a.unit(LenUnit.GAL); assertEquals("1.58503231415 gal", a.shown())
        a.unit(LenUnit.ML); assertEquals("6,000 mL", a.shown())
        a.unit(LenUnit.M3); assertEquals("0.006 m³", a.shown())
    }

    @Test fun theQa10SequenceGivesNoWrongNumber() {
        val a = boxCm()
        a.unit(LenUnit.L); assertEquals("6 L", a.shown())         // was「6,000 L」
        a.unit(LenUnit.M3); assertEquals("0.006 m³", a.shown())   // was「6 m³」
        // [m] on a volume: greyed, nothing changes (was big「6 L」over「= 6,000 cm」)
        assertFalse(a.s.unitEnabled(LenUnit.M))
        for (u in LenUnit.ROW.filter { it.isLength }) { a.unit(u); assertEquals("0.006 m³", a.shown()) }
        a.unit(LenUnit.L); assertEquals("6 L", a.shown())         // was「60 L」
    }

    @Test fun switchingBackAndForthNeverDrifts() {
        val a = boxCm()
        repeat(50) { for (u in LenUnit.VOL_ROW.shuffled(java.util.Random(it.toLong()))) a.unit(u) }
        a.unit(LenUnit.GAL); a.unit(LenUnit.FT3); a.unit(LenUnit.L)
        eq("6", a.s.calc.result!!)
        a.unit(LenUnit.CM3); eq("6000", a.s.calc.result!!)
        assertEquals(0.006, a.s.gaugeWant!!.volumeM3, 1e-12)
    }

    @Test fun lengthResultsStillConvertAsBefore() {
        val a = app()
        a.unit(LenUnit.CM); a.type("120+240=")
        eq("360", a.s.calc.result!!); assertEquals(LenUnit.CM, a.s.unit)
        assertTrue(LenUnit.ROW.all { a.s.unitEnabled(it) })
        a.unit(LenUnit.M); eq("3.6", a.s.calc.result!!); assertEquals("= 360 cm", a.s.convertedText)
        a.unit(LenUnit.MM); eq("3600", a.s.calc.result!!)
        a.unit(LenUnit.IN); a.unit(LenUnit.FT); a.unit(LenUnit.CM); eq("360", a.s.calc.result!!)
        // two factors only (a × b) stays a length product, as before
        val b = app(); b.unit(LenUnit.M); b.type("3*4=")
        assertEquals(LenUnit.M, b.s.unit); eq("12", b.s.calc.result!!)
    }

    @Test fun otherLengthsAndThePlainProduct() {
        val m = app(); m.unit(LenUnit.M); m.type("4*4*4=")
        assertEquals("64 m³", m.shown()); assertTrue(m.s.gaugeWant!!.dimsM.all { it == 4.0 })
        val mm = app(); mm.unit(LenUnit.MM); mm.type("100*100*100=")
        assertEquals("1 L", mm.shown())
        val inch = app(); inch.unit(LenUnit.IN); inch.type("10*10*10=")
        // 1000 in³ = 16.387064 L
        assertEquals("16.387064 L", inch.shown())
        // typed with no unit: plain 6,000; picking cm afterwards makes it the volume
        val n = app(); n.type("30*20*10=")
        eq("6000", n.s.calc.result!!); assertEquals(LenUnit.NONE, n.s.unit)
        n.unit(LenUnit.CM); assertEquals("6 L", n.shown()); assertTrue(n.s.sidesVolume)
        // — gives the plain product back, and cm again the volume
        n.unit(LenUnit.NONE); eq("6000", n.s.calc.result!!)
        n.unit(LenUnit.CM); assertEquals("6 L", n.shown())
    }

    @Test fun historyAndNextInputUseTheSameValue() {
        val fx = Fx()
        val a = AppController(fx).also { it.start(Settings(firstHintShown = true), emptyList()) }
        a.unit(LenUnit.CM); a.type("30*20*10=")
        val h = a.s.history.first()
        assertEquals("30 × 20 × 10 cm", h.expr); assertEquals("6", h.result); assertEquals(LenUnit.L, h.unit)
        // chaining keeps the volume: 6 L × 2 = 12 L
        a.type("*2="); assertEquals("12 L", a.shown())
        // a new number after a × b × c is in the length unit again
        val b = AppController(Fx()).also { it.start(Settings(firstHintShown = true), emptyList()) }
        b.unit(LenUnit.CM); b.type("30*20*10=")
        b.type("5="); assertEquals(LenUnit.CM, b.s.unit); assertEquals("5 cm", b.shown())
        // recall gives 6 L back as a volume
        b.recall(1); assertEquals("6 L", b.shown())
    }

    @Test fun theBoxFollowsTheUnitsWithTheSameValue() {
        val a = boxCm()
        a.ruler()
        assertTrue(a.s.line.on && a.s.line.isBox && a.s.line.fromSides)
        assertEquals(LenUnit.CM, a.s.line.sidesUnit)
        a.unit(LenUnit.M3)
        assertTrue(a.s.line.on && a.s.line.isBox && a.s.line.fromSides)
        assertEquals(LenUnit.M3, a.s.line.unit)
        assertEquals(0.006, a.s.line.volumeM3, 1e-12)
        assertEquals("0.006 m³", Fmt.volume(a.s.line.volumeM3, a.s.line.unit))
        assertEquals("30 × 20 × 10 cm", Fmt.sides(a.s.line.sides, a.s.line.sidesUnit))
        a.unit(LenUnit.M) // greyed: the box stays
        assertTrue(a.s.line.on && a.s.line.isBox)
        assertEquals("S10", a.s.stateId)
    }
}
