package io.embodit.spatialcalc.domain.session

import io.embodit.spatialcalc.domain.calc.BodySpec
import io.embodit.spatialcalc.domain.calc.CalcError
import io.embodit.spatialcalc.domain.calc.CalcResult
import io.embodit.spatialcalc.domain.calc.Channel
import io.embodit.spatialcalc.domain.calc.Evaluate
import io.embodit.spatialcalc.domain.calc.LenUnit
import io.embodit.spatialcalc.domain.calc.Paste
import io.embodit.spatialcalc.domain.calc.Q
import io.embodit.spatialcalc.domain.calc.ResultUnit
import io.embodit.spatialcalc.domain.calc.SlotDef
import io.embodit.spatialcalc.domain.calc.SlotKind
import io.embodit.spatialcalc.domain.calc.SlotUnit
import io.embodit.spatialcalc.domain.calc.SlotValue
import io.embodit.spatialcalc.domain.calc.Source
import io.embodit.spatialcalc.domain.calc.TemplateId
import io.embodit.spatialcalc.domain.calc.Templates
import io.embodit.spatialcalc.domain.calc.Token
import io.embodit.spatialcalc.domain.calc.Tolerance
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.domain.place.Aabb
import io.embodit.spatialcalc.domain.place.BodyPose
import io.embodit.spatialcalc.domain.place.PlaceBody
import io.embodit.spatialcalc.domain.place.PlaceOutcome
import io.embodit.spatialcalc.domain.place.yawFacing
import io.embodit.spatialcalc.domain.surface.SurfaceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

/** What the flow needs from the room (implemented by the Stage scene; fakes in tests). */
interface RoomEnv {
    /** Head position and horizontal forward, or null before tracking is valid. */
    fun head(): Pair<Vec3, Vec3>?
    /** Where the main window is believed to be (opened ahead of the head at open time), or null. */
    fun window(): Vec3?
    /** Floor height in Stage metres (detected floor, else head − 1.6 m). */
    fun floorY(): Float
    fun now(): Long
}

enum class Sfx { PIN, CHIP, POUR, THUD, TAPE }

/** Side effects the flow asks for. */
interface FlowEffects {
    fun sound(s: Sfx) {}
    fun save(cards: List<Card>, customs: List<CustomTemplate>) {}
    fun saveSettings(s: Settings) {}
    /** Create a world anchor at [at]; call [CalcFlow.onAnchor] with the id or null. */
    fun createAnchor(cardId: Long, at: Vec3) {}
    fun removeAnchor(anchorId: String) {}
    fun log(line: String) {}
}

/**
 * The calculator's whole state machine (design states S00–S26). Every input — window buttons, Stage taps and drags,
 * the controller stick, and the debug broadcast — calls these methods; nothing writes the state another way
 * (build.md 2026-10-29: debug commands use the user's path).
 */
class CalcFlow(private val env: RoomEnv, private val fx: FlowEffects) {
    private val _state = MutableStateFlow(CalcState())
    val state: StateFlow<CalcState> = _state.asStateFlow()
    val s: CalcState get() = _state.value

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private var tapeSeq = 0
    private var hintSeq = 0L

    private fun set(change: (CalcState) -> CalcState) {
        val before = _state.value
        val after = change(before)
        _state.value = after
        if (before.stateId != after.stateId) fx.log("STATE ${before.stateId} -> ${after.stateId} phase=${after.phase} page=${after.page}")
    }

    // ------------------------------------------------------------------ start / settings

    fun start(settings: Settings, cards: List<Card>, customs: List<CustomTemplate>) {
        _settings.value = settings
        set { it.copy(phase = if (settings.welcomeDone) Phase.PICK else Phase.WELCOME, cards = cards, customs = customs, typingUnit = if (settings.imperial) LenUnit.IN else LenUnit.CM) }
    }

    fun updateSettings(change: (Settings) -> Settings) {
        val n = change(_settings.value)
        _settings.value = n
        fx.saveSettings(n)
        if (s.typingUnit.imperial != n.imperial) set { it.copy(typingUnit = if (n.imperial) LenUnit.IN else LenUnit.CM, resultUnit = null) }
        fx.log("SETTINGS $n")
    }

    fun welcomeDone() {
        updateSettings { it.copy(welcomeDone = true) }
        if (s.phase == Phase.WELCOME) set { it.copy(phase = Phase.PICK) }
    }

    fun page(p: Page) = set { it.copy(page = p, settingsMore = false, confirm = if (it.confirm == Confirm.EXIT && p != Page.SETTINGS) null else it.confirm) }

    // ------------------------------------------------------------------ hints

    fun hint(h: Hint, arg: String = "") {
        hintSeq++
        set { it.copy(hint = h, hintArg = arg) }
        if (h != Hint.NONE) fx.log("HINT $h $arg")
    }

    fun setNoPlanes(on: Boolean) {
        val was = s.noPlanes
        set { it.copy(noPlanes = on) }
        if (was && !on && s.hint == Hint.NO_PLANES) hint(Hint.PLANES_BACK)
        fx.log("PLANES none=$on")
    }

    fun setLost(on: Boolean) { if (s.lost != on) { set { it.copy(lost = on) }; fx.log("TRACK lost=$on") } }

    fun setNoPermission(on: Boolean) { set { it.copy(noPermission = on) }; if (on) hint(Hint.NO_PERMISSION) }

    // ------------------------------------------------------------------ picking a job

    fun pick(t: TemplateId, round: Boolean = false) {
        if (t == TemplateId.CUSTOM) return
        val slots = Templates.slots(t, round).map { SlotValue(it) }
        set {
            it.copy(
                phase = Phase.FORMULA, page = Page.CALC, template = t, round = round, customId = null, customName = null, slots = slots, tokens = emptyList(),
                active = firstEmpty(slots), firstPin = null, tapes = emptyList(), result = CalcResult(error = CalcError.EMPTY), resultUnit = null,
                body = null, hint = Hint.NONE, chipMenu = null, resultMenu = false, savedPreview = false, savedCardId = null, replayCardId = null, fitBox = null, dirty = false, fitPerm = null, fitCell = null,
            )
        }
        fx.log("TEMPLATE $t round=$round slots=${slots.size}")
        tipOnce(t.id, "")
    }

