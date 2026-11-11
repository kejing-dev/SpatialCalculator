package io.embodit.spatialcalc.ui.stage

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import io.embodit.spatialcalc.domain.calc.BodySpec
import io.embodit.spatialcalc.domain.math.Quat4
import io.embodit.spatialcalc.domain.math.Vec3
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the visible parts of a result body under [parent] (local frame: x right, y up, z towards the user, origin at
 * the centre of the bottom face). Everything is the shared unit cube / cylinder / torus scaled to size: no mesh is
 * created per body (a mesh costs a rendered frame, 空间尺 runtime facts), so a live edit only re-scales entities.
 * Sizes are 1:1 with the formula (acceptance: body sides = chip values ± 1 mm).
 */
internal class BodyBuilder(private val r: SceneResources, private val parent: Entity) {
    var parts = 0
        private set
    /** Entities that wobble with the water surface (6 frame loop, entities3d.resultBody). */
    val waterTop = ArrayList<Entity>()

    fun cube(name: String, size: Vec3, at: Vec3, m: Material): Entity {
        val e = ModelEntity(r.cube, m)
        e.setName(name)
        parent.addChild(e)
        e.place(position = at, scale = Vec3(max(size.x, 1e-4f), max(size.y, 1e-4f), max(size.z, 1e-4f)))
        parts++
        return e
    }

    /** The 12 edges of a box of [size] whose bottom centre is at [base]. */
    fun edges(size: Vec3, base: Vec3, m: Material, t: Float = EDGE_M, topMaterial: Material? = null) {
        val hx = size.x / 2f; val hz = size.z / 2f; val h = size.y
        for (y in listOf(0f, h)) {
            val mm = if (y > 0f && topMaterial != null) topMaterial else m
            val a = cube("edge", Vec3(size.x + t, t, t), base + Vec3(0f, y, -hz), mm)
            val b = cube("edge", Vec3(size.x + t, t, t), base + Vec3(0f, y, hz), mm)
            val c = cube("edge", Vec3(t, t, size.z + t), base + Vec3(-hx, y, 0f), mm)
            val d = cube("edge", Vec3(t, t, size.z + t), base + Vec3(hx, y, 0f), mm)
            if (y > 0f && topMaterial != null) waterTop += listOf(a, b, c, d)
        }
        for (x in listOf(-hx, hx)) for (z in listOf(-hz, hz)) cube("edge", Vec3(t, h, t), base + Vec3(x, h / 2f, z), m)
    }

    fun build(spec: BodySpec, fit: Boolean = true) {
        when (spec) {
            is BodySpec.Box -> boxBody(spec)
            is BodySpec.Cylinder -> cylinder(spec)
            is BodySpec.Tiles -> tiles(spec)
            is BodySpec.Paint -> paint(spec)
            is BodySpec.Ghosts -> ghosts(spec)
            is BodySpec.Rug -> rug(spec)
            is BodySpec.Tape -> tape(spec.length.toFloat(), Vec3.ZERO)
            is BodySpec.Slab -> {
                val s = Vec3(spec.x.toFloat(), 0.004f, spec.z.toFloat())
                cube("fill", s, Vec3(0f, 0.002f, 0f), r.result[0]); edges(s, Vec3.ZERO, r.edge)
            }
        }
    }

    private fun boxBody(b: BodySpec.Box) {
        val s = Vec3(b.x.toFloat(), b.y.toFloat(), b.z.toFloat())
        if (b.oversize) { oversize(s); return }
        cube("fill", s, Vec3(0f, s.y / 2f, 0f), if (b.water) r.water[0] else r.result[0])
        edges(s, Vec3.ZERO, r.edge, topMaterial = if (b.water) r.waterLine else null)
    }

