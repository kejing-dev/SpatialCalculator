package io.embodit.spatialcalc.ui.window

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.design.windows.Toolbar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.spatialcalc.BuildConfig
import io.embodit.spatialcalc.R
import io.embodit.spatialcalc.domain.calc.Fmt
import io.embodit.spatialcalc.domain.calc.TemplateId
import io.embodit.spatialcalc.domain.session.Card
import io.embodit.spatialcalc.domain.session.CalcState
import io.embodit.spatialcalc.domain.session.Confirm
import io.embodit.spatialcalc.domain.session.Language
import io.embodit.spatialcalc.domain.session.Page
import io.embodit.spatialcalc.domain.session.Phase
import io.embodit.spatialcalc.domain.session.Settings
import io.embodit.spatialcalc.platform.AppLog
import io.embodit.spatialcalc.platform.Graph
import io.embodit.spatialcalc.ui.Containers
import io.embodit.spatialcalc.ui.theme.CalcColors
import kotlin.system.exitProcess

/**
 * The one system window (design windowModel): three pages switched by the native SpatialUI TabBar (D8) — 算 Calc /
 * 记录 Records / 设置 Settings — and the system Toolbar under the window on the Calc page (量一下 · 拟合盒 · 清空).
 * Dragging and closing are the system window controls (D21); nothing here draws a handle.
 */
@Composable
fun MainWindowUi() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.flow.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(state.page) { AppLog.i("PAGE ${state.page} ${state.stateId}") }

    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        when (state.page) {
            Page.CALC -> CalcPage(state, settings, graph)
            Page.RECORDS -> RecordsPage(state, settings, graph)
            Page.SETTINGS -> SettingsPage(state, settings, graph)
        }
    }

    // v0.1.2 (B31): the Toolbar only while measuring is the next step, with keys the panel does not already show
    val toolKeys = io.embodit.spatialcalc.domain.session.Chrome.toolbar(state)
    if (toolKeys.isNotEmpty()) {
        // The Toolbar is its own system window too: labels resolved here in the app language (QA tabbar-lang rule)
        val keys = toolKeys.map { id ->
            when (id) {
                io.embodit.spatialcalc.domain.session.ToolKeyId.MEASURE -> Triple(R.drawable.ic_straighten, stringResource(R.string.btn_measure), "measure")
                io.embodit.spatialcalc.domain.session.ToolKeyId.FITBOX -> Triple(R.drawable.ic_crop_free, stringResource(R.string.btn_fitbox), "fitbox")
            }
        }
        key(settings.language, keys.map { it.third }) {
            Toolbar {
                keys.forEach { (icon, label, id) ->
                    ToolKey(icon, label, "toolbar/$id", true) {
                        when (id) { "measure" -> graph.flow.measureButton(); else -> graph.fitBox() }
                    }
                }
            }
        }
    }

    // The TabBar is its own system window: labels are resolved here in the app language and passed in (tabbar-lang).
    val tabs = listOf(
        Triple(Page.CALC, R.drawable.ic_calculate, stringResource(R.string.tab_calc)),
        Triple(Page.RECORDS, R.drawable.ic_history, stringResource(R.string.tab_records)),
        Triple(Page.SETTINGS, R.drawable.ic_settings, stringResource(R.string.tab_settings)),
    )
    key(settings.language) { MainTabBar(tabs, state.page) { graph.flow.page(it) } }
}

@Composable
private fun ToolKey(icon: Int, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 52.dp, minWidth = 120.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.width(8.dp))
        // design-style: inherited-content-color Button
        Txt(label, tag, style = PicoTheme.typography.labelLarge)
    }
}

