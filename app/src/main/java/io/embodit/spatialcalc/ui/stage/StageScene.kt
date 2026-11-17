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
import io.embodit.spatialcalc.domain.place.Axis
import io.embodit.spatialcalc.domain.place.GaugeMath
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
    /** The line or the volume box (whichever is out: there is only ever one gauge). */
    data object Gauge : Hit()
}

/** Entity ids of the pickable things: one pure parser, replayed by HitReplayTest. */
object Targets {
    const val LINE = "line"
    const val BOX = "box"
    const val PLATE = "line:plate"
    fun resolve(id: String): Hit? =
        if (id == LINE || id.startsWith("$LINE:") || id == BOX || id.startsWith("$BOX:")) Hit.Gauge else null
}

class CalcFrameSystem : System() {
    override fun update(context: SceneUpdateContext) { ActiveScene.scene?.onFrame() }
}

internal object ActiveScene { @Volatile var scene: StageScene? = null }

/** Pre-declared Stage attachment panels: the HUD, the line's value plate and up to [TICK_SLOTS] tick numbers. */
object PanelIds {
    const val HUD = "hud"
    const val PLATE = "plate"
    /** The small turn panel beside the gauge: per axis [−15°] [+15°], and [回正] (profile D10 补). */
    const val CTL = "ctl"
    const val TICK_SLOTS = 11
    fun tick(i: Int) = "tick$i"
    val all = listOf(HUD, PLATE, CTL) + List(TICK_SLOTS) { tick(it) }
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
    private var isBox = false
    private var glass: Entity? = null
    private val edges = ArrayList<Entity>()
    private var boxDims: List<Double> = emptyList()
    private var resetSeen = 0
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
    @Volatile private var trigger = false
    @Volatile private var stickX = 0f
    @Volatile private var stickY = 0f
    private var stickLatch = 0
    private var stickNextMs = 0L
    private val listener = ControllerTrackingProvider.ControllerActionListener { d: ControllerActionData ->
        val r = d.right; val l = d.left
        grip = r.gripPressed || l.gripPressed
        trigger = r.triggerPressed || l.triggerPressed
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
        if (hit != Hit.Gauge || !app.s.line.on || closing) return false
        dragFrom = current ?: return false
        AppLog.i("DRAG ${if (isBox) "box" else "line"} start src=${source.label} from=${fmt(dragFrom)}")
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
        if (dragFrom != null) AppLog.i("DRAG ${if (isBox) "box" else "line"} end at=${fmt(userStart)}")
        dragFrom = null
    }

    /** debug `ruler drag dx,dy,dz`: the same start → move → end a real drag makes. */
    fun debugDrag(d: Vec3) { if (onDragStart(Hit.Gauge, InputSource.UNKNOWN)) { onDragMove(d); onDragEnd() } }

    /** debug `ruler dragdeltas n dx,dy,dz`: n per-event deltas summed by the board-input DragTotal, like a real pinch drag. */
    fun debugDragDeltas(n: Int, d: Vec3) {
        if (!onDragStart(Hit.Gauge, InputSource.UNKNOWN)) return
        val total = io.embodit.spatialcalc.ui.stage.input.DragTotal()
        var t = io.embodit.spatialcalc.ui.stage.input.Offset3M(0f, 0f, 0f)
        repeat(n) { t = total.add(io.embodit.spatialcalc.ui.stage.input.Offset3M(d.x, d.y, d.z)) }
        onDragMove(Vec3(t.x, t.y, t.z)); onDragEnd()
    }

    // ---------------------------------------------------------------- two-hand twist (detectSpatialRotateGesture)
    private var twistAxis: Axis? = null
    private var twistLast = 0f

    fun onTwistStart(hit: Hit?): Boolean {
        if (hit != Hit.Gauge || !app.s.line.on || closing) return false
        twistAxis = null; twistLast = 0f
        AppLog.i("TWIST start ${if (isBox) "box" else "line"}")
        return true
    }

