package io.embodit.spatialcalc.domain.session

import io.embodit.spatialcalc.domain.calc.TemplateId

/**
 * v0.1.2 「界面简洁」(profile B31, user 2026-11-12「界面还需要简洁一点，现在有点太乱」): which controls the Calc page shows
 * in each state. One primary button, at most one Toolbar key that is not on the panel, everything else in More (⋯).
 * Pure so [ChromeTest] can count every state (≤ 7 tappables incl. the 3 TabBar tabs and the 1:1 body).
 */
enum class PrimaryAction { MEASURE, PLACE, SAVE, NEXT }

enum class ToolKeyId { MEASURE, FITBOX }

/** v0.1.3 (q3-1): sound is no longer a More item: the mute key stands on the Calc page title row (D15, user 2026-11-12「常显静音」). */
enum class MoreItem { TYPE, MEASURE, FITBOX, SAVE, SAVE_TEMPLATE, UNIT, SHAPE, BRING, PUT_AWAY, HELP, CLEAR }

object Chrome {
    const val TABS = 3
    /** The always-on mute key (q3-1). Not counted in the B31 seven (user 2026-11-12). */
    const val MUTE = 1

    /** q3-2: the saved card is pinned (its pinned copy stands in the room) and nothing changed since: the job is done. */
    fun pinnedDone(s: CalcState): Boolean =
        s.savedCardId != null && !s.dirty && s.cards.any { it.id == s.savedCardId && it.pinned }

    /** A measure slot is still empty (or a free formula has nothing to compute yet): the next thing is to measure. */
    fun needMeasure(s: CalcState): Boolean = s.measureSlotEmpty || (s.tokensMode && !s.result.ok)

    /** The normal formula view (no keyboard, menu, confirm, saved card, naming, chip edit, aiming / placing). */
    fun plainFormula(s: CalcState): Boolean =
        s.page == Page.CALC && s.phase == Phase.FORMULA && s.template != null && s.confirm == null && !s.savedPreview && !s.naming &&
            s.chipMenu == null && !s.resultMenu

    fun primary(s: CalcState): PrimaryAction = when {
        needMeasure(s) -> PrimaryAction.MEASURE
        !s.result.ok -> PrimaryAction.PLACE // shown disabled; the hero line says what is wrong
        // q3-2: [钉在这里] hands the body to the pinned copy (body = null); = must not grow a second one
        pinnedDone(s) -> PrimaryAction.NEXT
        s.body == null && s.result.body != null -> PrimaryAction.PLACE
        s.savedCardId == null || s.dirty -> PrimaryAction.SAVE
        else -> PrimaryAction.NEXT
    }

    fun primaryEnabled(s: CalcState): Boolean = primary(s) != PrimaryAction.PLACE || s.result.ok

    /** Toolbar under the window: only while measuring is the next step, and never a copy of the panel's primary. */
    fun toolbar(s: CalcState): List<ToolKeyId> {
        if (!plainFormula(s) || s.body != null) return emptyList()
        return when {
            primary(s) == PrimaryAction.MEASURE -> listOf(ToolKeyId.FITBOX)
            s.tokensMode -> listOf(ToolKeyId.MEASURE)
            else -> emptyList()
        }
    }

    /** More (⋯): everything secondary, minus what the primary button or the Toolbar already shows. */
    fun more(s: CalcState): List<MoreItem> {
        val p = primary(s)
        val tb = toolbar(s.copy(resultMenu = false))
        val out = mutableListOf(MoreItem.TYPE)
        if (p != PrimaryAction.MEASURE && ToolKeyId.MEASURE !in tb) out += MoreItem.MEASURE
        if (ToolKeyId.FITBOX !in tb) out += MoreItem.FITBOX
        if (s.result.ok && p != PrimaryAction.SAVE && (s.savedCardId == null || s.dirty)) out += MoreItem.SAVE
        if (s.savedCardId != null && !s.dirty) out += MoreItem.SAVE_TEMPLATE
        if (s.result.q != null && s.result.count == null) out += MoreItem.UNIT
        if (s.template == TemplateId.TANK) out += MoreItem.SHAPE
        if (s.body != null) { out += MoreItem.BRING; out += MoreItem.PUT_AWAY }
        out += MoreItem.HELP
        out += MoreItem.CLEAR
        return out
    }

    /**
     * Tappables on screen in the plain formula view: TabBar tabs + title-row ⋯ + the formula strip (one control) + the
     * primary + Toolbar keys + the 1:1 body in the room (tap → More / turn the boxes). The mute key ([MUTE]) is on the
     * page too but outside the seven (user 2026-11-12); [shownCount] includes it.
     */
    fun formulaCount(s: CalcState): Int = TABS + 1 + 1 + 1 + toolbar(s).size + (if (s.body != null) 1 else 0)

    /** Everything tappable on screen, mute included (≤ 8 with a body, user 2026-11-12). */
    fun shownCount(s: CalcState): Int = formulaCount(s) + MUTE
}
