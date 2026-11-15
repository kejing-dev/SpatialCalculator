package io.embodit.spatialcalc.domain.app

import io.embodit.spatialcalc.domain.calc.LenUnit
import org.json.JSONArray
import org.json.JSONObject

/** files/history.json: [{expr, result, unit, at}] (design dataModel.history). */
object HistoryCodec {
    fun encode(items: List<HistoryItem>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("expr", it.expr).put("result", it.result).put("unit", it.unit.name).put("at", it.at)) }
    }.toString()

    fun decode(text: String): List<HistoryItem> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val r = o.getString("result")
            runCatching { java.math.BigDecimal(r) }.getOrNull() ?: return@mapNotNull null
            HistoryItem(o.getString("expr"), r, runCatching { LenUnit.valueOf(o.getString("unit")) }.getOrDefault(LenUnit.NONE), o.optLong("at"))
        }
    }.getOrDefault(emptyList())
}
