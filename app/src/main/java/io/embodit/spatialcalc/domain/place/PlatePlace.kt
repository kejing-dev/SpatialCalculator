package io.embodit.spatialcalc.domain.place

import io.embodit.spatialcalc.domain.math.Quat4
import io.embodit.spatialcalc.domain.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Where the box's value plate goes and how big it is drawn (v0.3.2, QA #10 q10-2; profile D13 3D 字 ≥ 0.5°, default
 * view ±39°). It still prefers the spot over the top of the box, facing the user. But:
 *  - its smallest line (「按 3 米画」/「= 6 L」) is drawn at least [MIN_TEXT_DEG] tall wherever the plate is (the plate
 *    grows with distance; a 64 m³ box, drawn as 3 m, sits about 3.3 m away);
 *  - its 8 corners stay within ±[AIM_DEG] of the default view (the window's level forward), however big or turned the
 *    box is: the plate slides back into view along the view sphere, and when that ray passes through the box it comes
 *    forward to just in front of the box's near face, so it is never buried in the glass.
 * Pure: unit-tested (PlatePlaceTest).
 */
object PlatePlace {
    /** QA's limit and the one we aim for (2° margin for smoothing and the text-width estimate). */
    const val VIEW_DEG = 39f
    const val AIM_DEG = 37f
    /** The smallest plate line, capital height (D13 floor 0.5°, with margin). */
    const val MIN_TEXT_DEG = 0.6f
    const val CAP = 0.72f
    /** Main digits 40 sp at 30 mm capitals (design geometry) → 1.04 mm per dp at the base size. */
    const val MAIN_SP = 40f
    const val MAIN_CAP_M = 0.030f
    val BASE_M_PER_DP: Float get() = MAIN_CAP_M / (MAIN_SP * CAP)
    /** The second / third line (「= 6 L」,「按 3 米画」): 28 sp (was 24 / 20 sp, 0.27° at 3.3 m). */
    const val SMALL_SP = 28f
    const val PAD_H_DP = 24f
    const val PAD_V_DP = 8f
    /** Gap over the box top, and the closest the plate comes to the eye. */
    const val GAP_M = 0.03f
    const val MIN_DIST_M = 0.6f
    const val FRONT_GAP_M = 0.05f

    /** A plate line: its text and font size (sp). */
    data class Line(val text: String, val sp: Float)

    /**
     * Conservative plate size in dp for its lines (BasicText, one line each): CJK one em, everything else 0.6 em,
     * line height 1.3 em, plus the capsule padding. Real text is narrower, so the corners we test are outside the real ones.
     */
    fun sizeDp(lines: List<Line>): Pair<Float, Float> {
        val w = lines.maxOf { l -> l.text.sumOf { c -> if (c.code >= 0x2E80) 1.0 else 0.6 }.toFloat() * l.sp }
        val h = lines.sumOf { it.sp.toDouble() * 1.3 }.toFloat()
        return (w + 2 * PAD_H_DP) to (h + 2 * PAD_V_DP)
    }

    /** Metres per dp so the smallest line ([minSp]) is at least [MIN_TEXT_DEG] tall at [distM] (never below the base size). */
    fun metresPerDp(distM: Float, minSp: Float): Float = max(BASE_M_PER_DP, tan(rad(MIN_TEXT_DEG)) * distM / (minSp * CAP))

    /** The capital height of a [sp] line in degrees at [distM] with [mPerDp]. */
    fun textDeg(sp: Float, mPerDp: Float, distM: Float): Float = deg(2f * kotlin.math.atan(sp * CAP * mPerDp / (2f * distM)))

    data class Placed(val center: Vec3, val mPerDp: Float, val wM: Float, val hM: Float, val clamped: Boolean) {
        /** Radius around the centre the turn panel keeps clear of (PanelPlace blob). */
        val padM: Float get() = max(PanelPlace.PLATE_PAD_M, kotlin.math.sqrt(wM * wM + hM * hM) / 2f + 0.02f)
    }

    /** The level view direction for the ±39° check: the window's forward (same frame as the turn panel and QA). */
    fun level(f: Vec3): Vec3 = Vec3(f.x, 0f, f.z).normalized()

    /**
     * The plate for a box at [c] turned [q] with drawn edges [dims], seen from [eye] whose default view is [viewF].
     * [wDp] × [hDp] is the plate in dp ([sizeDp]); [minSp] its smallest font.
     */
    fun place(eye: Vec3, viewF: Vec3, c: Vec3, q: Quat4, dims: List<Double>, wDp: Float, hDp: Float, minSp: Float): Placed {
        val f = level(viewF)
        val r = Vec3(-f.z, 0f, f.x)
        val top = GaugeMath.worldHalfHeight(q, dims)
        // 1. over the top of the box, size from the distance (a few rounds: the size moves the centre up a little)
        var mpd = BASE_M_PER_DP
        var p = c + Vec3.UP * (top + GAP_M + hDp * mpd / 2f)
        repeat(3) {
            mpd = metresPerDp(eye.distanceTo(p), minSp)
            p = c + Vec3.UP * (top + GAP_M + hDp * mpd / 2f)
        }
        var clamped = false
        // 2. into the default view: corners within ±AIM_DEG on the screen projection
        val lim = tan(rad(AIM_DEG))
        for (round in 0 until 6) {
            val wM = wDp * mpd; val hM = hDp * mpd
            val pts = PanelPlace.corners(p, eye, wM, hM)
            val proj = pts.mapNotNull { PanelPlace.project(eye, f, it) }
            if (proj.size == 8 && proj.all { abs(it.first) <= lim && abs(it.second) <= lim }) break
            clamped = true
            val d = p - eye
            val z = d.dot(f)
            val dist = max(MIN_DIST_M, d.length)
            if (z < 0.05f || proj.size < 8) { p = eye + f * dist; mpd = metresPerDp(dist, minSp); continue }
            val x = d.dot(r) / z; val y = d.y / z
            val tx = proj.maxOf { abs(it.first - x) }; val ty = proj.maxOf { abs(it.second - y) }
            val nx = x.coerceIn(-lim + tx, max(-lim + tx, lim - tx))
            val ny = y.coerceIn(-lim + ty, max(-lim + ty, lim - ty))
            val dir = (f + r * nx + Vec3.UP * ny).normalized()
            var t = dist
            // not inside the glass: when the ray meets the box first, stop just in front of its near face
            rayBox(eye, dir, c, q, dims)?.let { hit -> if (hit < t) t = max(MIN_DIST_M, hit - FRONT_GAP_M) }
            p = eye + dir * t
            mpd = metresPerDp(t, minSp)
        }
        return Placed(p, mpd, wDp * mpd, hDp * mpd, clamped)
    }

    /** The 8 corners' projected angles (degrees) for QA / tests / the debug dump. */
    fun cornerDegrees(eye: Vec3, viewF: Vec3, pl: Placed): List<Pair<Float, Float>> =
        PanelPlace.cornerDegrees(eye, level(viewF), PanelPlace.corners(pl.center, eye, pl.wM, pl.hM))

    /** Entry distance of the ray [o] + t·[dir] into the box (oriented, slab method), or null when it misses / starts inside. */
    fun rayBox(o: Vec3, dir: Vec3, c: Vec3, q: Quat4, dims: List<Double>): Float? {
        val inv = q.conjugate()
        val lo = inv.rotate(o - c)
        val ld = inv.rotate(dir)
        var t0 = -Float.MAX_VALUE; var t1 = Float.MAX_VALUE
        val h = floatArrayOf(dims[0].toFloat() / 2f, dims[1].toFloat() / 2f, dims[2].toFloat() / 2f)
        val oo = floatArrayOf(lo.x, lo.y, lo.z); val dd = floatArrayOf(ld.x, ld.y, ld.z)
        for (i in 0 until 3) {
            if (abs(dd[i]) < 1e-6f) { if (oo[i] < -h[i] || oo[i] > h[i]) return null; continue }
            var a = (-h[i] - oo[i]) / dd[i]; var b = (h[i] - oo[i]) / dd[i]
            if (a > b) { val s = a; a = b; b = s }
            t0 = max(t0, a); t1 = min(t1, b)
            if (t0 > t1) return null
        }
        return if (t0 > 0f) t0 else null
    }

    private fun rad(d: Float) = Math.toRadians(d.toDouble()).toFloat()
    private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()
}