    /** v0.1.2 (B31): a how-to line appears by itself only the first time; after that only from More → How to. */
    private fun tipOnce(key: String, arg: String) {
        if (key in _settings.value.tipsSeen) return
        updateSettings { it.copy(tipsSeen = it.tipsSeen + key) }
        hint(Hint.TIP, arg)
    }

    /** More → [How to]: the job's how-to (with a body standing: drag it / stick to turn). */
    fun showTip() {
        set { it.copy(resultMenu = false) }
        hint(Hint.TIP, if (s.body != null) "drag" else "")
    }

    /** The how-to line goes away by itself (no standing instructions, B31). */
    fun dismissTip() { if (s.hint == Hint.TIP) hint(Hint.NONE) }

    /** Chip edit mode off (the strip goes back to one quiet line). */
    fun closeChipMenu() = set { it.copy(chipMenu = null) }

    /** Settings → [More settings] / [‹ Back]. */
    fun settingsMore(on: Boolean) = set { it.copy(settingsMore = on, page = Page.SETTINGS) }

    fun pickCustom(id: Long) {
        val c = s.customs.firstOrNull { it.id == id } ?: return
        if (c.pattern.isNotEmpty()) {
            pick(TemplateId.FREE)
            set { it.copy(template = TemplateId.CUSTOM, customId = c.id, customName = c.name, tokens = c.pattern) }
            recompute()
        } else {
            val slots = c.slots.map { SlotValue(it) }
            pick(c.base, c.round)
            set { it.copy(customId = c.id, customName = c.name, slots = slots, active = firstEmpty(slots)) }
            recompute()
        }
    }

    fun setRound(round: Boolean) {
        if (s.template != TemplateId.TANK || s.round == round) return
        clearActiveTapes()
        pick(TemplateId.TANK, round)
    }

    private fun firstEmpty(slots: List<SlotValue>, from: Int = 0): Int {
        val order = slots.indices.drop(from) + slots.indices.take(from)
        return order.firstOrNull { slots[it].q == null && slots[it].def.kind != SlotKind.NUMBER } ?: from.coerceAtMost(max(0, slots.size - 1))
    }

    // ------------------------------------------------------------------ measuring (T1)

    /** Toolbar [量一下]. */
    fun measureButton() {
        when {
            s.phase == Phase.PICK || s.phase == Phase.WELCOME -> { pick(TemplateId.FREE); beginAim() }
            s.template == null -> return
            s.tokensMode -> { if (s.tokens.size >= MAX_CHIPS) hint(Hint.CHIP_CAP) else beginAim() }
            else -> beginAim()
        }
    }

    private fun beginAim() {
        if (s.page != Page.CALC) set { it.copy(page = Page.CALC) }
        set { it.copy(phase = Phase.AIM, firstPin = null, chipMenu = null, resultMenu = false, fitBox = null) }
    }

    fun cancelMeasure() {
        if (s.phase == Phase.AIM || s.phase == Phase.PULL) set { it.copy(phase = Phase.FORMULA, firstPin = null) }
    }

    /**
     * A tap on a surface (system pointer through the backstop → analytic plane hit, or the debug ray). In S03 it
     * starts a measurement only while a measure slot is empty (R2-10: a full formula does not arm surface pinches).
     */
    fun surfaceTap(tap: PinPoint) {
        val p = snapped(tap)
        when (s.phase) {
            Phase.AIM -> firstPin(p)
            Phase.PULL -> secondPin(p)
            Phase.PLACE -> placeAt(p)
            Phase.FORMULA -> if (s.body == null && (s.measureSlotEmpty || (s.tokensMode && s.tokens.isEmpty()))) { beginAim(); firstPin(p) }
                             else fx.log("TAP surface ignored reason=formula-full")
            Phase.PICK -> { pick(TemplateId.FREE); beginAim(); firstPin(p) }
            else -> fx.log("TAP surface ignored phase=${s.phase}")
        }
    }

    /**
     * Settings「吸附已有钉」(q1-1): a tap within [SNAP_M] of a pin already in the room lands exactly on it, so two tapes
     * share a corner (length then width from the same corner). Only while measuring; off in the settings = raw tap.
     */
    fun snapped(p: PinPoint): PinPoint {
        if (!_settings.value.snapPins || (s.phase != Phase.AIM && s.phase != Phase.PULL && s.phase != Phase.FORMULA && s.phase != Phase.PICK)) return p
        val pins = s.tapes.flatMap { listOf(it.a, it.b) } + listOfNotNull(s.firstPin)
        val near = pins.filter { it !== s.firstPin || s.phase != Phase.PULL }.minByOrNull { it.point.distanceTo(p.point) } ?: return p
        val d = near.point.distanceTo(p.point)
        if (d > SNAP_M) return p
        fx.log("SNAP to pin at=${near.point} d_mm=${(d * 1000).toInt()}")
        return p.copy(point = near.point, normal = near.normal, surface = near.surface)
    }

    private fun firstPin(p: PinPoint) {
        set { it.copy(phase = Phase.PULL, firstPin = p, hint = Hint.LANDED, hintArg = p.surface.name) }
        fx.sound(Sfx.PIN)
        fx.sound(Sfx.TAPE)
        fx.log("MEASURE pin1 at=${p.point} on=${p.surface} range=${"%.2f".format(p.rangeM)} ch=${p.channel}")
    }

    private fun secondPin(p: PinPoint) {
        val a = s.firstPin ?: return
        val len = a.point.distanceTo(p.point).toDouble()
        if (len < 0.01) { hint(Hint.PINS_CLOSE); return }
        val tolM = Tolerance.tapeMm(p.channel, a.rangeM, p.rangeM) / 1000.0
        val id = ++tapeSeq
        fx.sound(Sfx.PIN)
        fx.log("MEASURE pin2 at=${p.point} len=${"%.4f".format(len)} tol_mm=${(tolM * 1000).toInt()} ch=${p.channel}")
        val q = Q.length(len, tolM)
        if (s.tokensMode) {
            val tape = TapeRec(id, a, p, len, tolM, s.tokens.size)
            set { it.copy(tokens = it.tokens + Token.Num(q, Source.TAPE, id), tapes = it.tapes + tape, phase = Phase.FORMULA, firstPin = null, hint = Hint.READING_IN, chipFlight = env.now()) }
        } else {
            val slot = s.active.coerceIn(0, s.slots.size - 1)
            val old = s.slots[slot].tapeId
            val tape = TapeRec(id, a, p, len, tolM, slot)
            val slots = s.slots.toMutableList().also { it[slot] = SlotValue(it[slot].def, q, Source.TAPE, id, null) }
            val next = firstEmpty(slots, slot)
            val more = slots.any { it.q == null && it.def.kind != SlotKind.NUMBER }
            set {
                it.copy(
                    slots = slots, tapes = it.tapes.filter { t -> t.id != old } + tape, active = next, firstPin = null,
                    phase = if (more) Phase.AIM else Phase.FORMULA, hint = Hint.READING_IN, chipFlight = env.now(), dirty = true,
                )
            }
        }
        fx.sound(Sfx.CHIP)
        fx.log("CHIP in slot=${s.active} source=tape id=$id")
        recompute()
    }