@Composable
internal fun PageTitle(text: String, tag: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, "$tag/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ------------------------------------------------------------------ Records (S13)

@Composable
private fun RecordsPage(state: CalcState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    // v0.1.2 (B31): title + count (no [新建] / 「接着量一个」tiles: one primary at the bottom); one card open, its
    // secondary actions behind its own ⋯
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle(stringResource(R.string.tab_records), "records") {
            Txt(stringResource(R.string.records_count, state.cards.size), "records/count", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
        }
        if (state.cards.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).then(displayFrame()), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painter = painterResource(R.drawable.tpl_tank), contentDescription = null, modifier = Modifier.size(320.dp, 240.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                Txt(stringResource(R.string.records_empty), "records/empty", style = PicoTheme.typography.titleMedium, maxLines = 2)
            }
            PrimaryButton(stringResource(R.string.btn_measure), "records/measure", onClick = { flow.page(Page.CALC); flow.measureButton() }, modifier = Modifier.fillMaxWidth(), height = 60.dp)
            return@Column
        }
        if (state.confirm == Confirm.DELETE_CARD) {
            Txt(stringResource(R.string.delete_ask), "records/delete-ask", style = PicoTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "records/delete-cancel", onClick = { flow.confirmNo() }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_delete), "records/delete-yes", danger = true, onClick = { flow.confirmYes() }, modifier = Modifier.weight(1f))
            }
        }
        if (state.hint == io.embodit.spatialcalc.domain.session.Hint.BROUGHT) {
            Txt(stringResource(R.string.hint_brought), "records/brought", style = PicoTheme.typography.bodyMedium, color = CalcColors.TertiaryText)
        }
        val openId = state.cards.firstOrNull { it.id == state.expandedCard }?.id ?: state.cards.first().id
        // few cards: the open card takes the free height (big picture + result) so the page has no empty band (D44)
        val few = state.cards.size <= 2
        Column(
            Modifier.fillMaxWidth().weight(1f).then(if (few) Modifier else Modifier.verticalScroll(rememberScrollState())),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.cards.forEach { c ->
                val open = c.id == openId
                CardRow(c, open, few, settings, graph, if (open && few) Modifier.weight(1f) else Modifier)
            }
        }
        PrimaryButton(stringResource(R.string.records_next), "records/next", onClick = { flow.next(); flow.page(Page.CALC) }, modifier = Modifier.fillMaxWidth(), height = 60.dp)
    }
}

@Composable
private fun CardRow(c: Card, open: Boolean, fill: Boolean, settings: Settings, graph: Graph, modifier: Modifier = Modifier) {
    val flow = graph.flow
    val r = remember(c) { cardResult(c) }
    val text = resultText(r, c.template, c.resultUnit, settings.imperial) ?: "—"
    val defaultName = templateDefaultName(c)
    var menu by androidx.compose.runtime.remember(c.id, open) { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().then(if (open) displayFrame() else Modifier.clip(RoundedCornerShape(18.dp)).background(PicoTheme.colorScheme.fillSecondary))
            .then(if (open) Modifier else Modifier.clickable { flow.expand(c.id) }).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        val pinText = when { c.pinned && c.elsewhere -> stringResource(R.string.records_elsewhere); c.pinned -> "📍 " + stringResource(R.string.records_pinned); else -> null }
        if (!open) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(templateIcon(c.template)), contentDescription = null, modifier = Modifier.size(48.dp, 36.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(text, "records/result", style = PicoTheme.typography.titleMedium, color = CalcColors.TertiaryText, maxLines = 2)
                    Txt("${templateName(c.template, c.customName)} · ${fmtDate(c.createdAt)}", "records/meta", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
                }
                if (pinText != null) Txt(pinText, "records/pin", style = PicoTheme.typography.labelMedium, color = CalcColors.TertiaryText)
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().then(if (fill) Modifier.weight(1f, fill = false) else Modifier), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(templateIcon(c.template)), contentDescription = null, modifier = Modifier.size(if (fill) 160.dp else 96.dp, if (fill) 120.dp else 72.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(text, "records/result", style = TextStyle(fontSize = if (text.length > 16) 24.sp else 34.sp), color = CalcColors.TertiaryText, maxLines = 2)
                Txt("${templateName(c.template, c.customName)} · ${fmtDate(c.createdAt)}" + (pinText?.let { " · $it" } ?: ""), "records/meta", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                val unit = if (settings.imperial) io.embodit.spatialcalc.domain.calc.LenUnit.IN else io.embodit.spatialcalc.domain.calc.LenUnit.CM
                val formula = if (c.tokens.isNotEmpty()) c.tokens.joinToString(" ") { tokenLabel(it, unit) }
                else c.slots.map { "${slotName(it.def.key)} ${slotLabel(it, unit)}" }.joinToString(" · ")
                Txt(formula, "records/formula", style = PicoTheme.typography.bodyMedium, maxLines = 4)
            }
        }
        if (!menu) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.btn_replay), "records/replay", onClick = { flow.replay(c.id) }, modifier = Modifier.weight(1f))
                SecondaryButton("⋯ " + stringResource(R.string.btn_more), "records/more", onClick = { menu = true }, modifier = Modifier.weight(1f))
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(if (c.pinned) R.string.btn_unpin else R.string.btn_pin), "records/pin-toggle", onClick = { menu = false; if (c.pinned) flow.unpinCard(c.id) else flow.pinCard(c.id) }, enabled = c.pose != null, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_bring), "records/bring", onClick = { menu = false; graph.bringCard(c.id) }, enabled = c.size != null, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.btn_save_template), "records/template", onClick = { menu = false; flow.saveAsTemplate(defaultName, c.id) }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_delete), "records/delete", danger = true, onClick = { menu = false; flow.deleteAsk(c.id) }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_close), "records/more-close", onClick = { menu = false }, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun cardResult(c: Card) =
    if (c.tokens.isNotEmpty()) io.embodit.spatialcalc.domain.calc.Evaluate.free(c.tokens) else io.embodit.spatialcalc.domain.calc.Evaluate.template(c.template, c.round, c.slots)

