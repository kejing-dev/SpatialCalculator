package io.embodit.spatialcalc.ui.window

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import io.embodit.spatialcalc.R
import io.embodit.spatialcalc.domain.calc.LenUnit
import io.embodit.spatialcalc.domain.calc.SlotKind
import io.embodit.spatialcalc.domain.calc.SlotUnit
import io.embodit.spatialcalc.domain.calc.Source
import io.embodit.spatialcalc.domain.calc.TemplateId
import io.embodit.spatialcalc.domain.calc.Token
import io.embodit.spatialcalc.domain.session.CalcState
import io.embodit.spatialcalc.domain.session.Confirm
import io.embodit.spatialcalc.domain.session.Hint
import io.embodit.spatialcalc.domain.session.MoreItem
import io.embodit.spatialcalc.domain.session.Phase
import io.embodit.spatialcalc.domain.session.Settings
import io.embodit.spatialcalc.platform.Graph
import io.embodit.spatialcalc.ui.theme.CalcColors

/** The Calc page: S01 welcome, S02 job grid, S03–S09 / S26 formula (one column) and S07 typing (two columns). */
@Composable
internal fun CalcPage(state: CalcState, settings: Settings, graph: Graph) {
    when {
        state.phase == Phase.WELCOME || state.phase == Phase.LOADING -> Welcome(graph)
        state.phase == Phase.PICK || state.template == null -> PickJob(state, settings, graph)
        state.phase == Phase.TYPING -> Typing(state, settings, graph)
        else -> Formula(state, settings, graph)
    }
}

// ------------------------------------------------------------------ S01

@Composable
private fun Welcome(graph: Graph) {
    // q1-6 (D44): content block >= 80 % of the window height, centred (blank above + below <= 20 %)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(stringResource(R.string.app_name), "welcome/title", style = PicoTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
            WelcomeCard(R.drawable.tpl_tank, stringResource(R.string.welcome_1), "welcome/1", Modifier.weight(1f))
            WelcomeCard(R.drawable.tpl_free, stringResource(R.string.welcome_2), "welcome/2", Modifier.weight(1f))
            WelcomeCard(R.drawable.tpl_fit, stringResource(R.string.welcome_3), "welcome/3", Modifier.weight(1f))
        }
        // v0.1.2 (B31): [跳过] did the same as [开始] — one button
        PrimaryButton(stringResource(R.string.welcome_start), "welcome/start", onClick = { graph.flow.welcomeDone() }, modifier = Modifier.fillMaxWidth(), height = 60.dp)
    }
}

@Composable
private fun WelcomeCard(icon: Int, text: String, tag: String, modifier: Modifier) {
    Column(
        modifier.height(320.dp).clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillSecondary).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(136.dp, 102.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
        Txt(text, tag, style = PicoTheme.typography.titleMedium, maxLines = 4, align = TextAlign.Center)
    }
}