    // ------------------------------------------------------------------ chips (T8) and typing (T4)

    fun chipTap(i: Int) = set { it.copy(chipMenu = if (it.chipMenu == i) null else i, resultMenu = false) }

    fun selectSlot(i: Int) { if (i in s.slots.indices) set { it.copy(active = i, chipMenu = null) } }

    fun chipRemeasure(i: Int) {
        if (s.tokensMode) { chipDelete(i); beginAim(); return }
        if (i !in s.slots.indices) return
        set { it.copy(active = i, chipMenu = null) }
        beginAim()
    }

    fun chipDelete(i: Int) {
        if (s.tokensMode) {
            if (i !in s.tokens.indices) return
            val t = s.tokens[i]
            set { st -> st.copy(tokens = st.tokens.filterIndexed { k, _ -> k != i }, tapes = st.tapes.filter { (t as? Token.Num)?.tapeId != it.id }, chipMenu = null) }
        } else {
            if (i !in s.slots.indices) return
            val v = s.slots[i]
            val slots = s.slots.toMutableList().also { it[i] = SlotValue(v.def, if (v.def.default != null) Q(v.def.default * v.def.unit.toSi, 0.0, v.def.unit.dim) else null, if (v.def.default != null) Source.DEFAULT else Source.NONE) }
            set { st -> st.copy(slots = slots, tapes = st.tapes.filter { it.id != v.tapeId }, active = i, chipMenu = null) }
        }
        recompute()
    }

    /** [打数] / tap on a slot / chip「改数」: open the keyboard on slot [i] (or the active one). */
    fun openTyping(i: Int? = null) {
        val idx = i ?: s.active
        if (!s.tokensMode && idx in s.slots.indices) set { it.copy(active = idx) }
        val v = s.slots.getOrNull(idx)
        val buf = when {
            v == null -> ""
            // q1-2: LENGTH slots keep [SlotValue.typed] in metres; the buffer is in the typing unit (cm / in)
            v.source == Source.TYPED || v.source == Source.DEFAULT -> v.typed?.let { t ->
                io.embodit.spatialcalc.domain.calc.Fmt.plain(if (v.def.unit == SlotUnit.LENGTH) t * s.typingUnit.perM else t)
            } ?: ""
            else -> ""
        }
        set { it.copy(phase = Phase.TYPING, chipMenu = null, resultMenu = false, typingBuffer = buf, typingInit = buf) }
    }

    fun closeTyping() {
        if (s.phase != Phase.TYPING) return
        // q1-2: nothing typed → the chip keeps its exact value (no round trip through the display string)
        if (s.tokensMode || s.typingBuffer != s.typingInit) commitBuffer() else fx.log("CHIP typing closed unchanged")
        // K10 (QA3): [✓ 收起] goes back to the plain formula, never into chip edit mode
        set { it.copy(phase = Phase.FORMULA, typingBuffer = "", typingInit = "", chipMenu = null) }
    }

    /**
     * q3-3: a tap on a chip while the keyboard is open. The number in the field goes into the slot it was typed for
     * first (unchanged → nothing is written), then the keyboard moves to slot [i] with that slot's own value. Before
     * v0.1.3 the field kept its digits and the next slot got them glued on (20 → 2020 → 202020 cm).
     * Free formula: the number is appended as a chip and the keyboard closes on that chip's edit menu.
     */
    fun typingChipTap(i: Int) {
        if (s.phase != Phase.TYPING) { if (s.tokensMode || s.slots.getOrNull(i)?.q != null) chipTap(i) else { selectSlot(i); openTyping(i) }; return }
        if (s.tokensMode) { closeTyping(); if (i in s.tokens.indices) chipTap(i); return }
        if (i !in s.slots.indices) return
        if (i == s.active) return
        val from = s.active
        if (s.typingBuffer != s.typingInit) commitBuffer()
        fx.log("CHIP typing switch slot=$from -> $i")
        openTyping(i)
    }

    /** q3-1: the always-on mute key on the Calc page (same setting as Settings → Sound). */
    fun toggleMute() = updateSettings { it.copy(muted = !it.muted) }

    fun setTypingUnit(u: LenUnit) = set { it.copy(typingUnit = u) }

    /** One key of the S07 keyboard: digits, ".", "⌫", or an operator (free formula only). */
    fun key(k: String) {
        if (s.phase != Phase.TYPING) openTyping()
        when (k) {
            "⌫" -> {
                if (s.typingBuffer.isNotEmpty()) set { it.copy(typingBuffer = it.typingBuffer.dropLast(1)) }
                else if (s.tokensMode && s.tokens.isNotEmpty()) chipDelete(s.tokens.size - 1)
            }
            "." -> if ('.' !in s.typingBuffer) set { it.copy(typingBuffer = (if (it.typingBuffer.isEmpty()) "0" else it.typingBuffer) + ".") }
            in Token.OPS -> {
                if (!s.tokensMode) { commitBuffer(); return }
                commitBuffer()
                if (s.tokens.size >= MAX_CHIPS) { hint(Hint.CHIP_CAP); return }
                set { it.copy(tokens = it.tokens + Token.Op(k), dirty = true) }
                recompute()
            }
            else -> if (k.length == 1 && k[0].isDigit() && s.typingBuffer.length < 9) set { it.copy(typingBuffer = it.typingBuffer + k) }
        }
    }