    /**
     * [deg] about [axis] (view axes) since the twist began. The axis nearest to the turn picks which of the gauge's
     * angles moves (line: vertical → its turn, anything else → its tilt); the angle follows the change since the last event.
     */
    fun onTwist(axis: Vec3, deg: Float) {
        val a = when { kotlin.math.abs(axis.y) >= kotlin.math.abs(axis.x) && kotlin.math.abs(axis.y) >= kotlin.math.abs(axis.z) -> Axis.Y
            kotlin.math.abs(axis.x) >= kotlin.math.abs(axis.z) -> Axis.X; else -> Axis.Z }
        val sign = when (a) { Axis.X -> kotlin.math.sign(axis.x); Axis.Y -> kotlin.math.sign(axis.y); Axis.Z -> kotlin.math.sign(axis.z) }
        val total = deg * (if (sign == 0f) 1f else sign)
        if (twistAxis != a) { twistAxis = a; twistLast = total; AppLog.i("TWIST axis $a"); return }
        val delta = total - twistLast
        twistLast = total
        if (kotlin.math.abs(delta) < 45f) app.gaugeTwist(a, delta)
    }

    fun onTwistEnd() {
        if (twistAxis != null || app.s.line.on) app.gaugeTwistEnd()
        AppLog.i("TWIST end yaw=${app.s.line.yaw} pitch=${app.s.line.pitch} roll=${app.s.line.roll}")
        twistAxis = null
    }

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

    /**
     * grip + stick (design T4, profile D10 补): left / right = Y (the vertical axis), up / down = X (the line: its
     * horizontal axis, lay ↔ stand), trigger held + left / right = Z. 15° a notch; held, it repeats every 350 ms.
     */
    private fun stick() {
        if (!app.s.line.on || !grip) { stickLatch = 0; return }
        val x = stickX; val y = stickY
        val dir = when { x > 0.6f -> 1; x < -0.6f -> 2; y > 0.6f -> 3; y < -0.6f -> 4; abs(x) < 0.3f && abs(y) < 0.3f -> 0; else -> -1 }
        if (dir == -1) return
        val now = SystemClock.elapsedRealtime()
        if (dir == stickLatch && (dir == 0 || now < stickNextMs)) return
        stickNextMs = now + if (dir == stickLatch) 350L else 500L
        stickLatch = dir
        stickTurn(dir, trigger)
        if (dir != 0) AppLog.i("STICK grip dir=$dir trigger=$trigger")
    }

    /** One stick notch (also debug `stick right|left|up|down [trigger]`): the same mapping as the real controller. */
    fun stickTurn(dir: Int, triggerHeld: Boolean) {
        when (dir) {
            1 -> app.gaugeTurn(if (triggerHeld) Axis.Z else Axis.Y, -1) // stick right = clockwise seen from above
            2 -> app.gaugeTurn(if (triggerHeld) Axis.Z else Axis.Y, 1)
            3 -> app.gaugeTurn(Axis.X, 1) // up: the line stands up a notch; the box turns +15° about its X
            4 -> app.gaugeTurn(Axis.X, -1)
        }
    }

    fun window(): WindowPose? = graph.window.estimate ?: lastPose?.let { LinePlace.window(it.position, it.forward, graph.window.mPerDp) }

