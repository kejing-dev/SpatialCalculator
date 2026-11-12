package com.kejing.spatialcalc.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kejing.spatialcalc.R
import com.kejing.spatialcalc.domain.calc.CalcResult
import com.kejing.spatialcalc.domain.calc.Fmt
import com.kejing.spatialcalc.domain.calc.LenUnit
import com.kejing.spatialcalc.domain.calc.ResultUnit
import com.kejing.spatialcalc.domain.calc.SlotUnit
import com.kejing.spatialcalc.domain.calc.SlotValue
import com.kejing.spatialcalc.domain.calc.TemplateId
import com.kejing.spatialcalc.domain.calc.Token
import com.kejing.spatialcalc.domain.session.CalcState
import com.kejing.spatialcalc.domain.session.Hint
import com.kejing.spatialcalc.domain.surface.SurfaceKind
import java.util.Locale
import kotlin.math.max

@Composable
fun templateName(t: TemplateId?, custom: String? = null): String = custom ?: stringResource(
    when (t) {
        TemplateId.TANK -> R.string.tpl_tank; TemplateId.TILE -> R.string.tpl_tile; TemplateId.PAINT -> R.string.tpl_paint
        TemplateId.FIT -> R.string.tpl_fit; TemplateId.RUG -> R.string.tpl_rug; TemplateId.CUSTOM -> R.string.tpl_custom
        else -> R.string.tpl_free
    },
)

fun templateIcon(t: TemplateId?): Int = when (t) {
    TemplateId.TANK -> R.drawable.tpl_tank; TemplateId.TILE -> R.drawable.tpl_tile; TemplateId.PAINT -> R.drawable.tpl_paint
    TemplateId.FIT -> R.drawable.tpl_fit; TemplateId.RUG -> R.drawable.tpl_rug; TemplateId.CUSTOM -> R.drawable.tpl_custom
    else -> R.drawable.tpl_free
}

@Composable
fun templateTip(t: TemplateId?): String = stringResource(
    when (t) {
        TemplateId.TANK -> R.string.tip_tank; TemplateId.TILE -> R.string.tip_tile; TemplateId.PAINT -> R.string.tip_paint
        TemplateId.FIT -> R.string.tip_fit; TemplateId.RUG -> R.string.tip_rug; else -> R.string.tip_free
    },
)

@Composable
fun slotName(key: String): String = stringResource(
    when (key) {
        "length" -> R.string.slot_length; "width" -> R.string.slot_width; "depth" -> R.string.slot_depth; "diameter" -> R.string.slot_diameter
        "wall_w" -> R.string.slot_wall_w; "wall_h" -> R.string.slot_wall_h; "tile_w" -> R.string.slot_tile_w; "tile_h" -> R.string.slot_tile_h
        "gap" -> R.string.slot_gap; "cover" -> R.string.slot_cover; "coats" -> R.string.slot_coats
        "cell_w" -> R.string.slot_cell_w; "cell_d" -> R.string.slot_cell_d; "cell_h" -> R.string.slot_cell_h
        "box_l" -> R.string.slot_box_l; "box_w" -> R.string.slot_box_w; else -> R.string.slot_box_h
    },
)

@Composable
fun surfaceName(k: SurfaceKind): String = stringResource(
    when (k) {
        SurfaceKind.FLOOR -> R.string.surf_floor; SurfaceKind.TABLE -> R.string.surf_table; SurfaceKind.WALL -> R.string.surf_wall
        SurfaceKind.CEILING -> R.string.surf_ceiling; SurfaceKind.CABINET -> R.string.surf_cabinet; SurfaceKind.VIRTUAL -> R.string.surf_virtual
        SurfaceKind.OTHER -> R.string.surf_other
    },
)

/** A slot chip's label: its value (with ± for measured ones) in the chip unit, or the slot name while empty. */
@Composable
fun slotLabel(v: SlotValue, unit: LenUnit): String {
    val q = v.q ?: return slotName(v.def.key)
    return when (v.def.unit) {
        SlotUnit.LENGTH -> Fmt.length(q.v, q.tol, unit)
        SlotUnit.CM -> "${Fmt.plain(q.v * 100)} cm"
        SlotUnit.MM -> "${Fmt.plain(q.v * 1000)} mm"
        SlotUnit.M2 -> "${Fmt.plain(q.v)} m²"
        SlotUnit.COUNT -> "× ${Fmt.plain(q.v)}"
    }
}

