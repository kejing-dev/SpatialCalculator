package io.embodit.spatialcalc.ui.window

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.spatialcalc.BuildConfig
import io.embodit.spatialcalc.R
import io.embodit.spatialcalc.domain.app.AppController
import io.embodit.spatialcalc.domain.app.AppState
import io.embodit.spatialcalc.domain.app.Language
import io.embodit.spatialcalc.domain.app.Page
import io.embodit.spatialcalc.domain.app.Settings
import io.embodit.spatialcalc.domain.calc.CalcError
import io.embodit.spatialcalc.domain.calc.Calculator
import io.embodit.spatialcalc.domain.calc.Fmt
import io.embodit.spatialcalc.domain.calc.Key
import io.embodit.spatialcalc.domain.calc.LenUnit
import io.embodit.spatialcalc.domain.calc.Dim
import io.embodit.spatialcalc.domain.calc.LineProblem
import io.embodit.spatialcalc.domain.calc.Tok
import io.embodit.spatialcalc.platform.AppLog
import io.embodit.spatialcalc.platform.Graph
import io.embodit.spatialcalc.ui.Containers
import io.embodit.spatialcalc.ui.theme.CalcColors
import java.math.BigDecimal
import kotlin.system.exitProcess

/**
 * The one system window (design windowModel single_system_window, 400 × 716 dp). No TabBar (one main page, B31):
 * pages change in place — calculator (cold start), history, settings, how to use — and every page has ← back.
 * Dragging and closing are the system window controls (D21).
 */
@Composable
fun MainWindowUi() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.app.state.collectAsStateWithLifecycle()
    val settings by graph.app.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val converter = LocalPhysicalLengthConverter.current
    LaunchedEffect(converter) {
        val m = with(converter) { converter.dpToLength(1.dp, LengthUnit.Meters) }
        graph.window.mPerDp = m
        AppLog.i("WINDOW mPerDp=$m size=${"%.3f".format(400 * m)} x ${"%.3f".format(716 * m)} m")
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(state.page) { AppLog.i("PAGE ${state.page} ${state.stateId}") }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
        when (state.page) {
            Page.CALC -> CalcPage(state, settings, graph.app)
            Page.HISTORY -> HistoryPage(state, graph.app)
            Page.SETTINGS -> SettingsPage(state, settings, graph)
            Page.HOWTO -> HowToPage(graph.app)
        }
    }
}

private fun sp(v: Float) = TextUnit(v, TextUnitType.Sp)

// ---------------------------------------------------------------------------------------------------------- calc

@Composable
private fun CalcPage(st: AppState, settings: Settings, app: AppController) {
    Column(Modifier.fillMaxSize()) {
        // top row: history · mute (always on screen, D15) · settings
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            IconKey(R.drawable.ic_history, stringResource(R.string.history), "calc/history", { app.page(Page.HISTORY) })
            IconKey(
                if (settings.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                stringResource(if (settings.muted) R.string.unmute else R.string.mute), "calc/mute", { app.mute(!settings.muted) },
            )
            IconKey(R.drawable.ic_settings, stringResource(R.string.settings), "calc/settings", { app.page(Page.SETTINGS) })
        }
        Display(st, app)
        Spacer(Modifier.height(4.dp))
        UnitRow(LenUnit.ROW, st.unit, st::unitEnabled) { app.unit(it) }
        Spacer(Modifier.height(4.dp))
        UnitRow(LenUnit.VOL_ROW, st.unit, st::unitEnabled) { app.unit(it) }
        Spacer(Modifier.height(16.dp))
        Keypad { app.press(it) }
    }
}

