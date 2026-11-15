package io.embodit.spatialcalc.ui

import io.embodit.spatialcalc.ui.window.fitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** The result line never overflows: 52 sp ≤ 10 chars, 40 sp ≤ 13 chars, then fewer digits / scientific. */
class FitResultTest {
    @Test fun fits() {
        assertEquals("40" to 52f, fitResult(BigDecimal(40), ""))
        assertEquals("360 cm" to 52f, fitResult(BigDecimal(360), " cm"))
        assertEquals("1,234,567.5" to 40f, fitResult(BigDecimal("1234567.5"), ""))
        for (v in listOf("-99999999999.9", "0.333333333333333", "123456789.123456", "1e15", "-1.2345678e-9", "98765432109.87")) {
            val (s, size) = fitResult(BigDecimal(v), " ft")
            assertTrue("$v → $s", s.length <= 13 && size in listOf(40f, 52f))
        }
    }
}
