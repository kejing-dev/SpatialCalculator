package io.embodit.spatialcalc.domain.place

import io.embodit.spatialcalc.domain.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LinePlaceTest {
    private fun fwd(yawDeg: Double) = Math.toRadians(yawDeg).let { Vec3(-sin(it).toFloat(), 0f, -cos(it).toFloat()) }

    /** design acceptance: the default line stays out of the head → window sight cone (8 window positions), sitting and standing (D54). */
    @Test fun defaultLineIsOutOfTheSightCone() {
        for (eyeY in listOf(1.2f, 1.6f)) for (yaw in listOf(0.0, 45.0, 90.0, 135.0, 180.0, -45.0, -90.0, -135.0)) {
            val eye = Vec3(0.3f, eyeY, -0.2f)
            val w = LinePlace.window(eye, fwd(yaw))
            val start = LinePlace.defaultStart(w)
            val dir = LinePlace.direction(LinePlace.baseYaw(w), false)
            for (len in listOf(0.01f, 0.5f, 1.2f, 10f)) assertFalse("eye $eyeY yaw $yaw len $len", LinePlace.lineInSightCone(eye, w, start, dir, len))
            // it runs along the window's right, starts 8 cm off its edge, level with the result row
            assertEquals(1f, dir.dot(w.right), 1e-4f)
            assertEquals(w.widthM / 2 + LinePlace.GAP_M, (start - w.center).dot(w.right), 1e-4f)
            assertTrue((start - w.center).y > 0.1f)
            // D54: the window follows the eye height it was opened at; the line is within a comfortable view
            assertEquals(eyeY - LinePlace.WINDOW_DROP_M, w.center.y, 1e-4f)
            val down = LinePlace.downDeg(eye, start)
            // level with the result row near the top of an eye-level window: ≤ 12° up (v0.3.0: the window grew 52 dp for
            // the volume unit row, so the result row is 26 dp higher), never behind or under it
            assertTrue("down $down", down in -12f..35f)
            assertTrue(eye.distanceTo(start) >= 0.6f) // D14 clearance
        }
    }

    @Test fun lineDraggedBetweenEyeAndWindowIsInTheCone() {
        val eye = Vec3(0f, 1.6f, 0f)
        val w = LinePlace.window(eye, fwd(0.0))
        assertTrue(LinePlace.inSightCone(eye, w, Vec3(0f, 1.55f, -0.5f)))
        assertTrue(LinePlace.lineInSightCone(eye, w, Vec3(-0.1f, 1.55f, -0.5f), Vec3(1f, 0f, 0f), 0.2f))
        assertFalse(LinePlace.inSightCone(eye, w, Vec3(0f, 1.55f, -2.5f))) // behind the window
    }

    @Test fun yawFacesTheUser() {
        val w = LinePlace.window(Vec3(0f, 1.6f, 0f), fwd(90.0)) // looking toward -X
        val r = LinePlace.direction(LinePlace.baseYaw(w), false)
        assertEquals(0f, r.x, 1e-4f); assertEquals(-1f, r.z, 1e-4f) // to the user's right = -Z
        // a plate's +Z turns toward the eye
        val plate = Vec3(1f, 1.5f, 1f)
        val y = LinePlace.yawFacing(plate, Vec3.ZERO)
        val front = Vec3(sin(y), 0f, cos(y))
        assertTrue(front.dot(Vec3(-1f, 0f, -1f).normalized()) > 0.999f)
        assertEquals(Vec3.UP, LinePlace.direction(1f, true))
    }

    /** The value plate rides over the part of a 10 m line that is in view (design maxReach). */
    @Test fun visibleMiddle() {
        val eye = Vec3(0f, 1.6f, 0f)
        val start = Vec3(0.24f, 1.75f, -0.82f)
        val mid = LinePlace.visibleMid(eye, fwd(0.0), start, Vec3(1f, 0f, 0f), 10f)
        assertTrue("mid $mid", mid in 0.1f..0.3f) // ±35° at 0.82 m reaches x = 0.57 m: the plate sits over 0.24…0.57 m
        assertTrue(mid < 1f)
        assertEquals(0.4f, LinePlace.visibleMid(eye, fwd(0.0), Vec3(0.25f, 1.6f, -1.75f), Vec3(1f, 0f, 0f), 0.8f), 0.03f)
        // a 1.2 m line standing up from 15 cm above the eye: the plate stays in the lower, visible part
        val up = LinePlace.visibleMid(eye, fwd(0.0), Vec3(0.24f, 1.75f, -0.82f), Vec3.UP, 1.2f)
        assertTrue("up $up", up < 0.4f)
    }
}