/** Expression line (22 sp, ink 60 %), result line (52 → 40 sp, then fewer digits / scientific), a 20 dp note line. */
@Composable
private fun ColumnScope.Display(st: AppState, app: AppController) {
    val calc = st.calc
    val expr = when {
        calc.evaluated -> calc.exprText + " =" + (st.origin?.takeIf { it.second != st.unit && st.unit.hasUnit }?.let { " " + Fmt.number(it.first) + " " + it.second.symbol } ?: "")
        calc.error != null -> calc.exprText + " ="
        else -> Fmt.tokens(calc.tokens)
    }
    Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.CenterEnd) {
        if (st.showFirstHint && calc.tokens.isEmpty() && !calc.evaluated) {
            Txt(stringResource(R.string.first_hint), "calc/first-hint", size = sp(14f), color = CalcColors.InkSecondary, align = TextAlign.End)
        } else {
            // too long: keep the end in view and drop the start (calcModel.display「从左边省略」)
            val shown = if (expr.length > EXPR_MAX) "…" + expr.takeLast(EXPR_MAX - 1) else expr
            Txt(shown, "calc/expr", size = sp(22f), lineHeight = 1.1f, modifier = Modifier.wrapContentHeight(unbounded = true), color = CalcColors.InkDim, align = TextAlign.End)
        }
    }
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        if (st.rulerShown) {
            // [摆出来] (was 📏): one key; a ruler icon for a length, a cube for a volume, with its name under it (B19)
            Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IconKey(
                    if (st.rulerBox) R.drawable.ic_cube else R.drawable.ic_straighten,
                    stringResource(if (st.line.on) R.string.ruler_hide else R.string.ruler_show), "calc/ruler",
                    { app.ruler() }, on = st.line.on, dim = !st.rulerEnabled,
                )
                Txt(stringResource(if (st.line.on) R.string.ruler_caption_off else R.string.ruler_caption), "calc/ruler-caption", size = sp(11f), lineHeight = 1.0f,
                    color = CalcColors.InkSecondary, align = TextAlign.Center)
            }
            Spacer(Modifier.width(4.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { ResultText(st) }
    }
    Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.CenterEnd) {
        val note = when (st.lineProblem) {
            LineProblem.NEGATIVE -> stringResource(if (st.unit.isVolume || st.rulerBox) R.string.line_negative_volume else R.string.line_negative)
            LineProblem.TOO_SHORT -> stringResource(R.string.line_too_short)
            null -> null
        }
        if (note != null) Txt(note, "calc/line-note", size = sp(14f), color = CalcColors.ErrorText, align = TextAlign.End)
        // why the other row is greyed once there is a result (q11-1; D43: a greyed key says why)
        else if (st.lockedDim == Dim.VOLUME) Txt(stringResource(R.string.volume_note), "calc/volume-note", size = sp(14f), color = CalcColors.InkSecondary, align = TextAlign.End)
        else if (st.lockedDim == Dim.LENGTH) Txt(stringResource(R.string.length_note), "calc/length-note", size = sp(14f), color = CalcColors.InkSecondary, align = TextAlign.End)
    }
}

private const val EXPR_MAX = 26

@Composable
private fun ResultText(st: AppState) {
    val calc = st.calc
    if (calc.error != null) {
        Txt(stringResource(if (calc.error == CalcError.DIV_ZERO) R.string.div_zero else R.string.too_big), "calc/error", size = sp(32f), color = CalcColors.ErrorText, align = TextAlign.End)
        return
    }
    val suffix = if (st.unit.hasUnit) " " + st.unit.symbol else ""
    val single = calc.tokens.size == 1 && !calc.evaluated
    // a × b × c with a length unit is a volume in the model itself (v0.3.2 q10-1): result + unit already read「6 L」
    val (text, size) = when {
        calc.evaluated && calc.result != null -> fitResult(calc.result, suffix, if (st.converted) CONVERTED_SIG else Fmt.MAX_SIG)
        single -> fitTyped(Fmt.typed(calc.tokens[0] as Tok.Num) + suffix)
        calc.tokens.isEmpty() -> "0$suffix" to 52f
        // a × b × c in a length previews as the volume = will give (「6 L」, not「6,000 cm」)
        else -> st.previewValue?.let { (v, u) -> fitResult(v, if (u.hasUnit) " " + u.symbol else "") } ?: ("" to 52f)
    }
    val preview = !calc.evaluated && !single && calc.tokens.isNotEmpty()
    Txt(
        text, "calc/result", size = sp(size), lineHeight = 1.0f, align = TextAlign.End,
        // the 52 sp line box is taller than its 64 dp row only by the font's top padding: measure height unbounded, width stays checked
        modifier = Modifier.wrapContentHeight(unbounded = true), color = if (preview) CalcColors.InkSecondary.copy(alpha = 0.75f) else CalcColors.Ink,
        style = PicoTheme.typography.bodyLarge.copy(fontWeight = if (preview) FontWeight.Normal else FontWeight.Light, fontFeatureSettings = "tnum"),
    )
}