    /** The number being typed goes into the active slot (or appends a white chip in a free formula). */
    private fun commitBuffer() {
        val n = s.typingBuffer.toDoubleOrNull() ?: return
        set { it.copy(typingBuffer = "") }
        if (s.tokensMode) {
            if (s.tokens.size >= MAX_CHIPS) { hint(Hint.CHIP_CAP); return }
            val q = Q.length(n / s.typingUnit.perM)
            set { it.copy(tokens = it.tokens + Token.Num(q, Source.TYPED), dirty = true) }
        } else {
            val i = s.active.coerceIn(0, s.slots.size - 1)
            val def = s.slots[i].def
            val q = if (def.unit == SlotUnit.LENGTH) Q.length(n / s.typingUnit.perM) else Q(n * def.unit.toSi, 0.0, def.unit.dim)
            val old = s.slots[i].tapeId
            val slots = s.slots.toMutableList().also { it[i] = SlotValue(def, q, Source.TYPED, null, if (def.unit == SlotUnit.LENGTH) n / s.typingUnit.perM else n) }
            set { it.copy(slots = slots, tapes = it.tapes.filter { t -> t.id != old }, active = firstEmpty(slots, i), dirty = true) }
        }
        fx.sound(Sfx.CHIP)
        fx.log("CHIP typed value=$n unit=${s.typingUnit}")
        recompute()
    }

    /** Paste「60×30×35 cm」: split into the empty length slots from the active one on (or appended chips). */
    fun paste(text: String) {
        val parts = Paste.parse(text, s.typingUnit)
        if (parts == null) { hint(Hint.PASTE_BAD); fx.log("PASTE bad \"$text\""); return }
        if (s.tokensMode) {
            var toks = s.tokens
            parts.forEachIndexed { k, m -> if (k > 0) toks = toks + Token.Op("×"); toks = toks + Token.Num(Q.length(m), Source.TYPED) }
            if (toks.size > MAX_CHIPS) { hint(Hint.CHIP_CAP); return }
            set { it.copy(tokens = toks, dirty = true, phase = Phase.FORMULA) }
        } else {
            val slots = s.slots.toMutableList()
            val lengthSlots = slots.indices.filter { slots[it].def.unit == SlotUnit.LENGTH }
            val start = lengthSlots.firstOrNull { it >= s.active && slots[it].q == null } ?: lengthSlots.firstOrNull { slots[it].q == null } ?: lengthSlots.firstOrNull() ?: return
            val targets = lengthSlots.filter { it >= start }.take(parts.size)
            targets.forEachIndexed { k, i -> slots[i] = SlotValue(slots[i].def, Q.length(parts[k]), Source.TYPED, null, parts[k]) }
            set { it.copy(slots = slots, active = firstEmpty(slots, targets.last()), dirty = true, phase = Phase.FORMULA) }
        }
        hint(Hint.PASTE_SPLIT, parts.size.toString())
        fx.sound(Sfx.CHIP)
        fx.log("PASTE \"$text\" -> ${parts.joinToString { "%.4f".format(it) }}")
        recompute()
    }

    // ------------------------------------------------------------------ result (T5) and live edit (S11)

    private fun recompute() {
        val r = if (s.tokensMode) Evaluate.free(s.tokens) else if (s.template != null) Evaluate.template(s.template!!, s.round, s.slots, s.fitPerm) else CalcResult(error = CalcError.EMPTY)
        set { it.copy(result = r) }
        fx.log("CALC ok=${r.ok} q=${r.q} count=${r.count} err=${r.error} body=${r.body}")
        val b = s.body
        val spec = r.body
        if (b != null && spec != null && r.ok) {
            val size = sizeOf(spec, b.quarterTurns)
            set { it.copy(body = b.copy(size = size)) }
            fx.log("BODY resize size=$size")
        } else if (b != null && !r.ok) {
            set { it.copy(body = null) }
        }
    }

    fun equals() {
        val r = s.result
        if (s.phase == Phase.TYPING) closeTyping()
        // q3-2: the result is pinned in the room already; a second = would stand a copy on top of it
        if (Chrome.pinnedDone(s)) { fx.log("EQUALS ignored: card ${s.savedCardId} is pinned"); return }
        if (!s.result.ok) { hint(if (r.error == CalcError.EMPTY) Hint.NOT_FULL else hintOf(r.error)); return }
        val spec = s.result.body
        if (spec == null) { fx.log("EQUALS no body (m⁰)"); return }
        val size = sizeOf(spec, 0)
        val auto = autoPose(spec, size)
        if (auto == null) {
            set { it.copy(phase = Phase.PLACE, chipMenu = null, resultMenu = false) }
            fx.log("PLACE ask (no measured object)")
            return
        }
        grow(auto.first, size, auto.second)
        if (bigBox(spec)) hint(Hint.OVERSIZE)
    }

    private fun grow(pose: BodyPose, size: Vec3, attached: Boolean) {
        set { it.copy(phase = Phase.FORMULA, body = BodyState(pose, size, env.now(), attached = attached), resultMenu = false, chipMenu = null, hint = if (bigBox(it.result.body)) Hint.OVERSIZE else Hint.NONE) }
        fx.sound(if (s.template == TemplateId.TANK) Sfx.POUR else Sfx.THUD)
        fx.log("BODY grow pos=${pose.position} yaw=${"%.3f".format(pose.yaw)} size=$size attached=$attached")
        if (s.hint == Hint.NONE) tipOnce("drag", "drag")
    }

    /** S21 is about volumes / slabs bigger than the room; a 5 m tiled wall is not「穿墙」. */
    private fun bigBox(b: BodySpec?) = (b is BodySpec.Box || b is BodySpec.Slab) && b.oversize

    fun cancelPlace() { if (s.phase == Phase.PLACE) set { it.copy(phase = Phase.FORMULA) } }

    private fun placeAt(p: PinPoint) {
        val spec = s.result.body ?: return
        val size = sizeOf(spec, 0)
        val head = env.head()?.first ?: Vec3(0f, 1.6f, 0f)
        val vertical = spec is BodySpec.Tiles || spec is BodySpec.Paint
        val yaw = if (vertical && p.surface == SurfaceKind.WALL) kotlin.math.atan2(p.normal.x, p.normal.z) else yawFacing(p.point, head)
        val pos = if (vertical && p.surface == SurfaceKind.WALL) p.point + p.normal * 0.004f - Vec3.UP * 0f else p.point
        grow(BodyPose(pos, yaw), size, attached = vertical && p.surface == SurfaceKind.WALL)
    }

