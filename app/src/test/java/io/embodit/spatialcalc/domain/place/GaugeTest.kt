package io.embodit.spatialcalc.domain.place

import io.embodit.spatialcalc.domain.app.AppController
import io.embodit.spatialcalc.domain.app.AppEffects
import io.embodit.spatialcalc.domain.app.HistoryItem
import io.embodit.spatialcalc.domain.app.Settings
import io.embodit.spatialcalc.domain.app.Sfx
import io.embodit.spatialcalc.domain.app.StopSlider
import io.embodit.spatialcalc.domain.calc.Key
import io.embodit.spatialcalc.domain.calc.LenUnit
import io.embodit.spatialcalc.domain.calc.LineProblem
import io.embodit.spatialcalc.domain.math.Quat4
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.ui.stage.input.DragTotal
import io.embodit.spatialcalc.ui.stage.input.Offset3M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** v0.3.0 (user 2026-11-16, profile D10 补): volumes, drag, two-axis line turn, three-axis box turn, [回正], q8-1 slider. */
class GaugeTest {
    private class Fx : AppEffects {
        override fun sound(s: Sfx) {}
        override fun saveSettings(s: Settings) {}
        override fun saveHistory(items: List<HistoryItem>) {}
        override fun log(line: String) {}
        override fun now() = 0L
    }

    private fun app() = AppController(Fx()).also { it.start(Settings(firstHintShown = true), emptyList()) }
    private fun AppController.type(keys: String) = keys.forEach { c -> press(Key.of(c)!!) }

    private fun near(a: Vec3, b: Vec3, tol: Float = 1e-4f) = assertTrue("$a vs $b", a.distanceTo(b) < tol)

    // ---------------------------------------------------------------- volume → cube

    @Test fun oneLitreIsATenCentimetreCube() {
        assertEquals(0.1, GaugeMath.cubeEdge(0.001), 1e-9)
        val a = app()
        a.unit(LenUnit.L); a.type("1="); a.ruler()
        assertTrue(a.s.line.on && a.s.line.isBox)
        a.s.line.dimsM.forEach { assertEquals(0.1, it, 1e-9) }
        assertEquals("S10", a.s.stateId)
    }

    @Test fun everyVolumeUnitGivesTheRightEdge() {
        val cases = mapOf(LenUnit.ML to 0.01, LenUnit.CM3 to 0.01, LenUnit.M3 to 1.0, LenUnit.FT3 to 0.3048, LenUnit.GAL to Math.cbrt(0.003785411784))
        for ((u, edge) in cases) {
            val a = app(); a.unit(u); a.type("1="); a.ruler()
            assertEquals("$u", edge, a.s.line.dimsM[0], 1e-9)
        }
        // 1.5 L → ∛0.0015 m ≈ 11.4 cm
        val a = app(); a.unit(LenUnit.L); a.type("1.5="); a.ruler()
        assertEquals(0.11447, a.s.line.dimsM[0], 1e-4)
    }

    @Test fun volumeUnitsConvertBetweenEachOther() {
        val a = app()
        a.unit(LenUnit.L); a.type("1.5=")
        a.unit(LenUnit.ML)
        assertEquals(0, a.s.calc.result!!.compareTo(java.math.BigDecimal("1500")))
        a.unit(LenUnit.CM3)
        assertEquals(0, a.s.calc.result!!.compareTo(java.math.BigDecimal("1500")))
        a.unit(LenUnit.L)
        assertEquals(0, a.s.calc.result!!.compareTo(java.math.BigDecimal("1.5")))
    }

    @Test fun threeSidesInALengthMakeThatBox() {
        val a = app()
        a.unit(LenUnit.CM); a.type("30*20*10=")
        assertTrue(a.s.rulerBox)
        a.ruler()
        assertTrue(a.s.line.isBox && a.s.line.fromSides)
        assertEquals(listOf(0.3, 0.2, 0.1), a.s.line.dimsM.map { Math.round(it * 1e6) / 1e6 })
        assertEquals(0.006, a.s.line.volumeM3, 1e-9)
        // a plain length is still the line
        a.ruler(); a.type("c12+3="); assertFalse(a.s.rulerBox)
        a.ruler(); assertFalse(a.s.line.isBox)
        // switching the unit to a volume turns the line into a cube that follows
        a.unit(LenUnit.L)
        assertTrue(a.s.line.on && a.s.line.isBox)
    }