    private fun updateLine(r: Res, root: Entity, now: Long, dt: Float) {
        val ls = app.s.line
        if (ls.on && (lineHolder == null || ls.serial != serial || closing)) {
            if (lineHolder != null && ls.serial != serial) destroyLine()
            if (lineHolder == null) { if (ls.isBox) createBox(r, root, ls, now) else createLine(r, root, ls.serial, now) }
            closing = false
        }
        val holder = lineHolder ?: return
        if (!ls.on && !closing) { closing = true; animateTo(0f, now, 250L); AppLog.i("GAUGE collapse") }
        if (ls.on && !isBox) {
            // spec (ticks) follows the result's length and unit
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
        if (ls.on && isBox) {
            val dims = GaugeMath.drawnDims(ls.dimsM)
            if (dims != boxDims) { boxDims = dims; colliderLen = -1f; AppLog.i("BOX dims=${dims} vol=${ls.volumeM3} clipped=${ls.clipped}") }
        }
        // grow: 400 ms ease-out (cubic-bezier .2,.8,.2,1 ≈ cubic ease-out), 300 ms change, 250 ms collapse. Box: 0 → 1 scale.
        val k = ((now - lenStart).toFloat() / lenDur).coerceIn(0f, 1f)
        val e = 1f - (1f - k) * (1f - k) * (1f - k)
        shownLen = lenFrom + (lenTo - lenFrom) * e
        if (closing && k >= 1f) { destroyLine(); return }
        val len: Float
        if (isBox) {
            len = max(shownLen, 0.001f)
            shapeBox(len)
            if (k >= 1f && colliderLen < 0f) setBoxCollider(r)
        } else {
            len = max(shownLen, 0.0005f)
            tube?.place(position = Vec3(len / 2f, 0f, 0f), scale = Vec3(TUBE_D, len, TUBE_D))
            capR?.place(position = Vec3(len, 0f, 0f))
            breakMark?.place(position = Vec3(len + 0.012f, 0f, 0f))
            for ((t, at) in ticks) t.place(scale = if (at <= shownLen + 1e-4) Vec3(1f, 1f, 1f) else Vec3(0.001f, 0.001f, 0.001f))
            if (k >= 1f && abs(len - colliderLen) > 0.005f) setCollider(r, len)
        }

        // where: beside the window until the user moves it; re-placed when the window reopens (Bring it back)
        val w = window()
        if (w != null && (defaultStart == null || graph.window.opens != opensSeen)) {
            opensSeen = graph.window.opens
            val eye = lastPose?.position ?: (w.center - w.forward * LinePlace.DISTANCE_M)
            defaultStart = if (isBox) GaugeMath.defaultBoxCenter(w, eye, boxDims.ifEmpty { listOf(0.1, 0.1, 0.1) }, graph.window.mPerDp)
                else LinePlace.defaultStart(w, graph.window.mPerDp)
            if (userStart == null) baseYaw = LinePlace.baseYaw(w)
        }
        val target = userStart ?: defaultStart ?: return
        val cur = current
        // critically damped follow, 60 ms time constant (design animation: drag without jitter)
        current = if (cur == null) target else cur + (target - cur) * (1f - exp(-dt / 0.06f))
        // [回正]: face the user from where it is now (yaw only, D9); the three angles are already 0
        if (ls.resetSerial != resetSeen) {
            resetSeen = ls.resetSerial
            lastPose?.let { baseYaw = LinePlace.yawFacing(current!!, it.position) }
            AppLog.i("GAUGE reset baseYaw=${"%.1f".format(Locale.US, Math.toDegrees(baseYaw.toDouble()))}°")
        }
        val q = rotation()
        holder.place(position = current, rotation = q)

        // D20: in the head → window sight cone (only if the user dragged it there): 35 %, never pushed away
        val h = lastPose
        val inCone = h != null && w != null && if (isBox) boxPoints(current!!, q, boxDims, len).any { LinePlace.inSightCone(h.position, w, it) }
            else LinePlace.lineInSightCone(h.position, w, current!!, q.rotate(Vec3(1f, 0f, 0f)), len)
        if (inCone != faded) {
            faded = inCone
            if (inCone) holder.components.set(OpacityControllerComponent(0.35f)) else holder.components.remove(OpacityControllerComponent::class.java)
            AppLog.i("GAUGE faded=$inCone")
        }
    }

    /** The gauge's orientation now (GaugeMath: Ry(base + yaw) · Rx(pitch) · Rz(roll); the line has no pitch). */
    private fun rotation(): Quat4 {
        val ls = app.s.line
        return GaugeMath.rotation(baseYaw, ls.yaw, if (isBox) ls.pitch else 0f, ls.roll)
    }

    /** Centre and eight corners of the box (for the sight-cone check). */
    private fun boxPoints(c: Vec3, q: Quat4, dims: List<Double>, grow: Float): List<Vec3> {
        if (dims.size < 3) return listOf(c)
        val hx = dims[0].toFloat() / 2f * grow; val hy = dims[1].toFloat() / 2f * grow; val hz = dims[2].toFloat() / 2f * grow
        val out = arrayListOf(c)
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) out += c + q.rotate(Vec3(sx * hx, sy * hy, sz * hz))
        return out
    }

    /** Glass and the 12 edge bars at [grow] (0 → 1) of the drawn size. */
    private fun shapeBox(grow: Float) {
        if (boxDims.size < 3) return
        val dx = boxDims[0].toFloat() * grow; val dy = boxDims[1].toFloat() * grow; val dz = boxDims[2].toFloat() * grow
        glass?.place(scale = Vec3(dx, dy, dz))
        var i = 0
        for (a in 0..2) {
            for (s1 in listOf(-1f, 1f)) for (s2 in listOf(-1f, 1f)) {
                val e = edges.getOrNull(i++) ?: continue
                when (a) {
                    0 -> e.place(position = Vec3(0f, s1 * dy / 2f, s2 * dz / 2f), scale = Vec3(dx + EDGE_D, EDGE_D, EDGE_D))
                    1 -> e.place(position = Vec3(s1 * dx / 2f, 0f, s2 * dz / 2f), scale = Vec3(EDGE_D, dy + EDGE_D, EDGE_D))
                    else -> e.place(position = Vec3(s1 * dx / 2f, s2 * dy / 2f, 0f), scale = Vec3(EDGE_D, EDGE_D, dz + EDGE_D))
                }
            }
        }
    }