fun tokenLabel(t: Token, unit: LenUnit): String = when (t) {
    is Token.Op -> t.sym
    is Token.Num -> if (t.q.dim == 1) Fmt.length(t.q.v, t.q.tol, unit) else Fmt.result(t.q, ResultUnit.ONE)
}

/**
 * The one text of the result (D38: the result line, the result plate and the records page all call this with the same
 * CalcResult). Counting jobs read as a range「42 块（41–43）」, the others as value ± tolerance in [unit].
 */
@Composable
fun resultText(r: CalcResult, template: TemplateId?, unit: ResultUnit?, imperial: Boolean): String? {
    val count = r.count
    if (count != null) {
        val ranged = count.lo != count.hi
        return when (template) {
            TemplateId.TILE -> if (ranged) stringResource(R.string.count_tiles, count.n, count.lo, count.hi, r.extra ?: 0) else stringResource(R.string.count_tiles_exact, count.n, r.extra ?: 0)
            TemplateId.PAINT -> if (ranged) stringResource(R.string.count_cans, count.n, count.lo, count.hi) else stringResource(R.string.count_cans_exact, count.n)
            TemplateId.FIT -> {
                val fit = r.fit
                if (fit != null && fit.count == 0) {
                    val d = Fmt.plain(max(fit.overM, 0.001) * 100)
                    stringResource(when (fit.overAxis) { 'w' -> R.string.doesnt_fit_w; 'd' -> R.string.doesnt_fit_d; else -> R.string.doesnt_fit_h }, d)
                } else {
                    val gap = Fmt.plain((fit?.gapM ?: 0.0) * 100)
                    if (ranged) stringResource(R.string.count_boxes, count.n, count.lo, count.hi, gap) else stringResource(R.string.count_boxes_exact, count.n, gap)
                }
            }
            else -> count.n.toString()
        }
    }
    val q = r.q ?: return null
    val u = unit?.takeIf { it.dim == q.dim } ?: ResultUnit.default(q.dim, imperial)
    val main = Fmt.result(q, u)
    val spec = r.rugSpec
    return if (template == TemplateId.RUG && spec != null) stringResource(R.string.rug_result, main, spec.first, spec.second) else main
}

@Composable
fun hintText(s: CalcState): String? = when (s.hint) {
    Hint.NONE -> null
    Hint.NO_PLANES -> stringResource(R.string.no_planes)
    Hint.PLANES_BACK -> stringResource(R.string.planes_back)
    Hint.LOST -> stringResource(R.string.hint_lost)
    Hint.NO_PERMISSION -> stringResource(R.string.hint_no_permission)
    Hint.PASTE_SPLIT -> stringResource(R.string.hint_paste_split, s.hintArg)
    Hint.PASTE_BAD -> stringResource(R.string.err_paste)
    Hint.CARD_CAP -> stringResource(R.string.err_card_cap)
    Hint.CHIP_CAP -> stringResource(R.string.err_chip_cap)
    Hint.PINS_CLOSE -> stringResource(R.string.err_pins_close)
    Hint.BOX_THIN -> stringResource(R.string.err_box_thin)
    Hint.UNIT_MISMATCH -> stringResource(R.string.err_unit_mismatch, s.hintArg.ifEmpty { s.result.errorDetail })
    Hint.BAD_DIM -> stringResource(R.string.err_dim)
    Hint.SAVED -> stringResource(R.string.saved)
    Hint.PIN_FAILED -> stringResource(R.string.hint_pin_failed)
    Hint.CANT_PLACE -> stringResource(R.string.hint_cant_place)
    Hint.READING_IN -> stringResource(R.string.reading_in)
    Hint.OVERSIZE -> stringResource(R.string.oversize)
    Hint.LANDED -> stringResource(R.string.landed_on, surfaceName(runCatching { SurfaceKind.valueOf(s.hintArg) }.getOrDefault(SurfaceKind.OTHER)))
    Hint.CONE_PLATE -> stringResource(R.string.hint_cone)
    Hint.NOT_FULL -> stringResource(R.string.err_not_full)
    Hint.TEMPLATE_SAVED -> stringResource(R.string.hint_template_saved, s.hintArg)
    Hint.BROUGHT -> stringResource(R.string.hint_brought)
}

fun isErrorHint(h: Hint) = h in setOf(Hint.PASTE_BAD, Hint.CARD_CAP, Hint.CHIP_CAP, Hint.PINS_CLOSE, Hint.BOX_THIN, Hint.UNIT_MISMATCH, Hint.BAD_DIM, Hint.PIN_FAILED, Hint.NOT_FULL)

fun fmtDate(ms: Long): String = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(ms))