    @Test fun tooSmallAndNegativeVolumesAreRefused() {
        val a = app(); a.unit(LenUnit.ML); a.type("0.0001="); a.ruler()
        assertFalse(a.s.line.on); assertEquals(LineProblem.TOO_SHORT, a.s.lineProblem)
        val b = app(); b.unit(LenUnit.L); b.type("~2="); b.ruler()
        assertFalse(b.s.line.on); assertEquals(LineProblem.NEGATIVE, b.s.lineProblem)
        val c = app(); c.unit(LenUnit.M3); c.type("64="); c.ruler()
        assertTrue(c.s.line.clipped); assertEquals("S10b", c.s.stateId)
    }

    // ---------------------------------------------------------------- turning

    @Test fun boxTurnsNinetyAboutEachAxis() {
        val x = Vec3(1f, 0f, 0f); val y = Vec3.UP; val z = Vec3(0f, 0f, 1f)
        // base yaw 0 = facing a user standing at +Z
        near(GaugeMath.rotation(0f, 90f, 0f, 0f).rotate(z), x)      // Y: the front face turns to the right
        near(GaugeMath.rotation(0f, 90f, 0f, 0f).rotate(y), y)
        near(GaugeMath.rotation(0f, 0f, 90f, 0f).rotate(y), z)      // X: the top tips toward the user
        near(GaugeMath.rotation(0f, 0f, 90f, 0f).rotate(x), x)
        near(GaugeMath.rotation(0f, 0f, 0f, 90f).rotate(x), y)      // Z: the right side rolls up
        near(GaugeMath.rotation(0f, 0f, 0f, 90f).rotate(z), z)
        // through the controller: six notches = 90° on each axis
        val a = app(); a.unit(LenUnit.L); a.type("1="); a.ruler()
        for (axis in Axis.entries) repeat(6) { a.gaugeTurn(axis, 1) }
        assertEquals(90f, a.s.line.yaw, 0f); assertEquals(90f, a.s.line.pitch, 0f); assertEquals(90f, a.s.line.roll, 0f)
        assertEquals("S10a", a.s.stateId)
    }

    @Test fun resetBringsAllThreeAxesBackUnderTwoDegrees() {
        val a = app(); a.unit(LenUnit.L); a.type("2="); a.ruler()
        a.gaugeTurn(Axis.X, 1); a.gaugeTurn(Axis.Y, -1); repeat(5) { a.gaugeTurn(Axis.Z, 1) }
        a.gaugeTwist(Axis.Y, 33f); a.gaugeTwistEnd()
        val serial = a.s.line.resetSerial
        a.gaugeReset()
        val l = a.s.line
        assertEquals(serial + 1, l.resetSerial)
        val q = GaugeMath.rotation(0.7f, l.yaw, l.pitch, l.roll)
        assertTrue(GaugeMath.angleBetween(q, Quat4.axisAngle(Vec3.UP, 0.7f)) < 2f)
        // [回正] faces the user from anywhere: four sides of the gauge, the front looks at the head (< 2°)
        val head = Vec3(0f, 1.6f, 0f)
        for (p in listOf(Vec3(0f, 1.5f, -1f), Vec3(1f, 1.5f, 0f), Vec3(0f, 1.5f, 1f), Vec3(-1f, 1.5f, 0f), Vec3(0.7f, 1.2f, -0.7f))) {
            val yaw = LinePlace.yawFacing(p, head)
            val front = GaugeMath.rotation(yaw, 0f, 0f, 0f).rotate(Vec3(0f, 0f, 1f))
            val toHead = Vec3(head.x - p.x, 0f, head.z - p.z).normalized()
            val deg = Math.toDegrees(kotlin.math.acos(front.dot(toHead).coerceIn(-1f, 1f).toDouble()))
            assertTrue("$p $deg", deg < 2.0)
        }
    }

