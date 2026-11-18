package io.embodit.spatialcalc.domain.app

import io.embodit.spatialcalc.domain.calc.Dim
import io.embodit.spatialcalc.domain.calc.Fmt
import io.embodit.spatialcalc.domain.calc.Key
import io.embodit.spatialcalc.domain.calc.LenUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.MathContext

/**
 * v0.3.3 (QA #11 q11-1, user 2026-11-17「Yes, grey them out」): once there is a result, only the unit keys of its own
 * dimension light up — a length result only the length keys, a volume result (12 L typed, or a × b × c) only the
 * volume keys — and switching always converts. QA saw 120 cm → [mL] →「120 mL」, back and forth → 0.317 cm, and
 * 1.5 L → [mm] →「1.5 mm」.
 */
class UnitLockTest {
    private class Fx : AppEffects {
        val log = ArrayList<String>()
        override fun sound(s: Sfx) {}
        override fun saveSettings(s: Settings) {}
        override fun saveHistory(items: List<HistoryItem>) {}
        override fun log(line: String) { log += line }
        override fun now() = 1L
    }

    private fun app(fx: Fx = Fx()) = AppController(fx).also { it.start(Settings(firstHintShown = true), emptyList()) }
    private fun AppController.type(keys: String) = keys.forEach { c -> press(Key.of(c)!!) }
    private fun AppController.shown(): String = Fmt.number(s.calc.result!!) + " " + s.unit.symbol
    private fun same(expected: BigDecimal, v: BigDecimal, why: String) =
        assertEquals("$why: $expected vs $v", 0, expected.round(MC).compareTo(v.round(MC)))

    private val lengths = LenUnit.ROW.filter { it.isLength }
    private val volumes = LenUnit.VOL_ROW

    /** A result typed in [u]: 120 in a length, 1.5 in a volume. */
    private fun resultIn(u: LenUnit, fx: Fx = Fx()): AppController = app(fx).also { it.unit(u); it.type(if (u.isLength) "120=" else "1.5=") }

    @Test fun theQa11SequencesNoLongerRelabel() {
        val a = resultIn(LenUnit.CM)
        assertEquals("120 cm", a.shown())
        a.unit(LenUnit.ML); assertEquals("120 cm", a.shown())             // was「120 mL」
        for (u in volumes) a.unit(u)
        a.unit(LenUnit.CM); assertEquals("120 cm", a.shown())             // was 0.317 cm
        val b = resultIn(LenUnit.L)
        assertEquals("1.5 L", b.shown())
        b.unit(LenUnit.MM); assertEquals("1.5 L", b.shown())              // was「1.5 mm」
        for (u in lengths) b.unit(u)
        b.unit(LenUnit.L); assertEquals("1.5 L", b.shown())               // was 0.00492 L
    }

    @Test fun onlyTheResultsOwnRowIsLit() {
        for (u in lengths + volumes) {
            val a = resultIn(u)
            val d = u.dim
            assertEquals("$u", d, a.s.lockedDim)
            for (k in LenUnit.ROW + LenUnit.VOL_ROW) {
                val want = k == LenUnit.NONE || k.dim == d
                assertEquals("$u result, key $k", want, a.s.unitEnabled(k))
            }
        }
        // a × b × c in cm is a volume (6 L): the length row greyed, as v0.3.2
        val box = app(); box.unit(LenUnit.CM); box.type("30*20*10=")
        assertEquals(Dim.VOLUME, box.s.lockedDim)
        assertTrue(lengths.none { box.s.unitEnabled(it) } && volumes.all { box.s.unitEnabled(it) })
        // a plain volume chained: 6 L × 2 = 12 L keeps the volume row only
        box.type("*2="); assertEquals("12 L", box.shown()); assertTrue(lengths.none { box.s.unitEnabled(it) })
    }

    @Test fun everyDimensionTimesEveryUnitBackAndForthFiftyTimesNeverDrifts() {
        for (start in lengths + volumes) {
            val a = resultIn(start)
            val typed = BigDecimal(if (start.isLength) "120" else "1.5")
            val row = if (start.isLength) lengths else volumes
            // the exact value in every unit of the row, from the definitions
            val want = row.associateWith { typed.multiply(start.exactFactor!!).divide(it.exactFactor!!, MathContext(34)) }
            repeat(50) { round ->
                for (u in row) {
                    a.unit(u); same(want.getValue(u), a.s.calc.result!!, "$start r$round -> $u")
                    a.unit(start); same(typed, a.s.calc.result!!, "$start r$round -> $u -> back")
                }
            }
            a.unit(start); same(typed, a.s.calc.result!!, "$start end"); assertEquals(start, a.s.unit)
        }
    }

    @Test fun aGreyedKeyDoesNothing() {
        for (start in lengths + volumes) {
            val fx = Fx()
            val a = resultIn(start, fx)
            val other = if (start.isLength) volumes else lengths
            for (k in other) {
                val before = a.s
                a.unit(k)
                assertEquals("$start then greyed $k", before, a.s)
                assertTrue(fx.log.last().contains("refused"))
            }
        }
        // greyed keys tapped in between never shift the value either (50 rounds across both rows)
        val a = resultIn(LenUnit.CM)
        repeat(50) { for (u in LenUnit.VOL_ROW + LenUnit.ROW.filter { it.isLength }) a.unit(u); a.unit(LenUnit.CM) }
        assertEquals("120 cm", a.shown())
    }

    @Test fun whileTypingEveryKeyPicksTheUnit() {
        val a = app(); a.unit(LenUnit.CM); a.type("12")
        assertNull(a.s.lockedDim)
        assertTrue((LenUnit.ROW + LenUnit.VOL_ROW).all { a.s.unitEnabled(it) })
        a.unit(LenUnit.L); a.type("="); assertEquals("12 L", a.shown())
        // a plain-number result can still be given a unit, then it locks to that dimension
        val n = app(); n.type("120=")
        assertNull(n.s.lockedDim)
        n.unit(LenUnit.CM); assertEquals("120 cm", n.shown()); assertEquals(Dim.LENGTH, n.s.lockedDim)
        n.unit(LenUnit.ML); assertEquals("120 cm", n.shown())
        // [—] stays lit: back to a plain number, then any unit again
        n.unit(LenUnit.NONE); assertNull(n.s.lockedDim); n.unit(LenUnit.ML); assertEquals("120 mL", n.shown())
        // starting a new number unlocks: typing after a result
        n.type("5"); assertNull(n.s.lockedDim)
    }

    @Test fun previewOfABoxIsTheVolume() {
        val a = app(); a.unit(LenUnit.CM); a.type("30*20*10")
        val (v, u) = a.s.previewValue!!
        assertEquals(LenUnit.L, u); assertEquals(0, BigDecimal(6).compareTo(v))
        a.type("="); assertEquals("6 L", a.shown())
        val m = app(); m.unit(LenUnit.M); m.type("2*1*0.5")
        assertEquals(LenUnit.M3, m.s.previewValue!!.second)
        // two factors stay a length product; no unit stays plain
        val b = app(); b.unit(LenUnit.CM); b.type("30*20")
        assertEquals(LenUnit.CM, b.s.previewValue!!.second)
        val p = app(); p.type("30*20*10")
        assertEquals(LenUnit.NONE, p.s.previewValue!!.second)
    }

    private companion object { val MC = MathContext(20) }
}
