package com.kejing.spatialcalc.ui.window

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.kejing.spatialcalc.R
import com.kejing.spatialcalc.domain.calc.LenUnit
import com.kejing.spatialcalc.domain.calc.SlotKind
import com.kejing.spatialcalc.domain.calc.SlotUnit
import com.kejing.spatialcalc.domain.calc.Source
import com.kejing.spatialcalc.domain.calc.TemplateId
import com.kejing.spatialcalc.domain.calc.Token
import com.kejing.spatialcalc.domain.session.CalcState
import com.kejing.spatialcalc.domain.session.Confirm
import com.kejing.spatialcalc.domain.session.Hint
import com.kejing.spatialcalc.domain.session.Phase
import com.kejing.spatialcalc.domain.session.Settings
import com.kejing.spatialcalc.platform.Graph
import com.kejing.spatialcalc.ui.theme.CalcColors

/** The Calc page: S01 welcome, S02 job grid, S03–S09 / S26 formula (one column) and S07 typing (two columns). */
@Composable
internal fun CalcPage(state: CalcState, settings: Settings, graph: Graph) {
    when {
        state.phase == Phase.WELCOME || state.phase == Phase.LOADING -> Welcome(graph)
        state.phase == Phase.PICK || state.template == null -> PickJob(state, graph)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WelcomeCard(R.drawable.tpl_tank, stringResource(R.string.welcome_1), "welcome/1", Modifier.weight(1f))
            WelcomeCard(R.drawable.tpl_free, stringResource(R.string.welcome_2), "welcome/2", Modifier.weight(1f))
            WelcomeCard(R.drawable.tpl_fit, stringResource(R.string.welcome_3), "welcome/3", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.welcome_skip), "welcome/skip", onClick = { graph.flow.welcomeDone() }, modifier = Modifier.weight(1f), tall = true)
            PrimaryButton(stringResource(R.string.welcome_start), "welcome/start", onClick = { graph.flow.welcomeDone() }, modifier = Modifier.weight(2f))
        }
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
private fun PickJob(state: CalcState, graph: Graph) {
    val flow = graph.flow
    val fixed = listOf(TemplateId.TANK, TemplateId.TILE, TemplateId.PAINT, TemplateId.FIT, TemplateId.RUG, TemplateId.FREE)
    Column(Modifier.fillMaxSize().padding(bottom = 64.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            PageTitle(stringResource(R.string.pick_title), "pick")
            Txt(stringResource(R.string.pick_sub), "pick/sub", style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary)
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val items = fixed.map { Triple(templateIcon(it), it, null as Long?) } + state.customs.map { Triple(R.drawable.tpl_custom, TemplateId.CUSTOM, it.id as Long?) }
            items.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (icon, t, cid) ->
                        val name = if (cid != null) state.customs.first { it.id == cid }.name else templateName(t)
                        JobCard(icon, name, "pick/${cid ?: t.id}", Modifier.weight(1f)) { if (cid != null) flow.pickCustom(cid) else flow.pick(t) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Txt(stringResource(R.string.pick_footer), "pick/footer", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
    }
}

@Composable
private fun JobCard(icon: Int, name: String, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(128.dp).clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillSecondary).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(80.dp, 60.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
        Txt(name, tag, style = PicoTheme.typography.labelLarge, maxLines = 2, align = TextAlign.Center)
    }
}

// ------------------------------------------------------------------ S03 … S09, S26

@Composable
private fun TitleRow(state: CalcState, settings: Settings, graph: Graph) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(templateName(state.template, state.customName), "calc/title", style = PicoTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
        if (state.template == TemplateId.TANK && state.phase != Phase.TYPING) {
            Segments(listOf(false to stringResource(R.string.shape_box), true to stringResource(R.string.shape_round)), state.round, "calc/shape", { graph.flow.setRound(it) }, Modifier.width(200.dp), small = true)
            Spacer(Modifier.width(8.dp))
        }
        // D15: mute is always one tap away on the Calc page
        Button(
            onClick = { graph.flow.updateSettings { it.copy(muted = !it.muted) } },
            size = ButtonDefaults.Regular.copy(height = 52.dp, minWidth = 52.dp), shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary),
        ) {
            Icon(painter = painterResource(if (settings.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up), contentDescription = stringResource(if (settings.muted) R.string.unmute else R.string.mute), tint = PicoTheme.colorScheme.labelPrimary)
        }
    }
}

/** 引导行: the next step in a few words + progress dots over the measure slots. */
@Composable
private fun GuideRow(state: CalcState) {
    val measureSlots = state.slots.indices.filter { state.slots[it].def.kind != SlotKind.NUMBER }
    val text = when {
        state.phase == Phase.AIM -> stringResource(R.string.aim_first)
        state.phase == Phase.PULL -> stringResource(R.string.pull_other)
        state.phase == Phase.PLACE -> stringResource(R.string.hint_place)
        state.phase == Phase.FITBOX -> stringResource(R.string.hint_fitbox)
        state.body != null -> stringResource(R.string.drag_hint)
        state.tokensMode -> if (state.result.ok) stringResource(R.string.step_equals) else stringResource(R.string.step_free)
        state.measureSlotEmpty -> {
            val i = state.active.takeIf { state.slots.getOrNull(it)?.q == null } ?: measureSlots.first { state.slots[it].q == null }
            val n = measureSlots.indexOf(i) + 1
            stringResource(R.string.step_measure, n.coerceAtLeast(1), measureSlots.size, slotName(state.slots[i].def.key))
        }
        else -> stringResource(R.string.step_equals)
    }
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, "calc/guide", style = PicoTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2)
        if (!state.tokensMode) measureSlots.forEach { i ->
            val filled = state.slots[i].q != null
            Box(Modifier.padding(start = 6.dp).size(10.dp).clip(CircleShape).background(if (filled) CalcColors.Secondary else Color.Transparent).border(1.5.dp, CalcColors.InkSecondary, CircleShape))
        }
    }
}

