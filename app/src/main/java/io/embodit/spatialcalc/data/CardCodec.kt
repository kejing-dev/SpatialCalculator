package io.embodit.spatialcalc.data

import io.embodit.spatialcalc.domain.calc.Q
import io.embodit.spatialcalc.domain.calc.ResultUnit
import io.embodit.spatialcalc.domain.calc.SlotDef
import io.embodit.spatialcalc.domain.calc.SlotKind
import io.embodit.spatialcalc.domain.calc.SlotUnit
import io.embodit.spatialcalc.domain.calc.SlotValue
import io.embodit.spatialcalc.domain.calc.Source
import io.embodit.spatialcalc.domain.calc.TemplateId
import io.embodit.spatialcalc.domain.calc.Token
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.domain.place.BodyPose
import io.embodit.spatialcalc.domain.session.Card
import io.embodit.spatialcalc.domain.session.CustomTemplate
import org.json.JSONArray
import org.json.JSONObject

/** files/cards.json (dataModel.store): every card and the user templates, one JSON document. */
object CardCodec {
    const val VERSION = 1

    fun encode(cards: List<Card>, customs: List<CustomTemplate>): String = JSONObject().apply {
        put("v", VERSION)
        put("cards", JSONArray().apply { cards.forEach { put(card(it)) } })
        put("customs", JSONArray().apply { customs.forEach { put(custom(it)) } })
    }.toString()

    fun decode(text: String): Pair<List<Card>, List<CustomTemplate>> {
        val o = JSONObject(text)
        val cards = o.optJSONArray("cards")?.let { a -> (0 until a.length()).mapNotNull { runCatching { card(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList()
        val customs = o.optJSONArray("customs")?.let { a -> (0 until a.length()).mapNotNull { runCatching { custom(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList()
        return cards to customs
    }

    private fun vec(v: Vec3) = JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.z.toDouble())
    private fun vec(a: JSONArray) = Vec3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())

    private fun q(q: Q) = JSONArray().put(q.v).put(q.tol).put(q.dim)
    private fun q(a: JSONArray) = Q(a.getDouble(0), a.getDouble(1), a.getInt(2))

    private fun def(d: SlotDef) = JSONObject().put("key", d.key).put("kind", d.kind.name).put("unit", d.unit.name).apply { d.default?.let { put("def", it) } }
    private fun def(o: JSONObject) = SlotDef(o.getString("key"), SlotKind.valueOf(o.getString("kind")), SlotUnit.valueOf(o.getString("unit")), if (o.has("def")) o.getDouble("def") else null)

    private fun slot(v: SlotValue) = JSONObject().put("def", def(v.def)).put("src", v.source.name).apply {
        v.q?.let { put("q", q(it)) }
        v.typed?.let { put("typed", it) }
    }
    private fun slot(o: JSONObject) = SlotValue(def(o.getJSONObject("def")), o.optJSONArray("q")?.let { q(it) }, Source.valueOf(o.getString("src")), null, if (o.has("typed")) o.getDouble("typed") else null)

    private fun token(t: Token) = when (t) {
        is Token.Num -> JSONObject().put("n", q(t.q)).put("src", t.source.name)
        is Token.Op -> JSONObject().put("op", t.sym)
    }
    private fun token(o: JSONObject): Token = if (o.has("op")) Token.Op(o.getString("op")) else Token.Num(q(o.getJSONArray("n")), Source.valueOf(o.getString("src")))

    private fun card(c: Card) = JSONObject().apply {
        put("id", c.id); put("t", c.template.id); put("round", c.round); put("at", c.createdAt); put("pinned", c.pinned)
        c.customName?.let { put("name", it) }
        c.anchorId?.let { put("anchor", it) }
        c.anchorOffset?.let { put("anchorOff", vec(it)) }
        c.pose?.let { put("pos", vec(it.position)); put("yaw", it.yaw.toDouble()) }
        c.size?.let { put("size", vec(it)) }
        c.resultUnit?.let { put("unit", it.name) }
        put("slots", JSONArray().apply { c.slots.forEach { put(slot(it)) } })
        put("tokens", JSONArray().apply { c.tokens.forEach { put(token(it)) } })
    }

    private fun card(o: JSONObject) = Card(
        id = o.getLong("id"),
        template = TemplateId.of(o.getString("t")) ?: TemplateId.FREE,
        round = o.optBoolean("round"),
        customName = if (o.has("name")) o.getString("name") else null,
        slots = o.getJSONArray("slots").let { a -> (0 until a.length()).map { slot(a.getJSONObject(it)) } },
        tokens = o.getJSONArray("tokens").let { a -> (0 until a.length()).map { token(a.getJSONObject(it)) } },
        pose = o.optJSONArray("pos")?.let { BodyPose(vec(it), o.optDouble("yaw", 0.0).toFloat()) },
        size = o.optJSONArray("size")?.let { vec(it) },
        pinned = o.optBoolean("pinned"),
        anchorId = if (o.has("anchor")) o.getString("anchor") else null,
        anchorOffset = o.optJSONArray("anchorOff")?.let { vec(it) },
        createdAt = o.getLong("at"),
        resultUnit = if (o.has("unit")) runCatching { ResultUnit.valueOf(o.getString("unit")) }.getOrNull() else null,
    )

    private fun custom(c: CustomTemplate) = JSONObject().apply {
        put("id", c.id); put("name", c.name); put("base", c.base.id); put("round", c.round)
        put("slots", JSONArray().apply { c.slots.forEach { put(def(it)) } })
        put("pattern", JSONArray().apply { c.pattern.forEach { put(token(it)) } })
    }

    private fun custom(o: JSONObject) = CustomTemplate(
        o.getLong("id"), o.getString("name"), TemplateId.of(o.getString("base")) ?: TemplateId.FREE, o.optBoolean("round"),
        o.getJSONArray("slots").let { a -> (0 until a.length()).map { def(a.getJSONObject(it)) } },
        o.getJSONArray("pattern").let { a -> (0 until a.length()).map { token(a.getJSONObject(it)) } },
    )
}
