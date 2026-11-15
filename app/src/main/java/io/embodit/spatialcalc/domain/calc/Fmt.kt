package io.embodit.spatialcalc.domain.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Display formatting (design calcModel.display): ≤ 12 significant digits, thousands separators, scientific beyond. */
object Fmt {
    const val MAX_SIG = 12

    fun number(v: BigDecimal, maxSig: Int = MAX_SIG): String {
        if (v.signum() == 0) return "0"
        val abs = v.abs()
        if (abs >= BigDecimal("1e12") || abs < BigDecimal("1e-6")) return scientific(v, minOf(maxSig, 7))
        val r = v.round(MathContext(maxSig, RoundingMode.HALF_UP)).stripTrailingZeros()
        val plain = if (r.signum() == 0) "0" else r.toPlainString()
        return grouped(plain)
    }

    /** 1.2e15, -3.4567e-8 */
    fun scientific(v: BigDecimal, sig: Int): String {
        val r = v.round(MathContext(sig, RoundingMode.HALF_UP))
        val exp = r.precision() - r.scale() - 1
        val mant = r.movePointLeft(exp).stripTrailingZeros()
        val m = if (mant.signum() == 0) "0" else mant.toPlainString()
        return (if (m.startsWith("-")) "−" + m.substring(1) else m) + "e" + exp
    }

    /** "-1234567.5" → "−1,234,567.5"; keeps what the user typed after the point ("12." stays "12."). */
    fun grouped(plain: String): String {
        val neg = plain.startsWith("-")
        val body = plain.removePrefix("-")
        val dot = body.indexOf('.')
        val int = if (dot >= 0) body.substring(0, dot) else body
        val frac = if (dot >= 0) body.substring(dot) else ""
        val sb = StringBuilder()
        for ((i, c) in int.withIndex()) { if (i > 0 && (int.length - i) % 3 == 0) sb.append(','); sb.append(c) }
        return (if (neg) "−" else "") + sb + frac
    }

    /** A number as typed: grouped, "-" alone shows as "−", % appended. */
    fun typed(n: Tok.Num): String = (if (n.text == "-") "−" else grouped(n.text)) + if (n.pct) "%" else ""

    fun tokens(t: List<Tok>): String = t.joinToString(" ") { when (it) { is Tok.Num -> typed(it); is Tok.Opr -> it.op.sym } }

    /** A length for the plate / tick labels: up to 4 significant decimals, no trailing zeros. */
    fun length(meters: Double, unit: LenUnit): String = number(BigDecimal(meters / unit.factor).round(MathContext(6)), 6) + " " + unit.symbol
}
