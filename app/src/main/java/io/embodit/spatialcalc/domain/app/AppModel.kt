package io.embodit.spatialcalc.domain.app

import io.embodit.spatialcalc.domain.calc.CalcState
import io.embodit.spatialcalc.domain.calc.Calculator
import io.embodit.spatialcalc.domain.calc.Fmt
import io.embodit.spatialcalc.domain.calc.Key
import io.embodit.spatialcalc.domain.calc.LenUnit
import io.embodit.spatialcalc.domain.calc.LineProblem
import io.embodit.spatialcalc.domain.calc.Ruler
import io.embodit.spatialcalc.domain.calc.Op
import io.embodit.spatialcalc.domain.calc.Tok
import io.embodit.spatialcalc.domain.calc.Units
import io.embodit.spatialcalc.domain.place.Axis
import io.embodit.spatialcalc.domain.place.GaugeKind
import io.embodit.spatialcalc.domain.place.GaugeMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal

enum class Page { CALC, HISTORY, SETTINGS, HOWTO }
enum class Language { SYSTEM, ZH, EN }
enum class Sfx { KEY, EQUALS, TAPE_OUT, TAPE_IN, TICK }

/** One history row: the finished expression, the result as a plain decimal string, its unit. */
data class HistoryItem(val expr: String, val result: String, val unit: LenUnit, val at: Long) {
    val value: BigDecimal get() = BigDecimal(result)
}

/**
 * Saved settings (design dataModel.settings). volume: 0 / 1 / 2 = 0 / 50 / 100 %. Default 100 % (deviation: the short
 * key clicks measure about −24.8 dBFS voiced RMS at 50 %, under the B20 loudness floor of −20 dBFS).
 */
data class Settings(
    val volume: Int = 2,
    val muted: Boolean = false,
    val language: Language = Language.SYSTEM,
    val unit: LenUnit = LenUnit.NONE,
    val firstHintShown: Boolean = false,
)

/**
 * The gauge [摆出来] puts in the air (not saved): the 1:1 length line ([kind] LINE, [lengthM]) or a 1:1 volume
 * ([kind] BOX, edges [dimsM] in metres, [volumeM3]). Angles in degrees (GaugeMath): [yaw] about the vertical,
 * [pitch] about the gauge's X (box only), [roll] about its Z (the line's horizontal axis: 90 = standing).
 * [fromSides] = the box came from an a × b × c length result (its label shows the three sides).
 */
data class LineState(
    val on: Boolean = false,
    val kind: GaugeKind = GaugeKind.LINE,
    val lengthM: Double = 0.0,
    val dimsM: List<Double> = emptyList(),
    val volumeM3: Double = 0.0,
    val sides: List<BigDecimal> = emptyList(),
    val unit: LenUnit = LenUnit.NONE,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    /** Bumped every time the gauge is (re)opened: the Stage puts it back at the default spot beside the window. */
    val serial: Int = 0,
    /** Bumped by [回正]: the Stage turns it to face the user from where it is now. */
    val resetSerial: Int = 0,
) {
    val isBox: Boolean get() = kind == GaugeKind.BOX
    val standing: Boolean get() = !isBox && kotlin.math.abs(roll) >= 89.5f
    val turned: Boolean get() = yaw != 0f || pitch != 0f || roll != 0f
    val fromSides: Boolean get() = sides.size == 3
    val clipped: Boolean get() = if (isBox) dimsM.any { it > GaugeMath.MAX_EDGE_M + 1e-9 } else lengthM > Ruler.MAX_M
}

/** What [摆出来] would draw for the result on screen, or why it cannot. */
data class GaugeWant(val kind: GaugeKind, val lengthM: Double = 0.0, val dimsM: List<Double> = emptyList(), val volumeM3: Double = 0.0, val sides: List<BigDecimal> = emptyList(), val problem: LineProblem? = null)

/**
 * Everything the window and the Stage draw. [convertedFrom] = the result as it was computed before a unit switch
 * (the small「= 360 cm」line); [lineProblem] = why 📏 cannot draw (grey key, small text under the result).
 */
