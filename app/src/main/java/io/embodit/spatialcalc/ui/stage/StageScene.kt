package io.embodit.spatialcalc.ui.stage

import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.spatialcalc.domain.calc.BodySpec
import io.embodit.spatialcalc.domain.calc.Channel
import io.embodit.spatialcalc.domain.calc.Evaluate
import io.embodit.spatialcalc.domain.math.Quat4
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.domain.place.Aabb
import io.embodit.spatialcalc.domain.place.BodyPose
import io.embodit.spatialcalc.domain.place.HeadSampler
import io.embodit.spatialcalc.domain.place.PlaceBody
import io.embodit.spatialcalc.domain.place.ResultPlate
import io.embodit.spatialcalc.domain.place.SightCone
import io.embodit.spatialcalc.domain.place.bodyBox
import io.embodit.spatialcalc.domain.place.yawFacing
import io.embodit.spatialcalc.domain.session.CalcState
import io.embodit.spatialcalc.domain.session.Card
import io.embodit.spatialcalc.domain.session.Phase
import io.embodit.spatialcalc.domain.session.PinPoint
import io.embodit.spatialcalc.domain.session.RoomEnv
import io.embodit.spatialcalc.domain.session.Settings
import io.embodit.spatialcalc.domain.surface.HitSurfaceUseCase
import io.embodit.spatialcalc.domain.surface.PlaneSurface
import io.embodit.spatialcalc.domain.surface.SurfaceKind
import io.embodit.spatialcalc.platform.AnchorOutcome
import io.embodit.spatialcalc.platform.AppLog
import io.embodit.spatialcalc.platform.Graph
import io.embodit.spatialcalc.platform.HeadPose
import io.embodit.spatialcalc.platform.HeadTracker
import io.embodit.spatialcalc.platform.PlaneTracker
import io.embodit.spatialcalc.platform.SystemPointer
import io.embodit.spatialcalc.ui.stage.input.InputSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What the board-input layer resolves a hit entity to (names are set only from [Names]). */
sealed class Hit {
    data object Backstop : Hit()
    data object Body : Hit()
    data class Handle(val axis: Int, val sign: Int) : Hit()
    data class Pinned(val cardId: Long) : Hit()
}

/** Entity names: one pure place (board-input README 命中回放: names come from a function, resolved by a pure parser). */
object Names {
    const val BACKSTOP = "backstop"
    const val BODY = "body:active"
    fun handle(axis: Int, sign: Int) = "fh:$axis:${if (sign > 0) "p" else "n"}"
    fun pinned(id: Long) = "pinned:$id"

    fun resolve(name: String): Hit? = when {
        name == BACKSTOP || name.startsWith("$BACKSTOP:") -> Hit.Backstop
        name == BODY -> Hit.Body
        name.startsWith("fh:") -> name.split(":").let { p ->
            val axis = p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..2 } ?: return null
            val sign = when (p.getOrNull(2)) { "p" -> 1; "n" -> -1; else -> return null }
            Hit.Handle(axis, sign)
        }
        name.startsWith("pinned:") -> name.removePrefix("pinned:").toLongOrNull()?.let { Hit.Pinned(it) }
        else -> null
    }
}

class CalcFrameSystem : System() {
    override fun update(context: SceneUpdateContext) { ActiveScene.scene?.onFrame() }
}

internal object ActiveScene { @Volatile var scene: StageScene? = null }

/** Ids of the pre-declared Stage attachment panels (plates on 3D content; all control UI is in the window). */
object PanelIds {
    const val HUD = "hud"
    const val READING = "reading"
    const val RESULT = "result"
    const val DEMO = "demo"
    fun fit(i: Int) = "fit$i"
    fun pin(i: Int) = "pin$i"
    const val PIN_SLOTS = 10
    val all = listOf(HUD, READING, RESULT, DEMO) + List(3) { fit(it) } + List(PIN_SLOTS) { pin(it) }
}

/**
 * The Stage side: everything in the room (pins, tapes, result bodies, the fit box, plates) and the room facts the flow
 * needs. One calibrated root whose local space is Stage metres. State → entities in [render] (diffed by keys);
 * animation, facing and head-dependent checks in [onFrame] (one ECS system). Main thread only.
 */
class StageScene(private val graph: Graph) : RoomEnv {
    private val flow get() = graph.flow
    private val hits = HitSurfaceUseCase()
    val planes = PlaneTracker()
    val head = HeadTracker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var res: SceneResources? = null
    private var content: SpatialViewContent? = null
    private var root: Entity? = null
    private var probe: Entity? = null
    private var startedMs = 0L
    private var lastPose: HeadPose? = null
    private val sampler = HeadSampler()
    var forceNoPlanes = false
    var freeze = false

    /** Panel id → shown (Compose reads it: a panel composes its content only once it is in place). */
    val panelShown = mutableStateMapOf<String, Boolean>()
    /** Which pinned card each pin plate shows. */
    val pinPlates = mutableStateMapOf<Int, Long>()
    val ready = mutableStateOf(false)
    private val holders = HashMap<String, Entity>()
    private val panelWanted = HashMap<String, Pair<Vec3, Float>?>()
    private val panelScale = HashMap<String, Float>()
    private val pendingShow = HashMap<String, Int>()
    private val boundPanels = HashSet<String>()

