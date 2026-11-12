package com.kejing.spatialcalc.domain.session

import com.kejing.spatialcalc.domain.calc.CalcError
import com.kejing.spatialcalc.domain.calc.CalcResult
import com.kejing.spatialcalc.domain.calc.Channel
import com.kejing.spatialcalc.domain.calc.LenUnit
import com.kejing.spatialcalc.domain.calc.ResultUnit
import com.kejing.spatialcalc.domain.calc.SlotDef
import com.kejing.spatialcalc.domain.calc.SlotValue
import com.kejing.spatialcalc.domain.calc.TemplateId
import com.kejing.spatialcalc.domain.calc.Token
import com.kejing.spatialcalc.domain.math.Vec3
import com.kejing.spatialcalc.domain.place.BodyPose
import com.kejing.spatialcalc.domain.surface.SurfaceKind

/** The calculator page's own phase (design states S01–S09, S26). Overlays (confirm, saved card, menus) are separate fields. */
enum class Phase(val stateId: String) {
    LOADING("S00"), WELCOME("S01"), PICK("S02"), FORMULA("S03"), AIM("S04"), PULL("S05"), TYPING("S07"), PLACE("S09"), FITBOX("S26");
}

enum class Page { CALC, RECORDS, SETTINGS }

enum class Confirm { CLEAR, EXIT, DELETE_CARD }

/** One end of a tape: where it landed, on what, from how far, through which channel. */
data class PinPoint(val point: Vec3, val normal: Vec3, val surface: SurfaceKind, val rangeM: Float, val channel: Channel)

/** A measured tape of the active card (stays in the room as the evidence of where a number came from). */
data class TapeRec(val id: Int, val a: PinPoint, val b: PinPoint, val lengthM: Double, val tolM: Double, val slot: Int)

/** Inline hint line (S25 errors and one-shot notices). */
enum class Hint {
    NONE, NO_PLANES, PLANES_BACK, LOST, NO_PERMISSION, PASTE_SPLIT, PASTE_BAD, CARD_CAP, CHIP_CAP, PINS_CLOSE, BOX_THIN,
    UNIT_MISMATCH, BAD_DIM, SAVED, PIN_FAILED, CANT_PLACE, READING_IN, OVERSIZE, LANDED, CONE_PLATE, NOT_FULL, TEMPLATE_SAVED, BROUGHT,
}

data class BodyState(
    val pose: BodyPose,
    /** Bounding size (x width, y height, z depth) of the body as it stands. */
    val size: Vec3,
    val growStartMs: Long,
    /** The user moved it (S10): never pushed again (R1-3). */
    val userMoved: Boolean = false,
    /** Faded to 25 %: in the sight cone or the user's head is inside. */
    val faded: Boolean = false,
    /** For fit: extra 90° turns of the boxes the user asked for by tapping them (D18). */
    val quarterTurns: Int = 0,
    /** Attached to the measured wall / cell: never pushed (tile / paint / fit). */
    val attached: Boolean = false,
)

/** A saved card (dataModel.card). The result fields are one source for the result line, plate and records (D38). */
data class Card(
    val id: Long,
    val template: TemplateId,
    val round: Boolean,
    val customName: String?,
    val slots: List<SlotValue>,
    val tokens: List<Token>,
    val pose: BodyPose?,
    val size: Vec3?,
    val pinned: Boolean,
    val anchorId: String?,
    val anchorOffset: Vec3?,
    val createdAt: Long,
    val resultUnit: ResultUnit?,
    /** Pinned but its anchor did not come back where expected (behind / > 45° / > 2.5 m, or anchor failed). */
    val elsewhere: Boolean = false,
)

/** A user template (S12 / S13 [存成模板]): a fixed template with its own defaults, or a free formula pattern. */
data class CustomTemplate(val id: Long, val name: String, val base: TemplateId, val round: Boolean, val slots: List<SlotDef>, val pattern: List<Token>)

data class Settings(
    val language: Language = Language.SYSTEM,
    val imperial: Boolean = false,
    /** 0 淡 45 % · 1 中 60 % · 2 实 85 % (openForGateB Q5 default 45 %). */
    val opacity: Int = 0,
    val volume: Int = 1,
    val muted: Boolean = false,
    /** Not shown in v0.1.1 (q1-1): the half-trigger fine aim needs the tracked-pointer channel; kept for the prefs file. */
    val fineAim: Boolean = false,
    val snapPins: Boolean = true,
    /** Virtual table height above the floor (S17), metres 0.4–1.1. */
    val tableHeight: Float = 0.5f,
    val welcomeDone: Boolean = false,
)

enum class Language { SYSTEM, ZH, EN }

data class CalcState(
    val phase: Phase = Phase.LOADING,
    val page: Page = Page.CALC,
    val template: TemplateId? = null,
    val customId: Long? = null,
    val customName: String? = null,
    val round: Boolean = false,
    val slots: List<SlotValue> = emptyList(),
    val tokens: List<Token> = emptyList(),
    val active: Int = 0,
    val firstPin: PinPoint? = null,
    val tapes: List<TapeRec> = emptyList(),
    val result: CalcResult = CalcResult(error = CalcError.EMPTY),
    val resultUnit: ResultUnit? = null,
    val typingUnit: LenUnit = LenUnit.CM,
    val typingBuffer: String = "",
    /** What the keyboard opened with (q1-2: closing it unchanged must not rewrite the chip). */
    val typingInit: String = "",
    val body: BodyState? = null,
    val hint: Hint = Hint.NONE,
    val hintArg: String = "",
    val confirm: Confirm? = null,
    val confirmCardId: Long? = null,
    val chipMenu: Int? = null,
    val resultMenu: Boolean = false,
    val savedPreview: Boolean = false,
    val naming: Boolean = false,
    val savedCardId: Long? = null,
    val cards: List<Card> = emptyList(),
    val customs: List<CustomTemplate> = emptyList(),
    val expandedCard: Long? = null,
    val replayCardId: Long? = null,
    val noPlanes: Boolean = false,
    val lost: Boolean = false,
    val noPermission: Boolean = false,
    val restored: Int = 0,
    val fitBox: FitBoxState? = null,
    val headSampling: Boolean = false,
    val dirty: Boolean = false,
    val chipFlight: Long = 0L,
    /** fit: orientation the user turned the boxes to (null = the best packing). */
    val fitPerm: Int? = null,
    /** fit: the shelf cell measured with the fit box ([用这三边]); the ghost boxes stand in it (q1-8). */
    val fitCell: FitBoxState? = null,
) {
    /** S08: the formula has grown its body (or is placing it). */
    val showingResult: Boolean get() = body != null
    val isFree: Boolean get() = template == TemplateId.FREE || (template == TemplateId.CUSTOM && tokensMode)
    val tokensMode: Boolean get() = slots.isEmpty() && (template == TemplateId.FREE || template == TemplateId.CUSTOM)
    val measureSlotEmpty: Boolean get() = slots.any { it.q == null && it.def.kind != com.kejing.spatialcalc.domain.calc.SlotKind.NUMBER }
    val stateId: String get() = when {
        confirm == Confirm.EXIT -> "S16"
        confirm == Confirm.CLEAR -> "S23"
        page == Page.RECORDS -> "S13"
        page == Page.SETTINGS -> "S15"
        savedPreview -> "S12"
        phase == Phase.FORMULA && body != null -> "S08"
        else -> phase.stateId
    }
}

/** Fit box (S26): centre of its bottom face, yaw, and its three sides (x width, y height, z depth). */
data class FitBoxState(val pose: BodyPose, val size: Vec3, val channel: Channel)
