package io.embodit.spatialcalc.ui.stage

import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.spatialcalc.domain.calc.LineSpec
import io.embodit.spatialcalc.domain.calc.Ruler
import io.embodit.spatialcalc.domain.math.Quat4
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.domain.place.LinePlace
import io.embodit.spatialcalc.domain.place.WindowPose
import io.embodit.spatialcalc.platform.AppLog
import io.embodit.spatialcalc.platform.Graph
import io.embodit.spatialcalc.platform.HeadPose
import io.embodit.spatialcalc.platform.HeadTracker
import io.embodit.spatialcalc.ui.stage.input.InputSource
import io.embodit.spatialcalc.ui.theme.CalcColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** What a hit on our own colliders resolves to (board-input TargetResolver.byName). */
sealed class Hit {
    data object Line : Hit()
}

/** Entity ids of the pickable things: one pure parser, replayed by HitReplayTest. */
object Targets {
    const val LINE = "line"
    const val PLATE = "line:plate"
    fun resolve(id: String): Hit? = if (id == LINE || id.startsWith("$LINE:")) Hit.Line else null
}

class CalcFrameSystem : System() {
    override fun update(context: SceneUpdateContext) { ActiveScene.scene?.onFrame() }
}

internal object ActiveScene { @Volatile var scene: StageScene? = null }

/** Pre-declared Stage attachment panels: the HUD, the line's value plate and up to [TICK_SLOTS] tick numbers. */
object PanelIds {
    const val HUD = "hud"
    const val PLATE = "plate"
    const val TICK_SLOTS = 11
    fun tick(i: Int) = "tick$i"
    val all = listOf(HUD, PLATE) + List(TICK_SLOTS) { tick(it) }
}

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!
internal fun Vec3.sdk() = Vector3(x, y, z)
internal fun Vector3.domain() = Vec3(x, y, z)
internal fun Quat4.sdk() = Quat(x, y, z, w)