    /** The automatic drop point, or null when there is nothing measured to stand next to (→ S09). */
    private fun autoPose(spec: BodySpec, size: Vec3): Pair<BodyPose, Boolean>? {
        if (spec is BodySpec.Ghosts && s.fitCell != null) return BodyPose(s.fitCell!!.pose.position, s.fitCell!!.pose.yaw) to true
        val pins = s.tapes.flatMap { listOf(it.a, it.b) }
        if (pins.isEmpty()) return null
        val head = env.head()?.first ?: Vec3(0f, 1.6f, 0f)
        val floor = env.floorY()
        val virtualTable = floor + _settings.value.tableHeight
        val box = Aabb.of(pins.map { it.point })!!
        when (spec) {
            is BodySpec.Tiles, is BodySpec.Paint -> {
                val wall = pins.firstOrNull { it.surface == SurfaceKind.WALL } ?: return null
                val wTape = s.tapes.firstOrNull { it.slot == 0 } ?: s.tapes.first()
                val mid = (wTape.a.point + wTape.b.point) * 0.5f
                val bottom = pins.minOf { it.point.y }
                val n = Vec3(wall.normal.x, 0f, wall.normal.z).normalized()
                val pos = Vec3(mid.x, bottom, mid.z) + n * 0.004f
                return BodyPose(pos, kotlin.math.atan2(n.x, n.z)) to true
            }
            is BodySpec.Ghosts -> {
                // q1-8: the cell measured with the fit box: the boxes stand in it, on its floor, square to it
                s.fitCell?.let { c -> return BodyPose(c.pose.position, c.pose.yaw) to true }
                if (s.tapes.count { it.slot <= 2 } < 3) return null
                val cell = Aabb.of(s.tapes.filter { it.slot <= 2 }.flatMap { listOf(it.a.point, it.b.point) })!!
                val c = cell.center
                val pos = Vec3(c.x, cell.min.y, c.z)
                return BodyPose(pos, yawFacing(pos, head)) to true
            }
            is BodySpec.Rug -> {
                val c = box.center
                val pos = Vec3(c.x, floor, c.z)
                return BodyPose(pos, yawFacing(pos, head)) to true
            }
            else -> {
                val horizontal = pins.all { kotlin.math.abs(it.normal.y) > 0.8f }
                val bigSide = max(size.x, max(size.y, size.z)) > 0.5f
                val support = when {
                    horizontal -> pins.minOf { it.point.y }
                    bigSide -> floor
                    else -> pins.minOf { it.point.y }
                }
                val r = PlaceBody.place(head, env.window(), box, support, size, others = obstacles())
                fx.log("PLACE auto ${r.outcome} rounds=${r.rounds} ${r.log}")
                if (r.outcome == PlaceOutcome.ASK_USER) { hint(Hint.CANT_PLACE); return null }
                return r.pose to false
            }
        }
    }

    /** q1-9 (D17): bodies already standing in the room (pinned / restored cards) that a new body must not overlap. */
    fun obstacles(exceptCard: Long? = null): List<Aabb> = s.cards.filter { it.pinned && it.pose != null && it.size != null && it.id != exceptCard && it.id != s.replayCardId }
        .map { io.embodit.spatialcalc.domain.place.bodyBox(it.pose!!, it.size!!) }

    fun sizeOf(spec: BodySpec, quarterTurns: Int): Vec3 = when (spec) {
        is BodySpec.Box -> Vec3(spec.x.toFloat(), spec.y.toFloat(), spec.z.toFloat())
        is BodySpec.Cylinder -> Vec3(spec.diameter.toFloat(), spec.height.toFloat(), spec.diameter.toFloat())
        is BodySpec.Tiles -> Vec3(spec.width.toFloat(), spec.height.toFloat(), 0.01f)
        is BodySpec.Paint -> Vec3(spec.width.toFloat(), spec.height.toFloat(), 0.01f)
        is BodySpec.Ghosts -> Vec3(spec.cellX.toFloat(), spec.cellY.toFloat(), spec.cellZ.toFloat())
        is BodySpec.Rug -> Vec3(max(spec.x, spec.specX).toFloat(), 0.01f, max(spec.z, spec.specZ).toFloat())
        is BodySpec.Tape -> Vec3(spec.length.toFloat(), 0.02f, 0.02f)
        is BodySpec.Slab -> Vec3(spec.x.toFloat(), 0.004f, spec.z.toFloat())
    }

    private fun hintOf(e: CalcError?) = when (e) {
        CalcError.UNIT_MISMATCH -> Hint.UNIT_MISMATCH
        CalcError.BAD_DIM, CalcError.NEGATIVE_ROOT -> Hint.BAD_DIM
        CalcError.BOX_THIN -> Hint.BOX_THIN
        else -> Hint.NOT_FULL
    }

    /** S14: next display unit of the result. */
    fun cycleUnit() {
        val r = s.result
        val dim = r.q?.dim ?: return
        if (r.count != null) return
        val choices = ResultUnit.choices(dim, _settings.value.imperial)
        val cur = s.resultUnit ?: choices.first()
        val next = choices[(choices.indexOf(cur) + 1) % choices.size]
        set { it.copy(resultUnit = next) }
        fx.log("UNIT result=$next")
    }

    fun resultMenu(open: Boolean) = set { it.copy(resultMenu = open, chipMenu = null) }

    /** Result line menu [收起实体]: the number stays, the body fades; = grows it again. */
    fun collapseBody() { set { it.copy(body = null, resultMenu = false) }; fx.log("BODY collapse") }

    /** [带到面前] (activeBody): a head sample, then 1.2 m ahead through the same placement chain. */
    fun bringBodyToFront(sample: Pair<Vec3, Vec3>) {
        val b = s.body ?: return
        val r = PlaceBody.bringToFront(sample.first, sample.second, env.window(), env.floorY() + _settings.value.tableHeight, b.size, obstacles(s.savedCardId))
        set { it.copy(body = b.copy(pose = r.pose, userMoved = false, attached = false), resultMenu = false, headSampling = false) }
        fx.log("BODY front ${r.outcome} pos=${r.pose.position} ${r.log}")
        if (r.outcome == PlaceOutcome.ASK_USER) hint(Hint.CANT_PLACE)
    }

    fun setHeadSampling(on: Boolean) = set { it.copy(headSampling = on, resultMenu = false) }

    // ------------------------------------------------------------------ compare (T6 / T7)

    fun bodyDragged(pose: BodyPose, faded: Boolean) {
        val b = s.body ?: return
        set { it.copy(body = b.copy(pose = pose, userMoved = true, faded = faded)) }
    }