    private fun cylinder(c: BodySpec.Cylinder) {
        val d = c.diameter.toFloat(); val h = c.height.toFloat()
        val e = ModelEntity(r.cylinder, if (c.water) r.water[0] else r.result[0])
        e.setName("fill"); parent.addChild(e); e.place(position = Vec3(0f, h / 2f, 0f), scale = Vec3(d, h, d)); parts++
        for (y in listOf(0.002f, h)) {
            val ring = ModelEntity(r.ring, if (y > 0.01f && c.water) r.waterLine else r.edge)
            ring.setName("rim"); parent.addChild(ring); ring.place(position = Vec3(0f, y, 0f), scale = Vec3(d, d, d)); parts++
            if (y > 0.01f) waterTop += ring
        }
    }

    /** Oversize (> 2 m, D24 / R0 方向 C borrow): wire frame + 15 % fill + ticks every 0.5 m along three bottom edges. */
    private fun oversize(s: Vec3) {
        cube("fill", s, Vec3(0f, s.y / 2f, 0f), r.oversizeFill)
        edges(s, Vec3.ZERO, r.edge, t = 0.006f)
        val step = 0.5f
        var x = -s.x / 2f + step; var n = 0
        while (x < s.x / 2f - 0.01f && n < 30) { cube("tick", Vec3(0.008f, 0.06f, 0.008f), Vec3(x, 0.03f, s.z / 2f), r.edge); x += step; n++ }
        var z = -s.z / 2f + step
        while (z < s.z / 2f - 0.01f && n < 60) { cube("tick", Vec3(0.008f, 0.06f, 0.008f), Vec3(-s.x / 2f, 0.03f, z), r.edge); z += step; n++ }
        var y = step
        while (y < s.y - 0.01f && n < 90) { cube("tick", Vec3(0.06f, 0.008f, 0.008f), Vec3(-s.x / 2f, y, s.z / 2f), r.edge); y += step; n++ }
    }

    /** Wall of tiles in the body's XY plane: pitch = tile + grout; the last partial column / row in cutAmber. */
    private fun tiles(t: BodySpec.Tiles) {
        val w = t.width.toFloat(); val h = t.height.toFloat()
        val pw = t.pitchW.toFloat(); val ph = t.pitchH.toFloat()
        cube("fill", Vec3(w, h, 0.003f), Vec3(0f, h / 2f, 0f), r.tile[0])
        val gt = max(t.gap.toFloat(), 0.002f)
        var k = 1; var x = -w / 2f + pw
        while (x < w / 2f - 1e-4f && k < 80) { cube("grout", Vec3(gt, h, 0.005f), Vec3(x - gt / 2f, h / 2f, 0.001f), r.grout); x += pw; k++ }
        k = 1; var y = ph
        while (y < h - 1e-4f && k < 80) { cube("grout", Vec3(w, gt, 0.005f), Vec3(0f, y - gt / 2f, 0.001f), r.grout); y += ph; k++ }
        if (t.partialCol) {
            val full = floor(w / pw) * pw
            val rest = w - full
            cube("cut", Vec3(rest, h, 0.006f), Vec3(-w / 2f + full + rest / 2f, h / 2f, 0.002f), r.amberFill)
        }
        if (t.partialRow) {
            val full = floor(h / ph) * ph
            val rest = h - full
            cube("cut", Vec3(w, rest, 0.006f), Vec3(0f, full + rest / 2f, 0.002f), r.amberFill)
        }
        frame(w, h, r.edge)
    }

    private fun frame(w: Float, h: Float, m: Material, t: Float = EDGE_M) {
        cube("edge", Vec3(w + t, t, t), Vec3(0f, 0f, 0f), m)
        cube("edge", Vec3(w + t, t, t), Vec3(0f, h, 0f), m)
        cube("edge", Vec3(t, h, t), Vec3(-w / 2f, h / 2f, 0f), m)
        cube("edge", Vec3(t, h, t), Vec3(w / 2f, h / 2f, 0f), m)
    }

    /** One can's worth along the wall from the bottom, the rest of the wall outlined. */
    private fun paint(p: BodySpec.Paint) {
        val w = p.width.toFloat(); val h = p.height.toFloat(); val b = p.band.toFloat()
        cube("band", Vec3(w, b, 0.004f), Vec3(0f, b / 2f, 0.002f), r.paint[0])
        cube("edge", Vec3(w, EDGE_M, 0.006f), Vec3(0f, b, 0.003f), r.edge)
        frame(w, h, r.specLine)
    }