internal fun Entity.place(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = components[TransformComponent::class.java] ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

private fun yawQuat(yaw: Float) = Quat4.axisAngle(Vec3.UP, yaw)

/**
 * The Stage side: only the 1:1 length line (design entities3d) and its plates. State → entities every frame from
 * [onFrame] (one ECS system); all changes of the line's state go through [Graph.app]. Main thread only.
 */
class StageScene(private val graph: Graph) {
    private val app get() = graph.app
    val head = HeadTracker()
    private var content: SpatialViewContent? = null
    private var root: Entity? = null
    private var res: Res? = null
    private var startedMs = 0L
    var lastPose: HeadPose? = null
        private set

    /** Metres per dp of the Stage attachments (set by StageScreen from LocalPhysicalLengthConverter). */
    @Volatile var mPerDp = LinePlace.DEFAULT_M_PER_DP

    val panelShown = mutableStateMapOf<String, Boolean>()
    /** Text of the tick-number plates (index = slot). */
    val tickLabels = mutableStateListOf<String>().apply { repeat(PanelIds.TICK_SLOTS) { add("") } }
    val ready = mutableStateOf(false)
    private val holders = HashMap<String, Entity>()
    private val pendingShow = HashMap<String, Int>()
    private val wanted = HashSet<String>()
    private val boundPanels = HashSet<String>()

    // ---------------------------------------------------------------- line
    private var lineHolder: Entity? = null
    private var lineBody: Entity? = null
    private var tube: Entity? = null
    private var capR: Entity? = null
    private var breakMark: Entity? = null
    private val ticks = ArrayList<Pair<Entity, Double>>()
    private var serial = -1
    private var spec: LineSpec? = null
    private var specKey = ""
    private var shownLen = 0f
    private var lenFrom = 0f
    private var lenTo = 0f
    private var lenStart = 0L
    private var lenDur = 400L
    private var closing = false
    private var colliderLen = -1f
    private var baseYaw = 0f
    private var defaultStart: Vec3? = null
    private var opensSeen = -1
    /** Where the user put the line (null = beside the window); [current] is the smoothed position actually shown. */
    private var userStart: Vec3? = null
    private var current: Vec3? = null
    private var dragFrom: Vec3? = null
    private var faded = false
    private var plateAt: Vec3? = null
    private var lastFrameMs = 0L

    // ---------------------------------------------------------------- controller (grip + stick, design T4)
    private var controller: ControllerTrackingProvider? = null
    @Volatile private var grip = false
    @Volatile private var stickX = 0f
    @Volatile private var stickY = 0f
    private var stickLatch = 0
    private val listener = ControllerTrackingProvider.ControllerActionListener { d: ControllerActionData ->
        val r = d.right; val l = d.left
        grip = r.gripPressed || l.gripPressed
        val useRight = abs(r.thumbstickValue.x) + abs(r.thumbstickValue.y) >= abs(l.thumbstickValue.x) + abs(l.thumbstickValue.y)
        val st = if (useRight) r.thumbstickValue else l.thumbstickValue
        stickX = st.x; stickY = st.y
    }

    // ---------------------------------------------------------------- lifecycle

    suspend fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        this.content = content
        startedMs = SystemClock.elapsedRealtime()
        val root = Entity().apply { setName("stageRoot") }
        content.addEntity(root)
        // an entity added to a full-size SpatialView is not at the Stage origin: move the root so its local space = Stage
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rot = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply { setPosition(offset); setQuaternion(rot) }
        this.root = root
        ActiveScene.scene = this
        head.start()
        startController()
        for (id in PanelIds.all) {
            val h = Entity().apply { setName(if (id == PanelIds.PLATE) Targets.PLATE else "holder:$id") }
            root.addChild(h)
            h.place(position = STOW, scale = Vec3(0.001f, 0.001f, 0.001f))
            holders[id] = h
        }
        bind(attachments)
        res = Res.create()
        graph.stageRelease = { release() }
        ready.value = true
        AppLog.i("SCENE ready ms=${SystemClock.elapsedRealtime() - startedMs} mPerDp=$mPerDp")
    }

    fun bind(attachments: SpatialViewAttachments) {
        for (id in PanelIds.all) {
            if (id in boundPanels) continue
            val h = holders[id] ?: continue
            val panel = attachments.entity(id) ?: continue
            h.addChild(panel)
            boundPanels += id
        }
    }

    fun release() {
        runCatching { head.stop() }
        runCatching { controller?.removeControllerActionListener(listener); controller?.stop() }
        controller = null
    }

    fun dispose() {
        release()
        if (ActiveScene.scene === this) ActiveScene.scene = null
        runCatching { holders.values.forEach { it.removeAllChildren() } }
        runCatching { root?.destroy(true) }
        res?.close(); res = null
    }

    private fun startController() {
        runCatching {
            val p = ControllerTrackingProvider()
            p.addControllerActionListener(listener)
            val r = p.start()
            AppLog.i("TRACK controller start=$r support=${p.supportState}")
            controller = p
        }.onFailure { AppLog.w("TRACK controller unavailable: $it") }
    }

    // ---------------------------------------------------------------- input (board-input listener)

    fun onTap(hit: Hit?, source: InputSource) { AppLog.i("TAP src=${source.label} hit=$hit (the line only drags)") }

    fun onDragStart(hit: Hit, source: InputSource): Boolean {
        if (hit != Hit.Line || !app.s.line.on) return false
        dragFrom = current ?: return false
        AppLog.i("DRAG line start src=${source.label} from=${fmt(dragFrom)}")
        return true
    }

    /**
     * [total]: travel since the press (DragTotal, Stage axes). The line follows the hand / ray point, never the head
     * (D1: the system reports the pinch / ray point, the head pose is not read here). Released = it stays (no gravity).
     */
    fun onDragMove(total: Vec3) {
        val from = dragFrom ?: return
        userStart = from + total
    }

    fun onDragEnd() {
        if (dragFrom != null) AppLog.i("DRAG line end at=${fmt(userStart)}")
        dragFrom = null
    }

    /** debug `ruler drag dx,dy,dz`: the same start → move → end a real drag makes. */
    fun debugDrag(d: Vec3) { if (onDragStart(Hit.Line, InputSource.UNKNOWN)) { onDragMove(d); onDragEnd() } }

    // ---------------------------------------------------------------- per frame

    fun onFrame() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.016f else min(0.1f, (now - lastFrameMs) / 1000f)
        lastFrameMs = now
        val pose = head.pose()
        val valid = pose != null && head.steady
        if (valid) lastPose = pose
        graph.window.stepFrame(if (valid) pose!!.position to pose.forward else null, if (valid) pose!!.pitchDeg else null)
        placeHud()
        val r = res ?: return
        val root = root ?: return
        stick()
        updateLine(r, root, now, dt)
        placePlates(dt)
    }

    private fun stick() {
        if (!app.s.line.on || !grip) { stickLatch = 0; return }
        val x = stickX; val y = stickY
        val dir = when { x > 0.6f -> 1; x < -0.6f -> 2; y > 0.6f -> 3; y < -0.6f -> 4; abs(x) < 0.3f && abs(y) < 0.3f -> 0; else -> -1 }
        if (dir == -1 || dir == stickLatch) return
        stickLatch = dir
        when (dir) {
            1 -> app.lineTurn(-1) // stick right = clockwise seen from above
            2 -> app.lineTurn(1)
            3 -> app.lineStand(true)
            4 -> app.lineStand(false)
        }
        if (dir != 0) AppLog.i("STICK grip dir=$dir")
    }

    fun window(): WindowPose? = graph.window.estimate ?: lastPose?.let { LinePlace.window(it.position, it.forward, graph.window.mPerDp) }

    private fun updateLine(r: Res, root: Entity, now: Long, dt: Float) {
        val ls = app.s.line
        if (ls.on && (lineHolder == null || ls.serial != serial || closing)) {
            if (lineHolder != null && ls.serial != serial) destroyLine()
            if (lineHolder == null) createLine(r, root, ls.serial, now)
            closing = false
        }
        val holder = lineHolder ?: return
        if (!ls.on && !closing) { closing = true; animateTo(0f, now, 250L); AppLog.i("LINE collapse") }
        // spec (ticks) follows the result's length and unit
        if (ls.on) {
            val key = "${ls.lengthM}|${ls.unit}"
            if (key != specKey) {
                specKey = key
                val sp = Ruler.spec(ls.lengthM, ls.unit)
                spec = sp
                buildTicks(r, sp)
                breakMark?.place(scale = if (sp.clipped) Vec3(1f, 1f, 1f) else Vec3(0.001f, 0.001f, 0.001f))
                animateTo(sp.drawnM.toFloat(), now, if (shownLen <= 0.0001f) 400L else 300L)
                updateLabels(sp)
                AppLog.i("LINE spec length=${ls.lengthM} drawn=${sp.drawnM} step=${sp.majorStepM} ticks=${sp.ticks.size} clipped=${sp.clipped}")
            }
        }
        // length animation: 400 ms ease-out grow (cubic-bezier .2,.8,.2,1 ≈ cubic ease-out), 300 ms change, 250 ms collapse
        val k = ((now - lenStart).toFloat() / lenDur).coerceIn(0f, 1f)
        val e = 1f - (1f - k) * (1f - k) * (1f - k)
        shownLen = lenFrom + (lenTo - lenFrom) * e
        if (closing && k >= 1f) { destroyLine(); return }
        val len = max(shownLen, 0.0005f)
        tube?.place(position = Vec3(len / 2f, 0f, 0f), scale = Vec3(TUBE_D, len, TUBE_D))
        capR?.place(position = Vec3(len, 0f, 0f))
        breakMark?.place(position = Vec3(len + 0.012f, 0f, 0f))
        for ((t, at) in ticks) t.place(scale = if (at <= shownLen + 1e-4) Vec3(1f, 1f, 1f) else Vec3(0.001f, 0.001f, 0.001f))
        if (k >= 1f && abs(len - colliderLen) > 0.005f) setCollider(r, len)

        // where: beside the window until the user moves it; re-placed when the window reopens (Bring it back)
        val w = window()
        if (w != null && (defaultStart == null || graph.window.opens != opensSeen)) {
            opensSeen = graph.window.opens
            defaultStart = LinePlace.defaultStart(w, graph.window.mPerDp)
            if (userStart == null) baseYaw = LinePlace.baseYaw(w)
        }
        val target = userStart ?: defaultStart ?: return
        val cur = current
        // critically damped follow, 60 ms time constant (design animation: drag without jitter)
        current = if (cur == null) target else cur + (target - cur) * (1f - exp(-dt / 0.06f))
        val yaw = baseYaw + Math.toRadians(ls.yawSteps * 15.0).toFloat()
        holder.place(position = current, rotation = yawQuat(yaw))
        lineBody?.place(rotation = if (ls.standing) Quat4.axisAngle(Vec3(0f, 0f, 1f), (Math.PI / 2).toFloat()) else Quat4.IDENTITY)

        // D20: in the head → window sight cone (only if the user dragged it there): 35 %, never pushed away
        val h = lastPose
        val inCone = h != null && w != null && LinePlace.lineInSightCone(h.position, w, current!!, LinePlace.direction(yaw, ls.standing), len)
        if (inCone != faded) {
            faded = inCone
            if (inCone) holder.components.set(OpacityControllerComponent(0.35f)) else holder.components.remove(OpacityControllerComponent::class.java)
            AppLog.i("LINE faded=$inCone")
        }
    }

    private fun animateTo(len: Float, now: Long, dur: Long) { lenFrom = shownLen; lenTo = len; lenStart = now; lenDur = dur }

    private fun createLine(r: Res, root: Entity, ser: Int, now: Long) {
        serial = ser
        userStart = null; current = null; defaultStart = null; opensSeen = -1; specKey = ""; colliderLen = -1f; faded = false; plateAt = null
        shownLen = 0f; lenFrom = 0f; lenTo = 0f; lenStart = now
        val holder = Entity().apply { setName("line:holder") }
        root.addChild(holder)
        val body = Entity().apply { setName(Targets.LINE) }
        holder.addChild(body)
        // gate:allow geometry 1:1 length line = abstract indicator (design entities3d.assetNote: B12 allows procedural geometry for a scale line)
        tube = ModelEntity(r.cylinder, r.line).apply { setName("line:tube") }.also { body.addChild(it); it.place(rotation = Quat4.axisAngle(Vec3(0f, 0f, 1f), (-Math.PI / 2).toFloat())) }
        val capL = ModelEntity(r.box, r.line).apply { setName("line:capL") }
        body.addChild(capL); capL.place(scale = Vec3(0.004f, 0.040f, 0.004f))
        capR = ModelEntity(r.box, r.line).apply { setName("line:capR") }.also { body.addChild(it); it.place(scale = Vec3(0.004f, 0.040f, 0.004f)) }
        // ≈ break mark at the right end of a line longer than 10 m: two short slanted strokes
        val bm = Entity().apply { setName("line:break") }
        body.addChild(bm)
        for (i in 0..1) {
            val s = ModelEntity(r.box, r.line).apply { setName("line:break$i") }
            bm.addChild(s)
            s.place(position = Vec3(i * 0.012f, 0f, 0f), rotation = Quat4.axisAngle(Vec3(0f, 0f, 1f), 0.5f), scale = Vec3(0.003f, 0.036f, 0.003f))
        }
        breakMark = bm.also { it.place(scale = Vec3(0.001f, 0.001f, 0.001f)) }
        body.components.set(HoverEffectComponent())
        body.components.set(InteractableComponent())
        lineHolder = holder; lineBody = body
        AppLog.i("LINE entity created serial=$ser")
    }

    private fun setCollider(r: Res, len: Float) {
        val body = lineBody ?: return
        colliderLen = len
        // grab area: a 60 mm thick bar along the line (design angularCheck: ≈ 2° at 1.75 m)
        val shape = ShapeResource.createBox(Vector3(len + 0.04f, GRAB_D, GRAB_D)).offsetByTranslation(Vector3(len / 2f, 0f, 0f))
        body.components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = r.physics))
    }

    private fun buildTicks(r: Res, sp: LineSpec) {
        val body = lineBody ?: return
        ticks.forEach { runCatching { it.first.destroy(true) } }
        ticks.clear()
        for (t in sp.ticks) {
            val h = if (t.major) MAJOR_H else MINOR_H
            // hangs from the lower edge of the tube
            val holder = Entity().apply { setName("line:tickAt") }
            body.addChild(holder)
            holder.place(position = Vec3(t.atM.toFloat(), 0f, 0f))
            val e = ModelEntity(r.box, r.tick).apply { setName("line:tick") }
            holder.addChild(e)
            e.place(position = Vec3(0f, -TUBE_D / 2f - h / 2f, 0f), scale = Vec3(0.002f, h, 0.002f))
            ticks += holder to t.atM
        }
    }

    private fun destroyLine() {
        runCatching { lineHolder?.destroy(true) }
        lineHolder = null; lineBody = null; tube = null; capR = null; breakMark = null
        ticks.clear(); spec = null; specKey = ""; closing = false; shownLen = 0f; userStart = null; current = null; faded = false
        AppLog.i("LINE entity removed")
    }

    private fun updateLabels(sp: LineSpec) {
        val majors = sp.ticks.filter { it.major }
        val stride = max(1, (majors.size + PanelIds.TICK_SLOTS - 1) / PanelIds.TICK_SLOTS)
        val shown = majors.filterIndexed { i, _ -> i % stride == 0 }.take(PanelIds.TICK_SLOTS)
        for (i in 0 until PanelIds.TICK_SLOTS) tickLabels[i] = shown.getOrNull(i)?.label ?: ""
        labelAt = shown.map { it.atM.toFloat() }
    }

    private var labelAt: List<Float> = emptyList()

    // ---------------------------------------------------------------- plates

    /** Plate scale so its digits are [capM] tall: [fontSp] sp × 0.72 cap height × metres per dp. */
    private fun textScale(capM: Float, fontSp: Float) = capM / (fontSp * 0.72f * mPerDp)

    private fun placePlates(dt: Float) {
        val h = lastPose
        val ls = app.s.line
        val holder = lineHolder
        val start = current
        if (h == null || holder == null || start == null || closing || !ls.on) {
            show(PanelIds.PLATE, null); for (i in 0 until PanelIds.TICK_SLOTS) show(PanelIds.tick(i), null); return
        }
        val yaw = baseYaw + Math.toRadians(ls.yawSteps * 15.0).toFloat()
        val dir = LinePlace.direction(yaw, ls.standing)
        val len = max(shownLen, 0.001f)
        // value plate: over the middle of the part in view (horizontal), right of the middle (standing); 200 ms smoothing
        val mid = LinePlace.visibleMid(h.position, h.forward, start, dir, len)
        val side = if (ls.standing) Vec3(kotlin.math.cos(yaw), 0f, -kotlin.math.sin(yaw)) * 0.11f else Vec3.UP * (0.030f + PLATE_HALF_H)
        val want = start + dir * mid + side
        plateAt = plateAt?.let { it + (want - it) * (1f - exp(-dt / 0.2f)) } ?: want
        val ps = textScale(PLATE_CAP_M, PLATE_SP)
        show(PanelIds.PLATE, plateAt, LinePlace.yawFacing(plateAt!!, h.position), ps)
        // tick numbers under the major ticks (beside them when standing)
        val ts = textScale(TICK_CAP_M, TICK_SP)
        for (i in 0 until PanelIds.TICK_SLOTS) {
            val at = labelAt.getOrNull(i)
            if (at == null || at > shownLen + 1e-4f || tickLabels[i].isEmpty()) { show(PanelIds.tick(i), null); continue }
            val off = if (ls.standing) Vec3(kotlin.math.cos(yaw), 0f, -kotlin.math.sin(yaw)) * -0.05f else Vec3.UP * -(TUBE_D / 2f + MAJOR_H + 0.016f)
            val p = start + dir * at + off
            show(PanelIds.tick(i), p, LinePlace.yawFacing(p, h.position), ts)
        }
    }

    private fun show(id: String, at: Vec3?, yaw: Float = 0f, scale: Float = 1f) {
        val h = holders[id] ?: return
        if (at == null) {
            if (id in wanted) { h.place(position = STOW, scale = Vec3(0.001f, 0.001f, 0.001f)); wanted -= id; panelShown[id] = false }
            pendingShow.remove(id)
            return
        }
        h.place(position = at, rotation = yawQuat(yaw), scale = Vec3(scale, scale, scale))
        if (id !in wanted) { wanted += id; pendingShow[id] = 2 }
        val left = pendingShow[id]
        if (left != null) { if (left <= 0) { pendingShow.remove(id); panelShown[id] = true } else pendingShow[id] = left - 1 }
    }

    private var hudAt: Vec3? = null

    /** Loading, the level gate's「平视前方」, or [打开计算器] after the window was closed with ×. */
    private fun placeHud() {
        val h = lastPose
        val window = graph.window
        val want = !window.exiting && (res == null || window.levelHint || (window.panelState == "ON_DESTROY" && !window.gate.waiting))
        if (!want || h == null) { hudAt = null; show(PanelIds.HUD, null); return }
        val f = h.forward
        val flat = Vec3(f.x, 0f, f.z).normalized()
        val cur = hudAt
        val needs = cur == null || (cur - h.position).normalized().dot(f) < 0.7f
        if (needs) {
            val pitch = Math.toRadians(h.pitchDeg.coerceIn(-40f, 40f).toDouble()).toFloat()
            hudAt = h.position + flat * (0.75f * kotlin.math.cos(pitch)) + Vec3.UP * (0.75f * kotlin.math.sin(pitch))
        }
        show(PanelIds.HUD, hudAt, LinePlace.yawFacing(hudAt!!, h.position))
    }

    // ---------------------------------------------------------------- debug

    /** debug `dump`: the line's two ends in Stage metres (QA: length = result ± 1 mm) and the checks around it. */
    fun dump(): String {
        val h = lastPose
        val ls = app.s.line
        val start = current
        val yaw = baseYaw + Math.toRadians(ls.yawSteps * 15.0).toFloat()
        val dir = LinePlace.direction(yaw, ls.standing)
        val end = start?.let { it + dir * shownLen }
        val w = window()
        val d = if (h != null && start != null) h.position.distanceTo(start + dir * (shownLen / 2f)) else null
        return String.format(
            Locale.US,
            "LINE on=%s result=%.6f m drawn=%.6f m start=%s end=%s measured=%.6f m yaw=%d° standing=%s moved=%s faded=%s " +
                "head=%s window=%s mPerDp(stage)=%.6f mPerDp(window)=%.6f plate=%s plateScale=%.3f plateCapDeg=%s tickCapDeg=%s down=%s",
            ls.on, ls.lengthM, spec?.drawnM ?: 0.0, fmt(start), fmt(end), if (start != null && end != null) start.distanceTo(end).toDouble() else 0.0,
            ls.yawSteps * 15, ls.standing, userStart != null, faded, fmt(h?.position), fmt(w?.center), mPerDp, graph.window.mPerDp, fmt(plateAt),
            textScale(PLATE_CAP_M, PLATE_SP), d?.let { "%.2f".format(Locale.US, Ruler.angleDeg(PLATE_CAP_M.toDouble(), it.toDouble())) },
            d?.let { "%.2f".format(Locale.US, Ruler.angleDeg(TICK_CAP_M.toDouble(), it.toDouble())) },
            if (h != null && start != null) "%.1f".format(Locale.US, LinePlace.downDeg(h.position, start)) else null,
        )
    }

    companion object {
        val STOW = Vec3(0f, -40f, 0f)
        const val TUBE_D = 0.004f
        const val GRAB_D = 0.060f
        const val MAJOR_H = 0.024f
        const val MINOR_H = 0.012f
        /** Digits on the value plate 30 mm tall, tick numbers 18 mm (design geometry). */
        const val PLATE_CAP_M = 0.030f
        const val TICK_CAP_M = 0.018f
        const val PLATE_SP = 40f
        const val TICK_SP = 24f
        const val PLATE_HALF_H = 0.03f
        fun fmt(v: Vec3?): String = if (v == null) "null" else String.format(Locale.US, "(%.4f, %.4f, %.4f)", v.x, v.y, v.z)
    }
}

