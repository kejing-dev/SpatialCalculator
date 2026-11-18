package io.embodit.spatialcalc.domain.place

import io.embodit.spatialcalc.domain.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v0.3.1 (QA #9 q9-1 / q9-2 / q9-3): the turn panel stands at a fixed readable spot in front of the user, never on the
 * gauge. From the default head the panel and the gauge (line at any angle, 1 L / 1 m³ / 64 m³ boxes) never overlap,
 * nor the panel and the window; all 8 panel corners are within ±39°; text ≥ 0.5°, keys ≥ 2°.
 */
class PanelPlaceTest {
    private val eyes = listOf(1.6f, 1.2f) // standing / sitting (D54)
    private val forwards = listOf(Vec3(0f, 0f, -1f), Vec3(1f, 0f, 0f), Vec3(0.6f, 0f, 0.8f))

    private fun setups() = eyes.flatMap { h -> forwards.map { f -> Vec3(0.3f, h, -0.2f) to f.normalized() } }

    private fun lineCase(eye: Vec3, f: Vec3, len: Float, yaw: Float, roll: Float, drag: Vec3 = Vec3(0f, 0f, 0f)): Pair<WindowPose, List<PanelPlace.Blob>> {
        val w = LinePlace.window(eye, f)
        val start = LinePlace.defaultStart(w) + drag
        val q = GaugeMath.rotation(LinePlace.baseYaw(w), yaw, 0f, roll)
        val plate = PanelPlace.linePlate(eye, f, start, q, len, roll >= 90f)
        return w to PanelPlace.lineBlobs(start, q.rotate(Vec3(1f, 0f, 0f)), len, plate)
    }

    private fun boxCase(eye: Vec3, f: Vec3, m3: Double, yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f): Pair<WindowPose, List<PanelPlace.Blob>> {
        val w = LinePlace.window(eye, f)
        val e = GaugeMath.cubeEdge(m3)
        val dims = GaugeMath.drawnDims(listOf(e, e, e))
        val c = GaugeMath.defaultBoxCenter(w, eye, dims)
        val q = GaugeMath.rotation(LinePlace.baseYaw(w), yaw, pitch, roll)
        return w to PanelPlace.boxBlobs(c, q, dims, PanelPlace.boxPlate(c, q, dims))
    }

    @Test fun lineAtAnyAngleNeverUnderThePanel() {
        var n = 0
        for ((eye, f) in setups()) for (len in listOf(0.1f, 0.5f, 1.2f, 3f, 10f)) {
            var yaw = -180f
            while (yaw <= 180f) {
                var roll = 0f
                while (roll <= 90f) {
                    val (w, blobs) = lineCase(eye, f, len, yaw, roll)
                    val p = PanelPlace.choose(eye, w, 2, blobs)
                    assertTrue("line len=$len yaw=$yaw roll=$roll f=$f eye=${eye.y}: ${p.spot}", p.clear)
                    assertEquals(0f, PanelPlace.overlap(eye, w.forward, w, p.spot, 2, blobs), 0f)
                    n++
                    roll += 15f
                }
                yaw += 15f
            }
        }
        assertTrue(n > 1000)
    }

    @Test fun lineTurnedAndDraggedLikeQa9StillClear() {
        // QA #9 S05a-turned: +30° then dragged; and drags straight onto the default panel spot
        for ((eye, f) in setups()) {
            val w0 = LinePlace.window(eye, f)
            val onLeft = PanelPlace.center(w0, PanelPlace.spots[0]) - LinePlace.defaultStart(w0)
            for (drag in listOf(Vec3(0f, 0f, 0f), Vec3(-0.2f, -0.2f, 0.05f), onLeft, onLeft + Vec3(-0.1f, 0f, 0f), Vec3(0f, -0.45f, 0f))) {
                for (yaw in listOf(30f, -30f, 90f, 180f)) {
                    val (w, blobs) = lineCase(eye, f, 1.2f, yaw, 0f, drag)
                    val p = PanelPlace.choose(eye, w, 2, blobs)
                    assertTrue("drag=$drag yaw=$yaw: ${p.spot}", p.clear)
                }
            }
        }
    }

    @Test fun keepsItsSpotWhileClear() {
        val eye = Vec3(0f, 1.6f, 0f); val f = Vec3(0f, 0f, -1f)
        val (w, blobs) = lineCase(eye, f, 1.2f, 0f, 0f)
        assertEquals(0, PanelPlace.choose(eye, w, 2, blobs).index)
        assertEquals(3, PanelPlace.choose(eye, w, 2, blobs, keep = 3).index)
    }

    @Test fun boxesOfEverySizeNeverUnderThePanel() {
        for ((eye, f) in setups()) for (m3 in listOf(0.000001, 0.001, 0.0015, 0.006, 0.1, 1.0, 8.0, 64.0)) {
            val (w, blobs) = boxCase(eye, f, m3)
            val p = PanelPlace.choose(eye, w, 3, blobs)
            assertTrue("box $m3 m³ f=$f eye=${eye.y}: ${p.spot}", p.clear)
            // the default spot: left of the window, far from every box
            assertEquals("box $m3", "left", p.spot.id)
        }
        // turned boxes up to 1 m³ (all three axes, every 45°) also leave a clear spot
        for ((eye, f) in setups()) for (m3 in listOf(0.001, 0.1, 1.0)) for (a in listOf(-45f, 45f, 90f)) {
            for ((y, p, r) in listOf(Triple(a, 0f, 0f), Triple(0f, a, 0f), Triple(0f, 0f, a), Triple(a, a, a))) {
                val (w, blobs) = boxCase(eye, f, m3, y, p, r)
                assertTrue("box $m3 turned ($y,$p,$r)", PanelPlace.choose(eye, w, 3, blobs).clear)
            }
        }
    }

    @Test fun everyCornerOfEverySpotInTheDefaultView() {
        for ((eye, f) in setups()) {
            val w = LinePlace.window(eye, f)
            for (rows in listOf(2, 3)) for (s in PanelPlace.spots) {
                val c = PanelPlace.center(w, s)
                val deg = PanelPlace.cornerDegrees(eye, f, PanelPlace.corners(c, eye, PanelPlace.widthM(), PanelPlace.heightM(rows)))
                assertEquals(8, deg.size)
                deg.forEach { (x, y) -> assertTrue("${s.id} rows=$rows corner ($x, $y)", abs(x) <= PanelPlace.VIEW_DEG && abs(y) <= PanelPlace.VIEW_DEG) }
                // below the line of sight (top edge under the eye, D10) and not in the window
                if (PanelPlace.spots.indexOf(s) < PanelPlace.PRIMARY) assertTrue(deg.all { it.second < 0f })
                assertEquals(0f, PanelPlace.overlap(eye, f, w, s, rows, emptyList()), 0f)
                // readable distance 0.8–1.2 m; text ≥ 0.5°, keys ≥ 2° at that distance
                val d = eye.distanceTo(c)
                assertTrue("dist $d", d in 0.8f..1.2f)
                assertTrue("text ${PanelPlace.textDeg(d)}", PanelPlace.textDeg(d) >= 0.5f)
                assertTrue("key ${PanelPlace.keyDeg(d)}", PanelPlace.keyDeg(d) >= 2f)
            }
        }
    }

    @Test fun rowsAre16dpApartAndKeys64dp() {
        assertEquals(16f, PanelPlace.GAP_DP, 0f)
        assertEquals(64f, PanelPlace.KEY_DP, 0f)
        assertEquals(24f + 3 * 64f + 2 * 16f, PanelPlace.LINE_H_DP, 0f)
        assertEquals(24f + 4 * 64f + 3 * 16f, PanelPlace.BOX_H_DP, 0f)
        // 64 dp keys ≥ 4 cm (D17)
        assertTrue(PanelPlace.KEY_DP * PanelPlace.M_PER_DP >= 0.04f)
    }
}
