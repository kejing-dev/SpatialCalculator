package io.embodit.spatialcalc.ui.stage

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.spatialcalc.domain.math.Quat4
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.ui.theme.CalcColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!
internal fun Vec3.sdk() = Vector3(x, y, z)
internal fun Vector3.domain() = Vec3(x, y, z)
internal fun Quat4.sdk() = Quat(x, y, z, w)
internal fun Color.c4(alpha: Float = this.alpha) = Color4(red, green, blue, alpha)

internal fun Entity.place(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = components[TransformComponent::class.java] ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

/**
 * The shared meshes and materials of the Stage (budget: no external model, no texture file). Creating a resource
 * waits on the renderer, so everything is made concurrently off the main thread and kept global until [close].
 *
 * B12 / ui_gates "geometry": the design package's asset list says the result bodies are abstract geometry on purpose
 * (assets「无 Tripo（抽象几何体就是功能本身）」: the answer of a calculation shown at 1:1), as are the pins, tapes and
 * markers of the measuring instrument. Every built-in shape below carries its gate:allow reason.
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var cube: MeshResource; private set
    lateinit var cylinder: MeshResource; private set
    lateinit var sphere: MeshResource; private set
    lateinit var ring: MeshResource; private set

    lateinit var result: Array<UnlitMaterial>; private set
    lateinit var water: Array<UnlitMaterial>; private set
    lateinit var ghost: UnlitMaterial; private set
    lateinit var rug: Array<UnlitMaterial>; private set
    lateinit var paint: Array<UnlitMaterial>; private set
    lateinit var tile: Array<UnlitMaterial>; private set
    lateinit var edge: Material; private set
    lateinit var waterLine: Material; private set
    lateinit var grout: Material; private set
    lateinit var amber: Material; private set
    lateinit var amberFill: Material; private set
    lateinit var tapeYellow: Material; private set
    lateinit var ink: Material; private set
    lateinit var steel: Material; private set
    lateinit var flash: Material; private set
    lateinit var white: Material; private set
    lateinit var ray: Material; private set
    lateinit var fitFace: Material; private set
    lateinit var handle: Material; private set
    lateinit var specLine: Material; private set
    lateinit var oversizeFill: Material; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }

    /** Settings 结果体透明度: 淡 45 % / 中 60 % / 实 85 % (palette resultBlue @45 % default). */
    fun setOpacity(level: Int) {
        val o = OPACITY[level.coerceIn(0, 2)]
        result.forEach { it.setOpacity(o) }
        water.forEach { it.setOpacity(o) }
        rug.forEach { it.setOpacity(minOf(1f, o + 0.15f)) }
        tile.forEach { it.setOpacity(minOf(1f, o + 0.1f)) }
        paint.forEach { it.setOpacity(minOf(1f, o + 0.1f)) }
    }

    companion object {
        val OPACITY = floatArrayOf(0.45f, 0.60f, 0.85f)

        private fun unlit(color: Color, opacity: Float = 1f, depthWrite: Boolean = true): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) { setOpacity(opacity); setDepthWrite(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
                if (!depthWrite) setDepthWrite(false)
            }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T { toGlobal(); synchronized(r.all) { r.all += this }; return this }
            val io = Dispatchers.IO
            listOf(
                // gate:allow geometry unit cube scaled to the 1:1 result body / tape / tick / edge bar (design assets: abstract geometry is the answer itself)
                async(io) { r.cube = MeshResource.createBox(Vector3(1f, 1f, 1f), cornerRadius = 0f).keep() },
                // gate:allow geometry unit cylinder: round tank water column and the pin shank (design calcModel tank 圆缸; 空间尺 pin)
                async(io) { r.cylinder = MeshResource.createCylinder(radius = 0.5f, height = 1f).keep() },
                // gate:allow geometry unit sphere: pin head, fit-box face handles (abstract instrument parts)
                async(io) { r.sphere = MeshResource.createSphere(radius = 0.5f).keep() },
                async(io) { r.ring = MeshResource.createTorus(outerRingRadius = 0.5f, innerRingRadius = 0.46f).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
                async(io) { r.result = Array(1) { unlit(CalcColors.ResultBlue, OPACITY[0]).keep() } },
                async(io) { r.water = Array(1) { unlit(CalcColors.WaterBlue, OPACITY[0]).keep() } },
                async(io) { r.ghost = unlit(CalcColors.GhostBox, 0.30f).keep() },
                async(io) { r.rug = Array(1) { unlit(CalcColors.Rug, 0.6f).keep() } },
                async(io) { r.paint = Array(1) { unlit(CalcColors.Paint, 0.55f).keep() } },
                async(io) { r.tile = Array(1) { unlit(CalcColors.Tile, 0.55f).keep() } },
                async(io) { r.edge = unlit(CalcColors.ResultEdge).keep() },
                async(io) { r.waterLine = unlit(CalcColors.WaterLine).keep() },
                async(io) { r.grout = unlit(CalcColors.Grout).keep() },
                async(io) { r.amber = unlit(CalcColors.CutAmber).keep() },
                async(io) { r.amberFill = unlit(CalcColors.CutAmber, 0.45f).keep() },
                async(io) { r.tapeYellow = unlit(CalcColors.TapeYellow).keep() },
                async(io) { r.ink = unlit(CalcColors.TapeInk).keep() },
                async(io) { r.steel = unlit(CalcColors.Steel).keep() },
                async(io) { r.flash = unlit(CalcColors.TapeYellow, 0.6f).keep() },
                async(io) { r.white = unlit(Color(0xFFFFFFFF)).keep() },
                async(io) { r.ray = unlit(Color(0xFFFFFFFF), 0.55f).keep() },
                async(io) { r.fitFace = unlit(CalcColors.TapeYellow, 0.15f).keep() },
                async(io) { r.handle = unlit(CalcColors.TapeYellow).keep() },
                async(io) { r.specLine = unlit(Color(0xFFEFE6D8), 0.8f).keep() },
                async(io) { r.oversizeFill = unlit(CalcColors.ResultBlue, 0.15f).keep() },
            ).awaitAll()
            r
        }
    }
}