data class AppState(
    val page: Page = Page.CALC,
    val calc: CalcState = CalcState(),
    val unit: LenUnit = LenUnit.NONE,
    val origin: Pair<BigDecimal, LenUnit>? = null,
    val history: List<HistoryItem> = emptyList(),
    val line: LineState = LineState(),
    val lineProblem: LineProblem? = null,
    val clearAsk: Boolean = false,
    val exitAsk: Boolean = false,
    val showFirstHint: Boolean = false,
    /** a × b × c typed with a length unit: the three sides (as typed) and their unit, so [摆出来] makes that box. */
    val sides: List<BigDecimal>? = null,
    val sidesUnit: LenUnit = LenUnit.NONE,
) {
    /** The result on screen in metres, when there is one with a length unit. */
    val resultMeters: Double? get() = calc.result?.takeIf { calc.evaluated && calc.error == null && unit.isLength }?.let { Units.meters(it, unit) }

    /** The result on screen in m³, when there is one with a volume unit. */
    val resultCubicMeters: Double? get() = calc.result?.takeIf { calc.evaluated && calc.error == null && unit.isVolume }?.let { Units.cubicMeters(it, unit) }

    /** What [摆出来] makes of the result on screen: a volume unit → a cube; a × b × c in a length → that box; a length → the line. */
    val gaugeWant: GaugeWant? get() {
        resultCubicMeters?.let { v ->
            if (v < 0) return GaugeWant(GaugeKind.BOX, problem = LineProblem.NEGATIVE)
            val e = GaugeMath.cubeEdge(v)
            if (e < GaugeMath.MIN_EDGE_M - 1e-12) return GaugeWant(GaugeKind.BOX, problem = LineProblem.TOO_SHORT)
            return GaugeWant(GaugeKind.BOX, dimsM = listOf(e, e, e), volumeM3 = v)
        }
        val m = resultMeters ?: return null
        val sd = sides
        if (sd != null && sidesUnit.isLength) {
            val dims = sd.map { Units.meters(it, sidesUnit) ?: 0.0 }
            if (dims.any { it < 0 }) return GaugeWant(GaugeKind.BOX, problem = LineProblem.NEGATIVE)
            if (dims.any { it < GaugeMath.MIN_EDGE_M - 1e-12 }) return GaugeWant(GaugeKind.BOX, problem = LineProblem.TOO_SHORT)
            return GaugeWant(GaugeKind.BOX, dimsM = dims, volumeM3 = dims[0] * dims[1] * dims[2], sides = sd)
        }
        return GaugeWant(GaugeKind.LINE, lengthM = m, problem = Ruler.problem(m))
    }

    /** [摆出来] is on screen: a finished result with a length or volume unit (greyed when it cannot be drawn). */
    val rulerShown: Boolean get() = gaugeWant != null
    val rulerEnabled: Boolean get() = gaugeWant?.let { it.problem == null } == true
    /** The key shows a cube when it would make a volume. */
    val rulerBox: Boolean get() = gaugeWant?.kind == GaugeKind.BOX

    val convertedText: String? get() = origin?.takeIf { it.second != unit && unit.hasUnit }?.let { (v, u) -> "= " + Fmt.number(v) + " " + u.symbol }

    /** Stable id for QA (design states[]). */
    val stateId: String get() = when {
        page == Page.HISTORY -> "S06"
        page == Page.SETTINGS && exitAsk -> "S09"
        page == Page.SETTINGS -> "S07"
        page == Page.HOWTO -> "S08"
        line.on && line.isBox && line.clipped -> "S10b"
        line.on && line.isBox && line.turned -> "S10a"
        line.on && line.isBox -> "S10"
        line.on && line.clipped -> "S05b"
        line.on && line.turned -> "S05a"
        line.on -> "S05"
        calc.error != null -> "S04"
        calc.evaluated -> "S03"
        calc.tokens.isNotEmpty() -> "S02"
        else -> "S01"
    }
}

interface AppEffects {
    fun sound(s: Sfx)
    fun saveSettings(s: Settings)
    fun saveHistory(items: List<HistoryItem>)
    fun log(line: String)
    fun now(): Long
}

/**
 * The one place state changes (window keys, Stage line, controller and the debug broadcast all call these).
 * Main thread only. Pure Kotlin: unit tests drive it with fake effects.
 */