/** A converted result (cm → in, L → gal, …) shows at most 7 significant digits in every unit (ft³ and gal alike). */
internal const val CONVERTED_SIG = 7

/**
 * 52 sp up to 10 characters, 40 sp up to 13, then fewer significant digits (down to 6; down to 4 under 1, where the
 * leading zeros take the room), then scientific (calcModel.display). So 0.004238 ft³ stays a plain decimal (QA #11
 * known issue); only values under 1e-6, from 1e12 up, or too long are scientific.
 */
internal fun fitResult(v: BigDecimal, suffix: String, maxSig: Int = Fmt.MAX_SIG): Pair<String, Float> {
    val minSig = if (v.abs() < BigDecimal.ONE) 4 else 6
    for (sig in maxSig downTo minSig) {
        val s = Fmt.number(v, sig) + suffix
        if (s.length <= 10) return s to 52f
        if (s.length <= 13) return s to 40f
    }
    return Fmt.scientific(v, 4) + suffix to 40f
}

private fun fitTyped(s: String): Pair<String, Float> = s to when { s.length <= 10 -> 52f; s.length <= 13 -> 40f; else -> 32f }

/**
 * — mm cm m in ft (row 1) and mL L cm³ m³ ft³ gal (row 2, v0.3.0): text segment rows, the chosen one bold with a 4 dp
 * mist-teal dot under it (no frame). One unit at a time across both rows.
 */