// ------------------------------------------------------------------ S02

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickJob(state: CalcState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val fixed = listOf(TemplateId.TANK, TemplateId.TILE, TemplateId.PAINT, TemplateId.FIT, TemplateId.RUG, TemplateId.FREE)
    // v0.1.2 (B31): no Toolbar on this page, no subtitle / footer line; the six jobs are the page
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(BTN_GAP)) {
        PageTitle(stringResource(R.string.pick_title), "pick") { MuteKey(settings.muted, onClick = { flow.toggleMute() }) }
        // K6 (QA3): saved templates must not sit under the fold as a 6 px sliver. With templates the six job cards get
        // shorter and the templates follow as a labelled row of buttons, so up to six of them fit without scrolling.
        val customs = state.customs
        val cardH = if (customs.isEmpty()) 218.dp else 120.dp // v0.1.6: 140 → 120 (2-line English labels fit, OVERFLOW 0) so the 16 dp gaps (q6-1) keep two template rows above the fold
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(BTN_GAP)) {
            fixed.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
                    row.forEach { t -> JobCard(templateIcon(t), templateName(t), "pick/${t.id}", Modifier.weight(1f), cardH) { flow.pick(t) } }
                }
            }
            if (customs.isNotEmpty()) {
                Txt(stringResource(R.string.my_templates, customs.size), "pick/mine", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
                customs.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
                        row.forEach { c -> CustomTile(c.name, "pick/custom-${c.id}", Modifier.weight(1f)) { flow.pickCustom(c.id) } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * K6: a saved template on the job page. v0.1.5 (q5-1): a name that ends with its date stamp shows the stamp on its
 * own line, so two templates of the same job are told apart at a glance; the tile grows instead of cutting text.
 */
@Composable
private fun CustomTile(name: String, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val (base, stamp) = io.embodit.spatialcalc.domain.calc.DefaultName.split(name)
    Row(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(PicoTheme.colorScheme.fillSecondary).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(R.drawable.tpl_custom), contentDescription = null, modifier = Modifier.size(40.dp, 30.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            if (base.isNotEmpty()) Txt(base, tag, style = PicoTheme.typography.labelMedium, maxLines = 4)
            if (stamp != null) Txt(stamp, "$tag/date", style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}

@Composable
private fun JobCard(icon: Int, name: String, tag: String, modifier: Modifier, height: androidx.compose.ui.unit.Dp = 218.dp, onClick: () -> Unit) {
    val small = height < 200.dp
    Column(
        modifier.height(height).clip(RoundedCornerShape(22.dp)).background(PicoTheme.colorScheme.fillSecondary).clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = if (small) Modifier.size(64.dp, 48.dp) else Modifier.size(128.dp, 96.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
        Txt(name, tag, style = PicoTheme.typography.labelLarge, maxLines = 2, align = TextAlign.Center)
    }
}

// ------------------------------------------------------------------ S03 … S09, S26

/**
 * v0.1.2 (B31): title + one ⋯ More button. The tank shape, mute and the unit moved into More; the title row carries
 * no other control.
 */
@Composable
private fun TitleRow(state: CalcState, settings: Settings, graph: Graph, more: Boolean) {
    Row(Modifier.fillMaxWidth().height(BTN_H), verticalAlignment = Alignment.CenterVertically) {
        Txt(templateName(state.template, state.customName), "calc/title", style = PicoTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
        // q3-1 (D15, user 2026-11-12「常显静音」): mute stands on the page in every state, outside the B31 seven
        MuteKey(settings.muted, onClick = { graph.flow.toggleMute() })
        if (more) {
            Spacer(Modifier.width(BTN_GAP))
            val open = state.resultMenu
            Button(
                onClick = { graph.flow.resultMenu(!open) },
                size = ButtonDefaults.Regular.copy(height = BTN_H, minWidth = BTN_H), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (open) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary,
                    contentColor = if (open) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary,
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more), contentDescription = stringResource(if (open) R.string.btn_close else R.string.btn_more),
                    tint = if (open) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary,
                )
            }
        }
    }
}

/** The one visual lead of the page (B31): the result, large, centred. Errors take its place in the error colour. */
@Composable
private fun Hero(state: CalcState, settings: Settings, small: Boolean = false) {
    val text = resultText(state.result, state.template, state.resultUnit, settings.imperial)
    val err = state.result.error?.takeIf { it != io.embodit.spatialcalc.domain.calc.CalcError.EMPTY }
    val shown = when {
        err != null -> if (err == io.embodit.spatialcalc.domain.calc.CalcError.UNIT_MISMATCH) stringResource(R.string.err_unit_mismatch, state.result.errorDetail) else stringResource(R.string.err_dim)
        text == null -> stringResource(R.string.result_placeholder)
        else -> "= $text"
    }
    val size = when {
        err != null -> 22
        small -> if (shown.length > 18) 22 else 30
        shown.length <= 12 -> 60
        shown.length <= 18 -> 40
        shown.length <= 26 -> 30
        else -> 24
    }
    Txt(
        shown, if (err != null) "calc/result-error" else "calc/result", style = TextStyle(fontSize = size.sp, lineHeight = (size * 1.2).sp),
        color = when { err != null -> CalcColors.ErrorText; text == null -> CalcColors.InkSecondary; else -> CalcColors.TertiaryText },
        modifier = Modifier.fillMaxWidth(), maxLines = 2, align = TextAlign.Center,
    )
}

/** v0.1.2: the calculator「display」frame (a faint tint + hairline) shared by the Calc page and the records page. */
internal fun displayFrame(): Modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0x40FFFFFF)).border(1.5.dp, Color(0x40000000), RoundedCornerShape(24.dp))

@Composable
private fun ChipButton(text: String, tag: String, kind: Int, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // kind: 0 empty slot, 1 measured (yellow), 2 number (white), 3 operator (grey)
    val bg = when (kind) { 1 -> CalcColors.SecondaryContainer; 2 -> CalcColors.NumberChip; 3 -> CalcColors.OpChip; else -> Color(0x33FFFFFF) }
    val fg = when (kind) { 1 -> CalcColors.OnSecondaryContainer; else -> CalcColors.Ink }
    Button(
        onClick = onClick,
        size = ButtonDefaults.Regular.copy(height = BTN_H, minWidth = BTN_H),
        shape = RoundedCornerShape(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        modifier = modifier.then(if (active) Modifier.border(2.5.dp, CalcColors.Primary, RoundedCornerShape(16.dp)) else Modifier),
    ) {
        // design-style: inherited-content-color Button. q4-1: two lines of 16 sp fit the 64 dp chip, so a long
        // English slot name ("shelf height") wraps instead of widening the chip column
        Txt(text, tag, style = TextStyle(fontSize = 16.sp, lineHeight = 19.sp), color = fg, maxLines = 2, align = TextAlign.Center)
    }
}

/** A read-only chip inside the quiet formula strip (lighter: smaller, no button of its own). */
@Composable
private fun ChipLabel(text: String, tag: String, kind: Int, active: Boolean) {
    val bg = when (kind) { 1 -> CalcColors.SecondaryContainer; 2 -> CalcColors.NumberChip; 3 -> Color.Transparent; else -> Color(0x33FFFFFF) }
    val fg = when (kind) { 1 -> CalcColors.OnSecondaryContainer; else -> CalcColors.Ink }
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (active) Modifier.border(2.dp, CalcColors.Primary, RoundedCornerShape(10.dp)) else Modifier)
            .padding(horizontal = if (kind == 3) 2.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, tag, style = TextStyle(fontSize = 16.sp), color = fg) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(state: CalcState, unit: LenUnit, graph: Graph, wrap: Boolean = false, typing: Boolean = false, columns: Int = 0) {
    val flow = graph.flow
    val content: @Composable () -> Unit = {
        if (state.tokensMode) {
            state.tokens.forEachIndexed { i, t ->
                val kind = when { t is Token.Op -> 3; (t as Token.Num).source == Source.TYPED -> 2; else -> 1 }
                ChipButton(tokenLabel(t, unit), "calc/chip$i", kind, state.chipMenu == i) { if (typing) flow.typingChipTap(i) else flow.chipTap(i) }
            }
            if (state.tokens.isEmpty()) ChipButton("…", "calc/chip-empty", 0, true) { flow.openTyping() }
        } else {
            io.embodit.spatialcalc.domain.calc.Templates.prefix(state.template!!, state.round).takeIf { it.isNotEmpty() }?.let {
                Txt(it, "calc/join0", style = TextStyle(fontSize = 20.sp), color = CalcColors.InkSecondary, modifier = Modifier.padding(top = 10.dp))
            }
            state.slots.forEachIndexed { i, v ->
                if (i > 0) Txt(io.embodit.spatialcalc.domain.calc.Templates.joiner(state.template!!, state.round, i - 1), "calc/join$i", style = TextStyle(fontSize = 20.sp), color = CalcColors.InkSecondary, modifier = Modifier.padding(top = 10.dp))
                val kind = when { v.q == null -> 0; v.source == Source.TAPE || v.source == Source.FITBOX -> 1; else -> 2 }
                ChipButton(slotLabel(v, unit), "calc/chip$i", kind, (typing && i == state.active) || (!typing && i == state.active && v.q == null) || state.chipMenu == i) {
                    // q3-3: on the keyboard a chip tap saves the field into its slot first, then moves the keyboard
                    if (typing) flow.typingChipTap(i)
                    else if (v.q == null) { flow.selectSlot(i); if (v.def.kind == SlotKind.NUMBER) flow.openTyping(i) else flow.chipTap(i) } else flow.chipTap(i)
                }
            }
        }
    }
    if (wrap && !state.tokensMode && columns > 0) {
        // q4-1 (QA4): the template slots as a fixed grid (no joiners): six English slots take three rows of two on the
        // keyboard page instead of six rows of one; the formula itself is the result line under the grid
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BTN_GAP)) {
            state.slots.withIndex().chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
                    row.forEach { (i, v) ->
                        val kind = when { v.q == null -> 0; v.source == Source.TAPE || v.source == Source.FITBOX -> 1; else -> 2 }
                        ChipButton(slotLabel(v, unit), "calc/chip$i", kind, (typing && i == state.active) || (!typing && i == state.active && v.q == null) || state.chipMenu == i, Modifier.weight(1f)) {
                            if (typing) flow.typingChipTap(i)
                            else if (v.q == null) { flow.selectSlot(i); if (v.def.kind == SlotKind.NUMBER) flow.openTyping(i) else flow.chipTap(i) } else flow.chipTap(i)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    } else if (wrap) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP), verticalArrangement = Arrangement.spacedBy(BTN_GAP)) { content() }
    } else {
        Row(Modifier.fillMaxWidth().height(BTN_H).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(BTN_GAP), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/**
 * v0.1.2 (B31): the formula as ONE quiet control. Tap → chip edit mode (the chips become their own buttons with
 * 改数 · 重量 · 删), so the numbers stay one tap from being fixed without five buttons standing on the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormulaStrip(state: CalcState, unit: LenUnit, graph: Graph, enabled: Boolean) {
    val flow = graph.flow
    val onTap = {
        when {
            state.tokensMode && state.tokens.none { it is Token.Num } -> flow.openTyping()
            state.tokensMode -> flow.chipTap(state.tokens.indexOfFirst { it is Token.Num })
            else -> flow.chipTap(state.slots.indexOfFirst { it.q != null && it.def.kind != SlotKind.NUMBER }.takeIf { it >= 0 } ?: state.active)
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x66FFFFFF))
            .then(if (enabled) Modifier.clickable(onClick = onTap) else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.tokensMode) {
            state.tokens.forEachIndexed { i, t ->
                val kind = when { t is Token.Op -> 3; (t as Token.Num).source == Source.TYPED -> 2; else -> 1 }
                ChipLabel(tokenLabel(t, unit), "calc/strip$i", kind, false)
            }
            if (state.tokens.isEmpty()) ChipLabel("…", "calc/strip-empty", 0, false)
        } else {
            io.embodit.spatialcalc.domain.calc.Templates.prefix(state.template!!, state.round).takeIf { it.isNotEmpty() }?.let {
                ChipLabel(it, "calc/strip-join0", 3, false)
            }
            state.slots.forEachIndexed { i, v ->
                if (i > 0) ChipLabel(io.embodit.spatialcalc.domain.calc.Templates.joiner(state.template!!, state.round, i - 1), "calc/strip-join$i", 3, false)
                val kind = when { v.q == null -> 0; v.source == Source.TAPE || v.source == Source.FITBOX -> 1; else -> 2 }
                ChipLabel(slotLabel(v, unit), "calc/strip$i", kind, i == state.active && v.q == null && state.phase != Phase.FORMULA)
            }
        }
    }
}

@Composable
private fun Formula(state: CalcState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val unit = if (settings.imperial) LenUnit.IN else LenUnit.CM
    val plain = io.embodit.spatialcalc.domain.session.Chrome.plainFormula(state)
    val toolbar = io.embodit.spatialcalc.domain.session.Chrome.toolbar(state).isNotEmpty()
    // the how-to line goes by itself (no standing instructions, B31)
    androidx.compose.runtime.LaunchedEffect(state.hint, state.hintArg, state.template) {
        if (state.hint == Hint.TIP) { kotlinx.coroutines.delay(TIP_MS); flow.dismissTip() }
    }
    Column(Modifier.fillMaxSize().padding(bottom = if (toolbar) 64.dp else 0.dp), verticalArrangement = Arrangement.SpaceBetween) {
        TitleRow(state, settings, graph, more = plain || (state.resultMenu && state.phase == Phase.FORMULA && state.confirm == null))
        when {
            state.resultMenu && state.phase == Phase.FORMULA && state.confirm == null -> {
                Hero(state, settings, small = true)
                MoreSheet(state, settings, graph)
            }
            state.chipMenu != null && state.phase == Phase.FORMULA && state.confirm == null -> {
                Hero(state, settings, small = true)
                Txt(stringResource(R.string.strip_edit), "calc/strip-edit", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
                ChipRow(state, unit, graph, wrap = true, columns = 3)
                // chip menu (S03 / S11): 改数 · 重量 · 删
                val i = state.chipMenu
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
                    val isOp = state.tokensMode && state.tokens.getOrNull(i) is Token.Op
                    if (!isOp && !(state.tokensMode)) SecondaryButton(stringResource(R.string.btn_edit), "calc/chip-edit", onClick = { flow.openTyping(i) }, modifier = Modifier.weight(1f))
                    if (!isOp && state.slots.getOrNull(i)?.def?.unit?.let { it == SlotUnit.LENGTH } != false) SecondaryButton(stringResource(R.string.btn_remeasure), "calc/chip-remeasure", onClick = { flow.chipRemeasure(i) }, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.btn_delete), "calc/chip-delete", onClick = { flow.chipDelete(i) }, modifier = Modifier.weight(1f))
                }
                PrimaryButton(stringResource(R.string.btn_done), "calc/chip-done", onClick = { flow.closeChipMenu() }, modifier = Modifier.fillMaxWidth(), height = 56.dp)
            }
            else -> {
                // the display: one framed block that holds the result (the lead), the formula strip and the status line
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp).then(displayFrame()).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Hero(state, settings)
                    FormulaStrip(state, unit, graph, enabled = plain)
                    HintLine(state)
                }
                ActionRow(state, graph)
            }
        }
    }
}

/** More (⋯): the secondary items of this moment, two per row (Chrome.more decides which). */
@Composable
private fun MoreSheet(state: CalcState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val items = io.embodit.spatialcalc.domain.session.Chrome.more(state)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BTN_GAP)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
                row.forEach { item ->
                    val (label, danger) = moreLabel(item, state, settings)
                    SecondaryButton(label, "more/${item.name.lowercase()}", danger = danger, modifier = Modifier.weight(1f), onClick = {
                        val keepOpen = item == MoreItem.UNIT
                        if (!keepOpen) flow.resultMenu(false)
                        when (item) {
                            MoreItem.TYPE -> flow.openTyping()
                            MoreItem.MEASURE -> flow.measureButton()
                            MoreItem.FITBOX -> graph.fitBox()
                            MoreItem.SAVE -> flow.saveCard()
                            MoreItem.SAVE_TEMPLATE -> flow.startNaming()
                            MoreItem.UNIT -> flow.cycleUnit()
                            MoreItem.SHAPE -> flow.setRound(!state.round)
                            MoreItem.BRING -> graph.bringBody()
                            MoreItem.PUT_AWAY -> flow.collapseBody()
                            MoreItem.HELP -> flow.showTip()
                            MoreItem.CLEAR -> flow.clearAsk()
                        }
                    })
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun moreLabel(item: MoreItem, state: CalcState, settings: Settings): Pair<String, Boolean> = when (item) {
    MoreItem.TYPE -> stringResource(R.string.btn_type) to false
    MoreItem.MEASURE -> stringResource(R.string.btn_measure) to false
    MoreItem.FITBOX -> stringResource(R.string.btn_fitbox) to false
    MoreItem.SAVE -> stringResource(R.string.btn_save) to false
    MoreItem.SAVE_TEMPLATE -> stringResource(R.string.btn_save_template) to false
    MoreItem.UNIT -> stringResource(R.string.more_unit, (state.resultUnit ?: io.embodit.spatialcalc.domain.calc.ResultUnit.default(state.result.q!!.dim, settings.imperial)).label.ifEmpty { "·" }) to false
    MoreItem.SHAPE -> stringResource(if (state.round) R.string.more_shape_box else R.string.more_shape_round) to false
    MoreItem.BRING -> stringResource(R.string.btn_bring) to false
    MoreItem.PUT_AWAY -> stringResource(R.string.btn_hide_body) to false
    MoreItem.HELP -> stringResource(R.string.more_help) to false
    MoreItem.CLEAR -> stringResource(R.string.btn_clear) to true
}

/** The one primary button of the plain formula view (Chrome.primary). */
@Composable
private fun PrimaryAction(state: CalcState, graph: Graph) {
    val flow = graph.flow
    val p = io.embodit.spatialcalc.domain.session.Chrome.primary(state)
    val label = when (p) {
        io.embodit.spatialcalc.domain.session.PrimaryAction.MEASURE -> {
            val slot = state.slots.getOrNull(state.active)?.takeIf { it.q == null && it.def.kind != SlotKind.NUMBER }
                ?: state.slots.firstOrNull { it.q == null && it.def.kind != SlotKind.NUMBER }
            if (slot != null) stringResource(R.string.btn_measure_slot, slotName(slot.def.key)) else stringResource(R.string.btn_measure)
        }
        io.embodit.spatialcalc.domain.session.PrimaryAction.PLACE -> stringResource(R.string.btn_place)
        io.embodit.spatialcalc.domain.session.PrimaryAction.SAVE -> stringResource(R.string.btn_save)
        io.embodit.spatialcalc.domain.session.PrimaryAction.NEXT -> stringResource(R.string.records_next)
    }
    PrimaryButton(label, "calc/primary-${p.name.lowercase()}", enabled = io.embodit.spatialcalc.domain.session.Chrome.primaryEnabled(state), modifier = Modifier.fillMaxWidth(), height = 60.dp, onClick = {
        when (p) {
            io.embodit.spatialcalc.domain.session.PrimaryAction.MEASURE -> flow.measureButton()
            io.embodit.spatialcalc.domain.session.PrimaryAction.PLACE -> flow.equals()
            io.embodit.spatialcalc.domain.session.PrimaryAction.SAVE -> flow.saveCard()
            io.embodit.spatialcalc.domain.session.PrimaryAction.NEXT -> flow.next()
        }
    })
}

@Composable
private fun ActionRow(state: CalcState, graph: Graph) {
    val flow = graph.flow
    when {
        state.confirm == Confirm.CLEAR -> Column(verticalArrangement = Arrangement.spacedBy(BTN_GAP)) {
            Txt(stringResource(R.string.clear_ask), "calc/clear-ask", style = PicoTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "calc/clear-cancel", onClick = { flow.confirmNo() }, modifier = Modifier.weight(1f), tall = true)
                SecondaryButton(stringResource(R.string.btn_clear_yes), "calc/clear-yes", danger = true, onClick = { flow.confirmYes() }, modifier = Modifier.weight(1f), tall = true)
            }
        }
        state.naming -> NameRow(state, graph)
        state.savedPreview -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
            PrimaryButton(stringResource(R.string.btn_pin_here), "saved/pin", onClick = { flow.pinHere() }, modifier = Modifier.weight(1.4f), height = 56.dp)
            SecondaryButton(stringResource(R.string.btn_just_save), "saved/just", onClick = { flow.closeSaved() }, modifier = Modifier.weight(1f), tall = true)
            SecondaryButton(stringResource(R.string.btn_next), "saved/next", onClick = { flow.next() }, modifier = Modifier.weight(1f), tall = true)
        }
        state.phase == Phase.AIM || state.phase == Phase.PULL -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
            SecondaryButton(stringResource(R.string.btn_type), "calc/type", onClick = { flow.cancelMeasure(); flow.openTyping() }, modifier = Modifier.weight(1f), tall = true)
            SecondaryButton(stringResource(R.string.btn_cancel), "calc/cancel-measure", onClick = { flow.cancelMeasure() }, modifier = Modifier.weight(1f), tall = true)
        }
        state.phase == Phase.PLACE -> SecondaryButton(stringResource(R.string.btn_cancel), "calc/cancel-place", onClick = { flow.cancelPlace() }, modifier = Modifier.fillMaxWidth(), tall = true)
        state.phase == Phase.FITBOX -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
            PrimaryButton(stringResource(R.string.btn_use_sides), "fitbox/use", onClick = { flow.fitBoxUse() }, modifier = Modifier.weight(2f))
            SecondaryButton(stringResource(R.string.btn_cancel), "fitbox/cancel", onClick = { flow.fitBoxCancel() }, modifier = Modifier.weight(1f), tall = true)
        }
        else -> PrimaryAction(state, graph)
    }
}

@Composable
private fun NameRow(state: CalcState, graph: Graph) {
    val default = defaultTemplateName(state.template, state.round, state.customName, System.currentTimeMillis())
    NameEditor(default, "saved", onCancel = { graph.flow.cancelNaming() }, onOk = { graph.flow.saveAsTemplate(it) })
}

/**
 * The template naming row (K7 / K13 / K14): hint, the name field (its accessibility name is the hint, K14) and
 * [取消] [好]. Used by the Calc page ⋯ and by a record card's ⋯, so both ways to save a template ask for a name.
 */
@Composable
internal fun NameEditor(default: String, tag: String, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var name by remember(default) { mutableStateOf(default) }
    val hint = stringResource(R.string.name_hint)
    Column(verticalArrangement = Arrangement.spacedBy(BTN_GAP)) {
        Txt(hint, "$tag/name-hint", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        BasicTextField(
            value = name, onValueChange = { name = it.take(24) }, singleLine = true,
            textStyle = TextStyle(fontSize = 18.sp, color = CalcColors.Ink),
            modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCCFFFFFF)).padding(horizontal = 14.dp, vertical = 14.dp)
                .semantics { contentDescription = hint },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
            SecondaryButton(stringResource(R.string.btn_cancel), "$tag/name-cancel", onClick = onCancel, modifier = Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.btn_ok), "$tag/name-ok", onClick = { onOk(name.trim().ifEmpty { default }) }, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * One status line, only while there is something to say (B31: no standing instructions): the step while aiming /
 * placing / fitting, a one-shot hint (errors,「量到的数进来了」, the first-time how-to), or a live warning.
 */
@Composable
private fun HintLine(state: CalcState) {
    val guide = when (state.phase) {
        Phase.AIM -> stringResource(R.string.aim_first)
        Phase.PULL -> stringResource(R.string.pull_other)
        Phase.PLACE -> stringResource(R.string.hint_place)
        Phase.FITBOX -> stringResource(R.string.hint_fitbox)
        else -> null
    }
    val extra = when {
        state.lost -> stringResource(R.string.hint_lost)
        state.noPlanes && (state.phase == Phase.AIM || state.phase == Phase.PULL || state.phase == Phase.PLACE) -> stringResource(R.string.no_planes)
        state.body?.faded == true -> stringResource(if (state.body.attached) R.string.hint_cone_attached else R.string.hint_cone)
        state.template == TemplateId.FIT && state.result.fit?.count == 0 && state.body != null -> stringResource(R.string.hint_turn_boxes)
        state.result.partialEdge && state.template == TemplateId.TILE -> stringResource(R.string.tile_edge)
        state.headSampling -> stringResource(R.string.hint_look_ahead)
        else -> null
    }
    val main = hintText(state)?.takeIf { it != guide }
    val measureOr = if (state.phase == Phase.AIM && state.slots.getOrNull(state.active)?.def?.kind == SlotKind.MEASURE_OR_NUMBER) stringResource(R.string.or_type) else null
    val text = listOfNotNull(guide, main, extra, measureOr).joinToString(" · ")
    if (text.isEmpty()) return
    Txt(
        text, "calc/hint", style = PicoTheme.typography.bodyLarge, maxLines = 3, align = TextAlign.Center,
        color = if (isErrorHint(state.hint)) CalcColors.ErrorText else PicoTheme.colorScheme.labelSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val TIP_MS = 10_000L

// ------------------------------------------------------------------ S07 typing (two columns)

/**
 * q4-1 (QA4): title row on top, the input + slot grid (scrolls if it ever has to) beside the keyboard, and a bar along
 * the bottom that never moves: unit · [Paste] · [✓ Done]. However many slots a job has and in either language,
 * [✓ Done] stays on the panel (the old left column put six English slots one per row and pushed it off the bottom).
 */
@Composable
private fun Typing(state: CalcState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val context = LocalContext.current
    val unit = state.typingUnit
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(BTN_GAP)) {
        Row(Modifier.fillMaxWidth().height(BTN_H), verticalAlignment = Alignment.CenterVertically) {
            Txt(templateName(state.template, state.customName), "typing/title", style = PicoTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
            MuteKey(settings.muted, onClick = { flow.toggleMute() }, compact = true) // q3-1
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(BTN_GAP)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(BTN_GAP)) {
                val target = if (state.tokensMode) stringResource(R.string.chip_free_num) else state.slots.getOrNull(state.active)?.let { slotName(it.def.key) } ?: ""
                // q4-1: the「typing into」line lives inside the field (name left, number right), one row less than v0.1.3
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCCFFFFFF)).padding(horizontal = 12.dp)
                        .semantics { contentDescription = target },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val slotUnit = state.slots.getOrNull(state.active)?.def?.unit
                    val suffix = if (state.tokensMode || slotUnit == SlotUnit.LENGTH) unit.label else slotUnit?.label ?: ""
                    Txt(target, "typing/guide", style = TextStyle(fontSize = 13.sp, lineHeight = 16.sp), color = CalcColors.InkSecondary, maxLines = 2, modifier = Modifier.weight(1f))
                    Txt((state.typingBuffer.ifEmpty { "0" }) + " " + suffix, "typing/buffer", style = TextStyle(fontSize = 22.sp), color = CalcColors.Ink)
                }
                ChipRow(state, unit, graph, wrap = true, typing = true, columns = 2)
                ResultRowCompact(state, settings)
            }
            Keyboard(state, graph)
        }
        // the fixed bottom bar (q4-1): never scrolled, never pushed out
        Row(Modifier.fillMaxWidth().height(BTN_H), horizontalArrangement = Arrangement.spacedBy(BTN_GAP), verticalAlignment = Alignment.CenterVertically) {
            Segments(listOf(LenUnit.CM to "cm", LenUnit.M to "m", LenUnit.IN to "in", LenUnit.FT to "ft"), unit, "typing/unit", { flow.setTypingUnit(it) }, Modifier.weight(1f), small = true)
            SecondaryButton(stringResource(R.string.btn_paste), "typing/paste", onClick = { readClipboard(context)?.let { flow.paste(it) } ?: flow.hint(Hint.PASTE_BAD) }, minWidth = 104.dp)
            PrimaryButton(stringResource(R.string.btn_done), "typing/done", onClick = { flow.closeTyping() }, modifier = Modifier.width(132.dp))
        }
    }
}

@Composable
private fun ResultRowCompact(state: CalcState, settings: Settings) {
    val text = resultText(state.result, state.template, state.resultUnit, settings.imperial)
    val msg = hintText(state)
    Column {
        Txt(if (text == null) stringResource(R.string.result_placeholder) else "= $text", "typing/result", style = TextStyle(fontSize = 22.sp), color = CalcColors.TertiaryText, maxLines = 2)
        if (msg != null) Txt(msg, "typing/hint", style = PicoTheme.typography.bodySmall, color = if (isErrorHint(state.hint)) CalcColors.ErrorText else PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    }
}

/** 4 columns × 6 rows, every key 52 × 48 dp (design wireframe S07). Operators only act in a free formula. */
@Composable
private fun Keyboard(state: CalcState, graph: Graph) {
    val rows = listOf(
        listOf("7", "8", "9", "⌫"), listOf("4", "5", "6", "÷"), listOf("1", "2", "3", "×"),
        listOf("0", ".", "(", ")"), listOf("√", "x²", "π", "−"), listOf("+", "", "", ""),
    )
    Column(Modifier.width(236.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
        // gate:allow button-gap / button-height: calculator keypad, 4 keys × 6 rows in a 236 dp column (design S07
        // wireframe 52 × 48 dp keys); the page's own buttons (Paste, ✓ Done, mute) are 64 dp with 16 dp gaps
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { // gate:allow button-gap calculator keypad row: 4 keys × 52 dp + 3 gaps in a 236 dp column (design S07)
                row.forEach { k ->
                    if (k.isEmpty()) Spacer(Modifier.size(52.dp, 48.dp))
                    else {
                        val op = k in Token.OPS
                        Button(
                            onClick = { graph.flow.key(k) }, enabled = !op || state.tokensMode,
                            size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 52.dp), // gate:allow button-height calculator keypad key (24 keys in a 236 × 370 dp column, design S07 52 × 48 dp)
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (op) CalcColors.OpChip else CalcColors.NumberChip, contentColor = CalcColors.Ink),
                            modifier = Modifier.size(52.dp, 48.dp),
                        ) {
                            // design-style: inherited-content-color Button
                            Txt(k, "key/$k", style = TextStyle(fontSize = if (k.length > 1 || k == "⌫") 15.sp else 20.sp), color = CalcColors.Ink, align = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

private fun readClipboard(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}.getOrNull()

@Suppress("unused") private fun ColumnScope.unused() = Unit