    private fun setBoxCollider(r: Res) {
        val body = lineBody ?: return
        if (boxDims.size < 3) return
        colliderLen = 1f
        // grab area: the box itself, at least 60 mm a side (design angularCheck: ≈ 2° at 1.75 m)
        val shape = ShapeResource.createBox(Vector3(max(boxDims[0].toFloat(), GRAB_D), max(boxDims[1].toFloat(), GRAB_D), max(boxDims[2].toFloat(), GRAB_D)))
        body.components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = r.physics))
    }

    private fun createBox(r: Res, root: Entity, ls: io.embodit.spatialcalc.domain.app.LineState, now: Long) {
        serial = ls.serial
        isBox = true
        resetSeen = ls.resetSerial
        userStart = null; current = null; defaultStart = null; opensSeen = -1; specKey = ""; colliderLen = -1f; faded = false; plateAt = null
        boxDims = GaugeMath.drawnDims(ls.dimsM)
        shownLen = 0f; lenFrom = 0f; lenTo = 1f; lenStart = now; lenDur = 400L
        val holder = Entity().apply { setName("box:holder") }
        root.addChild(holder)
        val body = Entity().apply { setName(Targets.BOX) }
        holder.addChild(body)
        // gate:allow geometry 1:1 volume = abstract measuring indicator (a glass cube / box of the result's size, like the length line)
        glass = ModelEntity(r.box, r.glass).apply { setName("box:glass") }.also { body.addChild(it) }
        edges.clear()
        repeat(12) {
            // gate:allow geometry the 12 edge lines of the 1:1 volume indicator (abstract scale marks)
            val e = ModelEntity(r.box, r.line).apply { setName("box:edge") }
            body.addChild(e); edges += e
        }
        body.components.set(HoverEffectComponent())
        body.components.set(InteractableComponent())
        lineHolder = holder; lineBody = body
        shapeBox(0.001f)
        AppLog.i("BOX entity created serial=${ls.serial} dims=$boxDims")
    }

    private fun animateTo(len: Float, now: Long, dur: Long) { lenFrom = shownLen; lenTo = len; lenStart = now; lenDur = dur }

    private fun createLine(r: Res, root: Entity, ser: Int, now: Long) {
        serial = ser
        isBox = false
        resetSeen = app.s.line.resetSerial
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
        lineHolder = null; lineBody = null; tube = null; capR = null; breakMark = null; glass = null; edges.clear(); boxDims = emptyList()
        ticks.clear(); spec = null; specKey = ""; closing = false; shownLen = 0f; userStart = null; current = null; faded = false
        AppLog.i("GAUGE entity removed")
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
            show(PanelIds.PLATE, null); show(PanelIds.CTL, null); for (i in 0 until PanelIds.TICK_SLOTS) show(PanelIds.tick(i), null); return
        }
        val q = rotation()
        val ps = textScale(PLATE_CAP_M, PLATE_SP)
        val toObj = Vec3(start.x - h.position.x, 0f, start.z - h.position.z).normalized()
        val viewRight = Vec3(-toObj.z, 0f, toObj.x)
        val cs = CTL_M_PER_DP / mPerDp
        if (isBox) {
            // label over the top of the box, facing the user (yaw only); the turn panel under it
            val grow = max(shownLen, 0.001f)
            val dims = boxDims.map { it * grow }
            val want = start + Vec3.UP * (GaugeMath.worldHalfHeight(q, dims) + 0.03f + PLATE_HALF_H)
            plateAt = plateAt?.let { it + (want - it) * (1f - exp(-dt / 0.2f)) } ?: want
            show(PanelIds.PLATE, plateAt, LinePlace.yawFacing(plateAt!!, h.position), ps)
            // turn panel under the box (to the right it left the view on the emulator, 2026-11-16)
            val ctlH = CTL_BOX_H_DP * CTL_M_PER_DP
            val c = start - Vec3.UP * (GaugeMath.worldHalfHeight(q, dims) + 0.04f + ctlH / 2f) + viewRight * 0.03f
            show(PanelIds.CTL, c, LinePlace.yawFacing(c, h.position), cs)
            for (i in 0 until PanelIds.TICK_SLOTS) show(PanelIds.tick(i), null)
            return
        }
        val dir = q.rotate(Vec3(1f, 0f, 0f))
        val len = max(shownLen, 0.001f)
        // value plate: over the middle of the part in view, on the line's own "up" side; 200 ms smoothing
        val mid = LinePlace.visibleMid(h.position, h.forward, start, dir, len)
        val side = if (ls.standing) viewRight * 0.11f else q.rotate(Vec3.UP) * (0.030f + PLATE_HALF_H)
        val want = start + dir * mid + side
        plateAt = plateAt?.let { it + (want - it) * (1f - exp(-dt / 0.2f)) } ?: want
        show(PanelIds.PLATE, plateAt, LinePlace.yawFacing(plateAt!!, h.position), ps)
        // turn panel: hangs under the line's left end (the line only tilts up, so it never swings into it)
        val ctlH = CTL_LINE_H_DP * CTL_M_PER_DP
        val c = start + viewRight * (CTL_W_DP / 2f * CTL_M_PER_DP) - Vec3.UP * (0.09f + ctlH / 2f)
        show(PanelIds.CTL, c, LinePlace.yawFacing(c, h.position), cs)
        // tick numbers under the major ticks (on the line's "down" side, so they follow a slant)
        val ts = textScale(TICK_CAP_M, TICK_SP)
        val off = if (ls.standing) viewRight * -0.05f else q.rotate(Vec3.UP) * -(TUBE_D / 2f + MAJOR_H + 0.016f)
        for (i in 0 until PanelIds.TICK_SLOTS) {
            val at = labelAt.getOrNull(i)
            if (at == null || at > shownLen + 1e-4f || tickLabels[i].isEmpty()) { show(PanelIds.tick(i), null); continue }
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
        val q = rotation()
        val dir = q.rotate(Vec3(1f, 0f, 0f))
        val end = start?.let { it + dir * shownLen }
        val w = window()
        val d = if (h != null && start != null) h.position.distanceTo(start + dir * (shownLen / 2f)) else null
        return String.format(
            Locale.US,
            "GAUGE kind=%s dims=%s vol=%.6f m3 angles(yaw,pitch,roll)=(%.1f, %.1f, %.1f) baseYaw=%.1f° up=%s front=%s " +
            "LINE on=%s result=%.6f m drawn=%.6f m start=%s end=%s measured=%.6f m yaw=%.1f° standing=%s moved=%s faded=%s " +
                "head=%s window=%s mPerDp(stage)=%.6f mPerDp(window)=%.6f plate=%s plateScale=%.3f plateCapDeg=%s tickCapDeg=%s down=%s",
            ls.kind, boxDims, ls.volumeM3, ls.yaw, ls.pitch, ls.roll, Math.toDegrees(baseYaw.toDouble()), fmt(q.rotate(Vec3.UP)), fmt(q.rotate(Vec3(0f, 0f, 1f))),
            ls.on, ls.lengthM, spec?.drawnM ?: 0.0, fmt(start), fmt(end), if (start != null && end != null) start.distanceTo(end).toDouble() else 0.0,
            ls.yaw, ls.standing, userStart != null, faded, fmt(h?.position), fmt(w?.center), mPerDp, graph.window.mPerDp, fmt(plateAt),
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
        /** The turn panel: 1 dp = 0.7 mm, so its 64 dp keys are 4.5 cm (≥ 4 cm and ≈ 2.6° at 1 m, D17). */
        const val CTL_M_PER_DP = 0.0007f
        const val CTL_W_DP = 320f
        const val CTL_LINE_H_DP = 232f
        const val CTL_BOX_H_DP = 304f
        const val EDGE_D = 0.004f
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
    lateinit var glass: UnlitMaterial; private set
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
                // the volume's glass: mist teal at 18 % (see-through, the edges carry the shape)
                async(io) { r.glass = unlit(CalcColors.Accent, 0.18f).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
            ).awaitAll()
            r
        }
    }
}
