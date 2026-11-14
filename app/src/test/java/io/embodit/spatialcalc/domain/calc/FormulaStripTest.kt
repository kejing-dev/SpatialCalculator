package io.embodit.spatialcalc.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.1.6: the round tank formula strip read「直径 π·(½· 水深」— the bracket never closed. */
class FormulaStripTest {
    @Test fun roundTankReadsLikeTheFormula() {
        assertEquals("π·(½·直径)²×水深", Templates.displayFormula(TemplateId.TANK, true, listOf("直径", "水深")))
        assertEquals("π·(½·diameter)²×depth", Templates.displayFormula(TemplateId.TANK, true, listOf("diameter", "depth")))
    }

    @Test fun roundTankBracketsBalance() {
        val f = Templates.displayFormula(TemplateId.TANK, true, Templates.slots(TemplateId.TANK, true).map { it.key })
        assertEquals(f.count { it == '(' }, f.count { it == ')' })
    }

    @Test fun boxTankAndOthersHaveNoPrefix() {
        assertEquals("", Templates.prefix(TemplateId.TANK, false))
        assertEquals("长×宽×水深", Templates.displayFormula(TemplateId.TANK, false, listOf("长", "宽", "水深")))
        for (t in listOf(TemplateId.TILE, TemplateId.PAINT, TemplateId.FIT, TemplateId.RUG, TemplateId.FREE)) assertEquals("", Templates.prefix(t, false))
    }
}