/** Shared meshes / materials (no model file, no texture: design budget). Made off the main thread, closed on dispose. */
internal class Res private constructor() {
    private val all = ArrayList<Resource>()
    lateinit var cylinder: MeshResource; private set
    lateinit var box: MeshResource; private set
    lateinit var line: UnlitMaterial; private set
    lateinit var tick: UnlitMaterial; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }

    companion object {
        private fun unlit(c: Color, opacity: Float) = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
            setBaseColor(Color4(c.red, c.green, c.blue, 1f)); setOpacity(opacity); setDepthWrite(false)
            setCullingMode(MaterialCullingMode.NONE); setApplyToneMapping(false)
        }

        suspend fun create(): Res = coroutineScope {
            val r = Res()
            fun <T : Resource> T.keep(): T { toGlobal(); synchronized(r.all) { r.all += this }; return this }
            val io = Dispatchers.IO
            listOf(
                // gate:allow geometry Ø 4 mm tube of the 1:1 length line (abstract scale line, design entities3d.assetNote)
                async(io) { r.cylinder = MeshResource.createCylinder(radius = 0.5f, height = 1f).keep() },
                // gate:allow geometry end caps, ticks and the break mark of the length line (abstract scale marks)
                async(io) { r.box = MeshResource.createBox(Vector3(1f, 1f, 1f), cornerRadius = 0f).keep() },
                async(io) { r.line = unlit(CalcColors.Line, 0.90f).keep() },
                async(io) { r.tick = unlit(CalcColors.Line, 0.75f).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
            ).awaitAll()
            r
        }
    }
}
