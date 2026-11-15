package io.embodit.spatialcalc.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class UnitsRulerTest {
    private fun conv(v: String, a: LenUnit, b: LenUnit) = Fmt.number(Units.convert(BigDecimal(v), a, b))

    /** design acceptance: mm cm m in ft two-way, 20 cases. */
    @Test fun conversions() {
        val cases = listOf(
            Triple("360", LenUnit.CM to LenUnit.M, "3.6"), Triple("3.6", LenUnit.M to LenUnit.CM, "360"),
            Triple("1", LenUnit.M to LenUnit.MM, "1,000"), Triple("2500", LenUnit.MM to LenUnit.M, "2.5"),
            Triple("12", LenUnit.MM to LenUnit.CM, "1.2"), Triple("1.2", LenUnit.CM to LenUnit.MM, "12"),
            Triple("1", LenUnit.IN to LenUnit.CM, "2.54"), Triple("2.54", LenUnit.CM to LenUnit.IN, "1"),
            Triple("1", LenUnit.FT to LenUnit.IN, "12"), Triple("36", LenUnit.IN to LenUnit.FT, "3"),
            Triple("1", LenUnit.FT to LenUnit.CM, "30.48"), Triple("30.48", LenUnit.CM to LenUnit.FT, "1"),
            Triple("1", LenUnit.M to LenUnit.FT, "3.28083989501"), Triple("10", LenUnit.FT to LenUnit.M, "3.048"),
            Triple("100", LenUnit.MM to LenUnit.IN, "3.93700787402"), Triple("1", LenUnit.IN to LenUnit.MM, "25.4"),
            Triple("1", LenUnit.M to LenUnit.IN, "39.3700787402"), Triple("39.37", LenUnit.IN to LenUnit.M, "0.999998"),
            Triple("5", LenUnit.FT to LenUnit.MM, "1,524"), Triple("1524", LenUnit.MM to LenUnit.FT, "5"),
        )
        assertEquals(20, cases.size)
        for ((v, ab, want) in cases) assertEquals("$v ${ab.first} → ${ab.second}", want, conv(v, ab.first, ab.second))
        // round trip keeps the number (34 digits inside)
        assertEquals("7.25", conv(Fmt.number(Units.convert(BigDecimal("7.25"), LenUnit.FT, LenUnit.CM), 30).replace(",", ""), LenUnit.CM, LenUnit.FT))
    }

    /** design acceptance: tick step for 0.004 / 0.3 / 1.2 / 7 / 23.5 m and imperial. */
    @Test fun majorStep() {
        assertEquals(0.001, Ruler.spec(0.004, LenUnit.MM).majorStepM, 1e-9)
        assertEquals(0.05, Ruler.spec(0.3, LenUnit.CM).majorStepM, 1e-9)
        assertEquals(0.1, Ruler.spec(1.2, LenUnit.M).majorStepM, 1e-9)
        assertEquals(1.0, Ruler.spec(7.0, LenUnit.M).majorStepM, 1e-9)
        val long = Ruler.spec(23.5, LenUnit.M)
        assertEquals(1.0, long.majorStepM, 1e-9)
        assertTrue(long.clipped)
        assertEquals(10.0, long.drawnM, 1e-9)
        assertEquals(0.3048, Ruler.spec(1.8288, LenUnit.FT).majorStepM, 1e-9) // 6 ft → 1 ft steps
        assertEquals(0.0254, Ruler.spec(0.2032, LenUnit.IN).majorStepM, 1e-9) // 8 in → 1 in steps
    }

    @Test fun ticks() {
        val s = Ruler.spec(0.3, LenUnit.CM)
        assertEquals(7, s.ticks.count { it.major })                       // 0, 5, 10 … 30 cm
        assertEquals(listOf("0", "5", "10", "15", "20", "25", "30"), s.ticks.filter { it.major }.map { it.label })
        assertEquals(31, s.ticks.size)                                     // 1 cm minor ticks
        // minor ticks closer than 3 mm are not drawn
        assertTrue(Ruler.spec(0.004, LenUnit.MM).ticks.all { it.major })
        // budget: never more than 120 ticks
        for (m in listOf(0.001, 0.0123, 0.3, 0.99, 1.2, 4.4, 9.99, 23.5)) assertTrue("$m", Ruler.spec(m, LenUnit.CM).ticks.size <= Ruler.MAX_TICKS + 1)
        assertEquals(listOf("0", "0.5", "1", "1.5", "2"), Ruler.spec(2.0, LenUnit.M).ticks.filter { it.major }.map { it.label })
    }

    /** Line length = result ± 1 mm: the drawn length is the result itself up to 10 m. */
    @Test fun drawnLengthIsTheResult() {
        for (m in listOf(0.001, 0.0254, 0.3, 1.2, 3.6, 9.999)) assertEquals(m, Ruler.spec(m, LenUnit.M).drawnM, 1e-6)
        assertEquals(LineProblem.NEGATIVE, Ruler.problem(-0.2))
        assertEquals(LineProblem.TOO_SHORT, Ruler.problem(0.0004))
        assertEquals(LineProblem.TOO_SHORT, Ruler.problem(0.0))
        assertEquals(null, Ruler.problem(0.001))
    }

    /** 3D text ≥ 0.5° at the default 1.75 m (design angularCheck). */
    @Test fun textAngles() {
        assertTrue(Ruler.angleDeg(0.030, 1.75) >= 0.98)
        assertTrue(Ruler.angleDeg(0.018, 1.75) >= 0.5)
        assertTrue(Ruler.angleDeg(0.060, 1.75) >= 1.96)
    }
}