class AppController(private val fx: AppEffects) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    val s: AppState get() = _state.value

    fun start(settings: Settings, history: List<HistoryItem>) {
        _settings.value = settings
        _state.value = AppState(unit = settings.unit, history = history.take(MAX_HISTORY), showFirstHint = !settings.firstHintShown)
        fx.log("STATE start ${s.stateId} unit=${settings.unit} history=${history.size}")
    }

    private fun set(n: AppState, why: String) {
        val old = _state.value
        _state.value = n
        if (old.stateId != n.stateId || why.startsWith("!")) fx.log("STATE ${n.stateId} <- ${why.removePrefix("!")}")
    }

    private fun settings(f: (Settings) -> Settings) {
        val n = f(_settings.value)
        if (n != _settings.value) { _settings.value = n; fx.saveSettings(n) }
    }

    // ------------------------------------------------------------------ keys

    fun press(k: Key) {
        val st = s
        if (st.page != Page.CALC) return
        val calc = Calculator.press(st.calc, k)
        var n = st.copy(calc = calc, lineProblem = null, showFirstHint = false)
        if (st.showFirstHint) settings { it.copy(firstHintShown = true) }
        val newResult = calc.evaluated && calc.error == null && (calc.result != st.calc.result || !st.calc.evaluated)
        if (newResult) {
            n = n.copy(origin = if (n.unit.hasUnit) calc.result!! to n.unit else null)
            if (k == Key.EQ) n = n.copy(history = addHistory(n.history, calc.exprText, calc.result!!, n.unit))
            n = n.copy(sides = if (k == Key.EQ && !st.calc.evaluated) threeSides(st.calc.tokens) else null, sidesUnit = n.unit)
        }
        if (!calc.evaluated) n = n.copy(origin = null, sides = null)
        n = followLine(n)
        fx.sound(if (k == Key.EQ) Sfx.EQUALS else Sfx.KEY)
        fx.log("KEY ${k.label} expr=\"${if (calc.evaluated) calc.exprText else Fmt.tokens(calc.tokens)}\" result=${calc.result?.let(Calculator::plain)} err=${calc.error}")
        set(n, "key ${k.label}")
    }

    private fun addHistory(h: List<HistoryItem>, expr: String, r: BigDecimal, unit: LenUnit): List<HistoryItem> {
        val list = (listOf(HistoryItem(expr, Calculator.plain(r), unit, fx.now())) + h).take(MAX_HISTORY)
        fx.saveHistory(list)
        return list
    }

    /** a × b × c (three plain numbers multiplied): the sides of a box. */
    private fun threeSides(t: List<Tok>): List<BigDecimal>? {
        if (t.size != 5) return null
        val nums = listOf(t[0], t[2], t[4]).map { (it as? Tok.Num)?.takeIf { n -> !n.pct && n.text != "-" } ?: return null }
        if ((t[1] as? Tok.Opr)?.op != Op.MUL || (t[3] as? Tok.Opr)?.op != Op.MUL) return null
        return nums.map { it.value }
    }

    /**
     * The gauge follows the result while it is out (design follow): new size, a line becomes a box (or back) when the
     * unit says so, or it closes when it cannot draw. Typing a new number: it keeps the last result until =.
     */
    private fun followLine(n: AppState): AppState {
        if (!n.line.on) return n
        val w = n.gaugeWant ?: return n
        if (w.problem != null) return closeGauge(n, w.problem)
        val l = n.line
        if (w.kind != l.kind) {
            fx.log("GAUGE ${l.kind} -> ${w.kind}")
            return n.copy(line = gaugeOf(w, n.unit, l.serial + 1, l.resetSerial))
        }
        if (w.lengthM == l.lengthM && w.dimsM == l.dimsM && n.unit == l.unit && w.sides == l.sides) return n
        fx.log("GAUGE size ${l.kind} len ${l.lengthM} -> ${w.lengthM} dims ${l.dimsM} -> ${w.dimsM} unit=${n.unit}")
        return n.copy(line = l.copy(lengthM = w.lengthM, dimsM = w.dimsM, volumeM3 = w.volumeM3, sides = w.sides, unit = n.unit))
    }

    private fun closeGauge(n: AppState, p: LineProblem?): AppState {
        fx.sound(Sfx.TAPE_IN)
        fx.log("GAUGE closed problem=$p")
        return n.copy(line = n.line.copy(on = false), lineProblem = p)
    }

    private fun gaugeOf(w: GaugeWant, unit: LenUnit, serial: Int, resetSerial: Int) = LineState(
        on = true, kind = w.kind, lengthM = w.lengthM, dimsM = w.dimsM, volumeM3 = w.volumeM3, sides = w.sides, unit = unit,
        serial = serial, resetSerial = resetSerial,
    )

    // ------------------------------------------------------------------ unit row

    fun unit(u: LenUnit) {
        val st = s
        if (u == st.unit) return
        settings { it.copy(unit = u) }
        var n = st.copy(unit = u, lineProblem = null)
        val r = st.calc.result
        if (st.calc.evaluated && st.calc.error == null && r != null) {
            val origin = st.origin
            n = when {
                !u.hasUnit -> n.copy(origin = null)
                origin == null || origin.second.dim != u.dim -> n.copy(origin = r to u)
                else -> n.copy(calc = st.calc.copy(result = Units.convert(origin.first, origin.second, u), repeat = null))
            }
            // the sides of a × b × c were typed in no unit (or a volume): they take the new length unit, like the result
            if (n.sides != null && u.isLength && !st.sidesUnit.isLength) n = n.copy(sidesUnit = u)
        }
        if (!u.hasUnit && n.line.on) { fx.sound(Sfx.TAPE_IN); n = n.copy(line = n.line.copy(on = false)) }
        n = followLine(n)
        fx.sound(Sfx.KEY)
        fx.log("UNIT ${st.unit} -> $u result=${n.calc.result?.let(Calculator::plain)} conv=${n.convertedText}")
        set(n, "!unit $u")
    }

    // ------------------------------------------------------------------ 📏 and the line

    /**
     * [摆出来] (was 📏): out → in; in → out at the default spot, facing the user. The result's unit decides what comes
     * out: a length → the 1:1 line; a volume → a 1:1 cube (edge = ∛volume); a × b × c in a length → that box.
     */
    fun ruler() {
        val st = s
        if (st.line.on) {
            fx.sound(Sfx.TAPE_IN)
            fx.log("GAUGE off ${st.line.kind}")
            set(st.copy(line = st.line.copy(on = false)), "!ruler off")
            return
        }
        val w = st.gaugeWant ?: run { fx.log("GAUGE nothing to place (unit=${st.unit} evaluated=${st.calc.evaluated})"); return }
        if (w.problem != null) { fx.log("GAUGE refused ${w.kind} ${w.problem}"); set(st.copy(lineProblem = w.problem), "!ruler refused"); return }
        fx.sound(Sfx.TAPE_OUT)
        fx.log("GAUGE on ${w.kind} len=${w.lengthM} dims=${w.dimsM} vol=${w.volumeM3} unit=${st.unit}")
        set(st.copy(line = gaugeOf(w, st.unit, st.line.serial + 1, st.line.resetSerial), lineProblem = null), "!ruler on")
    }

    /**
     * One notch (±15°) about [axis] — the [−15°] [+15°] keys beside the gauge, grip + stick, and the debug command all
     * come here. Box: X / Y / Z. Line: Y = its vertical axis, Z (or X) = its horizontal axis (lay ↔ slant ↔ stand).
     */
    fun gaugeTurn(axis: Axis, dir: Int) {
        val st = s
        val l = st.line
        if (!l.on || dir == 0) return
        val n = when {
            axis == Axis.Y -> l.copy(yaw = GaugeMath.step(l.yaw, dir))
            !l.isBox -> l.copy(roll = GaugeMath.tiltStep(l.roll, dir))
            axis == Axis.X -> l.copy(pitch = GaugeMath.step(l.pitch, dir))
            else -> l.copy(roll = GaugeMath.step(l.roll, dir))
        }
        if (n == l) return
        fx.sound(Sfx.TICK)
        set(st.copy(line = n), "!gauge turn $axis ${if (dir > 0) "+" else "-"} → yaw=${n.yaw} pitch=${n.pitch} roll=${n.roll}")
    }

    /** Two-hand twist (free angle while held): [deltaDeg] about [axis] since the last call. */
    fun gaugeTwist(axis: Axis, deltaDeg: Float) {
        val st = s
        val l = st.line
        if (!l.on || deltaDeg == 0f || !deltaDeg.isFinite()) return
        val n = when {
            axis == Axis.Y -> l.copy(yaw = GaugeMath.wrap(l.yaw + deltaDeg))
            !l.isBox -> l.copy(roll = (l.roll + deltaDeg).coerceIn(0f, 90f))
            axis == Axis.X -> l.copy(pitch = GaugeMath.wrap(l.pitch + deltaDeg))
            else -> l.copy(roll = GaugeMath.wrap(l.roll + deltaDeg))
        }
        set(st.copy(line = n), "gauge twist")
    }

    /** Two-hand twist released: every axis snaps to 0 / 45 / 90 / … when within 10°. */
    fun gaugeTwistEnd() {
        val st = s
        val l = st.line
        if (!l.on) return
        val n = l.copy(yaw = GaugeMath.snap(l.yaw), pitch = GaugeMath.snap(l.pitch), roll = GaugeMath.snap(l.roll).let { if (l.isBox) it else it.coerceIn(0f, 90f) })
        fx.sound(Sfx.TICK)
        set(st.copy(line = n), "!gauge twist end yaw=${n.yaw} pitch=${n.pitch} roll=${n.roll}")
    }

    /** [回正]: all three axes back to 0 and the Stage turns it to face the user from where it is (D10 补). */
    fun gaugeReset() {
        val st = s
        val l = st.line
        if (!l.on) return
        fx.sound(Sfx.TICK)
        set(st.copy(line = l.copy(yaw = 0f, pitch = 0f, roll = 0f, resetSerial = l.resetSerial + 1)), "!gauge reset")
    }

    /** The old 📏 commands (debug `ruler turn` / `stand` / `lay`). */
    fun lineTurn(dir: Int) = gaugeTurn(Axis.Y, dir)

    fun lineStand(standing: Boolean) {
        val st = s
        if (!st.line.on || st.line.isBox || st.line.standing == standing) return
        fx.sound(Sfx.TICK)
        set(st.copy(line = st.line.copy(roll = if (standing) 90f else 0f)), "!line ${if (standing) "stand" else "lay"}")
    }

    // ------------------------------------------------------------------ pages

    fun page(p: Page) {
        val st = s
        if (st.page == p) return
        fx.sound(Sfx.KEY)
        set(st.copy(page = p, clearAsk = false, exitAsk = false), "page $p")
    }

    /** History row → back to the calculator with that result (and its unit), ready to keep going (T7). */
    fun recall(index: Int) {
        val st = s
        val item = st.history.getOrNull(index) ?: return
        settings { it.copy(unit = item.unit) }
        val calc = CalcState(evaluated = true, result = item.value, exprText = item.expr)
        var n = st.copy(page = Page.CALC, calc = calc, unit = item.unit, origin = if (item.unit.hasUnit) item.value to item.unit else null, lineProblem = null, sides = null)
        n = followLine(n)
        fx.sound(Sfx.KEY)
        fx.log("HISTORY recall $index ${item.expr} = ${item.result} ${item.unit}")
        set(n, "!recall")
    }

    fun clearHistoryAsk(ask: Boolean) { set(s.copy(clearAsk = ask), "!clear ask $ask") }

    fun clearHistory() {
        fx.saveHistory(emptyList())
        fx.log("HISTORY cleared")
        set(s.copy(history = emptyList(), clearAsk = false), "!history cleared")
    }

    fun exitAsk(ask: Boolean) { set(s.copy(exitAsk = ask, page = if (ask) Page.SETTINGS else s.page), "!exit ask $ask") }

    // ------------------------------------------------------------------ settings

    fun volume(level: Int) = settings { it.copy(volume = level.coerceIn(0, 2)) }.also { fx.log("SETTINGS volume=$level") }
    fun mute(m: Boolean) = settings { it.copy(muted = m) }.also { fx.log("SETTINGS muted=$m") }
    fun language(l: Language) = settings { it.copy(language = l) }.also { fx.log("SETTINGS language=$l") }

    /** debug `seedHistory n`: n plausible rows (newest first), as if typed. */
    fun seedHistory(n: Int) {
        val rows = listOf(
            "12 × 3 + 4" to ("40" to LenUnit.NONE), "60 + 45 + 60" to ("165" to LenUnit.CM), "1.2 × 3" to ("3.6" to LenUnit.M),
            "2,400 ÷ 6" to ("400" to LenUnit.MM), "200 + 10%" to ("220" to LenUnit.NONE), "36 − 4.5" to ("31.5" to LenUnit.IN),
            "8 × 25%" to ("2" to LenUnit.NONE), "0.1 + 0.2" to ("0.3" to LenUnit.NONE),
        )
        val now = fx.now()
        val items = (0 until n.coerceIn(0, MAX_HISTORY)).map { i -> val (e, r) = rows[i % rows.size]; HistoryItem(e, r.first, r.second, now - i * 60_000L) }
        fx.saveHistory(items)
        set(s.copy(history = items), "!seed $n")
    }

    companion object {
        const val MAX_HISTORY = 20
        const val HISTORY_SHOWN = 6
    }
}