@Composable
private fun templateDefaultName(c: Card): String = "${templateName(c.template, c.customName)} ${fmtDate(c.createdAt)}"

// ------------------------------------------------------------------ Settings (S15 / S16)

/**
 * v0.1.2 (B31): Settings first screen = language · units · sound (slider + mute) · [More settings] · [Quit app].
 * Language / units are one row each that steps to the next choice (the value is on the row). Opacity, snap, table
 * height, reopen panel and the about line live on the [More settings] sub-page.
 */
@Composable
private fun SettingsPage(state: CalcState, settings: Settings, graph: Graph) {
    if (state.settingsMore && state.confirm != Confirm.EXIT) { SettingsMorePage(settings, graph); return }
    val flow = graph.flow
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    val langs = listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        PageTitle(stringResource(R.string.tab_settings), "settings")
        StepRow(stringResource(R.string.set_language), langs.first { it.first == settings.language }.second, "settings/lang") {
            val i = langs.indexOfFirst { it.first == settings.language }
            val next = langs[(i + 1) % langs.size].first
            flow.updateSettings { it.copy(language = next) }
        }
        StepRow(stringResource(R.string.set_units), stringResource(if (settings.imperial) R.string.units_imperial else R.string.units_metric), "settings/units") {
            flow.updateSettings { it.copy(imperial = !it.imperial) }
        }
        OptionRow(stringResource(R.string.set_volume), "settings/volume", labelWidth = 120.dp, rowHeight = 56.dp) {
            // D15: mute one tap away, next to the level
            Button(
                onClick = { flow.updateSettings { it.copy(muted = !it.muted) } },
                size = ButtonDefaults.Regular.copy(height = 52.dp, minWidth = 52.dp), shape = androidx.compose.foundation.shape.CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (settings.muted) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary,
                    contentColor = if (settings.muted) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary,
                ),
            ) {
                Icon(
                    painter = painterResource(if (settings.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                    contentDescription = stringResource(if (settings.muted) R.string.unmute else R.string.mute),
                    tint = if (settings.muted) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary,
                )
            }
            Spacer(Modifier.width(12.dp))
            VolumeSlider(settings.volume, { v -> flow.updateSettings { it.copy(volume = v, muted = false) } }, Modifier.weight(1f))
        }
        SecondaryButton(stringResource(R.string.btn_settings_more) + "  ›", "settings/more", onClick = { flow.settingsMore(true) }, modifier = Modifier.fillMaxWidth(), tall = true)
        if (state.confirm != Confirm.EXIT) {
            SecondaryButton(stringResource(R.string.btn_exit_app), "settings/exit", danger = true, onClick = { flow.exitAsk() }, modifier = Modifier.fillMaxWidth(), tall = true)
        } else {
            // D26: the confirmation opens in place, inside this window
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(stringResource(R.string.exit_ask), "settings/exit-ask", style = PicoTheme.typography.titleMedium, maxLines = 2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(stringResource(R.string.btn_cancel), "settings/exit-cancel", onClick = { flow.confirmNo() }, modifier = Modifier.weight(1f), tall = true)
                    SecondaryButton(stringResource(R.string.btn_exit), "settings/exit-yes", danger = true, onClick = { exitApp(context, navigator, graph) }, modifier = Modifier.weight(1f), tall = true)
                }
            }
        }
    }
}

