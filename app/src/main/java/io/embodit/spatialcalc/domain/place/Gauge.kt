package io.embodit.spatialcalc.domain.place

import io.embodit.spatialcalc.domain.math.Quat4
import io.embodit.spatialcalc.domain.math.Vec3
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.roundToInt

/** What [摆出来] puts in the air: the 1:1 length line, or a 1:1 volume (a cube, or an a × b × c box). */
enum class GaugeKind { LINE, BOX }

/** The rotation axes of the UI rows / controller (profile D10 补 2026-11-16: line two axes, volume three). */
enum class Axis { X, Y, Z }

/**
 * Pure maths of the generated gauges (profile D10 补「生成的量具能拖能转，体积三轴」, D53 drag). Angles are degrees:
 * yaw about the vertical (Y), pitch about the gauge's own X, roll about its own Z. Orientation =
 * Ry(baseYaw + yaw) · Rx(pitch) · Rz(roll); baseYaw faces the user (the line runs along the user's right, the box's
 * front face looks at the user). The line uses yaw (vertical axis) and roll (horizontal axis: lay / slant / stand).
 */
object GaugeMath {
    const val STEP_DEG = 15f
    /** [回正] / two-hand release snap: within this of 0 / 45 / 90 / … it lands exactly there. */
    const val SNAP_DEG = 10f
    /** Box edges: drawn between these (a 1 mL cube is 1 cm; above 3 m the box is drawn at 3 m and labelled). */
    const val MIN_EDGE_M = 0.005
    const val MAX_EDGE_M = 3.0
    /** The near face of a box stays at least this far ahead of the head (D14: big things ≥ 0.8 m). */
    const val CLEAR_M = 0.8f

    fun rotation(baseYawRad: Float, yawDeg: Float, pitchDeg: Float, rollDeg: Float): Quat4 =
        Quat4.axisAngle(Vec3.UP, baseYawRad + rad(yawDeg)) *
            Quat4.axisAngle(Vec3(1f, 0f, 0f), rad(pitchDeg)) *
            Quat4.axisAngle(Vec3(0f, 0f, 1f), rad(rollDeg))

    /** The world direction the line runs in (its local +X). */
    fun lineDir(baseYawRad: Float, yawDeg: Float, rollDeg: Float): Vec3 = rotation(baseYawRad, yawDeg, 0f, rollDeg).rotate(Vec3(1f, 0f, 0f))

    /** One notch: ±15°, landing on a multiple of 15° (so 0 / 45 / 90 are always reachable), wrapped to (−180, 180]. */
    fun step(deg: Float, dir: Int): Float = wrap(((deg + STEP_DEG * dir.coerceIn(-1, 1)) / STEP_DEG).roundToInt() * STEP_DEG)

    /** Line tilt about its horizontal axis: lying (0°) → slanted → standing (90°); its right end only goes up. */
    fun tiltStep(deg: Float, dir: Int): Float = step(deg, dir).coerceIn(0f, 90f)

    /** After a free (two-hand) turn: within [SNAP_DEG] of a multiple of 45° → exactly there; otherwise to the whole degree. */
    fun snap(deg: Float): Float {
        val w = wrap(deg)
        val n45 = (w / 45f).roundToInt() * 45f
        return wrap(if (abs(w - n45) <= SNAP_DEG) n45 else w.roundToInt().toFloat())
    }

    fun wrap(deg: Float): Float {
        var d = deg % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return if (d == -0f) 0f else d
    }

    /** Smallest angle (degrees) between two rotations. */
    fun angleBetween(a: Quat4, b: Quat4): Float {
        val d = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w).coerceAtMost(1f)
        return Math.toDegrees(2.0 * kotlin.math.acos(d.toDouble())).toFloat()
    }

    /** Cube edge for a volume (m³ → m): 1 L → 0.1 m. */
    fun cubeEdge(m3: Double): Double = cbrt(m3)

    /** Drawn box edges: each clipped to [MAX_EDGE_M]. */
    fun drawnDims(dims: List<Double>): List<Double> = dims.map { it.coerceAtMost(MAX_EDGE_M) }

    /** Half the box's height in world space after a rotation (for the label above it). */
    fun worldHalfHeight(q: Quat4, dims: List<Double>): Float =
        listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f)).withIndex().sumOf { (i, a) -> abs(q.rotate(a).y) * dims[i] / 2.0 }.toFloat()

    /** Half the box's width across the user's view (for the control panel beside it). */
    fun worldHalfAcross(q: Quat4, dims: List<Double>, across: Vec3): Float =
        listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f)).withIndex().sumOf { (i, a) -> abs(q.rotate(a).dot(across)) * dims[i] / 2.0 }.toFloat()

    /**
     * Default box centre beside the window (same side and height as the line's left end), pushed back so its near face
     * is at least [CLEAR_M] ahead of the head (D14).
     */
    fun defaultBoxCenter(w: WindowPose, head: Vec3, dims: List<Double>, mPerDp: Float = w.widthM / LinePlace.WIDTH_DP): Vec3 {
        val start = LinePlace.defaultStart(w, mPerDp)
        val dx = dims[0].toFloat(); val dz = dims[2].toFloat()
        var c = start + w.right * (dx / 2f)
        val ahead = (c - head).dot(w.forward)
        val need = CLEAR_M + dz / 2f
        if (ahead < need) c += w.forward * (need - ahead)
        return c
    }

    private fun rad(d: Float) = Math.toRadians(d.toDouble()).toFloat()

    fun maxAbs(vararg a: Float): Float = a.fold(0f) { m, v -> max(m, abs(v)) }
}
