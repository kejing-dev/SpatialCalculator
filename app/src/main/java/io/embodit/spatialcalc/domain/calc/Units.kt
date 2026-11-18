package io.embodit.spatialcalc.domain.calc

import java.math.BigDecimal

/** What a unit measures. NONE = a plain number. */
enum class Dim { NONE, LENGTH, VOLUME }

/**
 * The unit rows (design calcModel.units): lengths (row 1) and volumes (row 2, v0.3.0). [factor] = metres per unit for
 * a length, cubic metres per unit for a volume. The name stays LenUnit (history.json stores these names).
 */
enum class LenUnit(val symbol: String, val factor: Double, private val exact: String?, val dim: Dim) {
    NONE("", 1.0, null, Dim.NONE),
    MM("mm", 0.001, "0.001", Dim.LENGTH), CM("cm", 0.01, "0.01", Dim.LENGTH), M("m", 1.0, "1", Dim.LENGTH),
    IN("in", 0.0254, "0.0254", Dim.LENGTH), FT("ft", 0.3048, "0.3048", Dim.LENGTH),
    ML("mL", 1e-6, "0.000001", Dim.VOLUME), L("L", 0.001, "0.001", Dim.VOLUME), CM3("cm³", 1e-6, "0.000001", Dim.VOLUME),
    M3("m³", 1.0, "1", Dim.VOLUME), FT3("ft³", 0.028316846592, "0.028316846592", Dim.VOLUME),
    GAL("gal", 0.003785411784, "0.003785411784", Dim.VOLUME);

    val isLength: Boolean get() = dim == Dim.LENGTH
    val isVolume: Boolean get() = dim == Dim.VOLUME
    val hasUnit: Boolean get() = dim != Dim.NONE
    val imperial: Boolean get() = this == IN || this == FT || this == FT3 || this == GAL
    val exactFactor: BigDecimal? get() = exact?.let(::BigDecimal)

    companion object {
        val ROW = listOf(NONE, MM, CM, M, IN, FT)
        val VOL_ROW = listOf(ML, L, CM3, M3, FT3, GAL)
        fun of(s: String): LenUnit? = entries.firstOrNull { it.name.equals(s, true) || (it.symbol.isNotEmpty() && it.symbol == s) }
            ?: when (s.lowercase()) { "none", "-", "—" -> NONE; "cm3" -> CM3; "m3" -> M3; "ft3" -> FT3; "ml" -> ML; "l" -> L; else -> null }
    }
}

object Units {
    /** Exact decimal conversion within one dimension (in, ft, ft³ and gal are defined exactly in metric). */
    fun convert(v: BigDecimal, from: LenUnit, to: LenUnit): BigDecimal {
        if (from == to || from.dim != to.dim) return v
        val f = from.exactFactor ?: return v
        val t = to.exactFactor ?: return v
        return v.multiply(f, Calculator.MC).divide(t, Calculator.MC)
    }

    /** The handiest metric volume unit for [m3]: < 1 L → mL, < 1 m³ → L, else m³ (window and box label alike). */
    fun autoVolume(m3: Double): LenUnit = when { m3 < 0.001 -> LenUnit.ML; m3 < 1.0 -> LenUnit.L; else -> LenUnit.M3 }

    fun meters(v: BigDecimal, unit: LenUnit): Double? = unit.takeIf { it.isLength }?.exactFactor?.let { v.multiply(it, Calculator.MC).toDouble() }

    fun cubicMeters(v: BigDecimal, unit: LenUnit): Double? = unit.takeIf { it.isVolume }?.exactFactor?.let { v.multiply(it, Calculator.MC).toDouble() }
}
