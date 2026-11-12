package io.embodit.spatialcalc.domain.session

import io.embodit.spatialcalc.domain.calc.Channel
import io.embodit.spatialcalc.domain.calc.TemplateId
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.domain.surface.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.2「界面简洁」(profile B31): every job, through its whole loop, keeps the Calc page at ≤ 7 tappables (3 TabBar
 * tabs + ⋯ + the formula strip + one primary + Toolbar keys + the 1:1 body), one primary, no key both on the panel
 * and on the Toolbar, and nothing that was on the v0.1.1 page is gone (it is in More).
 */
class ChromeTest {
    private var t = 1000L
    private val env = object : RoomEnv {
        override fun head() = Vec3(0f, 1.6f, 0f) to Vec3(0f, 0f, -1f)
        override fun window() = Vec3(0f, 1.6f, -1.75f)
        override fun floorY() = 0f
        override fun now() = t++
    }
    private val fx = object : FlowEffects {}
    private fun flow() = CalcFlow(env, fx).also { it.start(Settings(welcomeDone = true), emptyList(), emptyList()) }
    private fun pin(x: Float, y: Float, z: Float) = PinPoint(Vec3(x, y, z), Vec3.UP, SurfaceKind.TABLE, 1.2f, Channel.GAZE)

    private fun check(f: CalcFlow, where: String) {
        val s = f.s
        if (!Chrome.plainFormula(s)) return
        val n = Chrome.formulaCount(s)
        assertTrue("$where: $n tappables > 7", n <= 7)
        val tb = Chrome.toolbar(s)
        val p = Chrome.primary(s)
        if (p == PrimaryAction.MEASURE) assertFalse("$where: Measure on panel and Toolbar", ToolKeyId.MEASURE in tb)
        val more = Chrome.more(s)
        if (ToolKeyId.FITBOX in tb) assertFalse("$where: Fit box twice", MoreItem.FITBOX in more)
        if (ToolKeyId.MEASURE in tb) assertFalse("$where: Measure twice", MoreItem.MEASURE in more)
        // nothing lost: typing, sound, help and clear are always one ⋯ away
        assertTrue(more.containsAll(listOf(MoreItem.TYPE, MoreItem.SOUND, MoreItem.HELP, MoreItem.CLEAR)))
    }

    @Test fun everyJobStaysAtSevenOrFewerThroughItsLoop() {
        for (job in listOf(TemplateId.TANK, TemplateId.TILE, TemplateId.PAINT, TemplateId.FIT, TemplateId.RUG, TemplateId.FREE)) {
            val f = flow()
            f.pick(job)
            check(f, "$job empty")
            assertEquals(if (job == TemplateId.FREE) PrimaryAction.MEASURE else PrimaryAction.MEASURE, Chrome.primary(f.s))
            var k = 0
            while (Chrome.needMeasure(f.s) && k < 8) {
                f.measureButton()
                f.surfaceTap(pin(-0.3f + 0.01f * k, 0.75f, -1.5f)); f.surfaceTap(pin(0.3f, 0.75f + 0.05f * k, -1.5f))
                if (f.s.phase == Phase.AIM) f.cancelMeasure()
                check(f, "$job after measure $k"); k++
            }
            assertTrue("$job result", f.s.result.ok)
            if (f.s.result.body != null) {
                assertEquals(PrimaryAction.PLACE, Chrome.primary(f.s))
                f.equals()
                if (f.s.phase == Phase.PLACE) f.surfaceTap(pin(1f, 0f, -1.5f))
                check(f, "$job body")
                assertTrue(MoreItem.BRING in Chrome.more(f.s) && MoreItem.PUT_AWAY in Chrome.more(f.s))
            }
            assertEquals(PrimaryAction.SAVE, Chrome.primary(f.s))
            f.saveCard(); f.closeSaved()
            check(f, "$job saved")
            assertEquals(PrimaryAction.NEXT, Chrome.primary(f.s))
            assertTrue(MoreItem.SAVE_TEMPLATE in Chrome.more(f.s))
        }
    }

    @Test fun toolbarOnlyWhileMeasuringAndNeverOnThePickPage() {
        val f = flow()
        assertTrue(Chrome.toolbar(f.s).isEmpty()) // job grid
        f.pick(TemplateId.TANK)
        assertEquals(listOf(ToolKeyId.FITBOX), Chrome.toolbar(f.s))
        f.resultMenu(true)
        assertTrue(Chrome.toolbar(f.s).isEmpty())
        f.resultMenu(false)
        f.measureButton()
        assertTrue(Chrome.toolbar(f.s).isEmpty()) // aiming: the panel has [打数] [取消]
    }

    @Test fun howToShowsOnceThenOnlyFromMore() {
        val f = flow()
        f.pick(TemplateId.TANK)
        assertEquals(Hint.TIP, f.s.hint)
        f.dismissTip(); assertEquals(Hint.NONE, f.s.hint)
        f.next(); f.pick(TemplateId.TANK)
        assertEquals(Hint.NONE, f.s.hint) // second time: no standing how-to
        f.showTip(); assertEquals(Hint.TIP, f.s.hint)
    }

    @Test fun tankShapeAndUnitAreInMore() {
        val f = flow()
        f.pick(TemplateId.TANK)
        assertTrue(MoreItem.SHAPE in Chrome.more(f.s))
        f.setSlot(0, 0.6); f.setSlot(1, 0.3); f.setSlot(2, 0.3)
        assertTrue(MoreItem.UNIT in Chrome.more(f.s))
    }
}