@Composable
private fun ChipButton(text: String, tag: String, kind: Int, active: Boolean, onClick: () -> Unit) {
    // kind: 0 empty slot, 1 measured (yellow), 2 number (white), 3 operator (grey)
    val bg = when (kind) { 1 -> CalcColors.SecondaryContainer; 2 -> CalcColors.NumberChip; 3 -> CalcColors.OpChip; else -> Color(0x33FFFFFF) }
    val fg = when (kind) { 1 -> CalcColors.OnSecondaryContainer; else -> CalcColors.Ink }
    Button(
        onClick = onClick,
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = if (kind == 3) 48.dp else 72.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        modifier = if (active) Modifier.border(2.5.dp, CalcColors.Primary, RoundedCornerShape(14.dp)) else Modifier,
    ) {
        // design-style: inherited-content-color Button
        Txt(text, tag, style = TextStyle(fontSize = 18.sp), color = fg)
    }
}

@Composable
private fun ChipRow(state: CalcState, unit: LenUnit, graph: Graph, wrap: Boolean = false) {
    val flow = graph.flow
    val content: @Composable () -> Unit = {
        if (state.tokensMode) {
            state.tokens.forEachIndexed { i, t ->
                val kind = when { t is Token.Op -> 3; (t as Token.Num).source == Source.TYPED -> 2; else -> 1 }
                ChipButton(tokenLabel(t, unit), "calc/chip$i", kind, state.chipMenu == i) { flow.chipTap(i) }
            }
            if (state.tokens.isEmpty()) ChipButton("…", "calc/chip-empty", 0, true) { flow.openTyping() }
        } else {
            state.slots.forEachIndexed { i, v ->
                if (i > 0) Txt(com.kejing.spatialcalc.domain.calc.Templates.joiner(state.template!!, state.round, i - 1), "calc/join$i", style = TextStyle(fontSize = 20.sp), color = CalcColors.InkSecondary, modifier = Modifier.padding(top = 10.dp))
                val kind = when { v.q == null -> 0; v.source == Source.TAPE || v.source == Source.FITBOX -> 1; else -> 2 }
                ChipButton(slotLabel(v, unit), "calc/chip$i", kind, i == state.active && v.q == null || state.chipMenu == i) {
                    if (v.q == null) { flow.selectSlot(i); if (v.def.kind == SlotKind.NUMBER) flow.openTyping(i) else flow.chipTap(i) } else flow.chipTap(i)
                }
            }
        }
    }
    if (wrap) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    } else {
        Row(Modifier.fillMaxWidth().height(56.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun ResultRow(state: CalcState, settings: Settings, graph: Graph) {
    val text = resultText(state.result, state.template, state.resultUnit, settings.imperial)
    val err = state.result.error?.takeIf { it != com.kejing.spatialcalc.domain.calc.CalcError.EMPTY }
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(16.dp)).clickable { if (state.result.ok) graph.flow.resultMenu(!state.resultMenu) }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            err != null -> Txt(
                if (err == com.kejing.spatialcalc.domain.calc.CalcError.UNIT_MISMATCH) stringResource(R.string.err_unit_mismatch, state.result.errorDetail) else stringResource(R.string.err_dim),
                "calc/result-error", style = PicoTheme.typography.titleMedium, color = CalcColors.ErrorText, modifier = Modifier.weight(1f),
            )
            text == null -> Txt(stringResource(R.string.result_placeholder), "calc/result", style = TextStyle(fontSize = 30.sp), color = CalcColors.InkSecondary, modifier = Modifier.weight(1f))
            else -> Txt("= $text", "calc/result", style = TextStyle(fontSize = if (text.length > 22) 22.sp else 30.sp), color = CalcColors.TertiaryText, modifier = Modifier.weight(1f), maxLines = 2)
        }
        if (state.result.q != null && state.result.count == null) {
            SecondaryButton(stringResource(R.string.unit_tap).let { (state.resultUnit ?: com.kejing.spatialcalc.domain.calc.ResultUnit.default(state.result.q!!.dim, settings.imperial)).label.ifEmpty { "·" } }, "calc/unit", onClick = { graph.flow.cycleUnit() }, minWidth = 64.dp)
        }
    }
}

