package com.kejing.spatialcalc.domain.calc

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A number with a dimension in powers of length (calcModel.units: m⁰ count / plain, m¹, m², m³) and an absolute
 * tolerance in the same unit. Values are SI (metres^dim). Tolerance propagation is linear (calcModel.tolerance):
 * + − add absolute tolerances, × ÷ add relative ones, √ halves the relative one, x² doubles it.
 */
data class Q(val v: Double, val tol: Double = 0.0, val dim: Int = 0) {
    val rel: Double get() = if (v == 0.0) 0.0 else abs(tol / v)

    operator fun plus(o: Q): Q {
        if (dim != o.dim) throw CalcException(CalcError.UNIT_MISMATCH, "${dimName(dim)} + ${dimName(o.dim)}")
        return Q(v + o.v, tol + o.tol, dim)
    }

    operator fun minus(o: Q): Q {
        if (dim != o.dim) throw CalcException(CalcError.UNIT_MISMATCH, "${dimName(dim)} − ${dimName(o.dim)}")
        return Q(v - o.v, tol + o.tol, dim)
    }

    operator fun times(o: Q): Q {
        val r = v * o.v
        return Q(r, abs(r) * (rel + o.rel), checkDim(dim + o.dim))
    }

    operator fun div(o: Q): Q {
        if (o.v == 0.0) throw CalcException(CalcError.DIV_ZERO)
        val r = v / o.v
        return Q(r, abs(r) * (rel + o.rel), checkDim(dim - o.dim))
    }

    operator fun unaryMinus() = Q(-v, tol, dim)

    fun sqrtQ(): Q {
        if (dim % 2 != 0) throw CalcException(CalcError.BAD_DIM)
        if (v < 0) throw CalcException(CalcError.NEGATIVE_ROOT)
        val r = sqrt(v)
        return Q(r, r * rel / 2.0, dim / 2)
    }

    fun squared(): Q {
        val r = v * v
        return Q(r, r * rel * 2.0, checkDim(dim * 2))
    }

    fun times(k: Double) = Q(v * k, abs(tol * k), dim)

    companion object {
        fun length(m: Double, tolM: Double = 0.0) = Q(m, tolM, 1)
        fun count(n: Double) = Q(n, 0.0, 0)
        val PI = Q(Math.PI, 0.0, 0)

        fun checkDim(d: Int): Int {
            if (d < 0 || d > 3) throw CalcException(CalcError.BAD_DIM)
            return d
        }

        fun dimName(d: Int) = when (d) { 1 -> "m"; 2 -> "m²"; 3 -> "m³"; else -> "1" }
    }
}

/** The inline errors of S25 (and S03's red result line). Each maps to one line of copy. */
enum class CalcError { UNIT_MISMATCH, BAD_DIM, DIV_ZERO, NEGATIVE_ROOT, SYNTAX, PASTE, CARD_CAP, CHIP_CAP, PINS_CLOSE, BOX_THIN, EMPTY }

class CalcException(val error: CalcError, val detail: String = "") : RuntimeException("$error $detail")
