package com.kejing.spatialcalc.domain.calc

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Splits a product-page size string into lengths (S07 paste: 「60×30×35 cm」 → three slots). Accepts × x X * by ,
 * ， 、 / and spaces between numbers, one unit for all at the end or a unit after each number, Chinese labels
 * (长 / 宽 / 高 / 深) and Chinese units. Returns metres, or null when it cannot read the string.
 */
object Paste {
    private val UNIT = "(cm|mm|m|in|inch|inches|ft|feet|foot|厘米|毫米|米|公分|英寸|英尺|吋|呎|\"|')"
    private val NUMBER = Regex("""(\d+(?:\.\d+)?)\s*$UNIT?""", RegexOption.IGNORE_CASE)

    fun parse(raw: String, defaultUnit: LenUnit): List<Double>? {
        val s = raw.trim()
            .replace('，', ',').replace('、', ',').replace('：', ':')
            .replace(Regex("[长寬宽高深厚LWHDlwhd]\\s*[:：]?\\s*(?=\\d)"), " ")
        if (s.isEmpty()) return null
        // anything but numbers, units and separators means we did not understand it
        val leftover = NUMBER.replace(s, " ").replace(Regex("""[×xX*,/\s]|by"""), "")
        if (leftover.isNotEmpty()) return null
        val found = NUMBER.findAll(s).toList()
        if (found.isEmpty() || found.size > 6) return null
        val units = found.map { it.groupValues[2].takeIf { u -> u.isNotEmpty() }?.let(LenUnit::parse) }
        val trailing = units.lastOrNull { it != null }
        return found.mapIndexed { idx, m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            if (n <= 0.0) return null
            val u = units[idx] ?: trailing ?: defaultUnit
            n / u.perM
        }
    }
}

/**
 * Reading tolerance per aiming channel (design input.channels; constants are design values, calibrated on a device
 * later): gaze / pinch / emulator mouse ±(8 mm + 0.5 %·range) per end; controller ray ±(3 mm + 0.15 %·range); contact
 * ±2.5 mm. A tape combines its two ends as a root sum of squares and is rounded up the ladder (空间尺 ComputeTolerance).
 */
enum class Channel(val label: String) {
    GAZE("gaze"), RAY("ray"), CONTACT("contact"), DEBUG("gaze");

    fun endSigmaMm(rangeM: Float): Double = when (this) {
        GAZE, DEBUG -> 8.0 + 5.0 * rangeM
        RAY -> 3.0 + 1.5 * rangeM
        CONTACT -> 2.5
    }
}

object Tolerance {
    val LADDER_MM = intArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 80, 100, 150, 200)

    fun tapeMm(channel: Channel, rangeA: Float, rangeB: Float): Int {
        val a = channel.endSigmaMm(rangeA)
        val b = channel.endSigmaMm(rangeB)
        return roundUp(sqrt(a * a + b * b))
    }

    fun roundUp(mm: Double): Int {
        for (step in LADDER_MM) if (mm <= step + 1e-6) return step
        return ceil(mm).toInt()
    }
}
