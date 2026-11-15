package io.embodit.spatialcalc.domain.app

import io.embodit.spatialcalc.domain.calc.CalcState
import io.embodit.spatialcalc.domain.calc.Calculator
import io.embodit.spatialcalc.domain.calc.Fmt
import io.embodit.spatialcalc.domain.calc.Key
import io.embodit.spatialcalc.domain.calc.LenUnit
import io.embodit.spatialcalc.domain.calc.LineProblem
import io.embodit.spatialcalc.domain.calc.Ruler
import io.embodit.spatialcalc.domain.calc.Units
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

/** The 1:1 length line (not saved). [yawSteps] × 15° about its own vertical axis; [standing] = vertical. */
data class LineState(
    val on: Boolean = false,
    val lengthM: Double = 0.0,
    val unit: LenUnit = LenUnit.NONE,
    val yawSteps: Int = 0,
    val standing: Boolean = false,
    /** Bumped every time the line is (re)opened: the Stage puts it back at the default spot beside the window. */
    val serial: Int = 0,
)

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
) {
    /** The result on screen in metres, when there is one with a length unit. */
    val resultMeters: Double? get() = calc.result?.takeIf { calc.evaluated && calc.error == null && unit.isLength }?.let { Units.meters(it, unit) }

    /** 📏 is on screen: a finished result with a length unit (greyed when it cannot be drawn). */
    val rulerShown: Boolean get() = resultMeters != null
    val rulerEnabled: Boolean get() = resultMeters?.let { Ruler.problem(it) == null } == true

    val convertedText: String? get() = origin?.takeIf { it.second != unit && unit.isLength }?.let { (v, u) -> "= " + Fmt.number(v) + " " + u.symbol }

    /** Stable id for QA (design states[]). */
    val stateId: String get() = when {
        page == Page.HISTORY -> "S06"
        page == Page.SETTINGS && exitAsk -> "S09"
        page == Page.SETTINGS -> "S07"
        page == Page.HOWTO -> "S08"
        line.on && line.lengthM > Ruler.MAX_M -> "S05b"
        line.on && (line.standing || line.yawSteps != 0) -> "S05a"
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
            n = n.copy(origin = if (n.unit.isLength) calc.result!! to n.unit else null)
            if (k == Key.EQ) n = n.copy(history = addHistory(n.history, calc.exprText, calc.result!!, n.unit))
        }
        if (!calc.evaluated) n = n.copy(origin = null)
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

    /** The line follows the result while it is out (design follow): new length, or it closes when it cannot draw. */
    private fun followLine(n: AppState): AppState {
        if (!n.line.on) return n
        val m = n.resultMeters ?: return n // typing a new number: the line keeps the last result until =
        val p = Ruler.problem(m)
        if (p != null) { fx.sound(Sfx.TAPE_IN); return n.copy(line = n.line.copy(on = false), lineProblem = p) }
        if (m == n.line.lengthM && n.unit == n.line.unit) return n
        fx.log("LINE length ${n.line.lengthM} -> $m unit=${n.unit}")
        return n.copy(line = n.line.copy(lengthM = m, unit = n.unit))
    }

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
                !u.isLength -> n.copy(origin = null)
                origin == null || !origin.second.isLength -> n.copy(origin = r to u)
                else -> n.copy(calc = st.calc.copy(result = Units.convert(origin.first, origin.second, u), repeat = null))
            }
        }
        if (!u.isLength && n.line.on) { fx.sound(Sfx.TAPE_IN); n = n.copy(line = n.line.copy(on = false)) }
        n = followLine(n)
        fx.sound(Sfx.KEY)
        fx.log("UNIT ${st.unit} -> $u result=${n.calc.result?.let(Calculator::plain)} conv=${n.convertedText}")
        set(n, "!unit $u")
    }

    // ------------------------------------------------------------------ 📏 and the line

    /** 📏: out → in; in → out at the default spot, facing the user (also the「回正」path, design T5). */
    fun ruler() {
        val st = s
        if (st.line.on) {
            fx.sound(Sfx.TAPE_IN)
            fx.log("LINE off")
            set(st.copy(line = st.line.copy(on = false)), "!ruler off")
            return
        }
        val m = st.resultMeters ?: run { fx.log("LINE no length (unit=${st.unit} evaluated=${st.calc.evaluated})"); return }
        val p = Ruler.problem(m)
        if (p != null) { fx.log("LINE refused $p m=$m"); set(st.copy(lineProblem = p), "!ruler refused"); return }
        fx.sound(Sfx.TAPE_OUT)
        fx.log("LINE on m=$m unit=${st.unit}")
        set(st.copy(line = LineState(on = true, lengthM = m, unit = st.unit, serial = st.line.serial + 1), lineProblem = null), "!ruler on")
    }

    /** grip + stick left / right: 15° a notch about the line's vertical axis; 0° (facing the user) is a notch, so it snaps there. */
    fun lineTurn(dir: Int) {
        val st = s
        if (!st.line.on || dir == 0) return
        var steps = st.line.yawSteps + (if (dir > 0) 1 else -1)
        if (steps > 12) steps -= 24
        if (steps < -12) steps += 24
        fx.sound(Sfx.TICK)
        set(st.copy(line = st.line.copy(yawSteps = steps)), "!line turn ${steps * 15}°")
    }

    fun lineStand(standing: Boolean) {
        val st = s
        if (!st.line.on || st.line.standing == standing) return
        fx.sound(Sfx.TICK)
        set(st.copy(line = st.line.copy(standing = standing)), "!line ${if (standing) "stand" else "lay"}")
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
        var n = st.copy(page = Page.CALC, calc = calc, unit = item.unit, origin = if (item.unit.isLength) item.value to item.unit else null, lineProblem = null)
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