    // room entities
    private var tapeGroup: Entity? = null
    private var tapeKey = ""
    private var bodyHolder: Entity? = null
    private var bodyVisual: Entity? = null
    private var bodyKey = ""
    private var bodyWater = ArrayList<Entity>()
    private var bodySize = Vec3.ZERO
    private var growStart = 0L
    private var resizeFrom: Vec3? = null
    private var resizeStart = 0L
    private var demo: Entity? = null
    private var demoPose: Vec3? = null
    private var demoKey: String? = null
    private var fitHolder: Entity? = null
    private var fitKey = ""
    private val pinnedBodies = HashMap<Long, Entity>()
    private var pinnedKey = ""
    private var flash: Entity? = null
    private var flashUntil = 0L
    private var rayEntity: Entity? = null
    private var rayDot: Entity? = null
    private var lastRay: Pair<Vec3, Vec3>? = null
    private var lastRayHitPin: PinPoint? = null

    // drag
    private var dragKind: Hit? = null
    private var dragStartPose: BodyPose? = null
    private var dragLast = Vec3.ZERO

    // controller
    private var controller: ControllerTrackingProvider? = null
    @Volatile private var stickX = 0f
    private val stickListener = ControllerTrackingProvider.ControllerActionListener { d: ControllerActionData ->
        val r = d.right.thumbstickValue.x
        val l = d.left.thumbstickValue.x
        stickX = if (abs(r) >= abs(l)) r else l
    }

    // ------------------------------------------------------------------ RoomEnv

    override fun head(): Pair<Vec3, Vec3>? = lastPose?.let { it.position to it.forward }
    override fun window(): Vec3? = graph.window.estimate
    override fun floorY(): Float {
        val floors = surfaces().filter { it.kind == SurfaceKind.FLOOR }
        if (floors.isNotEmpty()) return floors.minOf { p -> p.vertices.minOf { it.y } }
        return (lastPose?.position?.y ?: 1.6f) - 1.6f
    }
    override fun now() = java.lang.System.currentTimeMillis()

    fun surfaces(): Collection<PlaneSurface> = if (forceNoPlanes) emptyList() else planes.all

    // ------------------------------------------------------------------ lifecycle