    @Test fun lineTurnsAboutTwoAxes() {
        val a = app(); a.unit(LenUnit.CM); a.type("80="); a.ruler()
        repeat(3) { a.gaugeTurn(Axis.X, 1) }                          // stick up three notches: slanted 45°
        assertEquals(45f, a.s.line.roll, 0f)
        val d = GaugeMath.lineDir(0f, a.s.line.yaw, a.s.line.roll)
        assertEquals(45.0, Math.toDegrees(kotlin.math.asin(d.y.toDouble())), 0.01)
        repeat(5) { a.gaugeTurn(Axis.Z, 1) }                          // standing, and it stops there
        assertEquals(90f, a.s.line.roll, 0f); assertTrue(a.s.line.standing)
        near(GaugeMath.lineDir(0f, 0f, 90f), Vec3.UP)
        repeat(9) { a.gaugeTurn(Axis.X, -1) }                         // back down to lying flat, not below
        assertEquals(0f, a.s.line.roll, 0f)
        repeat(6) { a.gaugeTurn(Axis.Y, 1) }                          // vertical axis: 90°
        near(GaugeMath.lineDir(0f, a.s.line.yaw, 0f), Vec3(0f, 0f, -1f))
        a.gaugeReset()
        assertEquals(0f, a.s.line.yaw, 0f); assertEquals(0f, a.s.line.roll, 0f)
    }

    @Test fun stepsAndSnapsLandOnZeroFortyFiveNinety() {
        assertEquals(15f, GaugeMath.step(0f, 1), 0f)
        assertEquals(45f, GaugeMath.step(37f, 1), 0f)   // a free angle steps onto the 15° grid
        assertEquals(180f, GaugeMath.step(165f, 1), 0f)
        assertEquals(-165f, GaugeMath.step(180f, 1), 0f)
        assertEquals(45f, GaugeMath.snap(52f), 0f)
        assertEquals(90f, GaugeMath.snap(81f), 0f)
        assertEquals(0f, GaugeMath.snap(-8f), 0f)
        assertEquals(66f, GaugeMath.snap(66.2f), 0f)    // between snaps: stays
        val a = app(); a.unit(LenUnit.L); a.type("1="); a.ruler()
        a.gaugeTwist(Axis.Z, 40f); a.gaugeTwist(Axis.Z, 8f); a.gaugeTwistEnd()
        assertEquals(45f, a.s.line.roll, 0f)
    }

    // ---------------------------------------------------------------- drag (D53)

    @Test fun dragDeltasAddUpForBothGauges() {
        val t = DragTotal()
        var last = Offset3M(0f, 0f, 0f)
        repeat(60) { last = t.add(Offset3M(0.002f, 0f, 0f)) }
        assertEquals(0.12f, last.x, 1e-5f)
        repeat(30) { last = t.add(Offset3M(0f, -0.001f, 0.002f)) }
        assertEquals(-0.03f, last.y, 1e-5f); assertEquals(0.06f, last.z, 1e-5f)
        t.reset(); assertEquals(0f, t.add(Offset3M(0f, 0f, 0f)).length, 0f)
    }

    @Test fun defaultBoxStaysOutOfTheFaceAndBesideTheWindow() {
        val head = Vec3(0f, 1.6f, 0f)
        val w = LinePlace.window(head, Vec3(0f, 0f, -1f))
        for (edge in listOf(0.1, 0.5, 1.0, 3.0)) {
            val c = GaugeMath.defaultBoxCenter(w, head, listOf(edge, edge, edge))
            val near = -(c.z) - edge / 2
            assertTrue("edge $edge near face ${near}", near >= GaugeMath.CLEAR_M - 1e-4)
            assertTrue("edge $edge right of window", c.x - edge / 2 >= w.widthM / 2 - 1e-4)
        }
    }

    // ---------------------------------------------------------------- q8-1 volume slider (B20)

    @Test fun volumeSliderTapAndDragBothChangeTheStop() {
        val s = StopSlider(2)
        assertEquals(0, s.change(0.1f)); assertEquals(0f, s.finish(), 0f)          // tap near 0 %
        assertEquals(1, s.change(1.0f)); assertEquals(1f, s.finish(), 0f)          // tap the middle
        assertEquals(2, s.change(1.9f)); s.finish()                                 // tap near 100 %
        assertNull(s.change(1.7f))                                                  // small drag, same stop
        assertEquals(1, s.change(1.4f))                                             // drag on past the middle
        assertEquals(1f, s.finish(), 0f)
        assertEquals(0, s.change(0.2f))                                             // and back down again
        // changed elsewhere (debug / restore): the slider follows, and a tap back to where it was still counts
        s.sync(2); assertEquals(2f, s.pos, 0f)
        assertEquals(0, s.change(0f))
    }
}
