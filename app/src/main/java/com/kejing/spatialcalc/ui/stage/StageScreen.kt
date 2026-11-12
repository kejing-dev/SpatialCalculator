package com.kejing.spatialcalc.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.kejing.spatialcalc.R
import com.kejing.spatialcalc.domain.calc.Evaluate
import com.kejing.spatialcalc.domain.calc.Fmt
import com.kejing.spatialcalc.domain.calc.LenUnit
import com.kejing.spatialcalc.domain.session.CalcState
import com.kejing.spatialcalc.domain.session.Settings
import com.kejing.spatialcalc.domain.math.Vec3
import com.kejing.spatialcalc.platform.AppLog
import com.kejing.spatialcalc.platform.Graph
import com.kejing.spatialcalc.ui.Containers
import com.kejing.spatialcalc.ui.stage.input.BoardInputListener
import com.kejing.spatialcalc.ui.stage.input.InputSource
import com.kejing.spatialcalc.ui.stage.input.Offset3M
import com.kejing.spatialcalc.ui.stage.input.TargetResolver
import com.kejing.spatialcalc.ui.stage.input.boardInput
import com.kejing.spatialcalc.ui.theme.CalcColors
import com.kejing.spatialcalc.ui.theme.localized
import com.kejing.spatialcalc.ui.window.resultText
import com.kejing.spatialcalc.ui.window.templateName

/** The Mixed Stage: one full-size SpatialView with the room content and the plates (attachment panels). */
@Composable
fun StageScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val navigator = LocalSpatialNavigator.current
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.flow.settings.collectAsStateWithLifecycle()
    val scene = remember(graph) { StageScene(graph) }
    val stageScope = androidx.compose.runtime.rememberCoroutineScope()

    DisposableEffect(scene) {
        registerSystem<CalcFrameSystem>()
        graph.window.attach(
            open = { navigator.openWindowContainer(Containers.MAIN, Containers.MAIN_TAG) },
            close = { navigator.closeWindowContainer(Containers.MAIN, Containers.MAIN_TAG) },
        )
        graph.window.closeStage = { stageScope.launch { runCatching { navigator.closeStage() } } }
        graph.window.request("start")
        AppLog.i("STAGE enter")
        onDispose {
            unregisterSystem<CalcFrameSystem>()
            graph.window.detach()
            scene.dispose()
            AppLog.i("STAGE disposed")
        }
    }

    // background: stop sensing work, keep the card; foreground: back where it was (container.lifecycle)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> AppLog.i("LIFECYCLE background")
                Lifecycle.Event.ON_START -> AppLog.i("LIFECYCLE foreground")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(state, settings, scene.ready.value) { scene.render(state, settings) }

    SpatialView(
        modifier = Modifier.fillMaxSize().boardInput(
            TargetResolver.byName(Names::resolve),
            object : BoardInputListener<Hit> {
                override fun onTap(target: Hit?, source: InputSource, position: Offset3D?) = scene.onTap(target, source, position)
                override fun onDragStart(target: Hit, source: InputSource): Boolean = scene.onDragStart(target)
                override fun onDragMove(totalM: Offset3M) = scene.onDragMove(Vec3(totalM.x, totalM.y, totalM.z))
                override fun onDragEnd() = scene.onDragEnd()
                override fun onDragCancel() = scene.onDragEnd()
                override fun onHover(target: Hit?, source: InputSource) = Unit // the system HoverEffect on bodies / handles is the feedback
            },
        ),
        attachments = {
            PanelIds.all.forEach { id -> AttachmentPanel(id) { PlateRoot(id, scene, graph) } }
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
        update = { _, attachments -> scene.bind(attachments) },
    )
}

/** Each plate is its own composition: it reads state + language itself (a language switch reaches it). */
@Composable
private fun PlateRoot(id: String, scene: StageScene, graph: Graph) {
    val base = LocalContext.current
    val st by graph.flow.state.collectAsStateWithLifecycle()
    val s by graph.flow.settings.collectAsStateWithLifecycle()
    val shown = scene.panelShown[id] == true
    key(s.language) {
        CompositionLocalProvider(LocalContext provides base.localized(s.language)) {
            PicoTheme {
                if (!shown) Box(Modifier.size(8.dp)) else when {
                    id == PanelIds.HUD -> Hud(graph, scene)
                    id == PanelIds.READING -> ReadingPlate(st, s)
                    id == PanelIds.RESULT -> ResultPlateUi(st, s, null)
                    id == PanelIds.DEMO -> SmallPlate(stringResource(R.string.demo_label), 220)
                    id.startsWith("fit") -> FitReading(st, s, id.removePrefix("fit").toInt())
                    id.startsWith("pin") -> ResultPlateUi(st, s, scene.pinPlates[id.removePrefix("pin").toInt()])
                }
            }
        }
    }
}