@Composable
private fun Formula(state: CalcState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val unit = if (settings.imperial) LenUnit.IN else LenUnit.CM
    Column(Modifier.fillMaxSize().padding(bottom = 64.dp), verticalArrangement = Arrangement.SpaceBetween) {
        TitleRow(state, settings, graph)
        GuideRow(state)
        // q1-5 (D16): the chips wrap onto a second line instead of running off the right edge
        ChipRow(state, unit, graph, wrap = true)
        // chip menu (S03 / S11): 改数 · 重量 · 删
        state.chipMenu?.let { i ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val isOp = state.tokensMode && state.tokens.getOrNull(i) is Token.Op
                if (!isOp && !(state.tokensMode)) SecondaryButton(stringResource(R.string.btn_edit), "calc/chip-edit", onClick = { flow.openTyping(i) }, modifier = Modifier.weight(1f))
                if (!isOp && state.slots.getOrNull(i)?.def?.unit?.let { it == SlotUnit.LENGTH } != false) SecondaryButton(stringResource(R.string.btn_remeasure), "calc/chip-remeasure", onClick = { flow.chipRemeasure(i) }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_delete), "calc/chip-delete", onClick = { flow.chipDelete(i) }, modifier = Modifier.weight(1f))
            }
        }
        ResultRow(state, settings, graph)
        if (state.resultMenu && state.result.ok) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.btn_bring), "calc/bring", onClick = { graph.bringBody() }, enabled = state.body != null, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_hide_body), "calc/collapse", onClick = { flow.collapseBody() }, enabled = state.body != null, modifier = Modifier.weight(1f))
            }
        }
        ActionRow(state, graph)
        HintLine(state)
    }
}

@Composable
private fun ActionRow(state: CalcState, graph: Graph) {
    val flow = graph.flow
    when {
        state.confirm == Confirm.CLEAR -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(stringResource(R.string.clear_ask), "calc/clear-ask", style = PicoTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "calc/clear-cancel", onClick = { flow.confirmNo() }, modifier = Modifier.weight(1f), tall = true)
                SecondaryButton(stringResource(R.string.btn_clear_yes), "calc/clear-yes", danger = true, onClick = { flow.confirmYes() }, modifier = Modifier.weight(1f), tall = true)
            }
        }
        state.naming -> NameRow(state, graph)
        state.savedPreview -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.btn_pin_here), "saved/pin", onClick = { flow.pinHere() }, modifier = Modifier.weight(1f), height = 52.dp)
                SecondaryButton(stringResource(R.string.btn_just_save), "saved/just", onClick = { flow.closeSaved() }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.btn_save_template), "saved/template", onClick = { flow.startNaming() }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_next), "saved/next", onClick = { flow.next() }, modifier = Modifier.weight(1f))
            }
        }
        state.phase == Phase.AIM || state.phase == Phase.PULL -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.btn_type), "calc/type", onClick = { flow.cancelMeasure(); flow.openTyping() }, modifier = Modifier.weight(1f), tall = true)
            SecondaryButton(stringResource(R.string.btn_cancel), "calc/cancel-measure", onClick = { flow.cancelMeasure() }, modifier = Modifier.weight(1f), tall = true)
        }
        state.phase == Phase.PLACE -> SecondaryButton(stringResource(R.string.btn_cancel), "calc/cancel-place", onClick = { flow.cancelPlace() }, modifier = Modifier.fillMaxWidth(), tall = true)
        state.phase == Phase.FITBOX -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(stringResource(R.string.btn_use_sides), "fitbox/use", onClick = { flow.fitBoxUse() }, modifier = Modifier.weight(2f))
            SecondaryButton(stringResource(R.string.btn_cancel), "fitbox/cancel", onClick = { flow.fitBoxCancel() }, modifier = Modifier.weight(1f), tall = true)
        }
        else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(stringResource(R.string.btn_equals), "calc/equals", onClick = { flow.equals() }, enabled = state.result.ok, modifier = Modifier.weight(1.3f))
            SecondaryButton(stringResource(R.string.btn_type), "calc/type", onClick = { flow.openTyping() }, modifier = Modifier.weight(1f), tall = true)
            SecondaryButton(stringResource(R.string.btn_save), "calc/save", onClick = { flow.saveCard() }, enabled = state.result.ok, modifier = Modifier.weight(1f), tall = true)
        }
    }
}

