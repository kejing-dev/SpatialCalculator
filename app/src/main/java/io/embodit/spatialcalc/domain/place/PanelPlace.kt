package io.embodit.spatialcalc.domain.place

import io.embodit.spatialcalc.domain.math.Quat4
import io.embodit.spatialcalc.domain.math.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the gauge's turn panel ([−15°] [+15°] per axis, [回正]) goes (v0.3.1, QA #9 q9-2 / q9-3; profile D8 / D13 / D17 /
 * D20 / D41). It no longer hangs on the gauge: it stands at a fixed, readable spot in front of the user
 * ([DIST_M] from the eye, below the line of sight, within ±39°), beside the main window, and picks the first of
 * [spots] whose outline, seen from the user, touches neither the gauge (line / box / its value plate) nor the window.
 * It keeps its spot while that stays clear, so it only moves when the user drags the gauge onto it. Pure: unit-tested.
 */
object PanelPlace {
    const val DIST_M = 0.9f
    /** 1 dp of the panel = 0.7 mm: 64 dp keys are 4.5 cm (≈ 2.85° at 0.9 m), 20 sp text has 10 mm capitals (≈ 0.64°). */
    const val M_PER_DP = 0.0007f
    const val W_DP = 320f
    const val KEY_DP = 64f
    const val GAP_DP = 16f
    const val PAD_DP = 12f
    const val TEXT_SP = 20f
    /** Capital height / font size. */
    const val CAP = 0.72f
    /** Line: turn + tilt rows and [回正]; box: X / Y / Z rows and [回正]. 16 dp between rows (B31). */
    fun heightDp(rows: Int): Float = 2 * PAD_DP + (rows + 1) * KEY_DP + rows * GAP_DP
    val LINE_H_DP = heightDp(2)
    val BOX_H_DP = heightDp(3)
    /** Keep at least this (tangent, ≈ 2°) between the panel and anything else. */
    const val MARGIN = 0.035f
    /** Limit of the default view for every panel corner (screen projection, both axes). */
    const val VIEW_DEG = 39f
    /** Value plate half height over the gauge (same as the scene). */
    const val PLATE_HALF_H = 0.03f
    /** Line obstacle: tube, ticks hanging 2.8 cm, tick numbers under them, the 6 cm grab bar. */
    const val LINE_PAD_M = 0.065f
    /** Value plate: up to ≈ 28 cm wide, 7 cm tall → a 0.14 m radius around its centre. */
    const val PLATE_PAD_M = 0.14f

    /** Spot = azimuth (+ right) and elevation (+ up) in degrees from the eye, in the window's frame. */
    data class Spot(val id: String, val azDeg: Float, val elDeg: Float)

    /** Left of the window first (the gauge starts on its right), then lower, under the window, on the right. */
    val spots = listOf(
        Spot("left", -24f, -12f),
        Spot("left-low", -24f, -26f),
        Spot("below", 0f, -32.5f),
        Spot("right-low", 24f, -26f),
        Spot("right", 24f, -12f),
        // fallbacks when the user dragged the gauge across all of the above: level with the eye, still beside the window
        Spot("left-level", -24f, 2f),
        Spot("right-level", 24f, 2f),
    )
    /** The first [PRIMARY] spots sit below the line of sight (D10); the rest are fallbacks. */
    const val PRIMARY = 5

    /** An obstacle: points (m) each padded by [padM]; seen from the eye it covers the box around their projections. */
    data class Blob(val points: List<Vec3>, val padM: Float)

    /** Where the window was opened from (the eye at that moment): the panel's frame. */
    fun eyeOf(w: WindowPose): Vec3 = w.center - w.forward * LinePlace.DISTANCE_M + Vec3.UP * LinePlace.WINDOW_DROP_M

