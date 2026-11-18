package io.embodit.spatialcalc.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialRotateGesture
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.spatialcalc.R
import io.embodit.spatialcalc.domain.calc.Fmt
import io.embodit.spatialcalc.domain.calc.Ruler
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.domain.place.Axis
import io.embodit.spatialcalc.domain.place.PanelPlace
import io.embodit.spatialcalc.platform.AppLog
import io.embodit.spatialcalc.platform.Graph
import io.embodit.spatialcalc.ui.Containers
import io.embodit.spatialcalc.ui.stage.input.BoardInputListener
import io.embodit.spatialcalc.ui.stage.input.InputSource
import io.embodit.spatialcalc.ui.stage.input.Offset3M
import io.embodit.spatialcalc.ui.stage.input.TargetResolver
import io.embodit.spatialcalc.ui.stage.input.boardInput
import io.embodit.spatialcalc.ui.stage.input.resolveUp
import io.embodit.spatialcalc.ui.theme.CalcColors
import io.embodit.spatialcalc.ui.theme.localized
import kotlinx.coroutines.launch

/** The Mixed Stage: one full-size SpatialView holding the length line and its plates. */
@Composable
fun StageScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val navigator = LocalSpatialNavigator.current
    val scene = remember(graph) { StageScene(graph) }
    val stageScope = androidx.compose.runtime.rememberCoroutineScope()
    val converter = LocalPhysicalLengthConverter.current
    scene.mPerDp = with(converter) { converter.dpToLength(1.dp, LengthUnit.Meters) }

    DisposableEffect(scene) {
        registerSystem<CalcFrameSystem>()
        graph.window.attach(
            open = { navigator.openWindowContainer(Containers.MAIN, Containers.MAIN_TAG) },
            close = { navigator.closeWindowContainer(Containers.MAIN, Containers.MAIN_TAG) },
        )
        graph.window.closeStage = { stageScope.launch { runCatching { navigator.closeStage() } } }
        graph.window.request("start")
        AppLog.i("STAGE enter mPerDp=${scene.mPerDp}")
        onDispose {
            unregisterSystem<CalcFrameSystem>()
            graph.window.detach()
            scene.dispose()
            AppLog.i("STAGE disposed")
        }
    }

    SpatialView(
        modifier = Modifier.fillMaxSize().gaugeTwist(scene).boardInput(
            TargetResolver.byName(Targets::resolve),
            object : BoardInputListener<Hit> {
                override fun onTap(target: Hit?, source: InputSource, position: com.pico.spatial.ui.geometry.Offset3D?) = scene.onTap(target, source)
                override fun onDragStart(target: Hit, source: InputSource): Boolean = scene.onDragStart(target, source)
                override fun onDragMove(totalM: Offset3M) = scene.onDragMove(Vec3(totalM.x, totalM.y, totalM.z))
                override fun onDragEnd() = scene.onDragEnd()
                override fun onDragCancel() = scene.onDragEnd()
                override fun onHover(target: Hit?, source: InputSource) = Unit // the system HoverEffect on the line is the feedback
            },
        ),
        attachments = {
            PanelIds.all.forEach { id -> AttachmentPanel(id) { PlateRoot(id, scene, graph) } }
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
        update = { _, attachments -> scene.bind(attachments) },
    )
}

/** Each plate is its own composition and reads state + language itself (a language switch reaches it). */
@Composable
private fun PlateRoot(id: String, scene: StageScene, graph: Graph) {
    val base = LocalContext.current
    val st by graph.app.state.collectAsStateWithLifecycle()
    val s by graph.app.settings.collectAsStateWithLifecycle()
    val shown = scene.panelShown[id] == true
    key(s.language) {
        CompositionLocalProvider(LocalContext provides base.localized(s.language)) {
            PicoTheme {
                if (!shown) Box(Modifier.size(8.dp)) else when {
                    id == PanelIds.HUD -> Hud(graph, scene)
                    id == PanelIds.PLATE -> if (st.line.isBox) BoxPlate(st.line) else ValuePlate(st.line.lengthM, st.line.unit)
                    id == PanelIds.CTL -> TurnPanel(st.line, graph)
                    id.startsWith("tick") -> TickLabel(scene.tickLabels[id.removePrefix("tick").toInt()])
                }
            }
        }
    }
}

