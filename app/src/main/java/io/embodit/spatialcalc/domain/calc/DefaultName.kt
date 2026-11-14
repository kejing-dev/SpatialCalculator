package io.embodit.spatialcalc.domain.calc

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v0.1.5 (QA5 q5-1): the default name of a saved template = short job name + " MM-dd HH:mm".
 * English used the full job question ("How many liters in this tank 11-12 20:42", 40 chars) and the date was cut off
 * in the job page tile. The base is now the short job name (en: Tank / Round tank / Tiles / Paint / Boxes / Rug /
 * Formula; zh keeps the job names as they were), a stamp already on the base (a template made from a template) is
 * dropped, and the base is capped so the whole name is at most [MAX] chars (the name field limit) with the date kept.
 */
object DefaultName {
    const val MAX = 24
    /** " MM-dd HH:mm" */
    const val STAMP_LEN = 12
    const val BASE_MAX = MAX - STAMP_LEN
    private val STAMP_AT_END = Regex("""\s*\d{2}-\d{2} \d{2}:\d{2}$""")

    fun stamp(ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).apply { timeZone = tz }.format(Date(ms))

    fun base(shortName: String): String = shortName.replace(STAMP_AT_END, "").trim().take(BASE_MAX).trim()

    fun of(shortName: String, ms: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val b = base(shortName)
        return if (b.isEmpty()) stamp(ms, tz) else "$b ${stamp(ms, tz)}"
    }

    /** Splits a name into (base, stamp) when it ends with a date stamp, so the job page can put the date on its own line. */
    fun split(name: String): Pair<String, String?> {
        val m = STAMP_AT_END.find(name) ?: return name to null
        return name.substring(0, m.range.first).trim() to m.value.trim()
    }
}