    /** The measured cell outlined, the boxes packed inside (each slightly smaller so they read as separate boxes). */
    private fun ghosts(g: BodySpec.Ghosts) {
        val cell = Vec3(g.cellX.toFloat(), g.cellY.toFloat(), g.cellZ.toFloat())
        val bx = g.boxX.toFloat(); val by = g.boxY.toFloat(); val bz = g.boxZ.toFloat()
        if (!g.fits) {
            edges(cell, Vec3.ZERO, r.amber, t = 0.006f)
            // one box stuck in the opening, sticking out where it is too big
            cube("box", Vec3(bx, by, bz), Vec3(0f, by / 2f, cell.z / 2f - bz / 2f + 0.02f), r.amberFill)
            return
        }
        edges(cell, Vec3.ZERO, r.edge)
        val x0 = -cell.x / 2f + bx / 2f; val z0 = cell.z / 2f - bz / 2f
        var n = 0
        for (iy in 0 until g.ny) for (iz in 0 until g.nz) for (ix in 0 until g.nx) {
            if (n >= MAX_GHOSTS) return
            cube("box", Vec3(bx * 0.94f, by * 0.94f, bz * 0.94f), Vec3(x0 + ix * bx, by / 2f + iy * by, z0 - iz * bz), r.ghost)
            n++
        }
    }

    /** Rug on the floor + the nearest standard size outlined from the same corner. */
    private fun rug(g: BodySpec.Rug) {
        val x = g.x.toFloat(); val z = g.z.toFloat()
        cube("rug", Vec3(x, 0.006f, z), Vec3(0f, 0.003f, 0f), r.rug[0])
        edges(Vec3(x, 0.006f, z), Vec3.ZERO, r.edge, t = 0.004f)
        val sx = g.specX.toFloat(); val sz = g.specZ.toFloat()
        val c = Vec3(-x / 2f + sx / 2f, 0.008f, -z / 2f + sz / 2f)
        val t = 0.006f
        cube("spec", Vec3(sx, t, t), c + Vec3(0f, 0f, -sz / 2f), r.specLine)
        cube("spec", Vec3(sx, t, t), c + Vec3(0f, 0f, sz / 2f), r.specLine)
        cube("spec", Vec3(t, t, sz), c + Vec3(-sx / 2f, 0f, 0f), r.specLine)
        cube("spec", Vec3(t, t, sz), c + Vec3(sx / 2f, 0f, 0f), r.specLine)
    }

    /** A tape along local +x from [from]: 16 mm yellow band, ink tick every 10 cm, red mark every metre. */
    fun tape(length: Float, from: Vec3) {
        cube("band", Vec3(length, 0.0015f, 0.016f), from + Vec3(length / 2f, 0.00075f, 0f), r.tapeYellow)
        var x = 0.1f; var n = 0
        while (x < length - 0.005f && n < 60) {
            val metre = kotlin.math.abs(x - Math.round(x)) < 0.001f
            cube("tick", Vec3(0.002f, 0.0018f, if (metre) 0.016f else 0.008f), from + Vec3(x, 0.0012f, -0.004f), if (metre) r.amber else r.ink)
            x += 0.1f; n++
        }
    }

    companion object {
        const val EDGE_M = 0.0035f
        const val MAX_GHOSTS = 64
    }
}

/** Yaw-only rotation about +Y (D9 / D10: pitch and roll forced to 0). */
internal fun yawQuat(yaw: Float) = Quat4.axisAngle(Vec3.UP, yaw)

/** Rotation that turns local +X onto the direction [d] (tapes): shortest arc, so a level tape stays flat. */
internal fun alongX(d: Vec3): Quat4 = Quat4.fromTo(Vec3.RIGHT, d.normalized())

internal fun clamp01(x: Float) = min(1f, max(0f, x))