@Composable
private fun PlateText(text: String, size: Int, tag: String, color: Color = Color.White, weight: FontWeight = FontWeight.Medium, modifier: Modifier = Modifier) {
    BasicText(
        text, modifier = modifier, style = TextStyle(fontSize = size.sp, color = color, fontWeight = weight, textAlign = TextAlign.Center), maxLines = 1,
        onTextLayout = { if (it.hasVisualOverflow) AppLog.w("OVERFLOW plate/$tag \"$text\"") },
    )
}

/** The number over the line on a plateInk capsule (yaw-only toward the user). Same source as the result line (D38). */
@Composable
private fun ValuePlate(lengthM: Double, unit: io.embodit.spatialcalc.domain.calc.LenUnit) {
    val value = Fmt.length(lengthM, unit)
    Column(
        Modifier.clip(RoundedCornerShape(40.dp)).background(CalcColors.PlateInk).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlateText(value, StageScene.PLATE_SP.toInt(), "value")
        if (lengthM > Ruler.MAX_M) PlateText(stringResource(R.string.line_clipped, value), 20, "clipped", color = Color(0xFFE6EBEF), weight = FontWeight.Normal)
    }
}

/** The volume's label over the box: "1.5 L" (from a volume) or "30 × 20 × 10 cm" + "= 6 L" (from three sides). */
@Composable
private fun BoxPlate(g: io.embodit.spatialcalc.domain.app.LineState) {
    val main = if (g.fromSides) Fmt.sides(g.sides, g.unit) else Fmt.volume(g.volumeM3, g.unit)
    Column(
        Modifier.clip(RoundedCornerShape(40.dp)).background(CalcColors.PlateInk).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlateText(main, StageScene.PLATE_SP.toInt(), "box-value")
        if (g.fromSides) PlateText("= " + Fmt.volumeAuto(g.volumeM3), 24, "box-volume", color = Color(0xFFE6EBEF), weight = FontWeight.Normal)
        if (g.clipped) PlateText(stringResource(R.string.box_clipped), 20, "box-clipped", color = Color(0xFFE6EBEF), weight = FontWeight.Normal)
    }
}

/**
 * The small turn panel beside the gauge (profile D10 补): per axis [−15°] [+15°] and [回正]. The box has X / Y / Z,
 * the line its vertical axis (turn) and horizontal axis (tilt: lay ↔ slant ↔ stand). Same entry as grip + stick.
 */
@Composable
private fun TurnPanel(g: io.embodit.spatialcalc.domain.app.LineState, graph: Graph) {
    val app = graph.app
    val rows = if (g.isBox) listOf(
        Axis.X to stringResource(R.string.axis_x), Axis.Y to stringResource(R.string.axis_y), Axis.Z to stringResource(R.string.axis_z),
    ) else listOf(Axis.Y to stringResource(R.string.axis_turn), Axis.Z to stringResource(R.string.axis_tilt))
    Column(
        Modifier.width(PanelPlace.W_DP.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF2F4F6F8)).padding(PanelPlace.PAD_DP.dp),
        // q9-1: 16 dp between the rows (B31: ≥ 64 dp keys, ≥ 16 dp apart in both directions)
        verticalArrangement = Arrangement.spacedBy(PanelPlace.GAP_DP.dp),
    ) {
        // 64 dp keys (B31) at 0.7 mm / dp = 4.5 cm in the room (D17 ≥ 4 cm), ≈ 2.85° at 0.9 m
        rows.forEach { (axis, label) ->
            Row(Modifier.fillMaxWidth().height(PanelPlace.KEY_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                PlateText(label, 20, "ctl-$axis", color = CalcColors.Ink, modifier = Modifier.weight(1f))
                TurnKey("−15°", "ctl/$axis-minus") { app.gaugeTurn(axis, -1) }
                Box(Modifier.width(PanelPlace.GAP_DP.dp))
                TurnKey("+15°", "ctl/$axis-plus") { app.gaugeTurn(axis, 1) }
            }
        }
        Button(
            onClick = { AppLog.i("UI tap ctl/reset"); app.gaugeReset() }, modifier = Modifier.fillMaxWidth().height(64.dp),
            size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 96.dp), shape = RoundedCornerShape(32.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CalcColors.Accent, contentColor = CalcColors.OnAccent),
        ) {
            Icon(painter = painterResource(R.drawable.ic_restart_alt), contentDescription = null, tint = CalcColors.OnAccent, modifier = Modifier.size(24.dp))
            Box(Modifier.width(8.dp))
            // design-style: inherited-content-color Button
            PlateText(stringResource(R.string.turn_reset), 20, "ctl-reset", color = CalcColors.OnAccent)
        }
    }
}