@Composable
private fun UnitRow(row: List<LenUnit>, unit: LenUnit, enabled: (LenUnit) -> Boolean, onPick: (LenUnit) -> Unit) {
    val none = stringResource(R.string.unit_none)
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        row.forEach { u ->
            val sel = u == unit
            val label = if (u == LenUnit.NONE) none else u.symbol
            // q10-1 / q11-1: once there is a result only its own dimension's row is lit; the other row is greyed
            val on = enabled(u)
            if (!on) {
                // Not a disabled Button: SpatialUI dims a disabled Button to ~30 % on top of the text colour (measured
                // 1.43:1 on the emulator, v0.3.3). A plain label in InkOff (≈ 4.0:1) that a11y reports as disabled; a tap
                // only logs and goes to AppController.unit, which refuses it (nothing changes).
                Box(
                    Modifier.weight(1f).height(48.dp)
                        .semantics { role = Role.Button; disabled() }
                        .pointerInput(u) { detectTapGestures { AppLog.i("UI tap unit/${u.name} (greyed)"); onPick(u) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Txt(label, "calc/unit-${u.name}", size = sp(17f), color = CalcColors.InkOff)
                        Spacer(Modifier.height(7.dp))
                    }
                }
                return@forEach
            }
            Button(
                onClick = { AppLog.i("UI tap unit/${u.name}"); onPick(u) },
                modifier = Modifier.weight(1f).height(48.dp),
                // gate:allow button-height unit row = one 48 dp text segment control (design panels.calc 单位行 48 dp; B19 ≥ 48 dp)
                size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 40.dp),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = CalcColors.Ink),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // design-style: inherited-content-color Button
                    Txt(label, "calc/unit-${u.name}", size = sp(17f), color = CalcColors.Ink,
                        style = PicoTheme.typography.bodyLarge.copy(fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal))
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(4.dp).clip(CircleShape).background(if (sel) CalcColors.Accent else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
    }
}

private val ROWS = listOf(
    listOf(Key.CLEAR, Key.BACK, Key.PCT, Key.DIV),
    listOf(Key.D7, Key.D8, Key.D9, Key.MUL),
    listOf(Key.D4, Key.D5, Key.D6, Key.SUB),
    listOf(Key.D1, Key.D2, Key.D3, Key.ADD),
    listOf(Key.SIGN, Key.D0, Key.DOT, Key.EQ),
)

/** 4 × 5 keys, 64 dp, 16 dp apart (B31). */
@Composable
private fun Keypad(onPress: (Key) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { row.forEach { k -> CalcKey(k, onPress) } }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- pages

@Composable
private fun Header(title: String, tag: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconKey(R.drawable.ic_arrow_back, stringResource(R.string.back), "$tag/back", onBack)
        Spacer(Modifier.width(16.dp))
        Txt(title, "$tag/title", style = PicoTheme.typography.titleMedium)
    }
}

/** Recent 6, newest first; a row brings its result back (T7). Clear asks in place. Content centred (D44 short page). */
@Composable
private fun HistoryPage(st: AppState, app: AppController) {
    Column(Modifier.fillMaxSize()) {
        Header(stringResource(R.string.history_title), "history") { app.page(Page.CALC) }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
            if (st.history.isEmpty()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painter = painterResource(R.drawable.ic_history), contentDescription = null, tint = CalcColors.InkSecondary, modifier = Modifier.size(96.dp))
                    Spacer(Modifier.height(16.dp))
                    Txt(stringResource(R.string.history_empty), "history/empty", style = PicoTheme.typography.titleSmall, color = CalcColors.InkSecondary)
                }
                return@Column
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                st.history.take(AppController.HISTORY_SHOWN).forEachIndexed { i, h ->
                    Button(
                        onClick = { AppLog.i("UI tap history/$i"); app.recall(i) }, modifier = Modifier.fillMaxWidth().height(64.dp),
                        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 96.dp), shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CalcColors.KeyFill, contentColor = CalcColors.Ink),
                    ) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            // design-style: inherited-content-color Button
                            Txt(h.expr + " =", "history/expr", size = sp(14f), color = CalcColors.InkSecondary, align = TextAlign.End)
                            Txt(Fmt.number(h.value) + (if (h.unit.hasUnit) " " + h.unit.symbol else ""), "history/result", size = sp(24f), color = CalcColors.Ink, align = TextAlign.End)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            ClearHistory(st, app)
        }
    }
}

@Composable
private fun ClearHistory(st: AppState, app: AppController) {
    if (!st.clearAsk) {
        RowButton(stringResource(R.string.clear_history), "history/clear", { app.clearHistoryAsk(true) })
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
            RowButton(stringResource(R.string.cancel), "history/clear-cancel", { app.clearHistoryAsk(false) }, Modifier.weight(1f))
            RowButton(stringResource(R.string.clear_confirm), "history/clear-yes", { app.clearHistory() }, Modifier.weight(1f), danger = true)
        }
    }
}