    fun center(w: WindowPose, spot: Spot, dist: Float = DIST_M): Vec3 {
        val az = rad(spot.azDeg); val el = rad(spot.elDeg)
        val d = w.forward * (cos(el) * cos(az)) + w.right * (cos(el) * sin(az)) + Vec3.UP * sin(el)
        return eyeOf(w) + d * dist
    }

    fun widthM() = W_DP * M_PER_DP
    fun heightM(rows: Int) = heightDp(rows) * M_PER_DP

    /** The panel's 8 corners (a 8 mm thick slab facing [eye], yaw only). */
    fun corners(c: Vec3, eye: Vec3, wM: Float, hM: Float): List<Vec3> {
        val n = Vec3(eye.x - c.x, 0f, eye.z - c.z).normalized()
        val r = Vec3(-n.z, 0f, n.x)
        val out = ArrayList<Vec3>(8)
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f))
            out += c + r * (sx * wM / 2f) + Vec3.UP * (sy * hM / 2f) + n * (sz * 0.004f)
        return out
    }

    /** Screen projection (tangent plane) of [p] from [eye] looking along level [f]; null when not in front. */
    fun project(eye: Vec3, f: Vec3, p: Vec3): Pair<Float, Float>? {
        val r = Vec3(-f.z, 0f, f.x)
        val d = p - eye
        val z = d.dot(f)
        if (z < 0.05f) return null
        return d.dot(r) / z to d.y / z
    }

    /** Projected corner angles (degrees, horizontal / vertical) — what QA measures on a screenshot. */
    fun cornerDegrees(eye: Vec3, f: Vec3, pts: List<Vec3>): List<Pair<Float, Float>> = pts.mapNotNull { p ->
        project(eye, f, p)?.let { (x, y) -> deg(kotlin.math.atan(x)) to deg(kotlin.math.atan(y)) }
    }

    /** Tangent-plane rectangle [x0, y0, x1, y1] covered by a blob (null when it is all behind the eye). */
    fun rect(eye: Vec3, f: Vec3, b: Blob): FloatArray? {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        var any = false
        for (p in b.points) {
            val z = (p - eye).dot(f)
            val (x, y) = project(eye, f, p) ?: continue
            val pad = b.padM / z
            x0 = min(x0, x - pad); x1 = max(x1, x + pad); y0 = min(y0, y - pad); y1 = max(y1, y + pad); any = true
        }
        return if (any) floatArrayOf(x0, y0, x1, y1) else null
    }

    private fun hit(a: FloatArray, b: FloatArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    private fun area(a: FloatArray, b: FloatArray): Float =
        max(0f, min(a[2], b[2]) - max(a[0], b[0])) * max(0f, min(a[3], b[3]) - max(a[1], b[1]))

    /** The window rectangle plus the system controls under it (D8 / native-window-move.md §2). */
    fun windowBlob(w: WindowPose): Blob {
        val bar = LinePlace.SYSTEM_BAR_DP * (w.widthM / LinePlace.WIDTH_DP)
        val pts = ArrayList<Vec3>()
        for (sx in listOf(-1f, 1f)) for (y in listOf(w.heightM / 2f, -w.heightM / 2f - bar)) pts += w.center + w.right * (sx * w.widthM / 2f) + Vec3.UP * y
        return Blob(pts, 0.01f)
    }

    /** The line as small blobs every 5 cm (a slanted line covers only its own strip), and its value plate. */
    fun lineBlobs(start: Vec3, dir: Vec3, len: Float, plate: Vec3?): List<Blob> {
        val n = max(2, (len / 0.05f).toInt())
        val out = (0..n).map { Blob(listOf(start + dir * (len * it / n)), LINE_PAD_M) }.toMutableList()
        if (plate != null) out += Blob(listOf(plate), PLATE_PAD_M)
        return out
    }

    /** The box's 8 corners and its value plate. */
    fun boxBlobs(c: Vec3, q: Quat4, dims: List<Double>, plate: Vec3?, platePad: Float = PLATE_PAD_M): List<Blob> {
        val hx = dims[0].toFloat() / 2f; val hy = dims[1].toFloat() / 2f; val hz = dims[2].toFloat() / 2f
        val pts = ArrayList<Vec3>()
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) pts += c + q.rotate(Vec3(sx * hx, sy * hy, sz * hz))
        val out = mutableListOf(Blob(pts, 0.01f))
        if (plate != null) out += Blob(listOf(plate), platePad)
        return out
    }

    /** The box's value plate (the scene puts it there): over the top, facing the user. */
    fun boxPlate(c: Vec3, q: Quat4, dims: List<Double>): Vec3 = c + Vec3.UP * (GaugeMath.worldHalfHeight(q, dims) + 0.03f + PLATE_HALF_H)

    /** The line's value plate (the scene puts it there): over the middle of the part in view, on the line's up side. */
    fun linePlate(eye: Vec3, headForward: Vec3, start: Vec3, q: Quat4, len: Float, standing: Boolean): Vec3 {
        val dir = q.rotate(Vec3(1f, 0f, 0f))
        val toObj = Vec3(start.x - eye.x, 0f, start.z - eye.z).normalized()
        val viewRight = Vec3(-toObj.z, 0f, toObj.x)
        val mid = LinePlace.visibleMid(eye, headForward, start, dir, len)
        val side = if (standing) viewRight * 0.11f else q.rotate(Vec3.UP) * (0.030f + PLATE_HALF_H)
        return start + dir * mid + side
    }

    data class Pick(val index: Int, val spot: Spot, val center: Vec3, val clear: Boolean)

    /** The panel rectangle (tangent plane, with [MARGIN]) at [spot]. */
    fun panelRect(eye: Vec3, f: Vec3, w: WindowPose, spot: Spot, rows: Int): FloatArray? {
        val c = center(w, spot)
        val r = rect(eye, f, Blob(corners(c, eye, widthM(), heightM(rows)), 0f)) ?: return null
        return floatArrayOf(r[0] - MARGIN, r[1] - MARGIN, r[2] + MARGIN, r[3] + MARGIN)
    }

    /** Overlap (tangent-plane area) of the panel at [spot] with the window and [obstacles]; 0 = clear. */
    fun overlap(eye: Vec3, f: Vec3, w: WindowPose, spot: Spot, rows: Int, obstacles: List<Blob>): Float {
        val p = panelRect(eye, f, w, spot, rows) ?: return Float.MAX_VALUE
        var a = 0f
        for (b in obstacles + windowBlob(w)) {
            val r = rect(eye, f, b) ?: continue
            if (hit(p, r)) a += max(area(p, r), 1e-6f)
        }
        return a
    }

    /**
     * The spot for the panel, seen from [eye]: [keep] while it stays clear; else the first clear one; else the one
     * with the least overlap ([Pick.clear] = false, logged by the scene).
     */
    fun choose(eye: Vec3, w: WindowPose, rows: Int, obstacles: List<Blob>, keep: Int? = null): Pick {
        val f = w.forward
        val ov = spots.map { overlap(eye, f, w, it, rows, obstacles) }
        val i = when {
            keep != null && keep in spots.indices && ov[keep] == 0f -> keep
            ov.any { it == 0f } -> ov.indexOfFirst { it == 0f }
            else -> ov.indices.minByOrNull { ov[it] }!!
        }
        return Pick(i, spots[i], center(w, spots[i]), ov[i] == 0f)
    }

    /** Angular size (degrees) at [distM]: 20 sp capitals and the 64 dp keys. */
    fun textDeg(distM: Float) = deg(2 * kotlin.math.atan(TEXT_SP * CAP * M_PER_DP / (2 * distM)))
    fun keyDeg(distM: Float) = deg(2 * kotlin.math.atan(KEY_DP * M_PER_DP / (2 * distM)))

    private fun rad(d: Float) = Math.toRadians(d.toDouble()).toFloat()
    private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()
}
