package com.kejing.spatialcalc.domain.place

import com.kejing.spatialcalc.domain.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * acceptance.unitTests「结果体落点链」and panelGeometryCheck: 9 body sizes × 8 bearings × object distance 0.5 / 0.8 / 2 m.
 * Every automatic drop point is out of the head → window sight cone, clear of the head (0.6 / 0.8 m), outside the
 * measured object — or the chain hands over to the user (S09). Never inside the cone.
 */
class PlacementTest {
    private val head = Vec3(0f, 1.6f, 0f)
    private val sides = listOf(0.1f, 0.6f, 2.5f)
    private val sizes = sides.flatMap { a -> listOf(Vec3(a, a, a), Vec3(a, 0.3f, 0.3f), Vec3(0.3f, a, a)) }.distinct().take(9)

    private fun bearing(i: Int) = i * 45.0

    @Test fun chainNeverDropsIntoTheCone() {
        var asked = 0; var ok = 0
        for (size in sizes) for (b in 0 until 8) for (dist in listOf(0.5f, 0.8f, 2f)) {
            val a = Math.toRadians(bearing(b)).toFloat()
            val dir = Vec3(sin(a), 0f, -cos(a))
            val objC = head + dir * dist - Vec3(0f, 0.8f, 0f)
            val obj = Aabb(objC - Vec3(0.15f, 0f, 0.15f), objC + Vec3(0.15f, 0.3f, 0.15f))
            val window = head + Vec3(0f, 0f, -1.75f) // system default: 1.75 m ahead
            val r = PlaceBody.place(head, window, obj, objC.y, size)
            val box = bodyBox(r.pose, size)
            if (r.outcome == PlaceOutcome.OK) {
                ok++
                assertFalse("in cone size=$size b=$b d=$dist ${r.log}", SightCone(head, window).touches(box))
                val clear = if (size.x >= 1f || size.y >= 1f || size.z >= 1f) 0.8f else 0.6f
                assertTrue("too close size=$size b=$b d=$dist ${box.distanceTo(head)}", box.distanceTo(head) >= clear - 1e-3f)
                assertFalse("in object size=$size b=$b d=$dist", box.intersects(obj, -0.005f))
                assertTrue(r.rounds <= 2)
            } else asked++
        }
        assertTrue("most drops are automatic: ok=$ok asked=$asked", ok > asked)
    }

    @Test fun smallBodyLandsRightOfTheObject() {
        val objC = Vec3(0f, 0.75f, -1.5f)
        val obj = Aabb(objC - Vec3(0.3f, 0f, 0.15f), objC + Vec3(0.3f, 0.3f, 0.15f))
        val r = PlaceBody.place(head, Vec3(-0.74f, 1.6f, -1.59f), obj, 0.75f, Vec3(0.6f, 0.3f, 0.3f))
        assertEquals(PlaceOutcome.OK, r.outcome)
        assertTrue(r.pose.position.x > obj.max.x)
        assertEquals(0.75f, r.pose.position.y, 1e-4f)
    }

    @Test fun conePushedOutByAtLeastTenCm() {
        val window = Vec3(0f, 1.6f, -1.75f)
        val obj = Aabb(Vec3(-0.6f, 0.7f, -1.4f), Vec3(-0.4f, 0.9f, -1.2f))
        val r = PlaceBody.place(head, window, obj, 0.7f, Vec3(0.3f, 0.3f, 0.3f))
        if (r.outcome == PlaceOutcome.OK) {
            val box = bodyBox(r.pose, Vec3(0.3f, 0.3f, 0.3f))
            val cone = SightCone(head, window)
            assertFalse(cone.touches(box))
        }
    }

    @Test fun plateCornersOutsideBodyAndSightLinesClear() {
        for (size in sizes) for (b in 0 until 8) {
            val a = Math.toRadians(bearing(b)).toFloat()
            val eye = Vec3(sin(a) * 2f, 1.6f, cos(a) * 2f)
            val body = BodyPose(Vec3(0f, 0f, 0f), yawFacing(Vec3.ZERO, eye))
            val box = bodyBox(body, size)
            if (box.contains(eye)) continue
            val (c, yaw) = ResultPlate.pose(body, size, eye)
            for (corner in ResultPlate.corners(c, yaw)) {
                assertFalse("corner in box size=$size b=$b", box.contains(corner))
                assertFalse("sight line through box size=$size b=$b", segmentHitsBox(eye, corner, Aabb(box.min + Vec3(0.001f, 0.001f, 0.001f), box.max - Vec3(0.001f, 0.001f, 0.001f))))
            }
        }
    }

    @Test fun plateDownAngleWithinFortyDegrees() {
        val body = BodyPose(Vec3(0f, 0f, -1.5f), 0f)
        val (c, _) = ResultPlate.pose(body, Vec3(0.6f, 0.3f, 0.3f), head)
        assertTrue(ResultPlate.downAngle(c, head) <= 40f)
    }

    @Test fun bringToFrontIsAheadOfTheSample() {
        val f = Vec3(sin(0.5f), 0f, -cos(0.5f))
        val r = PlaceBody.bringToFront(head, f, null, 0.5f, Vec3(0.3f, 0.3f, 0.3f))
        val d = r.pose.position - head
        val ang = Math.toDegrees(Math.acos(((d.x * f.x + d.z * f.z) / Math.hypot(d.x.toDouble(), d.z.toDouble())).coerceIn(-1.0, 1.0)))
        assertTrue(ang < 2.0)
        assertEquals(1.2, Math.hypot(d.x.toDouble(), d.z.toDouble()), 0.01)
    }

    @Test fun headSampleIgnoresThePressPose() {
        // looking at the window 25° to the left when pressing, then turning to straight ahead and holding still
        val hs = HeadSampler()
        hs.start(0L)
        var got: HeadSampler.Sample? = null
        var t = 0L
        val left = Vec3(-sin(Math.toRadians(25.0)).toFloat(), 0f, -cos(Math.toRadians(25.0)).toFloat())
        while (t < 200L) { got = got ?: hs.step(t, head, left); t += 16 }
        assertEquals(null, got)
        // turning (yaw changes every frame)
        for (k in 1..15) { val a = Math.toRadians(25.0 - k * 25.0 / 15).toFloat(); got = got ?: hs.step(t, head, Vec3(-sin(a), 0f, -cos(a))); t += 16 }
        while (got == null && t < 3000L) { got = hs.step(t, head, Vec3(0f, 0f, -1f)); t += 16 }
        val s = got!!
        assertTrue("yaw ${s.yawDeg}", abs(s.yawDeg) < 2f)
    }

    @Test fun levelGateWaitsForALevelHead() {
        val g = LevelGate()
        g.ask("start", 0)
        assertEquals(null, g.step(10, -30f))
        assertTrue(g.hintWanted(-30f))
        assertEquals(null, g.step(100, 2f))
        assertEquals("start", g.step(450, 1f))
        g.ask("recall", 1000)
        assertEquals(null, g.step(1000, null))
        assertEquals("recall/nohead", g.step(6100, null))
    }
}
