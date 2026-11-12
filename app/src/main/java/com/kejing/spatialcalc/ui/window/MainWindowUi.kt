package com.kejing.spatialcalc.ui.window

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
import androidx.compose.ui.unit.dp
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
import com.kejing.spatialcalc.BuildConfig
import com.kejing.spatialcalc.R
import com.kejing.spatialcalc.domain.calc.Fmt
import com.kejing.spatialcalc.domain.calc.TemplateId
import com.kejing.spatialcalc.domain.session.Card
import com.kejing.spatialcalc.domain.session.CalcState
import com.kejing.spatialcalc.domain.session.Confirm
import com.kejing.spatialcalc.domain.session.Language
import com.kejing.spatialcalc.domain.session.Page
import com.kejing.spatialcalc.domain.session.Phase
import com.kejing.spatialcalc.domain.session.Settings
import com.kejing.spatialcalc.platform.AppLog
import com.kejing.spatialcalc.platform.Graph
import com.kejing.spatialcalc.ui.Containers
import com.kejing.spatialcalc.ui.theme.CalcColors
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

    if (state.page == Page.CALC && state.phase != Phase.WELCOME) {
        // The Toolbar is its own system window too: labels resolved here in the app language (QA tabbar-lang rule)
        val enabled = state.confirm == null && !state.savedPreview
        val keys = listOf(
            Triple(R.drawable.ic_straighten, stringResource(R.string.btn_measure), "measure"),
            Triple(R.drawable.ic_crop_free, stringResource(R.string.btn_fitbox), "fitbox"),
            Triple(R.drawable.ic_delete_sweep, stringResource(R.string.btn_clear), "clear"),
        )
        key(settings.language) {
            Toolbar {
                keys.forEach { (icon, label, id) ->
                    ToolKey(icon, label, "toolbar/$id", enabled && (id != "clear" || state.phase != Phase.PICK)) {
                        when (id) { "measure" -> graph.flow.measureButton(); "fitbox" -> graph.fitBox(); else -> graph.flow.clearAsk() }
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        PageTitle(stringResource(R.string.tab_records), "records") {
            Txt(stringResource(R.string.records_count, state.cards.size), "records/count", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
            Spacer(Modifier.width(12.dp))
            SecondaryButton(stringResource(R.string.btn_new), "records/new", onClick = { flow.next(); flow.page(Page.CALC) })
        }
        if (state.cards.isEmpty()) {
            // short page: one centred block (the page Column centres it): picture, line, the three jobs, [量一下] (D44)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painter = painterResource(R.drawable.tpl_tank), contentDescription = null, modifier = Modifier.size(240.dp, 180.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                Txt(stringResource(R.string.records_empty), "records/empty", style = PicoTheme.typography.titleMedium, maxLines = 2)
                NextTiles(graph)
                PrimaryButton(stringResource(R.string.btn_measure), "records/measure", onClick = { flow.page(Page.CALC); flow.measureButton() }, modifier = Modifier.width(240.dp))
            }
            return@Column
        }
        if (state.confirm == Confirm.DELETE_CARD) {
            Txt(stringResource(R.string.delete_ask), "records/delete-ask", style = PicoTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "records/delete-cancel", onClick = { flow.confirmNo() }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_delete), "records/delete-yes", danger = true, onClick = { flow.confirmYes() }, modifier = Modifier.weight(1f))
            }
        }
        if (state.hint == com.kejing.spatialcalc.domain.session.Hint.BROUGHT) {
            Txt(stringResource(R.string.hint_brought), "records/brought", style = PicoTheme.typography.bodyMedium, color = CalcColors.TertiaryText)
        }
        // q1-7: the list takes only the height it needs (scrolls past the window); the next actions follow right under
        // it, so there is no empty band between them; one card is always open (the newest unless another is tapped),
        // so a short list still fills the page (D44 blank <= 25 %)
        val openId = state.cards.firstOrNull { it.id == state.expandedCard }?.id ?: state.cards.first().id
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.cards.forEach { c -> CardRow(c, c.id == openId, true, settings, graph) }
        }
        Txt(stringResource(R.string.records_next), "records/next", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
        NextTiles(graph)
    }
}

/** 「接着量一个」: three common jobs, one tap starts a fresh card (records page, also its empty state). */
@Composable
private fun NextTiles(graph: Graph) {
    val flow = graph.flow
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(TemplateId.TANK, TemplateId.TILE, TemplateId.FIT).forEach { t ->
            // icon over a two-line name: the English names are long (「How many liters in this tank」)
            Column(
                Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(18.dp)).background(PicoTheme.colorScheme.fillSecondary)
                    .clickable { flow.next(); flow.page(Page.CALC); flow.pick(t) }.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Icon(painter = painterResource(templateIcon(t)), contentDescription = null, modifier = Modifier.size(40.dp, 30.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                Txt(templateName(t), "records/next-${t.id}", style = PicoTheme.typography.labelMedium, maxLines = 2, align = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun CardRow(c: Card, open: Boolean, keepOpen: Boolean, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val r = remember(c) { cardResult(c) }
    val text = resultText(r, c.template, c.resultUnit, settings.imperial) ?: "—"
    val defaultName = templateDefaultName(c)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PicoTheme.colorScheme.fillSecondary)
            .clickable { if (!(open && keepOpen)) flow.expand(if (open) null else c.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(templateIcon(c.template)), contentDescription = null, modifier = Modifier.size(48.dp, 36.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(text, "records/result", style = PicoTheme.typography.titleMedium, color = CalcColors.TertiaryText, maxLines = 2)
                Txt("${templateName(c.template, c.customName)} · ${fmtDate(c.createdAt)}", "records/meta", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
            }
            val pinText = when { c.pinned && c.elsewhere -> stringResource(R.string.records_elsewhere); c.pinned -> "📍 " + stringResource(R.string.records_pinned); else -> stringResource(R.string.records_unpinned) }
            Txt(pinText, "records/pin", style = PicoTheme.typography.labelMedium, color = if (c.pinned) CalcColors.TertiaryText else PicoTheme.colorScheme.labelSecondary)
        }
        if (open) {
            val unit = if (settings.imperial) com.kejing.spatialcalc.domain.calc.LenUnit.IN else com.kejing.spatialcalc.domain.calc.LenUnit.CM
            val formula = if (c.tokens.isNotEmpty()) c.tokens.joinToString(" ") { tokenLabel(it, unit) }
            else c.slots.map { "${slotName(it.def.key)} ${slotLabel(it, unit)}" }.joinToString(" · ")
            Txt(formula, "records/formula", style = PicoTheme.typography.bodyMedium, maxLines = 4)
            Txt(stringResource(R.string.disclaimer), "records/disclaimer", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.btn_replay), "records/replay", onClick = { flow.replay(c.id) }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(if (c.pinned) R.string.btn_unpin else R.string.btn_pin), "records/pin-toggle", onClick = { if (c.pinned) flow.unpinCard(c.id) else flow.pinCard(c.id) }, enabled = c.pose != null, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_bring), "records/bring", onClick = { graph.bringCard(c.id) }, enabled = c.size != null, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.btn_save_template), "records/template", onClick = { flow.saveAsTemplate(defaultName, c.id) }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_delete), "records/delete", danger = true, onClick = { flow.deleteAsk(c.id) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun cardResult(c: Card) =
    if (c.tokens.isNotEmpty()) com.kejing.spatialcalc.domain.calc.Evaluate.free(c.tokens) else com.kejing.spatialcalc.domain.calc.Evaluate.template(c.template, c.round, c.slots)

@Composable
private fun templateDefaultName(c: Card): String = "${templateName(c.template, c.customName)} ${fmtDate(c.createdAt)}"

// ------------------------------------------------------------------ Settings (S15 / S16)

@Composable
private fun SettingsPage(state: CalcState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        PageTitle(stringResource(R.string.tab_settings), "settings")
        OptionRow(stringResource(R.string.set_language), "settings/lang", labelWidth = 136.dp, rowHeight = 52.dp) {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)),
                settings.language, "settings/lang", { l -> flow.updateSettings { it.copy(language = l) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.set_units), "settings/units", labelWidth = 136.dp, rowHeight = 52.dp) {
            Segments(listOf(false to stringResource(R.string.units_metric), true to stringResource(R.string.units_imperial)),
                settings.imperial, "settings/units", { v -> flow.updateSettings { it.copy(imperial = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.set_opacity), "settings/opacity", labelWidth = 136.dp, rowHeight = 52.dp) {
            Segments(listOf(0 to stringResource(R.string.op_low), 1 to stringResource(R.string.op_mid), 2 to stringResource(R.string.op_high)),
                settings.opacity, "settings/opacity", { v -> flow.updateSettings { it.copy(opacity = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.set_volume), "settings/volume", labelWidth = 136.dp, rowHeight = 52.dp) {
            VolumeSlider(settings.volume, { v -> flow.updateSettings { it.copy(volume = v, muted = false) } }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleButton(stringResource(R.string.set_mute), "settings/mute", settings.muted, { flow.updateSettings { it.copy(muted = !it.muted) } }, Modifier.weight(1f))
            ToggleButton(stringResource(R.string.set_snap), "settings/snap", settings.snapPins, { flow.updateSettings { it.copy(snapPins = !it.snapPins) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.set_table), "settings/table", labelWidth = 136.dp, rowHeight = 52.dp) {
            SecondaryButton("−", "settings/table-minus", onClick = { flow.updateSettings { it.copy(tableHeight = ((it.tableHeight - 0.05f) * 100).let { v -> Math.round(v) / 100f }.coerceIn(0.4f, 1.1f)) } }, minWidth = 64.dp)
            Txt("${Fmt.plain(settings.tableHeight.toDouble())} m", "settings/table-value", style = PicoTheme.typography.titleMedium, modifier = Modifier.width(96.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
            SecondaryButton("+", "settings/table-plus", onClick = { flow.updateSettings { it.copy(tableHeight = ((it.tableHeight + 0.05f) * 100).let { v -> Math.round(v) / 100f }.coerceIn(0.4f, 1.1f)) } }, minWidth = 64.dp)
        }
        Txt(stringResource(R.string.about_text, BuildConfig.VERSION_NAME) + " · " + stringResource(R.string.disclaimer), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
        if (state.confirm != Confirm.EXIT) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_reopen_window), "settings/reopen", onClick = { graph.window.recall() }, modifier = Modifier.weight(1f), tall = true)
                SecondaryButton(stringResource(R.string.btn_exit_app), "settings/exit", danger = true, onClick = { flow.exitAsk() }, modifier = Modifier.weight(1f), tall = true)
            }
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
