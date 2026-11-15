package io.embodit.spatialcalc.domain.calc

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** One tick of the length line, [atM] metres from the left end. [label] only on major ticks. */
data class Tick(val atM: Double, val major: Boolean, val label: String?)

/** What the line draws for a length (design spatialLayout.lengthLine). */
data class LineSpec(val lengthM: Double, val drawnM: Double, val clipped: Boolean, val majorStepM: Double, val ticks: List<Tick>)

enum class LineProblem { NEGATIVE, TOO_SHORT }

object Ruler {
    const val MIN_M = 0.001
    const val MAX_M = 10.0
    const val MAX_TICKS = 120
    const val MIN_MINOR_GAP_M = 0.003

    val METRIC_STEPS = doubleArrayOf(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0)
    private const val INCH = 0.0254
    val IMPERIAL_STEPS = doubleArrayOf(INCH / 8, INCH, 6 * INCH, 12 * INCH, 60 * INCH)

    fun problem(lengthM: Double): LineProblem? = when {
        lengthM < 0 -> LineProblem.NEGATIVE
        lengthM < MIN_M - 1e-12 -> LineProblem.TOO_SHORT
        else -> null
    }

    private fun count(drawn: Double, step: Double) = floor(drawn / step + 1e-6).toInt()

    /**
     * Major step: the smallest step whose major-tick count lands in 4–10; when none does, the step whose count is
     * nearest to 4–10 (ties → the smaller step). 0.004 m → 1 mm, 0.3 m → 5 cm, 1.2 m → 10 cm, 7 m → 1 m.
     */
    fun majorStep(drawnM: Double, imperial: Boolean): Double {
        val steps = if (imperial) IMPERIAL_STEPS else METRIC_STEPS
        steps.firstOrNull { count(drawnM, it) in 4..10 }?.let { return it }
        fun dist(n: Int) = if (n < 4) 4 - n else if (n > 10) n - 10 else 0
        return steps.minWith(compareBy<Double>({ dist(count(drawnM, it)) }, { it }))
    }

    fun spec(lengthM: Double, unit: LenUnit): LineSpec {
        val drawn = minOf(lengthM, MAX_M)
        val major = majorStep(drawn, unit.imperial)
        val minor = major / (if (unit.imperial) 4 else 5)
        val ticks = ArrayList<Tick>()
        val nMajor = count(drawn, major)
        val withMinor = minor >= MIN_MINOR_GAP_M - 1e-9 && nMajor * (if (unit.imperial) 4 else 5) <= MAX_TICKS
        val step = if (withMinor) minor else major
        val per = if (withMinor) (if (unit.imperial) 4 else 5) else 1
        val n = count(drawn, step)
        for (i in 0..n) {
            val at = i * step
            val isMajor = i % per == 0
            ticks += Tick(at, isMajor, if (isMajor) label(at, unit) else null)
        }
        return LineSpec(lengthM, drawn, lengthM > MAX_M + 1e-9, major, ticks)
    }

    /** Tick numbers in the chosen unit, no unit symbol ("0 10 20" for cm). */
    fun label(atM: Double, unit: LenUnit): String {
        val u = if (unit == LenUnit.NONE) LenUnit.M else unit
        val v = atM / u.factor
        val r = Math.round(v * 1000.0) / 1000.0
        return if (abs(r - Math.rint(r)) < 1e-9) Math.rint(r).toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
    }

    /** Visual angle (degrees) of something [sizeM] tall seen from [distM]. */
    fun angleDeg(sizeM: Double, distM: Double): Double = Math.toDegrees(2 * kotlin.math.atan(sizeM / (2 * max(distM, 1e-6))))
}
