package io.embodit.spatialcalc.domain.place

import io.embodit.spatialcalc.domain.math.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the main window is believed to be (SDK 6.1 has no query for a Planar window's world pose, needsCheck N5):
 * the system opens it [LinePlace.DISTANCE_M] ahead of the head at the moment of the level-gated open, facing the user.
 * [forward] = level direction from the user toward the window.
 */
data class WindowPose(val center: Vec3, val forward: Vec3, val widthM: Float, val heightM: Float) {
    val right: Vec3 get() = Vec3(-forward.z, 0f, forward.x)
}

/**
 * The 1:1 length line's default spot and the checks around it (design spatialLayout.lengthLine, D14 / D20 / D54).
 * Pure: unit tests run it for sitting and standing eye heights and eight window positions.
 */
object LinePlace {
    /**
     * The system opens a Full Space Planar window about 0.82 m ahead (feedback/pico-cli-feedback.md「Planar 窗口实际画出来的大小」:
     * region-snap depth test; spatial-calc v0.2.0 emulator-5564: a 400 dp window drawn 308 px wide where the line's own
     * ticks give 435 px per metre at 1.75 m → 0.32 m wide at ≈ 0.8 m, with the converter's 0.0008 m/dp). Not 1.75 m.
     */
    const val DISTANCE_M = 0.82f
    /** Window centre below the eye at the system's default open (emulator 2026-11-14: the window centre is at eye level). */
    const val WINDOW_DROP_M = 0.0f
    const val WIDTH_DP = 400f
    const val HEIGHT_DP = 664f
    /** Result row centre above the window centre: 664/2 − (24 + 48 + 32 + 8 + 32) = 188 dp. */
    const val RESULT_ROW_DP = 188f
    /** The system controls under a window (dot, ×, handle): ≈ 60 dp (native-window-move.md §2). */
    const val SYSTEM_BAR_DP = 60f
    const val GAP_M = 0.08f
    /** Design estimate (needsCheck N3) until the window's own converter reports its dp → m. */
    const val DEFAULT_M_PER_DP = 0.0008f

    fun window(head: Vec3, headForward: Vec3, mPerDp: Float = DEFAULT_M_PER_DP): WindowPose {
        val f = Vec3(headForward.x, 0f, headForward.z).normalized()
        val c = head + f * DISTANCE_M - Vec3.UP * WINDOW_DROP_M
        return WindowPose(c, f, WIDTH_DP * mPerDp, HEIGHT_DP * mPerDp)
    }

    /** Left end of the line: 8 cm right of the window edge, level with the result row, in the window's plane. */
    fun defaultStart(w: WindowPose, mPerDp: Float = w.widthM / WIDTH_DP): Vec3 =
        w.center + w.right * (w.widthM / 2f + GAP_M) + Vec3.UP * (RESULT_ROW_DP * mPerDp)

    /** Yaw (radians about +Y) that turns local +X onto the window's right: the line faces the user. */
    fun baseYaw(w: WindowPose): Float { val r = w.right; return atan2(-r.z, r.x) }

    /** World direction the line runs in. */
    fun direction(yaw: Float, standing: Boolean): Vec3 = if (standing) Vec3.UP else Vec3(cos(yaw), 0f, -sin(yaw))

    /** A yaw that faces [target] from [from] (only about +Y; plates never pitch, D9). */
    fun yawFacing(from: Vec3, target: Vec3): Float = atan2((target.x - from.x), (target.z - from.z))

    /**
     * Inside the head → window sight cone (window rect + the system bar under it, D20)? The point is projected from
     * the eye onto the window plane; it counts when it lies between eye and window and lands inside the rect.
     */
    fun inSightCone(eye: Vec3, w: WindowPose, p: Vec3, marginM: Float = 0.02f): Boolean {
        val f = w.forward
        val dWin = (w.center - eye).dot(f)
        val dP = (p - eye).dot(f)
        if (dP <= 0.05f || dP >= dWin) return false
        val hit = eye + (p - eye) * (dWin / dP)
        val rel = hit - w.center
        val x = rel.dot(w.right)
        val y = rel.y
        val bar = SYSTEM_BAR_DP * (w.widthM / WIDTH_DP)
        return x in (-w.widthM / 2 - marginM)..(w.widthM / 2 + marginM) && y in (-w.heightM / 2 - bar - marginM)..(w.heightM / 2 + marginM)
    }

    /** True when any of the line's sample points is inside the sight cone. */
    fun lineInSightCone(eye: Vec3, w: WindowPose, start: Vec3, dir: Vec3, len: Float): Boolean {
        val n = max(2, (len / 0.05f).toInt())
        return (0..n).any { inSightCone(eye, w, start + dir * (len * it / n)) }
    }

    /**
     * Distance along the line of the middle of the part within [halfFovDeg] of where the head looks (yaw and pitch, so
     * a standing line works too): the value plate rides there, so a 10 m line still shows its number in view (design
     * maxReach). Nothing in view: the part nearest to the view direction.
     */
    fun visibleMid(eye: Vec3, headForward: Vec3, start: Vec3, dir: Vec3, len: Float, halfFovDeg: Float = 35f): Float {
        val f = headForward.normalized()
        val c = cos(Math.toRadians(halfFovDeg.toDouble())).toFloat()
        var lo = Float.NaN; var hi = Float.NaN
        var best = 0f; var bestDot = -2f
        val n = max(8, (len / 0.05f).toInt())
        for (i in 0..n) {
            val t = len * i / n
            val to = start + dir * t - eye
            val d = if (to.length < 1e-4f) 1f else to.normalized().dot(f)
            if (d > bestDot) { bestDot = d; best = t }
            if (d >= c) { if (lo.isNaN()) lo = t; hi = t }
        }
        if (lo.isNaN()) return best
        return (lo + hi) / 2f
    }

    /** Down angle (degrees) from the eye to [p]: + below the horizon. */
    fun downDeg(eye: Vec3, p: Vec3): Float {
        val d = p - eye
        return Math.toDegrees(atan2((-d.y).toDouble(), Vec3(d.x, 0f, d.z).length.toDouble())).toFloat()
    }
}
