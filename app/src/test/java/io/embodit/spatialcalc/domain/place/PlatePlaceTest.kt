package io.embodit.spatialcalc.domain.place

import io.embodit.spatialcalc.domain.math.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v0.3.2 (QA #10 q10-2): the box's value plate. QA saw on the 64 m³ box (drawn as 3 m) a second line「按 3 米画」of
 * about 0.27° and, after Y +15° and Z −15°, the whole plate at about 42° up, out of view. Now, for 1 L / 1 m³ / 64 m³
 * and 30 × 20 × 10 cm boxes at every turn on the panel's 15° steps (and dragged), from standing and sitting eyes:
 * all 8 plate corners within ±39° of the default view, every plate line ≥ 0.5° tall, the plate not inside the box,
 * and the turn panel still clear of the box, the plate and the window.
 */
class PlatePlaceTest {
    private val eyes = listOf(1.6f, 1.2f) // standing / sitting (D54)
    private val forwards = listOf(Vec3(0f, 0f, -1f), Vec3(1f, 0f, 0f), Vec3(0.6f, 0f, 0.8f))

    private data class Case(val name: String, val dims: List<Double>, val lines: List<PlatePlace.Line>)

    private fun cube(m3: Double, label: String, clipped: Boolean): Case {
        val e = GaugeMath.cubeEdge(m3)
        val lines = mutableListOf(PlatePlace.Line(label, PlatePlace.MAIN_SP))
        if (clipped) lines += PlatePlace.Line("Drawn as a 3 m box", PlatePlace.SMALL_SP)
        return Case(label, GaugeMath.drawnDims(listOf(e, e, e)), lines)
    }

    private val cases = listOf(
        cube(0.001, "1 L", false),
        cube(1.0, "1 m³", false),
        cube(64.0, "64 m³", true),
        Case("30x20x10 cm", listOf(0.3, 0.2, 0.1), listOf(PlatePlace.Line("30 × 20 × 10 cm", PlatePlace.MAIN_SP), PlatePlace.Line("= 6 L", PlatePlace.SMALL_SP))),
        Case("4x4x4 m", listOf(3.0, 3.0, 3.0), listOf(PlatePlace.Line("4 × 4 × 4 m", PlatePlace.MAIN_SP), PlatePlace.Line("= 64 m³", PlatePlace.SMALL_SP), PlatePlace.Line("Drawn as a 3 m box", PlatePlace.SMALL_SP))),
    )

    private var panelClear = 0
    private var inGlass = 0
    private val panelLost = ArrayList<String>()

    private val steps = listOf(-90f, -45f, -30f, -15f, 0f, 15f, 30f, 45f, 90f)

    private fun check(name: String, eye: Vec3, f: Vec3, c: Vec3, q: io.embodit.spatialcalc.domain.math.Quat4, case: Case, w: WindowPose) {
        val (wDp, hDp) = PlatePlace.sizeDp(case.lines)
        val pl = PlatePlace.place(eye, f, c, q, case.dims, wDp, hDp, PlatePlace.SMALL_SP)
        val deg = PlatePlace.cornerDegrees(eye, f, pl)
        assertTrue("$name: corners behind the eye", deg.size == 8)
        for ((h, v) in deg) assertTrue("$name: corner (${"%.1f".format(h)}, ${"%.1f".format(v)}) outside ±39°", abs(h) <= PlatePlace.VIEW_DEG && abs(v) <= PlatePlace.VIEW_DEG)
        val d = eye.distanceTo(pl.center)
        val small = case.lines.minOf { it.sp }
        assertTrue("$name: smallest line ${PlatePlace.textDeg(small, pl.mPerDp, d)}° < 0.5°", PlatePlace.textDeg(small, pl.mPerDp, d) >= 0.5f)
        // not in the glass — unless a big turned box reaches the face itself (a 3 m box tipped on two axes can bring a
        // corner within 0.6 m of the eye); then the plate is at the closest readable distance, in view, over everything
        if (inside(pl.center, c, q, case.dims)) { assertTrue("$name: plate inside the box at ${"%.2f".format(d)} m", d <= PlatePlace.MIN_DIST_M + 0.01f); inGlass++ }
        // the plate never costs the turn panel its clear spot (wherever the box alone leaves one; a 3 m box turned on
        // two axes can cover all seven spots by itself — that is the box, not the plate)
        val bare = PanelPlace.choose(eye, w, 3, PanelPlace.boxBlobs(c, q, case.dims, null))
        val pick = PanelPlace.choose(eye, w, 3, PanelPlace.boxBlobs(c, q, case.dims, pl.center, pl.padM))
        if (bare.clear) { if (pick.clear) panelClear++ else panelLost += "$name (${pick.spot.id})" }
    }

    private fun inside(p: Vec3, c: Vec3, q: io.embodit.spatialcalc.domain.math.Quat4, dims: List<Double>): Boolean {
        val l = q.conjugate().rotate(p - c)
        return abs(l.x) < dims[0] / 2 && abs(l.y) < dims[1] / 2 && abs(l.z) < dims[2] / 2
    }

    @Test fun everyBoxAtEveryTurnKeepsItsPlateInViewAndReadable() {
        var n = 0
        for (h in eyes) for (f0 in forwards) {
            val f = f0.normalized()
            val eye = Vec3(0.3f, h, -0.2f)
            val w = LinePlace.window(eye, f)
            for (case in cases) {
                val c = GaugeMath.defaultBoxCenter(w, eye, case.dims)
                for (x in steps) for (y in steps) for (z in steps) {
                    val q = GaugeMath.rotation(LinePlace.baseYaw(w), y, x, z)
                    check("${case.name} eye=$h f=$f0 x=$x y=$y z=$z", eye, f, c, q, case, w)
                    n++
                }
            }
        }
        assertTrue(n > 10000)
        println("PLATE cases=$n panelClear=$panelClear inGlassAtMinDist=$inGlass")
        assertTrue("turn panel lost its spot to the plate in ${panelLost.size} cases, e.g. ${panelLost.take(5)}", panelLost.isEmpty())
    }

    @Test fun qa10TheSixtyFourCubicMetreBoxTurnedYPlusZMinus() {
        // QA #10: 64 m³, Y +15°, Z −15° → plate rose to 41.9° (out of view); its「按 3 米画」was 0.27°
        for (h in eyes) {
            val eye = Vec3(0f, h, 0f); val f = Vec3(0f, 0f, -1f)
            val w = LinePlace.window(eye, f)
            val case = cases[2]
            val c = GaugeMath.defaultBoxCenter(w, eye, case.dims)
            val q = GaugeMath.rotation(LinePlace.baseYaw(w), 15f, 0f, -15f)
            check("qa10 eye=$h", eye, f, c, q, case, w)
            // the old spot (over the top, 30 mm digits) really was out of view: the test would have caught it
            val old = PanelPlace.boxPlate(c, q, case.dims)
            val oldDeg = Math.toDegrees(kotlin.math.atan(((old.y - eye.y) / (eye.z - old.z)).toDouble()))
            assertTrue("old plate at $oldDeg°", oldDeg > 39.0 || h < 1.5f)
        }
    }

    @Test fun draggedBoxesToo() {
        val eye = Vec3(0f, 1.6f, 0f); val f = Vec3(0f, 0f, -1f)
        val w = LinePlace.window(eye, f)
        for (case in cases) for (drag in listOf(Vec3(0f, 0.5f, 0f), Vec3(-0.4f, 0.3f, 0.2f), Vec3(0.6f, -0.3f, -0.5f), Vec3(0f, 1f, -1f))) {
            val c = GaugeMath.defaultBoxCenter(w, eye, case.dims) + drag
            for (y in steps) {
                val q = GaugeMath.rotation(LinePlace.baseYaw(w), y, 0f, 0f)
                val (wDp, hDp) = PlatePlace.sizeDp(case.lines)
                val pl = PlatePlace.place(eye, f, c, q, case.dims, wDp, hDp, PlatePlace.SMALL_SP)
                val deg = PlatePlace.cornerDegrees(eye, f, pl)
                assertTrue("${case.name} drag=$drag y=$y", deg.size == 8 && deg.all { abs(it.first) <= 39f && abs(it.second) <= 39f })
                assertTrue(PlatePlace.textDeg(PlatePlace.SMALL_SP, pl.mPerDp, eye.distanceTo(pl.center)) >= 0.5f)
            }
        }
    }

    @Test fun smallBoxesKeepTheDesignSize() {
        // a 1 L cube about 1 m away keeps the 30 mm digits (no growth below the floor distance)
        val eye = Vec3(0f, 1.6f, 0f); val f = Vec3(0f, 0f, -1f)
        val w = LinePlace.window(eye, f)
        val case = cases[0]
        val c = GaugeMath.defaultBoxCenter(w, eye, case.dims)
        val (wDp, hDp) = PlatePlace.sizeDp(case.lines)
        val pl = PlatePlace.place(eye, f, c, GaugeMath.rotation(LinePlace.baseYaw(w), 0f, 0f, 0f), case.dims, wDp, hDp, PlatePlace.SMALL_SP)
        assertTrue(abs(pl.mPerDp - PlatePlace.BASE_M_PER_DP) < 1e-6f)
        assertTrue(!pl.clamped)
    }
}