@Composable
private fun NameRow(state: CalcState, graph: Graph) {
    val default = templateName(state.template, state.customName) + " " + fmtDate(System.currentTimeMillis())
    var name by remember { mutableStateOf(default) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(stringResource(R.string.name_hint), "saved/name-hint", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = name, onValueChange = { name = it.take(24) }, singleLine = true,
                textStyle = TextStyle(fontSize = 18.sp, color = CalcColors.Ink),
                modifier = Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCCFFFFFF)).padding(horizontal = 14.dp, vertical = 14.dp),
            )
            PrimaryButton(stringResource(R.string.btn_ok), "saved/name-ok", onClick = { graph.flow.saveAsTemplate(name) }, modifier = Modifier.width(110.dp), height = 52.dp)
        }
    }
}

@Composable
private fun HintLine(state: CalcState) {
    val extra = when {
        state.lost -> stringResource(R.string.hint_lost)
        state.noPlanes && (state.phase == Phase.AIM || state.phase == Phase.PULL || state.phase == Phase.PLACE) -> stringResource(R.string.no_planes)
        state.body?.faded == true -> stringResource(if (state.body.attached) R.string.hint_cone_attached else R.string.hint_cone)
        state.template == TemplateId.FIT && state.result.fit?.count == 0 && state.body != null -> stringResource(R.string.hint_turn_boxes)
        state.result.partialEdge && state.template == TemplateId.TILE -> stringResource(R.string.tile_edge)
        state.headSampling -> stringResource(R.string.hint_look_ahead)
        else -> null
    }
    val main = hintText(state)
    val measureOr = if (state.phase == Phase.AIM && state.slots.getOrNull(state.active)?.def?.kind == SlotKind.MEASURE_OR_NUMBER) " · " + stringResource(R.string.or_type) else ""
    val text = listOfNotNull(main, extra).joinToString(" · ").ifEmpty { templateTip(state.template) } + measureOr
    Txt(
        text, "calc/hint", style = PicoTheme.typography.bodyMedium, maxLines = 2,
        color = if (isErrorHint(state.hint)) CalcColors.ErrorText else PicoTheme.colorScheme.labelSecondary,
        modifier = Modifier.fillMaxWidth().height(44.dp),
    )
}

// ------------------------------------------------------------------ S07 typing (two columns)

@Composable
private fun Typing(state: CalcState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val context = LocalContext.current
    val unit = state.typingUnit
    Row(Modifier.fillMaxSize().padding(bottom = 64.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Txt(templateName(state.template, state.customName), "typing/title", style = PicoTheme.typography.titleMedium)
            val target = if (state.tokensMode) stringResource(R.string.chip_free_num) else state.slots.getOrNull(state.active)?.let { slotName(it.def.key) } ?: ""
            Txt(stringResource(R.string.step_typing, target), "typing/guide", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
            Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCCFFFFFF)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                val slotUnit = state.slots.getOrNull(state.active)?.def?.unit
                val suffix = if (state.tokensMode || slotUnit == SlotUnit.LENGTH) unit.label else slotUnit?.label ?: ""
                Txt((state.typingBuffer.ifEmpty { "0" }) + " " + suffix, "typing/buffer", style = TextStyle(fontSize = 24.sp), color = CalcColors.Ink)
            }
            ChipRow(state, unit, graph, wrap = true)
            ResultRowCompact(state, settings)
            Segments(listOf(LenUnit.CM to "cm", LenUnit.M to "m", LenUnit.IN to "in", LenUnit.FT to "ft"), unit, "typing/unit", { flow.setTypingUnit(it) }, Modifier.fillMaxWidth(), small = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.btn_paste), "typing/paste", onClick = { readClipboard(context)?.let { flow.paste(it) } ?: flow.hint(Hint.PASTE_BAD) }, modifier = Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.btn_done), "typing/done", onClick = { flow.closeTyping() }, modifier = Modifier.weight(1f), height = 52.dp)
            }
        }
        Keyboard(state, graph)
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
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    if (k.isEmpty()) Spacer(Modifier.size(52.dp, 48.dp))
                    else {
                        val op = k in Token.OPS
                        Button(
                            onClick = { graph.flow.key(k) }, enabled = !op || state.tokensMode,
                            size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 52.dp),
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