/** A whole-row button: label left, current value right; a tap steps to the next value. */
@Composable
private fun StepRow(label: String, value: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(18.dp)).background(PicoTheme.colorScheme.fillSecondary)
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Txt("$value  ›", "$tag/value", style = PicoTheme.typography.titleMedium, color = CalcColors.TertiaryText)
    }
}

/** Settings → [More settings]: result body opacity, snap to pins, virtual table height, reopen panel, about. */
@Composable
private fun SettingsMorePage(settings: Settings, graph: Graph) {
    val flow = graph.flow
    val ops = listOf(0 to stringResource(R.string.op_low), 1 to stringResource(R.string.op_mid), 2 to stringResource(R.string.op_high))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("‹ " + stringResource(R.string.btn_back), "settings/back", onClick = { flow.settingsMore(false) }, minWidth = 112.dp)
            Spacer(Modifier.width(16.dp))
            Txt(stringResource(R.string.btn_settings_more), "settings-more/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        StepRow(stringResource(R.string.set_opacity), ops.first { it.first == settings.opacity }.second, "settings/opacity") {
            flow.updateSettings { it.copy(opacity = (it.opacity + 1) % 3) }
        }
        ToggleButton(stringResource(R.string.set_snap), "settings/snap", settings.snapPins, { flow.updateSettings { it.copy(snapPins = !it.snapPins) } }, Modifier.fillMaxWidth())
        OptionRow(stringResource(R.string.set_table), "settings/table", labelWidth = 150.dp, rowHeight = 56.dp) {
            SecondaryButton("−", "settings/table-minus", onClick = { flow.updateSettings { it.copy(tableHeight = ((it.tableHeight - 0.05f) * 100).let { v -> Math.round(v) / 100f }.coerceIn(0.4f, 1.1f)) } }, minWidth = 64.dp)
            Txt("${Fmt.plain(settings.tableHeight.toDouble())} m", "settings/table-value", style = PicoTheme.typography.titleMedium, modifier = Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center)
            SecondaryButton("+", "settings/table-plus", onClick = { flow.updateSettings { it.copy(tableHeight = ((it.tableHeight + 0.05f) * 100).let { v -> Math.round(v) / 100f }.coerceIn(0.4f, 1.1f)) } }, minWidth = 64.dp)
        }
        SecondaryButton(stringResource(R.string.btn_reopen_window), "settings/reopen", onClick = { graph.window.recall() }, modifier = Modifier.fillMaxWidth(), tall = true)
        Txt(stringResource(R.string.about_text, BuildConfig.VERSION_NAME) + " · " + stringResource(R.string.disclaimer), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
    }
}

/**
 * D26 / D44 exit: release audio, trackers and anchors, close the main window and the Stage (both containers), then
 * remove every task and end the process.
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

/** The native TabBar with labels already resolved in the app language (kept last: no string lookups below). */
@Composable
private fun MainTabBar(tabs: List<Triple<Page, Int, String>>, current: Page, onPick: (Page) -> Unit) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = current == page,
                onClick = { onPick(page) },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}