    fun bodyFaded(faded: Boolean) { val b = s.body ?: return; if (b.faded != faded) set { it.copy(body = b.copy(faded = faded)) } }

    fun rotateBody(deltaYaw: Float) {
        val b = s.body ?: return
        set { it.copy(body = b.copy(pose = b.pose.copy(yaw = b.pose.yaw + deltaYaw), userMoved = true)) }
    }

    /** fit: tap a ghost box = turn the boxes to the next orientation (90° steps, D18) and re-pack in it. */
    fun turnBoxes() {
        if (s.template != TemplateId.FIT || !Evaluate.full(s.slots)) return
        val cur = s.fitPerm ?: s.result.fit?.perm ?: 0
        set { it.copy(fitPerm = (cur + 1) % 6) }
        fx.log("FIT turn perm=${s.fitPerm}")
        recompute()
    }

    // ------------------------------------------------------------------ save / pin (T9)

    fun saveCard() {
        if (!s.result.ok) { hint(Hint.NOT_FULL); return }
        if (s.cards.size >= MAX_CARDS) { hint(Hint.CARD_CAP); return }
        val id = env.now()
        val b = s.body
        val card = Card(id, s.template ?: TemplateId.FREE, s.round, s.customName, s.slots, s.tokens, b?.pose, b?.size, false, null, null, id, s.resultUnit)
        val cards = listOf(card) + s.cards
        set { it.copy(cards = cards, savedPreview = true, savedCardId = id, dirty = false) }
        fx.save(cards, s.customs)
        hint(Hint.SAVED)
        fx.log("CARD saved id=$id")
    }

    fun closeSaved() = set { it.copy(savedPreview = false, naming = false) }

    fun pinHere() {
        val id = s.savedCardId ?: return
        pinCard(id)
        set { it.copy(savedPreview = false) }
    }

    fun pinCard(id: Long) {
        val card = s.cards.firstOrNull { it.id == id } ?: return
        if (s.cards.count { it.pinned } >= MAX_PINNED) { hint(Hint.CARD_CAP); return }
        val pose = card.pose ?: s.body?.pose ?: return
        if (s.noPermission) { onAnchor(id, null); return }
        fx.createAnchor(id, pose.position)
    }

    /** The anchor came back (or failed: S24, the card is kept unpinned and marked). */
    fun onAnchor(cardId: Long, anchorId: String?) {
        val cards = s.cards.map { if (it.id == cardId) it.copy(pinned = anchorId != null, anchorId = anchorId, anchorOffset = Vec3.ZERO, elsewhere = false) else it }
        set { it.copy(cards = cards) }
        fx.save(cards, s.customs)
        if (anchorId == null) hint(Hint.PIN_FAILED)
        else if (s.savedCardId == cardId && s.body != null) set { it.copy(body = null, tapes = emptyList()) } // pinned: the pinned copy stands there now
        fx.log("ANCHOR card=$cardId id=$anchorId")
    }

    fun unpinCard(id: Long) {
        val c = s.cards.firstOrNull { it.id == id } ?: return
        c.anchorId?.let { fx.removeAnchor(it) }
        val cards = s.cards.map { if (it.id == id) it.copy(pinned = false, anchorId = null, elsewhere = false) else it }
        set { it.copy(cards = cards) }
        fx.save(cards, s.customs)
        fx.log("ANCHOR unpin card=$id")
    }

    /** Restored pinned card pose from its anchor (S20): no push, no cone check. */
    fun restoredPose(id: Long, pose: BodyPose, elsewhere: Boolean) {
        set { st -> st.copy(cards = st.cards.map { if (it.id == id) it.copy(pose = pose, elsewhere = elsewhere) else it }, restored = st.restored + 1) }
        fx.log("RESTORE card=$id pos=${pose.position} elsewhere=$elsewhere")
    }

    fun startNaming() = set { it.copy(naming = true) }

    /** K7 (QA3): the naming row's [取消]: nothing is saved, back to where it was opened from. */
    fun cancelNaming() = set { it.copy(naming = false) }

    /** [存成模板]: from the active card (S12) or a record (S13). */
    fun saveAsTemplate(name: String, fromCard: Long? = null) {
        if (s.customs.size >= MAX_CUSTOMS) { hint(Hint.CARD_CAP); return }
        val src = fromCard?.let { id -> s.cards.firstOrNull { it.id == id } }
        val template = src?.template ?: s.template ?: return
        val round = src?.round ?: s.round
        val slots = src?.slots ?: s.slots
        val tokens = src?.tokens ?: s.tokens
        val base = if (template == TemplateId.CUSTOM) TemplateId.FREE else template
        val c = if (tokens.isNotEmpty()) {
            // measured numbers stay as measure placeholders (value dropped), typed numbers keep their value
            CustomTemplate(env.now(), name.ifBlank { "#${s.customs.size + 1}" }, TemplateId.FREE, false, emptyList(), tokens.map { t -> if (t is Token.Num && t.source == Source.TAPE) t.copy(q = t.q.copy(tol = 0.0), tapeId = null, source = Source.TYPED) else t })
        } else {
            CustomTemplate(env.now(), name.ifBlank { "#${s.customs.size + 1}" }, base, round, slots.map { v -> if (v.def.kind == SlotKind.NUMBER && v.typed != null) v.def.copy(default = v.typed) else v.def }, emptyList())
        }
        val customs = s.customs + c
        set { it.copy(customs = customs, naming = false, savedPreview = false) }
        fx.save(s.cards, customs)
        hint(Hint.TEMPLATE_SAVED, c.name)
        fx.log("TEMPLATE saved ${c.name} base=${c.base}")
    }

    /** [下一个] / after clearing: back to the job grid; the active card's body, pins and tapes go (pinned cards stay). */
    fun next() {
        clearActiveTapes()
        set { it.copy(phase = Phase.PICK, template = null, slots = emptyList(), tokens = emptyList(), body = null, savedPreview = false, naming = false, confirm = null, firstPin = null, result = CalcResult(error = CalcError.EMPTY), dirty = false, fitBox = null, fitCell = null, hint = Hint.NONE) }
    }

    private fun clearActiveTapes() = set { it.copy(tapes = emptyList(), firstPin = null) }

    /** Toolbar [清空] (T11): asks first when there is something unsaved. */
    fun clearAsk() { if (s.dirty || s.body != null && s.savedCardId == null) set { it.copy(confirm = Confirm.CLEAR) } else next() }