/** Volume · language · bring it back · how to use · clear history · about · quit (exit asks in place, D26 / D44). */
@Composable
private fun SettingsPage(st: AppState, settings: Settings, graph: Graph) {
    val app = graph.app
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    Column(Modifier.fillMaxSize()) {
        Header(stringResource(R.string.settings), "settings") { app.page(Page.CALC) }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            Row(Modifier.fillMaxWidth().height(BTN_H), verticalAlignment = Alignment.CenterVertically) {
                IconKey(
                    if (settings.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                    stringResource(if (settings.muted) R.string.unmute else R.string.mute), "settings/mute", { app.mute(!settings.muted) },
                )
                Spacer(Modifier.width(16.dp))
                VolumeSlider(settings.volume, { app.volume(it) }, Modifier.weight(1f))
            }
            OptionRow(stringResource(R.string.language), "settings/language") {
                Segments(
                    listOf(Language.SYSTEM to stringResource(R.string.follow_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)),
                    settings.language, "settings/language", { app.language(it) }, Modifier.weight(1f),
                )
            }
            RowButton(stringResource(R.string.bring_back), "settings/bring", { graph.window.recall() }, icon = R.drawable.ic_open_with)
            RowButton(stringResource(R.string.how_to), "settings/howto", { app.page(Page.HOWTO) }, icon = R.drawable.ic_pan_tool_alt, trailing = R.drawable.ic_chevron_right)
            ClearHistory(st, app)
            if (!st.exitAsk) {
                Box(Modifier.fillMaxWidth().height(BTN_H), contentAlignment = Alignment.Center) {
                    Txt(stringResource(R.string.about, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodyMedium, color = CalcColors.InkSecondary)
                }
                RowButton(stringResource(R.string.exit_app), "settings/exit", { app.exitAsk(true) }, icon = R.drawable.ic_logout, danger = true)
            } else {
                Box(Modifier.fillMaxWidth().height(BTN_H), contentAlignment = Alignment.Center) {
                    Txt(stringResource(R.string.exit_ask), "settings/exit-ask", style = PicoTheme.typography.titleSmall, maxLines = 2, align = TextAlign.Center)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
                    RowButton(stringResource(R.string.cancel), "settings/exit-cancel", { app.exitAsk(false) }, Modifier.weight(1f))
                    RowButton(stringResource(R.string.exit), "settings/exit-yes", { exitApp(context, navigator, graph) }, Modifier.weight(1f), danger = true)
                }
            }
        }
    }
}

/** One row per main action: icon + eye-hand way + controller way (B19, strings how_to_*). */
@Composable
private fun HowToPage(app: AppController) {
    val rows = listOf(
        Triple(R.drawable.ic_pan_tool_alt, R.string.how_to_tap_1, R.string.how_to_tap_2),
        Triple(R.drawable.ic_straighten, R.string.how_to_ruler_1, R.string.how_to_ruler_2),
        Triple(R.drawable.ic_cube, R.string.how_to_volume_1, R.string.how_to_volume_2),
        Triple(R.drawable.ic_open_with, R.string.how_to_drag_1, R.string.how_to_drag_2),
        Triple(R.drawable.ic_rotate_right, R.string.how_to_turn_1, R.string.how_to_turn_2),
        Triple(R.drawable.ic_restart_alt, R.string.how_to_box_1, R.string.how_to_box_2),
        Triple(R.drawable.ic_pan_tool_alt, R.string.how_to_twist_1, R.string.how_to_twist_2),
        Triple(R.drawable.ic_history, R.string.how_to_history_1, R.string.how_to_history_2),
    )
    Column(Modifier.fillMaxSize()) {
        Header(stringResource(R.string.how_to), "howto") { app.page(Page.SETTINGS) }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            rows.forEachIndexed { i, (icon, a, b) ->
                Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(CalcColors.KeyFill), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(icon), contentDescription = null, tint = CalcColors.Ink, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(stringResource(a), "howto/$i-a", size = sp(16f), maxLines = 2)
                        Spacer(Modifier.height(2.dp))
                        Txt(stringResource(b), "howto/$i-b", size = sp(14f), color = CalcColors.InkSecondary, maxLines = 2)
                    }
                }
            }
        }
    }
}

/**
 * D26 / D44 exit: release audio and trackers, close the main window and the Stage (both containers), then
 * finishAndRemoveTask and end the process (no recents entry, nothing left running).
 */
internal fun exitApp(context: Context, navigator: SpatialNavigator, graph: Graph) {
    AppLog.i("EXIT release + close window + closeStage + finishAndRemoveTask + exitProcess")
    graph.window.exiting = true
    runCatching { graph.release() }
    runCatching { navigator.closeWindowContainer(Containers.MAIN, Containers.MAIN_TAG) }.onFailure { AppLog.w("EXIT close window: $it") }
    runCatching { graph.window.closeStage?.invoke() }.onFailure { AppLog.w("EXIT close stage: $it") }
    val main = Handler(Looper.getMainLooper())
    main.postDelayed({
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }
        AppLog.i("EXIT finishAndRemoveTask")
        main.postDelayed({ exitProcess(0) }, 300)
    }, 400)
}
