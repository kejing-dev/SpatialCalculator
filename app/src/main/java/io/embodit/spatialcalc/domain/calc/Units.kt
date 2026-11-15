package io.embodit.spatialcalc.domain.calc

import java.math.BigDecimal

/** The unit row (design calcModel.units): length only. [factor] = metres per unit. */
enum class LenUnit(val symbol: String, val factor: Double, private val exact: String?) {
    NONE("", 1.0, null), MM("mm", 0.001, "0.001"), CM("cm", 0.01, "0.01"), M("m", 1.0, "1"),
    IN("in", 0.0254, "0.0254"), FT("ft", 0.3048, "0.3048");

    val isLength: Boolean get() = this != NONE
    val imperial: Boolean get() = this == IN || this == FT
    val exactFactor: BigDecimal? get() = exact?.let(::BigDecimal)

    companion object {
        val ROW = listOf(NONE, MM, CM, M, IN, FT)
        fun of(s: String): LenUnit? = entries.firstOrNull { it.name.equals(s, true) || (it.symbol.isNotEmpty() && it.symbol == s) }
            ?: if (s.equals("none", true) || s == "-" || s == "—") NONE else null
    }
}

object Units {
    /** Exact decimal conversion (in and ft are defined in mm, so mm ↔ in ↔ ft stays exact up to the division). */
    fun convert(v: BigDecimal, from: LenUnit, to: LenUnit): BigDecimal {
        val f = from.exactFactor ?: return v
        val t = to.exactFactor ?: return v
        if (from == to) return v
        return v.multiply(f, Calculator.MC).divide(t, Calculator.MC)
    }

    fun meters(v: BigDecimal, unit: LenUnit): Double? = unit.exactFactor?.let { v.multiply(it, Calculator.MC).toDouble() }
}