    fun confirmYes() {
        when (s.confirm) {
            Confirm.CLEAR -> next()
            Confirm.DELETE_CARD -> s.confirmCardId?.let { deleteCard(it) }
            else -> Unit
        }
        set { it.copy(confirm = null, confirmCardId = null) }
    }

    fun confirmNo() = set { it.copy(confirm = null, confirmCardId = null) }

    fun exitAsk() = set { it.copy(confirm = Confirm.EXIT, page = Page.SETTINGS, settingsMore = false) }

    // ------------------------------------------------------------------ records (S13)

    fun expand(id: Long?) = set { it.copy(expandedCard = if (id == null || it.expandedCard == id) null else id) }

    /** K8 (QA3): records show one card at a time; the「2 / 7 ›」row steps to the next (wraps). */
    fun recordStep(delta: Int = 1) {
        if (s.cards.isEmpty()) return
        val cur = s.cards.indexOfFirst { it.id == s.expandedCard }.coerceAtLeast(0)
        val n = s.cards.size
        val next = s.cards[((cur + delta) % n + n) % n].id
        set { it.copy(expandedCard = next) }
        fx.log("RECORDS show ${s.cards.indexOfFirst { it.id == next } + 1}/$n")
    }

    fun deleteAsk(id: Long) = set { it.copy(confirm = Confirm.DELETE_CARD, confirmCardId = id) }

    fun deleteCard(id: Long) {
        val c = s.cards.firstOrNull { it.id == id } ?: return
        c.anchorId?.let { fx.removeAnchor(it) }
        val cards = s.cards.filter { it.id != id }
        set { it.copy(cards = cards, expandedCard = null) }
        fx.save(cards, s.customs)
        fx.log("CARD delete id=$id")
    }

    /** [重放实体]: load the card back as the active one; its body regrows where it was (no push, R2-5). */
    fun replay(id: Long) {
        val c = s.cards.firstOrNull { it.id == id } ?: return
        clearActiveTapes()
        val slots = c.slots
        set {
            it.copy(
                phase = Phase.FORMULA, page = Page.CALC, template = c.template, round = c.round, customName = c.customName, slots = slots, tokens = c.tokens,
                active = 0, body = null, savedCardId = c.id, replayCardId = c.id, fitCell = null, resultUnit = c.resultUnit, dirty = false, savedPreview = false,
            )
        }
        recompute()
        val spec = s.result.body
        if (spec != null && c.pose != null && s.result.ok) {
            set { it.copy(body = BodyState(c.pose, sizeOf(spec, 0), env.now(), userMoved = true)) }
            fx.sound(Sfx.THUD)
        } else if (spec != null) equals()
        fx.log("CARD replay id=$id")
    }

    /**
     * Records [带到面前] (q1-3, D43): the card's body + plate move 1.2 m ahead, visibly, right now. Pinned: the pinned
     * body moves and is re-anchored there. Showing as the active card: the active body moves. Otherwise the card is
     * loaded as the active one and grows there (the records page stays open), with a hint saying where it went.
     */
    fun bringCardToFront(id: Long, sample: Pair<Vec3, Vec3>) {
        val c = s.cards.firstOrNull { it.id == id } ?: run { set { it.copy(headSampling = false) }; return }
        val size = c.size ?: (if (c.tokens.isNotEmpty()) Evaluate.free(c.tokens) else Evaluate.template(c.template, c.round, c.slots)).body?.let { sizeOf(it, 0) }
        if (size == null) { set { it.copy(headSampling = false) }; return }
        val r = PlaceBody.bringToFront(sample.first, sample.second, env.window(), env.floorY() + _settings.value.tableHeight, size, obstacles(id))
        val cards = s.cards.map { if (it.id == id) it.copy(pose = r.pose, size = size, elsewhere = false) else it }
        set { it.copy(cards = cards, headSampling = false) }
        fx.save(cards, s.customs)
        val page = s.page
        when {
            c.pinned -> { c.anchorId?.let { fx.removeAnchor(it) }; fx.createAnchor(id, r.pose.position) }
            s.body != null && (s.savedCardId == id || s.replayCardId == id) -> set { it.copy(body = it.body!!.copy(pose = r.pose, userMoved = false, attached = false)) }
            else -> { replay(id); set { it.copy(page = page) } }
        }
        fx.sound(Sfx.THUD)
        hint(Hint.BROUGHT)
        fx.log("CARD front id=$id pos=${r.pose.position} pinned=${c.pinned} ${r.outcome} ${r.log}")
    }

    // ------------------------------------------------------------------ fit box (S26, T3)

    fun openFitBox(at: PinPoint) {
        val head = env.head()?.first ?: Vec3(0f, 1.6f, 0f)
        val base = if (at.surface.horizontal) at.point.y else env.floorY() + _settings.value.tableHeight
        // q1-4 (D41 / D8): never under / behind the window and its Toolbar: swing it out to the side about the head
        val pos = PlaceBody.besideWindow(head, env.window(), Vec3(at.point.x, base, at.point.z))
        set { it.copy(phase = Phase.FITBOX, fitBox = FitBoxState(BodyPose(pos, yawFacing(pos, head)), Vec3(0.3f, 0.3f, 0.3f), at.channel), page = Page.CALC) }
        fx.log("FITBOX open at=$pos")
    }

    fun fitBoxButton(at: PinPoint?) {
        if (s.template == null) pick(TemplateId.FREE)
        val p = at ?: run {
            val (h, f) = env.head() ?: (Vec3(0f, 1.6f, 0f) to Vec3(0f, 0f, -1f))
            // no ray hit: 1.0 m out, to the right of the window (besideWindow in openFitBox keeps it clear)
            val w = env.window()
            val dir = if (w != null) io.embodit.spatialcalc.domain.place.flatDir(h, w) else Vec3(f.x, 0f, f.z).normalized()
            val ang = Math.toRadians(PlaceBody.FIT_SIDE_DEG.toDouble())
            val right = Vec3((dir.x * kotlin.math.cos(ang) - dir.z * kotlin.math.sin(ang)).toFloat(), 0f, (dir.x * kotlin.math.sin(ang) + dir.z * kotlin.math.cos(ang)).toFloat())
            val ahead = h + right * 1.0f
            PinPoint(Vec3(ahead.x, env.floorY() + _settings.value.tableHeight, ahead.z), Vec3.UP, SurfaceKind.VIRTUAL, 1.0f, Channel.GAZE)
        }
        openFitBox(p)
    }