    suspend fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        this.content = content
        startedMs = SystemClock.elapsedRealtime()
        val root = Entity().apply { setName("stageRoot") }
        content.addEntity(root)
        val probe = Entity().apply { setName("contentProbe") }
        content.addEntity(probe)
        this.probe = probe
        // an entity added to a full-size SpatialView is not at the Stage origin: move the root so its local space = Stage
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rot = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply { setPosition(offset); setQuaternion(rot) }
        this.root = root
        AppLog.i("STAGE root offset=(${offset.x}, ${offset.y}, ${offset.z})")
        ActiveScene.scene = this
        graph.room = this
        head.start()
        planes.start { id, s, added -> if (added && s != null) AppLog.i("PLANES add ${s.label} kind=${s.kind}") }
        startController()
        // panel holders first: the hud says「准备中」while the meshes are made
        for (id in PanelIds.all) {
            val h = Entity().apply { setName("holder:$id") }
            root.addChild(h)
            h.place(position = STOW, scale = Vec3(0.001f, 0.001f, 0.001f))
            holders[id] = h
        }
        bind(attachments)
        val r = SceneResources.create()
        res = r
        r.setOpacity(flow.settings.value.opacity)
        buildBackstop(r, root)
        graph.anchors.start()
        graph.stageRelease = { release() }
        graph.lastRayHit = { lastRayHitPin }
        restorePinned()
        ready.value = true
        AppLog.i("SCENE ready ms=${SystemClock.elapsedRealtime() - startedMs}")
        render(flow.s, flow.settings.value)
    }

    /** Parent freshly composed panel entities under their holders. */
    fun bind(attachments: SpatialViewAttachments) {
        for (id in PanelIds.all) {
            val h = holders[id] ?: continue
            if (id in boundPanels) continue
            val panel = attachments.entity(id) ?: continue
            h.addChild(panel)
            boundPanels += id
            AppLog.i("PANEL bound id=$id")
        }
    }

    fun release() {
        runCatching { planes.stop() }
        runCatching { head.stop() }
        runCatching { controller?.removeControllerActionListener(stickListener); controller?.stop() }
        controller = null
        runCatching { graph.anchors.stop() }
    }

    fun dispose() {
        release()
        scope.cancel()
        if (ActiveScene.scene === this) ActiveScene.scene = null
        if (graph.room === this) graph.room = null
        runCatching { holders.values.forEach { it.removeAllChildren() } }
        runCatching { root?.destroy(true) }
        runCatching { probe?.destroy(true) }
        res?.close()
        res = null
    }

    private fun startController() {
        runCatching {
            val p = ControllerTrackingProvider()
            p.addControllerActionListener(stickListener)
            val r = p.start()
            AppLog.i("TRACK controller start=$r support=${p.supportState}")
            controller = p
        }.onFailure { AppLog.w("TRACK controller unavailable: $it") }
    }

    /** Six slabs far outside any room catch taps that hit none of our objects (gate:allow: invisible collider, D24 plane-free). */
    private fun buildBackstop(r: SceneResources, root: Entity) {
        val shell = Entity().apply { setName(Names.BACKSTOP) }
        root.addChild(shell)
        val h = 9f; val t = 0.2f; val cy = 1.5f
        listOf(
            Vec3(h, cy, 0f) to Vec3(t, 2 * h, 2 * h), Vec3(-h, cy, 0f) to Vec3(t, 2 * h, 2 * h),
            Vec3(0f, cy, h) to Vec3(2 * h, 2 * h, t), Vec3(0f, cy, -h) to Vec3(2 * h, 2 * h, t),
            Vec3(0f, cy + h, 0f) to Vec3(2 * h, t, 2 * h), Vec3(0f, cy - h, 0f) to Vec3(2 * h, t, 2 * h),
        ).forEachIndexed { i, (c, size) ->
            val slab = Entity().apply { setName("${Names.BACKSTOP}:$i") }
            slab.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(size.sdk())), physicsMaterial = r.physics))
            slab.components.set(InteractableComponent())
            shell.addChild(slab)
            slab.place(position = c)
        }
    }

    // ------------------------------------------------------------------ input (board-input template listener)

    /** View-local pixels of a spatial event → Stage metres. */
    fun toStage(o: Offset3D): Vec3? {
        val c = content ?: return null
        val p = probe ?: return null
        return runCatching {
            val local = c.convertPosition(Vector3(o.x, o.y, o.z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
            p.convertPositionTo(local, null).domain()
        }.getOrNull()
    }

    /** Where a pointing line starts: the controller for a controller press, the (left, on the emulator) eye otherwise. */
    private fun origin(source: InputSource): Vec3 {
        if (source == InputSource.CONTROLLER) lastRay?.let { return it.first }
        val h = lastPose ?: return Vec3(0f, 1.6f, 0f)
        return h.position + h.right * (SystemPointer.eyeOffsetM ?: 0f)
    }

    private fun channelOf(source: InputSource) = when (source) { InputSource.CONTROLLER -> Channel.RAY; InputSource.UNKNOWN -> Channel.DEBUG; else -> Channel.GAZE }

    fun onTap(hit: Hit?, source: InputSource, position: Offset3D?) {
        val stage = position?.let { toStage(it) }
        AppLog.i("TAP src=${source.label} hit=$hit stage=${fmt(stage)} phase=${flow.s.phase}")
        when (hit) {
            Hit.Body -> if (flow.s.template == io.embodit.spatialcalc.domain.calc.TemplateId.FIT) flow.turnBoxes() else flow.resultMenu(true)
            is Hit.Handle, is Hit.Pinned -> Unit
            Hit.Backstop -> {
                if (stage == null) { AppLog.i("TAP backstop without position"); return }
                val o = origin(source)
                val d = (stage - o).normalized()
                surfaceTapAlong(o, d, channelOf(source), source)
            }
            null -> AppLog.i("TAP nothing")
        }
    }

    /** One pointing line → the analytic surface it hits (or the virtual table plane, S17) → the flow. Debug `ray`/`tap` use it too. */
    fun surfaceTapAlong(origin: Vec3, dir: Vec3, channel: Channel, source: InputSource = InputSource.UNKNOWN) {
        val pin = resolveLine(origin, dir, channel) ?: run { AppLog.i("TAP line hit nothing origin=${fmt(origin)} dir=${fmt(dir)}"); return }
        // contact channel (触测): the controller tip within 12 cm of a surface, the tip's foot is the point
        val contact = if (source == InputSource.CONTROLLER) contactPoint() else null
        val p = contact ?: pin
        AppLog.i("MEASURE hit at=${fmt(p.point)} on=${p.surface} range=${"%.2f".format(Locale.US, p.rangeM)} ch=${p.channel}")
        if (flow.s.phase == Phase.AIM || flow.s.phase == Phase.PULL || flow.s.phase == Phase.FORMULA || flow.s.phase == Phase.PICK) flashPlane(p)
        flow.surfaceTap(p)
    }

    fun resolveLine(origin: Vec3, dir: Vec3, channel: Channel): PinPoint? {
        val hit = hits.nearest(surfaces(), origin, dir)
        if (hit != null) return PinPoint(hit.point, hit.normal, hit.plane.kind, hit.distance, channel)
        // no detected surface on the line: the virtual table height (S17), only when looking down at it
        val y = floorY() + flow.settings.value.tableHeight
        if (dir.y < -0.05f) {
            val t = (y - origin.y) / dir.y
            if (t in 0.05f..8f) return PinPoint(origin + dir * t, Vec3.UP, SurfaceKind.VIRTUAL, t, channel)
        }
        return null
    }

    private fun contactPoint(): PinPoint? {
        val (o, d) = lastRay ?: return null
        val tip = o + d * 0.06f
        var best: PinPoint? = null
        for (p in surfaces()) {
            val dist = hits.distanceToPlane(p, tip)
            if (dist <= 0.12f && hits.contains(p, tip, 0.01f) && (best == null || dist < best.rangeM)) {
                best = PinPoint(hits.project(p, tip), p.normal, p.kind, dist, Channel.CONTACT)
            }
        }
        return best?.copy(rangeM = 0.5f)
    }

    fun onDragStart(hit: Hit): Boolean {
        val s = flow.s
        return when {
            hit == Hit.Body && s.body != null -> { dragKind = hit; dragStartPose = s.body.pose; dragLast = Vec3.ZERO; AppLog.i("DRAG body start"); true }
            hit is Hit.Handle && s.fitBox != null -> { dragKind = hit; dragLast = Vec3.ZERO; AppLog.i("DRAG handle ${hit.axis}${hit.sign}"); true }
            else -> false
        }
    }

    /** [total]: the hand's travel since the press (Stage axes). */
    fun onDragMove(total: Vec3) {
        when (val k = dragKind) {
            Hit.Body -> {
                val start = dragStartPose ?: return
                var pose = start.copy(position = start.position + total)
                // D1 snap: only to the measured target (the wall / cell it was attached to), 8 cm
                val home = attachedHome
                if (home != null && pose.position.distanceTo(home.position) < 0.08f) pose = home
                flow.bodyDragged(pose, flow.s.body?.faded ?: false)
            }
            is Hit.Handle -> {
                val fb = flow.s.fitBox ?: return
                val c = kotlin.math.cos(fb.pose.yaw); val sn = kotlin.math.sin(fb.pose.yaw)
                val axisDir = when (k.axis) { 0 -> Vec3(c, 0f, -sn); 1 -> Vec3.UP; else -> Vec3(sn, 0f, c) } * k.sign.toFloat()
                // the hand's move projected on the handle's own normal (works without a depth axis on the emulator)
                val along = total.dot(axisDir) - dragLast.dot(axisDir)
                dragLast = total
                if (abs(along) > 1e-4f) flow.fitBoxFace(k.axis, k.sign, along)
            }
            else -> Unit
        }
    }

    fun onDragEnd() {
        if (dragKind == Hit.Body) AppLog.i("DRAG body end pos=${fmt(flow.s.body?.pose?.position)}")
        dragKind = null; dragStartPose = null
    }

    private var attachedHome: BodyPose? = null

    // ------------------------------------------------------------------ render (state → entities)

    fun render(s: CalcState, settings: Settings) {
        val r = res ?: return
        val root = root ?: return
        r.setOpacity(settings.opacity)
        renderTapes(r, root, s)
        renderBody(r, root, s)
        renderDemo(r, root, s)
        renderFitBox(r, root, s)
        renderPinned(r, root, s)
    }

    private fun renderTapes(r: SceneResources, root: Entity, s: CalcState) {
        val key = s.tapes.joinToString { "${it.id}" } + "|" + (s.firstPin?.point?.let(::fmt) ?: "") + "|" + (s.body == null)
        if (key == tapeKey) return
        tapeKey = key
        runCatching { tapeGroup?.destroy(true) }
        val g = Entity().apply { setName("tapes") }
        root.addChild(g)
        tapeGroup = g
        // pinned cards keep only body + plate; active card keeps its tapes as evidence until it is cleared
        s.tapes.filter { it.lengthM > 0.0 }.forEach { t ->
            val holder = Entity().apply { setName("tape") }
            g.addChild(holder)
            holder.place(position = t.a.point, rotation = alongX(t.b.point - t.a.point))
            BodyBuilder(r, holder).tape(t.lengthM.toFloat(), Vec3.ZERO)
            pin(r, g, t.a.point); pin(r, g, t.b.point)
        }
        s.firstPin?.let { pin(r, g, it.point) }
    }

    private fun pin(r: SceneResources, parent: Entity, at: Vec3) {
        val shank = ModelEntity(r.cylinder, r.steel).apply { setName("pin") }
        parent.addChild(shank); shank.place(position = at + Vec3(0f, 0.02f, 0f), scale = Vec3(0.004f, 0.04f, 0.004f))
        val headE = ModelEntity(r.sphere, r.tapeYellow).apply { setName("pinhead") }
        parent.addChild(headE); headE.place(position = at + Vec3(0f, 0.045f, 0f), scale = Vec3(0.014f, 0.014f, 0.014f))
        val washer = ModelEntity(r.cylinder, r.tapeYellow).apply { setName("washer") }
        parent.addChild(washer); washer.place(position = at + Vec3(0f, 0.001f, 0f), scale = Vec3(0.033f, 0.002f, 0.033f))
    }

    private fun renderBody(r: SceneResources, root: Entity, s: CalcState) {
        val b = s.body
        val spec = s.result.body
        if (b == null || spec == null) {
            if (bodyHolder != null) { runCatching { bodyHolder?.destroy(true) }; bodyHolder = null; bodyVisual = null; bodyKey = ""; attachedHome = null }
            return
        }
        val holder = bodyHolder ?: Entity().apply { setName(Names.BODY); root.addChild(this) }.also {
            bodyHolder = it; growStart = SystemClock.elapsedRealtime(); bodySize = b.size
            attachedHome = if (b.attached) b.pose else null
            AppLog.i("BODY entity created")
        }
        holder.place(position = b.pose.position, rotation = yawQuat(b.pose.yaw))
        val key = "$spec"
        if (key != bodyKey) {
            if (bodyKey.isNotEmpty() && bodySize != b.size) { resizeFrom = Vec3(bodySize.x / max(b.size.x, 1e-3f), bodySize.y / max(b.size.y, 1e-3f), bodySize.z / max(b.size.z, 1e-3f)); resizeStart = SystemClock.elapsedRealtime() }
            bodyKey = key
            bodySize = b.size
            runCatching { bodyVisual?.destroy(true) }
            val v = Entity().apply { setName("vis") }
            holder.addChild(v)
            val builder = BodyBuilder(r, v)
            builder.build(spec)
            bodyWater = builder.waterTop
            bodyVisual = v
            // collider = the whole body (abstract, not a plane): drag / tap it to compare; the visible fill carries the hover
            val size = Vec3(max(b.size.x, 0.03f), max(b.size.y, 0.03f), max(b.size.z, 0.03f))
            holder.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(size.sdk()).offsetByTranslation(Vector3(0f, size.y / 2f, 0f))), physicsMaterial = r.physics))
            holder.components.set(InteractableComponent())
            holder.components.set(HoverEffectComponent())
            AppLog.i("BODY build spec=$spec parts=${builder.parts} size=${b.size}")
        }
        val faded = b.faded
        val op = holder.components[OpacityControllerComponent::class.java]
        if (faded && op == null) holder.components.set(OpacityControllerComponent(0.4f))
        if (!faded && op != null) holder.components.remove(OpacityControllerComponent::class.java)
    }

    private fun renderDemo(r: SceneResources, root: Entity, s: CalcState) {
        val want = (s.phase == Phase.WELCOME || s.phase == Phase.PICK) && s.body == null
        if (!want) { if (demo != null) { runCatching { demo?.destroy(true) }; demo = null; demoPose = null; demoKey = null }; return }
        // K9 (QA3): pinned bodies restored after a restart arrive after the demo block: keep it clear of them
        val others = flow.obstacles()
        val key = others.toString()
        if (demo != null) {
            if (key == demoKey) return
            demoKey = key
            val p0 = demoPose ?: return
            val h0 = lastPose ?: return
            val p = PlaceBody.clearOf(p0, h0.position, DEMO_SIZE, others)
            if (p != p0) { demo?.place(position = p, rotation = yawQuat(yawFacing(p, h0.position))); demoPose = p; AppLog.i("DEMO 10 L moved clear of pinned at=${fmt(p)}") }
            return
        }
        val h = lastPose ?: return
        val f = Vec3(h.forward.x, 0f, h.forward.z).normalized()
        val right = Vec3(-f.z, 0f, f.x)
        // to the right of the window (1.75 m ahead), on the virtual table / detected table: out of the sight cone
        // beside the window (it opens 1.75 m ahead), a little below the eye: in view, 23° off the sight line
        val y = max(floorY() + flow.settings.value.tableHeight, h.position.y - 0.65f)
        val p = PlaceBody.clearOf(Vec3(h.position.x, y, h.position.z) + f * 1.6f + right * 0.7f, h.position, DEMO_SIZE, others)
        demoKey = key
        val e = Entity().apply { setName("demo") }
        root.addChild(e)
        e.place(position = p, rotation = yawQuat(yawFacing(p, h.position)))
        val b = BodyBuilder(r, e)
        b.build(BodySpec.Box(0.25, 0.2, 0.2, water = true))
        b.tape(0.3f, Vec3(-0.15f, 0f, 0.16f))
        demo = e; demoPose = p
        AppLog.i("DEMO 10 L at=${fmt(p)}")
    }

    private fun renderFitBox(r: SceneResources, root: Entity, s: CalcState) {
        val fb = s.fitBox
        if (fb == null) { if (fitHolder != null) { runCatching { fitHolder?.destroy(true) }; fitHolder = null; fitKey = "" }; return }
        val key = "${fb.size}|${fb.pose}"
        if (key == fitKey) return
        fitKey = key
        runCatching { fitHolder?.destroy(true) }
        val h = Entity().apply { setName("fitbox") }
        root.addChild(h)
        h.place(position = fb.pose.position, rotation = yawQuat(fb.pose.yaw))
        val b = BodyBuilder(r, h)
        b.cube("face", fb.size, Vec3(0f, fb.size.y / 2f, 0f), r.fitFace)
        b.edges(fb.size, Vec3.ZERO, r.tapeYellow)
        val d = max(0.04f, (lastPose?.position?.distanceTo(fb.pose.position) ?: 1f) * 0.035f)
        for (axis in 0..2) for (sign in listOf(-1, 1)) {
            if (axis == 1 && sign < 0) continue // the bottom face sits on its support
            val at = when (axis) { 0 -> Vec3(sign * fb.size.x / 2f, fb.size.y / 2f, 0f); 1 -> Vec3(0f, fb.size.y, 0f); else -> Vec3(0f, fb.size.y / 2f, sign * fb.size.z / 2f) }
            val handle = Entity().apply { setName(Names.handle(axis, sign)) }
            handle.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createSphere(d)), physicsMaterial = r.physics))
            handle.components.set(InteractableComponent())
            handle.components.set(HoverEffectComponent())
            h.addChild(handle); handle.place(position = at)
            val vis = ModelEntity(r.sphere, r.handle).apply { setName("hv") }
            handle.addChild(vis); vis.place(scale = Vec3(d, d, d))
        }
        fitHolder = h
    }

    private fun renderPinned(r: SceneResources, root: Entity, s: CalcState) {
        val shown = s.cards.filter { it.pinned && it.pose != null && it.size != null && it.id != s.replayCardId }
        val key = shown.joinToString { "${it.id}@${it.pose}" }
        if (key == pinnedKey) return
        pinnedKey = key
        pinnedBodies.values.forEach { runCatching { it.destroy(true) } }
        pinnedBodies.clear()
        pinPlates.clear()
        shown.take(PanelIds.PIN_SLOTS).forEachIndexed { i, c ->
            val res = if (c.tokens.isNotEmpty()) Evaluate.free(c.tokens) else Evaluate.template(c.template, c.round, c.slots)
            val spec = res.body ?: return@forEachIndexed
            val e = Entity().apply { setName(Names.pinned(c.id)) }
            root.addChild(e)
            e.place(position = c.pose!!.position, rotation = yawQuat(c.pose.yaw))
            BodyBuilder(r, e).build(spec)
            pinnedBodies[c.id] = e
            pinPlates[i] = c.id
        }
    }

    // ------------------------------------------------------------------ per frame

    fun onFrame() {
        val now = SystemClock.elapsedRealtime()
        val pose = head.pose()
        val valid = pose != null && head.steady
        if (valid) lastPose = pose
        graph.window.stepFrame(if (valid) pose!!.position to pose.forward else null, if (valid) pose!!.pitchDeg else null)
        flow.setLost(head.lost(now))
        if (head.noPermission && !flow.s.noPermission) flow.setNoPermission(true)
        if (res == null) { placeHud(now); return }
        // planes: none for 5 s → S17 (never a scanning gate)
        val none = surfaces().isEmpty()
        if (none && now - startedMs > 5000 && !flow.s.noPlanes) flow.setNoPlanes(true)
        if (!none && flow.s.noPlanes && !forceNoPlanes) flow.setNoPlanes(false)
        // headSample buttons (D29)
        val pending = graph.pendingSample
        if (pending != null && lastPose != null) {
            if (!sampler.pending) sampler.start(now)
            sampler.step(now, lastPose!!.position, lastPose!!.forward)?.let { smp ->
                graph.pendingSample = null
                AppLog.i("HEADSAMPLE pos=${fmt(smp.position)} yaw=${"%.1f".format(Locale.US, smp.yawDeg)}")
                pending(smp.position to smp.forward)
            }
        }
        if (!freeze) animate(now)
        stick(now)
        controllerRay()
        fadeCheck()
        placePanels(now)
    }

    private var lastAnim = 0L

    private fun animate(now: Long) {
        val v = bodyVisual
        if (v != null) {
            // growth 1.2 s ease-out from the drop point; live edits interpolate 0.3 s from the old size
            val g = clamp01((now - growStart) / 1200f)
            val ease = 1f - (1f - g) * (1f - g) * (1f - g)
            var sc = Vec3(ease, ease, ease)
            resizeFrom?.let { from ->
                val k = clamp01((now - resizeStart) / 300f)
                sc = Vec3(from.x + (1f - from.x) * k, from.y + (1f - from.y) * k, from.z + (1f - from.z) * k) * ease
                if (k >= 1f) resizeFrom = null
            }
            v.place(scale = Vec3(max(sc.x, 0.01f), max(sc.y, 0.01f), max(sc.z, 0.01f)))
            // water surface: a 1 cm sway every 0.6 s (moves the top line, no mesh rebuild)
            val dy = 0.005f * sin((now % 600L) / 600f * 2f * Math.PI.toFloat())
            for (e in bodyWater) { val p = e.transform().position; e.transform().setPosition(Vector3(p.x, bodySize.y + dy, p.z)) }
        }
        if (flash != null && now > flashUntil) { runCatching { flash?.destroy(true) }; flash = null }
        lastAnim = now
    }

    private var lastStickMs = 0L

    /** Joystick left / right turns the result body (or the fit box) about its own vertical axis (D1). */
    private fun stick(now: Long) {
        val dt = if (lastStickMs == 0L) 0f else (now - lastStickMs) / 1000f
        lastStickMs = now
        applyStick(stickX, dt)
    }

    fun applyStick(x: Float, dt: Float) {
        if (abs(x) < 0.2f || dt <= 0f) return
        val d = -x * Math.toRadians(90.0).toFloat() * min(dt, 0.1f)
        when {
            flow.s.phase == Phase.FITBOX -> flow.fitBoxRotate(d)
            flow.s.body != null -> flow.rotateBody(d)
        }
    }

    /** The app draws its own controller ray and its hit dot (D41), and remembers the hit for [拟合盒]. */
    private fun controllerRay() {
        val r = res ?: return
        val root = root ?: return
        val p = controller ?: return
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) { hideRay(); return }
        val data = runCatching { p.latestData }.getOrNull() ?: run { hideRay(); return }
        val pose = data.right ?: data.left ?: run { hideRay(); return }
        val o = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val q = Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w)
        val d = q.rotate(Vec3.AHEAD).normalized()
        if (!o.isFinite() || (o.x == 0f && o.y == 0f && o.z == 0f)) { hideRay(); return }
        lastRay = o to d
        val hit = resolveLine(o, d, Channel.RAY)
        lastRayHitPin = hit
        val len = hit?.rangeM ?: 3f
        val ray = rayEntity ?: ModelEntity(r.cube, r.ray).apply { setName("ctrlRay"); root.addChild(this) }.also { rayEntity = it }
        ray.place(position = o + d * (len / 2f), rotation = alongX(d), scale = Vec3(len, 0.002f, 0.002f))
        val dot = rayDot ?: ModelEntity(r.sphere, r.white).apply { setName("ctrlDot"); root.addChild(this) }.also { rayDot = it }
        if (hit != null) dot.place(position = hit.point, scale = Vec3(0.015f, 0.015f, 0.015f)) else dot.place(position = STOW)
    }

    private fun hideRay() { rayEntity?.place(position = STOW); rayDot?.place(position = STOW) }

    /** Head inside the body, or the body in the head → window sight cone: 25 % + a hint, never pushed (R1-3). */
    private fun fadeCheck() {
        val b = flow.s.body ?: return
        val h = lastPose ?: return
        val box = bodyBox(b.pose, b.size)
        val inside = box.contains(h.position)
        val win = window()
        val inCone = win != null && SightCone(h.position, win).touches(box)
        val f = inside || inCone
        if (f != b.faded) { flow.bodyFaded(f); AppLog.i("BODY faded=$f inside=$inside cone=$inCone") }
    }

    private fun flashPlane(p: PinPoint) {
        val r = res ?: return
        val root = root ?: return
        runCatching { flash?.destroy(true) }
        val plane = surfaces().firstOrNull { hits.contains(it, p.point, 0.02f) && hits.distanceToPlane(it, p.point) < 0.02f } ?: return
        val g = Entity().apply { setName("flash") }
        root.addChild(g)
        for ((a, b) in plane.edges) {
            val e = ModelEntity(r.cube, r.flash)
            g.addChild(e)
            e.place(position = (a + b) * 0.5f, rotation = alongX(b - a), scale = Vec3(a.distanceTo(b), 0.004f, 0.004f))
        }
        flash = g
        flashUntil = SystemClock.elapsedRealtime() + 600
    }

    // ------------------------------------------------------------------ plates

    private fun show(id: String, at: Vec3?, yaw: Float = 0f, scale: Float = 1f) {
        val h = holders[id] ?: return
        if (at == null) {
            if (panelShown[id] == true || panelWanted[id] != null) { h.place(position = STOW, scale = Vec3(0.001f, 0.001f, 0.001f)); panelShown[id] = false; panelWanted[id] = null }
            pendingShow.remove(id)
            return
        }
        h.place(position = at, rotation = yawQuat(yaw), scale = Vec3(scale, scale, scale))
        if (panelWanted[id] == null) pendingShow[id] = 2 // compose its content two frames after it is in place
        panelWanted[id] = at to yaw
        val left = pendingShow[id]
        if (left != null) { if (left <= 0) { pendingShow.remove(id); panelShown[id] = true } else pendingShow[id] = left - 1 }
    }

    private var hudAt: Vec3? = null

    private fun placeHud(now: Long) {
        val h = lastPose
        val window = graph.window
        val want = !window.exiting && (res == null || window.levelHint || (window.panelState == "ON_DESTROY" && !window.gate.waiting) || flow.s.headSampling)
        if (!want || h == null) { hudAt = null; show(PanelIds.HUD, null); return }
        val f = h.forward
        val flat = Vec3(f.x, 0f, f.z).normalized()
        val cur = hudAt
        // in the line of sight (pitch clamped to ±40°), only re-placed when it has left the view
        val needs = cur == null || run { val to = (cur - h.position).normalized(); to.dot(f) < 0.7f }
        if (needs) {
            val pitch = Math.toRadians(h.pitchDeg.coerceIn(-40f, 40f).toDouble()).toFloat()
            hudAt = h.position + flat * (0.75f * kotlin.math.cos(pitch)) + Vec3.UP * (0.75f * kotlin.math.sin(pitch))
        }
        show(PanelIds.HUD, hudAt, yawFacing(hudAt!!, h.position))
    }

    private fun placePanels(now: Long) {
        placeHud(now)
        val h = lastPose ?: return
        val s = flow.s
        // reading plate: over the last tape's middle, 3 cm up
        val tape = s.tapes.lastOrNull { it.lengthM > 0 }
        if (tape != null && s.body == null) {
            val mid = (tape.a.point + tape.b.point) * 0.5f + Vec3(0f, 0.05f, 0f)
            show(PanelIds.READING, mid, yawFacing(mid, h.position), distanceScale(mid, h.position))
        } else show(PanelIds.READING, null)
        // result plate: 0.05 m outside the top edge facing the user (panelGeometryCheck), yaw only
        val b = s.body
        if (b != null) {
            val (c, yaw) = ResultPlate.pose(b.pose, b.size, h.position)
            show(PanelIds.RESULT, c, yaw, distanceScale(c, h.position))
        } else show(PanelIds.RESULT, null)
        // demo label above the 10 L block
        val dp = demoPose
        if (dp != null && demo != null) { val at = dp + Vec3(0f, 0.28f, 0f); show(PanelIds.DEMO, at, yawFacing(at, h.position)) } else show(PanelIds.DEMO, null)
        // fit box: one reading per side
        val fb = s.fitBox
        for (i in 0..2) {
            if (fb == null) { show(PanelIds.fit(i), null); continue }
            val c = kotlin.math.cos(fb.pose.yaw); val sn = kotlin.math.sin(fb.pose.yaw)
            val right = Vec3(c, 0f, -sn); val toward = Vec3(sn, 0f, c)
            val p = fb.pose.position
            val at = when (i) {
                0 -> p + toward * (fb.size.z / 2f + 0.03f) + Vec3(0f, fb.size.y + 0.06f, 0f)
                1 -> p + right * (fb.size.x / 2f + 0.12f) + Vec3(0f, fb.size.y / 2f, 0f)
                else -> p + right * (-fb.size.x / 2f - 0.12f) + toward * (fb.size.z / 2f) + Vec3(0f, fb.size.y / 2f, 0f)
            }
            show(PanelIds.fit(i), at, yawFacing(at, h.position))
        }
        // pinned cards: their plates on the same rule
        for (i in 0 until PanelIds.PIN_SLOTS) {
            val id = pinPlates[i]
            val card = id?.let { cid -> s.cards.firstOrNull { it.id == cid } }
            if (card?.pose == null || card.size == null) { show(PanelIds.pin(i), null); continue }
            val (c, yaw) = ResultPlate.pose(card.pose, card.size, h.position)
            show(PanelIds.pin(i), c, yaw, distanceScale(c, h.position))
        }
    }

    /** Constant view angle beyond 3 m (空间尺 ScaleForDistance). */
    private fun distanceScale(at: Vec3, eye: Vec3): Float = max(1f, at.distanceTo(eye) / 3f)

    // ------------------------------------------------------------------ anchors (S20)

    private fun restorePinned() {
        val pinned = flow.s.cards.filter { it.pinned && it.anchorId != null }
        if (pinned.isEmpty()) return
        scope.launch {
            when (val r = graph.anchors.loadAll()) {
                is AnchorOutcome.Ok -> {
                    val byId = r.value.associateBy { it.id }
                    for (c in pinned) {
                        val a = byId[c.anchorId]
                        if (a == null) { flow.restoredPose(c.id, c.pose ?: continue, elsewhere = true); continue }
                        val pos = a.pose.position + (c.anchorOffset ?: Vec3.ZERO)
                        flow.restoredPose(c.id, BodyPose(pos, c.pose?.yaw ?: 0f), elsewhere = isElsewhere(pos))
                    }
                }
                is AnchorOutcome.Failed -> AppLog.w("ANCHOR load failed code=${r.code} ${r.message}")
            }
        }
    }

    /** D9 补充: behind, > 45° off the view, or > 2.5 m away → left where it is, marked「在房间别处」. */
    private fun isElsewhere(p: Vec3): Boolean {
        val h = lastPose ?: return false
        val to = Vec3(p.x - h.position.x, 0f, p.z - h.position.z)
        val f = Vec3(h.forward.x, 0f, h.forward.z).normalized()
        if (to.length > 2.5f) return true
        val c = to.normalized().dot(f)
        return c < kotlin.math.cos(Math.toRadians(45.0)).toFloat()
    }

    // ------------------------------------------------------------------ debug

    fun dump(): String {
        val h = lastPose
        val b = flow.s.body
        val cone = if (h != null && b != null && window() != null) SightCone(h.position, window()!!).angleOff(bodyBox(b.pose, b.size).center) else null
        return "head=${fmt(h?.position)} fwd=${fmt(h?.forward)} pitch=${h?.pitchDeg} window=${fmt(window())} floor=${floorY()} planes=${surfaces().size} " +
            "bodyEntity=${bodyHolder != null} bodyAabb=${b?.let { val x = bodyBox(it.pose, it.size); "${fmt(x.min)}..${fmt(x.max)}" }} coneAngle=$cone " +
            "panels=${panelShown.filterValues { it }.keys} ray=${lastRay?.let { fmt(it.first) }}"
    }

    fun evidence(): String {
        val h = lastPose ?: return "no head"
        val b = flow.s.body
        val sb = StringBuilder("EVIDENCE head=${fmt(h.position)}")
        if (b != null) {
            val box = bodyBox(b.pose, b.size)
            sb.append(" body=${fmt(box.min)}..${fmt(box.max)} distHead=${"%.3f".format(Locale.US, box.distanceTo(h.position))}")
            val (c, _) = ResultPlate.pose(b.pose, b.size, h.position)
            sb.append(" plateDown=${"%.1f".format(Locale.US, ResultPlate.downAngle(c, h.position))}")
        }
        return sb.toString()
    }

    companion object {
        val STOW = Vec3(0f, -40f, 0f)
        /** The 10 L demo block (0.25 × 0.2 × 0.2 m) with its tape, as a box for clearance (K9). */
        val DEMO_SIZE = Vec3(0.32f, 0.25f, 0.26f)
        fun fmt(v: Vec3?): String = if (v == null) "null" else String.format(Locale.US, "(%.4f, %.4f, %.4f)", v.x, v.y, v.z)
    }
}

@Suppress("unused") private fun aabbUnused(a: Aabb) = a
@Suppress("unused") private fun cardUnused(c: Card) = c
