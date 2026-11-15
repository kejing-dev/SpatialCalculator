package io.embodit.spatialcalc.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** design acceptance.unitTests: precedence / chained = / % in three contexts / ± / ⌫ / 0.1+0.2 / scientific / ÷0, ≥ 30 cases. */
class CalculatorTest {
    private fun run(keys: String, from: CalcState = CalcState()): CalcState = keys.fold(from) { s, c -> Calculator.press(s, Key.of(c)!!) }
    private fun result(keys: String): String = run(keys).result!!.let { Fmt.number(it) }
    private fun shown(keys: String): String = run(keys).let { s -> s.result?.let { Fmt.number(it) } ?: "err:${s.error}" }

    @Test fun precedence() {
        assertEquals("40", result("12*3+4="))
        assertEquals("14", result("2+3*4="))
        assertEquals("20", result("2*3+4*3.5="))
        assertEquals("5", result("10-10/2="))
        assertEquals("1", result("8/4/2="))
        assertEquals("−6", result("2-2*4="))
        assertEquals("7", result("1+2*3="))
    }

    @Test fun decimalsAreExact() {
        assertEquals("0.3", result("0.1+0.2="))
        assertEquals("0.1", result("0.3-0.2="))
        assertEquals("1.21", result("1.1*1.1="))
        assertEquals("0.333333333333", result("1/3="))
        assertEquals("0.666666666667", result("2/3="))
        assertEquals("1", result("1/3*3="))
    }

    @Test fun repeatedEquals() {
        assertEquals("108", result("12*3=="))
        assertEquals("15", result("5+5=="))
        assertEquals("2.5", result("10/2=="))
    }

    @Test fun afterEquals() {
        assertEquals("9", result("4+4=1+8="))      // a digit after = starts a new one
        assertEquals("16", result("4+4=*2="))      // an operator after = goes on from the result
        assertEquals("0.5", result("4+4=.5="))
    }

    @Test fun percent() {
        assertEquals("220", result("200+10%="))
        assertEquals("180", result("200-10%="))
        assertEquals("0.5", result("50%="))
        assertEquals("2", result("8*25%="))
        assertEquals("4", result("200/50%/100="))
        assertEquals("0.4", result("40=%="))
    }

    @Test fun sign() {
        assertEquals("−5", result("5~="))
        assertEquals("−3", result("5~+2="))
        assertEquals("−40", result("12*3+4=~"))
        assertEquals("15", result("5*~3~="))
        assertEquals("−7", result("~7="))
    }

    @Test fun backspaceAndClear() {
        assertEquals("12", result("123<="))
        assertEquals("12", result("12+<="))
        assertEquals("10", result("10%<="))
        assertEquals("40", result("12*3+4=<"))   // ⌫ does nothing on a result
        assertEquals(CalcState(), run("12+3c"))
        assertEquals("0", Fmt.tokens(run("0000").tokens))
    }

    @Test fun operatorReplacesOperator() {
        assertEquals("7", result("5+*-+2="))
        assertEquals("5", result("5+="))
    }

    @Test fun errors() {
        assertEquals("err:DIV_ZERO", shown("5/0="))
        assertEquals("err:DIV_ZERO", shown("5/(0)=".filter { it != '(' && it != ')' }))
        assertEquals("err:TOO_BIG", shown("999999999999999*999999999999999*999999999999999*999999999999999*999999999999999*999999999999999*999999999999999="))
        // the next key clears the error
        val s = run("5/0=7")
        assertNull(s.error)
        assertEquals("7", Fmt.tokens(s.tokens))
    }

    @Test fun largeNumbersGoScientific() {
        assertEquals("1e15", Fmt.number(run("1000000*1000000000=").result!!))
        assertEquals("1.234567e15", Fmt.number(run("1234567*1000000000=").result!!))
        assertEquals("999,999,999,999", Fmt.number(run("999999999999=").result!!))
        assertEquals("1e-7", Fmt.number(run("1/10000000=").result!!))
        assertEquals("1,234,567.5", Fmt.number(run("1234567.5=").result!!))
    }

    @Test fun typingLimitsAndDots() {
        assertEquals("123,456,789,012,345", Fmt.tokens(run("1234567890123456789").tokens))
        assertEquals("1.5", Fmt.tokens(run("1..5").tokens))
        assertEquals("0.5", Fmt.tokens(run(".5").tokens))
        assertEquals("12 × 3 + 4", Fmt.tokens(run("12*3+4").tokens))
    }

    @Test fun previewWhileTyping() {
        assertEquals("40", Fmt.number(Calculator.preview(run("12*3+4"))!!))
        assertEquals("36", Fmt.number(Calculator.preview(run("12*3+"))!!))
        assertNull(Calculator.preview(run("5/0")))
    }
}