    /** Face handle drag (each handle moves only along its own normal). [axis] 0 x / 1 y / 2 z, [sign] ±1, [d] metres along the normal. */
    fun fitBoxFace(axis: Int, sign: Int, d: Float) {
        val fb = s.fitBox ?: return
        val sz = fb.size
        val nv = when (axis) { 0 -> sz.x; 1 -> sz.y; else -> sz.z } + d
        if (nv < 0.01f) { hint(Hint.BOX_THIN); return }
        val newSize = when (axis) { 0 -> Vec3(nv, sz.y, sz.z); 1 -> Vec3(sz.x, nv, sz.z); else -> Vec3(sz.x, sz.y, nv) }
        // the opposite face stays where it is: the centre moves half the change along the handle's normal
        val c = kotlin.math.cos(fb.pose.yaw); val sn = kotlin.math.sin(fb.pose.yaw)
        val shift = when (axis) {
            0 -> Vec3(c, 0f, -sn) * (d / 2f * sign)
            2 -> Vec3(sn, 0f, c) * (d / 2f * sign)
            else -> Vec3.ZERO
        }
        set { it.copy(fitBox = fb.copy(size = newSize, pose = fb.pose.copy(position = fb.pose.position + shift))) }
    }

    fun fitBoxRotate(dYaw: Float) { val fb = s.fitBox ?: return; set { it.copy(fitBox = fb.copy(pose = fb.pose.copy(yaw = fb.pose.yaw + dYaw))) } }

    fun fitBoxCancel() { if (s.phase == Phase.FITBOX) set { it.copy(phase = Phase.FORMULA, fitBox = null) } }

    /** [用这三边]: three measured chips go into the first three measure slots (or three chips in a free formula). */
    fun fitBoxUse() {
        val fb = s.fitBox ?: return
        val tolMm = Tolerance.roundUp(fb.channel.endSigmaMm(1.0f) * 1.414)
        val tol = tolMm / 1000.0
        val sides = listOf(fb.size.x.toDouble(), fb.size.z.toDouble(), fb.size.y.toDouble()) // width, depth, height
        if (s.tokensMode || s.template == null) {
            if (s.template == null) pick(TemplateId.FREE)
            var toks = s.tokens
            sides.forEachIndexed { k, m -> if (k > 0 || toks.isNotEmpty()) toks = toks + Token.Op("×"); toks = toks + Token.Num(Q.length(m, tol), Source.FITBOX) }
            set { it.copy(tokens = toks.take(MAX_CHIPS), phase = Phase.FORMULA, fitBox = null, dirty = true, chipFlight = env.now()) }
        } else {
            val slots = s.slots.toMutableList()
            val targets = slots.indices.filter { slots[it].def.kind != SlotKind.NUMBER }.take(3)
            val order = if (s.template == TemplateId.TANK) listOf(sides[0], sides[1], sides[2]) else sides
            targets.forEachIndexed { k, i -> slots[i] = SlotValue(slots[i].def, Q.length(order[k], tol), Source.FITBOX, null, null) }
            set { it.copy(slots = slots, active = firstEmpty(slots), phase = Phase.FORMULA, fitBox = null, dirty = true, chipFlight = env.now(), fitCell = if (s.template == TemplateId.FIT) fb else it.fitCell) }
            // the box stands where the object was measured: its pins become the "object" for placement
            val p = fb.pose.position
            val half = Vec3(fb.size.x, 0f, fb.size.z) * 0.5f
            val pin = { v: Vec3 -> PinPoint(v, Vec3.UP, SurfaceKind.TABLE, 1f, fb.channel) }
            val a = pin(p - half); val b = pin(p + half + Vec3(0f, fb.size.y, 0f))
            set { it.copy(tapes = it.tapes + TapeRec(++tapeSeq, a, b, 0.0, tol, -1)) }
        }
        fx.sound(Sfx.CHIP)
        fx.log("FITBOX use ${sides.joinToString { "%.3f".format(it) }} tol_mm=$tolMm")
        recompute()
    }

    // ------------------------------------------------------------------ debug

    fun debugError(kind: String) {
        val h = when (kind) {
            "paste" -> Hint.PASTE_BAD; "cardCap" -> Hint.CARD_CAP; "chipCap" -> Hint.CHIP_CAP; "pinsClose" -> Hint.PINS_CLOSE
            "dim" -> Hint.BAD_DIM; "boxThin" -> Hint.BOX_THIN; "unitMismatch" -> Hint.UNIT_MISMATCH; else -> Hint.NONE
        }
        hint(h, if (h == Hint.UNIT_MISMATCH) "m² + m" else "")
    }

    /** Fills slot [i] with [m] metres as if typed (debug `chip i set`). */
    fun setSlot(i: Int, value: Double) {
        if (s.tokensMode) return
        set { it.copy(active = i) }
        set { it.copy(typingBuffer = io.embodit.spatialcalc.domain.calc.Fmt.plain(value)) }
        val prev = s.phase
        set { it.copy(phase = Phase.TYPING) }
        commitBuffer()
        set { it.copy(phase = if (prev == Phase.TYPING) Phase.TYPING else Phase.FORMULA) }
    }

    fun dump(): String = with(s) {
        "state=$stateId phase=$phase page=$page template=$template round=$round active=$active slots=[${slots.joinToString { "${it.def.key}=${it.q?.v?.let { v -> "%.4f".format(v) }}±${it.q?.tol?.let { t -> "%.4f".format(t) }}/${it.source}" }}] " +
            "tokens=${tokens.size} result=${result.q} count=${result.count} err=${result.error} body=${body?.let { "pos=${it.pose.position} yaw=${"%.3f".format(it.pose.yaw)} size=${it.size} moved=${it.userMoved} faded=${it.faded}" }} " +
            "tapes=${tapes.size} cards=${cards.size} pinned=${cards.count { it.pinned }} customs=${customs.size} hint=$hint confirm=$confirm noPlanes=$noPlanes"
    }

    companion object {
        const val MAX_CHIPS = 12
        const val MAX_CARDS = 200
        const val MAX_PINNED = 10
        const val MAX_CUSTOMS = 12
        /** 吸附已有钉: radius of the snap to an existing pin. */
        const val SNAP_M = 0.03f
    }
}
