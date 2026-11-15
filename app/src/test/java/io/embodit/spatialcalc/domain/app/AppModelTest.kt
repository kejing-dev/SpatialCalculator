package io.embodit.spatialcalc.domain.app

import io.embodit.spatialcalc.domain.calc.Fmt
import io.embodit.spatialcalc.domain.calc.Key
import io.embodit.spatialcalc.domain.calc.LenUnit
import io.embodit.spatialcalc.domain.calc.LineProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppModelTest {
    private class Fx : AppEffects {
        val sounds = ArrayList<Sfx>(); var saved: List<HistoryItem>? = null; var settings: Settings? = null; var t = 1_000L
        override fun sound(s: Sfx) { sounds += s }
        override fun saveSettings(s: Settings) { settings = s }
        override fun saveHistory(items: List<HistoryItem>) { saved = items }
        override fun log(line: String) {}
        override fun now() = t++
    }

    private fun app(settings: Settings = Settings(firstHintShown = true), history: List<HistoryItem> = emptyList()) =
        Fx().let { fx -> AppController(fx).also { it.start(settings, history) } to fx }

    private fun AppController.type(keys: String) = keys.forEach { c -> press(Key.of(c)!!) }

    @Test fun coldStartIsTheCalculator() {
        val (a, _) = app(Settings())
        assertEquals(Page.CALC, a.s.page)
        assertEquals("S01", a.s.stateId)
        assertTrue(a.s.showFirstHint)
        a.type("1")
        assertFalse(a.s.showFirstHint)
        assertTrue(a.settings.value.firstHintShown)
    }

    @Test fun historyKeepsTwentyNewestFirstAndSaves() {
        val (a, fx) = app()
        repeat(25) { a.type("${it % 10}+1=") }
        assertEquals(20, a.s.history.size)
        assertEquals("4 + 1", a.s.history[0].expr)
        assertEquals("5", a.s.history[0].result)
        assertEquals(20, fx.saved!!.size)
        a.type("=") // = = repeats the last + 1 and is a new row
        assertEquals("5 + 1", a.s.history[0].expr)
        assertEquals("6", a.s.history[0].result)
        a.type("c=") // = on an empty calculator adds nothing
        assertEquals("5 + 1", a.s.history[0].expr)
    }

    @Test fun recallBringsTheResultBackWithItsUnit() {
        val (a, _) = app()
        a.unit(LenUnit.CM); a.type("60+45+60=")
        a.unit(LenUnit.NONE); a.type("2*2=")
        a.page(Page.HISTORY)
        a.recall(1)
        assertEquals(Page.CALC, a.s.page)
        assertEquals(LenUnit.CM, a.s.unit)
        assertEquals("165", Fmt.number(a.s.calc.result!!))
        a.type("*2=")
        assertEquals("330", Fmt.number(a.s.calc.result!!))
        assertEquals("165 × 2", a.s.calc.exprText)
    }

    @Test fun clearHistoryAsksFirst() {
        val (a, fx) = app()
        a.type("1+1=")
        a.clearHistoryAsk(true)
        assertTrue(a.s.clearAsk)
        assertEquals(1, a.s.history.size)
        a.clearHistory()
        assertEquals(0, a.s.history.size)
        assertEquals(emptyList<HistoryItem>(), fx.saved)
    }

    @Test fun unitSwitchConvertsTheResultAndShowsTheOriginal() {
        val (a, _) = app()
        a.unit(LenUnit.CM)
        a.type("120*3=")
        assertEquals("360", Fmt.number(a.s.calc.result!!))
        a.unit(LenUnit.M)
        assertEquals("3.6", Fmt.number(a.s.calc.result!!))
        assertEquals("= 360 cm", a.s.convertedText)
        a.unit(LenUnit.FT)
        assertEquals("11.811023622", Fmt.number(a.s.calc.result!!))
        a.unit(LenUnit.CM)
        assertEquals("360", Fmt.number(a.s.calc.result!!))
        assertNull(a.s.convertedText)
        // no unit → a unit: the number just gets the unit
        val (b, _) = app()
        b.type("40=")
        b.unit(LenUnit.CM)
        assertEquals("40", Fmt.number(b.s.calc.result!!))
    }

    @Test fun rulerOnlyForAPositiveLength() {
        val (a, fx) = app()
        a.type("1.2=")
        assertFalse(a.s.rulerShown)            // no unit: no 📏
        a.unit(LenUnit.M)
        assertTrue(a.s.rulerShown && a.s.rulerEnabled)
        a.ruler()
        assertTrue(a.s.line.on)
        assertEquals(1.2, a.s.line.lengthM, 1e-9)
        assertEquals("S05", a.s.stateId)
        assertEquals(Sfx.TAPE_OUT, fx.sounds.last())
        // the line follows the next result, and the unit (same physical length)
        a.type("+0.3=")
        assertEquals(1.5, a.s.line.lengthM, 1e-9)
        a.unit(LenUnit.CM)
        assertEquals(1.5, a.s.line.lengthM, 1e-9)
        assertEquals(LenUnit.CM, a.s.line.unit)
        // a negative result closes it and says why
        a.type("~")
        assertFalse(a.s.line.on)
        assertEquals(LineProblem.NEGATIVE, a.s.lineProblem)
        assertFalse(a.s.rulerEnabled)
        a.ruler()
        assertFalse(a.s.line.on)
        // < 1 mm
        val (b, _) = app()
        b.unit(LenUnit.MM); b.type("0.4="); b.ruler()
        assertFalse(b.s.line.on)
        assertEquals(LineProblem.TOO_SHORT, b.s.lineProblem)
        b.type("c")
        assertNull(b.s.lineProblem)
    }

    @Test fun longLinesAreClipped() {
        val (a, _) = app()
        a.unit(LenUnit.M); a.type("23.5="); a.ruler()
        assertEquals("S05b", a.s.stateId)
    }

    @Test fun turnSnapsThroughZeroAndStands() {
        val (a, _) = app()
        a.unit(LenUnit.CM); a.type("80="); a.ruler()
        a.lineTurn(1); a.lineTurn(1)
        assertEquals(2, a.s.line.yawSteps)
        a.lineTurn(-1); a.lineTurn(-1)
        assertEquals(0, a.s.line.yawSteps)    // back to facing the user exactly
        assertEquals("S05", a.s.stateId)
        a.lineStand(true)
        assertTrue(a.s.line.standing)
        assertEquals("S05a", a.s.stateId)
        // hide + show again = back to default (回正)
        a.ruler(); a.ruler()
        assertFalse(a.s.line.standing)
        assertEquals(0, a.s.line.yawSteps)
        assertEquals(2, a.s.line.serial)
    }

    @Test fun settingsArePersisted() {
        val (a, fx) = app()
        a.volume(1); a.mute(true); a.language(Language.EN); a.unit(LenUnit.IN)
        assertEquals(Settings(volume = 1, muted = true, language = Language.EN, unit = LenUnit.IN, firstHintShown = true), fx.settings)
        assertEquals(2, Settings().volume) // default stop 100 % (deviation: loudness)
    }

    @Test fun historyCodecRoundTrip() {
        val items = listOf(HistoryItem("12 × 3 + 4", "40", LenUnit.NONE, 5L), HistoryItem("1.2 × 3", "3.6", LenUnit.M, 6L))
        assertEquals(items, HistoryCodec.decode(HistoryCodec.encode(items)))
        assertEquals(emptyList<HistoryItem>(), HistoryCodec.decode("not json"))
    }

    @Test fun keysOnlyActOnTheCalcPage() {
        val (a, _) = app()
        a.page(Page.SETTINGS)
        a.type("5")
        assertTrue(a.s.calc.tokens.isEmpty())
        a.exitAsk(true)
        assertEquals("S09", a.s.stateId)
    }
}
