package com.kejing.spatialcalc.domain.calc

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Length units of chips and typing (calcModel.units: m / cm / mm / in / ft). */
enum class LenUnit(val label: String, val perM: Double, val imperial: Boolean) {
    CM("cm", 100.0, false), M("m", 1.0, false), MM("mm", 1000.0, false), IN("in", 39.3700787, true), FT("ft", 3.2808399, true);

    companion object {
        fun parse(s: String): LenUnit? = when (s.trim().lowercase(Locale.US)) {
            "cm", "厘米", "公分" -> CM
            "m", "米", "公尺" -> M
            "mm", "毫米" -> MM
            "in", "inch", "inches", "\"", "英寸", "吋" -> IN
            "ft", "feet", "foot", "'", "英尺", "呎" -> FT
            else -> null
        }
    }
}

/** How a result of a given dimension is shown (S14: m³ ↔ L ↔ gal; m² ↔ ft²; m ↔ cm ↔ in ↔ ft). */
enum class ResultUnit(val dim: Int, val label: String, val perSi: Double) {
    M(1, "m", 1.0), CM(1, "cm", 100.0), MM(1, "mm", 1000.0), IN(1, "in", 39.3700787), FT(1, "ft", 3.2808399),
    M2(2, "m²", 1.0), FT2(2, "ft²", 10.7639104),
    L(3, "L", 1000.0), M3(3, "m³", 1.0), GAL(3, "gal", 264.172052),
    ONE(0, "", 1.0);

    companion object {
        fun choices(dim: Int, imperial: Boolean): List<ResultUnit> = when (dim) {
            1 -> if (imperial) listOf(IN, FT, CM, M) else listOf(M, CM, MM, IN, FT)
            2 -> if (imperial) listOf(FT2, M2) else listOf(M2, FT2)
            3 -> if (imperial) listOf(GAL, L, M3) else listOf(L, M3, GAL)
            else -> listOf(ONE)
        }

        fun default(dim: Int, imperial: Boolean) = choices(dim, imperial).first()
    }
}

object Fmt {
    /**
     * Value and tolerance in [unit]: decimals follow the tolerance (one significant digit of the tolerance, at most
     * three decimals); with no tolerance a short natural form. Returns (value, tolerance or null).
     */
    fun pair(siValue: Double, siTol: Double, perSi: Double): Pair<String, String?> {
        val v = siValue * perSi
        val t = siTol * perSi
        val dec = decimalsFor(v, t)
        val vs = if (t > 0.0) num(v, dec) else plain(v)
        val ts = if (t > 0.0) num(roundUpTo(t, dec), dec) else null
        return vs to ts
    }

    fun decimalsFor(v: Double, t: Double): Int {
        if (t > 0.0) {
            val e = floor(log10(t))
            val lead = t / 10.0.pow(e)
            // two significant digits when the tolerance starts with 1 or 2 ("± 1.2 L"), else one ("± 4 mm")
            val d = -e.toInt() + if (lead < 3.0) 1 else 0
            return d.coerceIn(0, 3)
        }
        val a = abs(v)
        return when {
            a == 0.0 -> 0
            a >= 100 -> 1
            a >= 1 -> 2
            else -> 3
        }
    }

    fun roundUpTo(x: Double, dec: Int): Double {
        val k = 10.0.pow(dec)
        return max(ceil(x * k - 1e-9) / k, 1.0 / k)
    }

    fun num(x: Double, dec: Int): String = String.format(Locale.US, "%.${dec}f", x)

    /** A chip label: "62.0 ± 0.4 cm" (± uses U+00B1). */
    fun length(m: Double, tolM: Double, unit: LenUnit): String {
        val (v, t) = pair(m, tolM, unit.perM)
        return if (t == null) "$v ${unit.label}" else "$v ± $t ${unit.label}"
    }

    /** A result: "48.0 ± 1.2 L". */
    fun result(q: Q, unit: ResultUnit): String {
        val (v, t) = pair(q.v, q.tol, unit.perSi)
        val u = if (unit.label.isEmpty()) "" else " ${unit.label}"
        return if (t == null) "$v$u" else "$v ± $t$u"
    }

    /** A typed number with its own unit ("30 cm", "2 mm", "10 m²", "2"). */
    fun plain(x: Double): String {
        val a = abs(x)
        val dec = when { a >= 100 -> 1; a >= 1 -> 2; else -> 3 }
        val s = num(x, dec)
        return if ('.' in s) s.trimEnd('0').trimEnd('.') else s
    }
}