@Composable
private fun TurnKey(text: String, tag: String, onClick: () -> Unit) {
    Button(
        onClick = { AppLog.i("UI tap $tag"); onClick() }, modifier = Modifier.size(80.dp, 64.dp),
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 80.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CalcColors.KeyFill, contentColor = CalcColors.Ink),
    ) {
        // design-style: inherited-content-color Button
        PlateText(text, 20, tag, color = CalcColors.Ink)
    }
}

@Composable
private fun TickLabel(text: String) {
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(CalcColors.PlateInk).padding(horizontal = 8.dp, vertical = 2.dp)) {
        PlateText(text, StageScene.TICK_SP.toInt(), "tick")
    }
}

/** Stage HUD in the line of sight: loading, the level gate「平视前方」, or [打开计算器] after × closed the window. */
@Composable
private fun Hud(graph: Graph, scene: StageScene) {
    val window = graph.window
    if (window.exiting) return
    Box(Modifier.size(360.dp, 110.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF2F4F6F8)), contentAlignment = Alignment.Center) {
        when {
            !scene.ready.value -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Box(Modifier.width(12.dp))
                PlateText(stringResource(R.string.loading), 20, "hud-loading", color = CalcColors.Ink)
            }
            window.levelHint -> PlateText(stringResource(R.string.level_hint), 20, "hud-level", color = CalcColors.Ink)
            else -> Button(
                onClick = { window.request("hud") }, size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 240.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CalcColors.Accent, contentColor = CalcColors.OnAccent),
            ) {
                // design-style: inherited-content-color Button
                PlateText(stringResource(R.string.open_window), 18, "hud-open", color = CalcColors.OnAccent)
            }
        }
    }
}

/**
 * Two-hand twist (profile D10 补「双手捏住扭」): pinch the gauge with both hands and turn them. SDK 6.1.9
 * detectSpatialRotateGesture reports a rotation (degrees about an axis, view space) for the gesture; the scene turns it
 * into one of the gauge's three angles and snaps to 0 / 45 / 90° on release. Only for hits on the gauge.
 */
private fun Modifier.gaugeTwist(scene: StageScene): Modifier = composed {
    val context = LocalContext.current
    pointerInput(Unit) {
        var active = false
        detectSpatialRotateGesture(
            context = context,
            targetedToEntity = TargetEntity.any(),
            onRotateStart = { v ->
                val (hit, path) = resolveUp(v.targetEntity, TargetResolver.byName(Targets::resolve))
                active = scene.onTwistStart(hit)
                AppLog.i("TWIST press hit=$hit path=$path left=${v.leftInteractionKind} right=${v.rightInteractionKind} taken=$active")
            },
            onRotateEnd = { if (active) scene.onTwistEnd(); active = false },
            onRotateCancel = { if (active) scene.onTwistEnd(); active = false },
        ) { v ->
            if (active) {
                val r = v.rotation
                AppLog.i("TWIST deg=${"%.1f".format(r.degree)} axis=(${"%.2f".format(r.axis.x)}, ${"%.2f".format(r.axis.y)}, ${"%.2f".format(r.axis.z)})")
                scene.onTwist(Vec3(r.axis.x, r.axis.y, r.axis.z), r.degree)
            }
        }
    }
}
