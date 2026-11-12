package io.embodit.spatialcalc.domain.calc

/**
 * The five fixed jobs + free formula (calcModel.templates; room / curtain / move are v2, not built).
 * Slot kinds (calcModel.chips): MEASURE waits for a measurement (can be typed); NUMBER is prefilled with a default
 * and skipped by auto-advance; MEASURE_OR_NUMBER is a length that is measured or typed / pasted.
 */
enum class TemplateId(val id: String) {
    TANK("tank"), TILE("tile"), PAINT("paint"), FIT("fit"), RUG("rug"), FREE("free"), CUSTOM("custom");

    companion object {
        fun of(id: String): TemplateId? = entries.firstOrNull { it.id == id }
    }
}

enum class SlotKind { MEASURE, NUMBER, MEASURE_OR_NUMBER }

/** What a typed number in a slot means. LENGTH slots hold metres; the others hold the number as typed. */
enum class SlotUnit(val label: String, val dim: Int, val toSi: Double) {
    LENGTH("", 1, 1.0), CM("cm", 1, 0.01), MM("mm", 1, 0.001), M2("m²", 2, 1.0), COUNT("", 0, 1.0);
}

/** [key] names the slot's copy (strings slot_<key>). */
data class SlotDef(val key: String, val kind: SlotKind, val unit: SlotUnit = SlotUnit.LENGTH, val default: Double? = null)

object Templates {
    val TANK_BOX = listOf(
        SlotDef("length", SlotKind.MEASURE), SlotDef("width", SlotKind.MEASURE), SlotDef("depth", SlotKind.MEASURE),
    )
    val TANK_ROUND = listOf(SlotDef("diameter", SlotKind.MEASURE), SlotDef("depth", SlotKind.MEASURE))
    val TILE = listOf(
        SlotDef("wall_w", SlotKind.MEASURE), SlotDef("wall_h", SlotKind.MEASURE),
        SlotDef("tile_w", SlotKind.NUMBER, SlotUnit.CM, 30.0), SlotDef("tile_h", SlotKind.NUMBER, SlotUnit.CM, 60.0),
        SlotDef("gap", SlotKind.NUMBER, SlotUnit.MM, 2.0),
    )
    val PAINT = listOf(
        SlotDef("wall_w", SlotKind.MEASURE), SlotDef("wall_h", SlotKind.MEASURE),
        SlotDef("cover", SlotKind.NUMBER, SlotUnit.M2, 10.0), SlotDef("coats", SlotKind.NUMBER, SlotUnit.COUNT, 2.0),
    )
    val FIT = listOf(
        SlotDef("cell_w", SlotKind.MEASURE), SlotDef("cell_d", SlotKind.MEASURE), SlotDef("cell_h", SlotKind.MEASURE),
        SlotDef("box_l", SlotKind.MEASURE_OR_NUMBER), SlotDef("box_w", SlotKind.MEASURE_OR_NUMBER), SlotDef("box_h", SlotKind.MEASURE_OR_NUMBER),
    )
    val RUG = listOf(SlotDef("length", SlotKind.MEASURE), SlotDef("width", SlotKind.MEASURE))

    fun slots(t: TemplateId, round: Boolean = false): List<SlotDef> = when (t) {
        TemplateId.TANK -> if (round) TANK_ROUND else TANK_BOX
        TemplateId.TILE -> TILE
        TemplateId.PAINT -> PAINT
        TemplateId.FIT -> FIT
        TemplateId.RUG -> RUG
        TemplateId.FREE, TemplateId.CUSTOM -> emptyList()
    }

    /** Operator glyph shown between slot chips in a template formula (display only; the maths is [Evaluate]). */
    fun joiner(t: TemplateId, round: Boolean, index: Int): String = when (t) {
        TemplateId.TANK -> if (round) (if (index == 0) "π·(½·" else ")²×") else "×"
        TemplateId.TILE -> when (index) { 0 -> "÷"; 1 -> "|"; 2 -> "×"; else -> "+" }
        TemplateId.PAINT -> when (index) { 0 -> "×"; 1 -> "÷"; else -> "×" }
        TemplateId.FIT -> if (index == 2) "|" else "×"
        else -> "×"
    }

    /** Standard rug sizes (cm) the rug result is compared with. */
    val RUG_SPECS = listOf(160 to 230, 200 to 300, 240 to 340)
}

/** One filled (or empty) slot of the current card. */
data class SlotValue(
    val def: SlotDef,
    val q: Q? = def.default?.let { Q(it * def.unit.toSi, 0.0, def.unit.dim) },
    val source: Source = if (def.default != null) Source.DEFAULT else Source.NONE,
    val tapeId: Int? = null,
    /** The slot's quantity only for display of typed numbers (the number as typed in the slot's own unit). */
    val typed: Double? = def.default,
)

enum class Source { NONE, TAPE, FITBOX, TYPED, DEFAULT }