private val PlateShape = RoundedCornerShape(16.dp)

@Composable
private fun PlateText(text: String, size: Int, color: Color = Color.White, weight: FontWeight = FontWeight.SemiBold, tag: String) {
    androidx.compose.foundation.text.BasicText(
        text, style = TextStyle(fontSize = size.sp, color = color, fontWeight = weight, textAlign = TextAlign.Center), maxLines = 2,
        onTextLayout = { if (it.hasVisualOverflow) AppLog.w("OVERFLOW plate/$tag \"$text\"") },
    )
}

@Composable
private fun SmallPlate(text: String, width: Int) {
    Box(Modifier.size(width.dp, 56.dp).clip(PlateShape).background(CalcColors.PlateInk), contentAlignment = Alignment.Center) {
        PlateText(text, 18, tag = "small")
    }
}

/** 读数牌 (空间尺): "0.620 m ± 4 mm" on plateInk. */
@Composable
private fun ReadingPlate(st: CalcState, s: Settings) {
    val t = st.tapes.lastOrNull { it.lengthM > 0 } ?: return
    val u = if (s.imperial) LenUnit.IN else if (t.lengthM >= 1.0) LenUnit.M else LenUnit.CM
    SmallPlate(Fmt.length(t.lengthM, t.tolM, u), 240)
}

/** 结果牌 304 × 96: the big number (same source as the result line, D38) + template name, result-blue bar on the left. */
@Composable
private fun ResultPlateUi(st: CalcState, s: Settings, cardId: Long?) {
    val card = cardId?.let { id -> st.cards.firstOrNull { it.id == id } }
    val result = if (card != null) (if (card.tokens.isNotEmpty()) Evaluate.free(card.tokens) else Evaluate.template(card.template, card.round, card.slots)) else st.result
    val template = card?.template ?: st.template
    val text = resultText(result, template, card?.resultUnit ?: st.resultUnit, s.imperial) ?: return
    val name = templateName(template, card?.customName ?: st.customName)
    Row(Modifier.size(304.dp, 96.dp).clip(PlateShape).background(CalcColors.PlateInk), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(8.dp).fillMaxHeight().background(CalcColors.Tertiary))
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
            PlateText(text, if (text.length > 18) 18 else 24, tag = "result")
            PlateText((if (card?.pinned == true) "📍 " else "") + name, 13, color = Color(0xFFCFD6DD), weight = FontWeight.Normal, tag = "result-name")
        }
    }
}

@Composable
private fun FitReading(st: CalcState, s: Settings, i: Int) {
    val fb = st.fitBox ?: return
    val m = when (i) { 0 -> fb.size.x; 1 -> fb.size.y; else -> fb.size.z }
    val u = if (s.imperial) LenUnit.IN else LenUnit.CM
    SmallPlate(Fmt.length(m.toDouble(), 0.0, u), 160)
}

/** Stage HUD in the line of sight: S00「准备中」, the level gate「平视前方」, headSample「看正前方」, or [打开面板] after ×. */
@Composable
private fun Hud(graph: Graph, scene: StageScene) {
    val window = graph.window
    val st by graph.flow.state.collectAsStateWithLifecycle()
    if (window.exiting) return
    Box(Modifier.size(360.dp, 110.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF2FBF8F2)), contentAlignment = Alignment.Center) {
        when {
            !scene.ready.value -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Box(Modifier.width(12.dp))
                PlateText(stringResource(R.string.loading), 20, color = CalcColors.Ink, tag = "hud-loading")
            }
            st.headSampling -> PlateText(stringResource(R.string.hint_look_ahead), 20, color = CalcColors.Ink, tag = "hud-sample")
            window.levelHint -> PlateText(stringResource(R.string.level_hint), 20, color = CalcColors.Ink, tag = "hud-level")
            // q1-10: the default Button is ~40 dp tall, too short for a 20 sp line (OVERFLOW in English): 64 dp, 18 sp
            else -> Button(
                onClick = { window.request("chip") }, size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 240.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
            ) {
                // design-style: inherited-content-color Button (same colours as the window's primary button)
                PlateText(stringResource(R.string.open_panel), 18, color = PicoTheme.colorScheme.labelPrimaryLight, tag = "hud-open")
            }
        }
    }
}
