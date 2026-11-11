package io.embodit.spatialcalc.domain.session

import io.embodit.spatialcalc.data.CardCodec
import io.embodit.spatialcalc.domain.calc.BodySpec
import io.embodit.spatialcalc.domain.calc.Channel
import io.embodit.spatialcalc.domain.calc.TemplateId
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.domain.place.SightCone
import io.embodit.spatialcalc.domain.place.bodyBox
import io.embodit.spatialcalc.domain.surface.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state machine: every mode has an exit (D1), debug and real input share one path, 1:1 sizes. */
class FlowTest {
    private var t = 1000L
    private val head = Vec3(0f, 1.6f, 0f)
    private val env = object : RoomEnv {
        override fun head() = head to Vec3(0f, 0f, -1f)
        override fun window() = Vec3(0f, 1.6f, -1.75f)
        override fun floorY() = 0f
        override fun now() = t++
    }
    private val logs = ArrayList<String>()
    private val sounds = ArrayList<Sfx>()
    private val anchors = ArrayList<Long>()
    private val fx = object : FlowEffects {
        override fun log(line: String) { logs += line }
        override fun sound(s: Sfx) { sounds += s }
        override fun createAnchor(cardId: Long, at: Vec3) { anchors += cardId }
    }
    private fun flow() = CalcFlow(env, fx).also { it.start(Settings(welcomeDone = true), emptyList(), emptyList()) }
    private fun pin(x: Float, y: Float, z: Float, kind: SurfaceKind = SurfaceKind.TABLE) = PinPoint(Vec3(x, y, z), Vec3.UP, kind, 1.2f, Channel.GAZE)

    private fun measure(f: CalcFlow, a: PinPoint, b: PinPoint) { f.surfaceTap(a); f.surfaceTap(b) }

    @Test fun tankMeasuredGrowsBesideTheTankAtOneToOne() {
        val f = flow()
        f.pick(TemplateId.TANK)
        assertEquals(Phase.FORMULA, f.s.phase)
        f.measureButton(); assertEquals(Phase.AIM, f.s.phase)
        measure(f, pin(-0.3f, 0.75f, -1.5f), pin(0.3f, 0.75f, -1.5f))
        assertEquals(Phase.AIM, f.s.phase) // auto-advance to the next measure slot
        measure(f, pin(0.3f, 0.75f, -1.35f), pin(0.3f, 0.75f, -1.65f))
        measure(f, pin(0.3f, 0.75f, -1.5f), pin(0.3f, 1.05f, -1.5f))
        assertEquals(Phase.FORMULA, f.s.phase)
        assertTrue(f.s.result.ok)
        f.equals()
        val b = f.s.body!!
        assertEquals(0.6f, b.size.x, 0.001f); assertEquals(0.3f, b.size.y, 0.001f); assertEquals(0.3f, b.size.z, 0.001f)
        assertFalse(SightCone(head, env.window()).touches(bodyBox(b.pose, b.size)))
        assertTrue(Sfx.POUR in sounds)
        assertEquals("S08", f.s.stateId)
    }

    @Test fun fullFormulaDoesNotArmSurfacePinches() {
        val f = flow()
        f.pick(TemplateId.RUG)
        measure(f, pin(0f, 0f, -1f, SurfaceKind.FLOOR), pin(2f, 0f, -1f, SurfaceKind.FLOOR))
        measure(f, pin(0f, 0f, -1f, SurfaceKind.FLOOR), pin(0f, 0f, -2.5f, SurfaceKind.FLOOR))
        assertEquals(Phase.FORMULA, f.s.phase)
        f.surfaceTap(pin(1f, 0f, -1f, SurfaceKind.FLOOR))
        assertEquals(Phase.FORMULA, f.s.phase)
        assertTrue(logs.any { "formula-full" in it })
    }

    @Test fun everyModeHasAnExit() {
        val f = flow()
        f.pick(TemplateId.TANK)
        f.measureButton(); f.cancelMeasure(); assertEquals(Phase.FORMULA, f.s.phase)
        f.measureButton(); f.surfaceTap(pin(0f, 0.7f, -1f)); assertEquals(Phase.PULL, f.s.phase); f.cancelMeasure(); assertEquals(Phase.FORMULA, f.s.phase)
        f.openTyping(); assertEquals(Phase.TYPING, f.s.phase); f.closeTyping(); assertEquals(Phase.FORMULA, f.s.phase)
        f.fitBoxButton(null); assertEquals(Phase.FITBOX, f.s.phase); f.fitBoxCancel(); assertEquals(Phase.FORMULA, f.s.phase)
        // typed only -> = asks for a drop point (S09) -> a tap places it; or cancel
        f.paste("60x30x35 cm"); f.equals(); assertEquals(Phase.PLACE, f.s.phase)
        f.cancelPlace(); assertEquals(Phase.FORMULA, f.s.phase)
        f.equals(); f.surfaceTap(pin(0.8f, 0.5f, -1.2f, SurfaceKind.VIRTUAL)); assertEquals(Phase.FORMULA, f.s.phase); assertNotNull(f.s.body)
        // after placing, surface taps do not move it (locked: only dragging the body itself moves it)
        val before = f.s.body!!.pose
        f.surfaceTap(pin(0f, 0.5f, -2f)); assertEquals(before, f.s.body!!.pose)
        f.clearAsk(); assertEquals(Confirm.CLEAR, f.s.confirm); f.confirmNo(); assertNull(f.s.confirm)
        f.clearAsk(); f.confirmYes(); assertEquals(Phase.PICK, f.s.phase); assertNull(f.s.body)
    }

    @Test fun liveEditResizesTheBody() {
        val f = flow()
        f.pick(TemplateId.TILE)
        f.paste("2.4m x 2.4m")
        f.equals(); f.surfaceTap(pin(0f, 0f, -2f, SurfaceKind.WALL).copy(normal = Vec3(0f, 0f, 1f)))
        val n30 = f.s.result.count!!.n
        f.setSlot(2, 60.0) // tile width 30 -> 60 cm
        assertTrue(f.s.result.count!!.n < n30)
        val tiles = f.s.result.body as BodySpec.Tiles
        assertEquals(0.6, tiles.tileW, 1e-9)
        assertNotNull(f.s.body)
    }

    @Test fun pasteSplitsIntoThreeSlots() {
        val f = flow()
        f.pick(TemplateId.FIT)
        f.selectSlot(3)
        f.paste("38×28×24 cm")
        assertEquals(0.38, f.s.slots[3].q!!.v, 1e-9); assertEquals(0.24, f.s.slots[5].q!!.v, 1e-9)
        assertEquals(Hint.PASTE_SPLIT, f.s.hint)
        f.paste("not a size"); assertEquals(Hint.PASTE_BAD, f.s.hint)
    }

    @Test fun pinsTooCloseIsAnInlineError() {
        val f = flow()
        f.pick(TemplateId.RUG); f.measureButton()
        f.surfaceTap(pin(0f, 0f, -1f)); f.surfaceTap(pin(0.005f, 0f, -1f))
        assertEquals(Hint.PINS_CLOSE, f.s.hint); assertEquals(Phase.PULL, f.s.phase)
    }

    @Test fun freeFormulaOversize() {
        val f = flow()
        f.measureButton() // from the job grid: free formula
        assertEquals(TemplateId.FREE, f.s.template)
        f.cancelMeasure()
        f.paste("3m x 3m x 2.5m")
        f.equals(); f.surfaceTap(pin(1.5f, 0f, -3f, SurfaceKind.FLOOR))
        assertTrue(f.s.result.body!!.oversize)
        assertEquals(Hint.OVERSIZE, f.s.hint)
    }

    @Test fun saveAndPinAndCodecRoundTrip() {
        val f = flow()
        f.pick(TemplateId.RUG); f.paste("230 x 140"); f.equals(); f.surfaceTap(pin(0f, 0f, -2f, SurfaceKind.FLOOR))
        f.saveCard(); assertTrue(f.s.savedPreview); assertEquals(1, f.s.cards.size)
        f.pinHere(); assertEquals(listOf(f.s.cards[0].id), anchors)
        f.onAnchor(f.s.cards[0].id, null); assertEquals(Hint.PIN_FAILED, f.s.hint); assertFalse(f.s.cards[0].pinned)
        f.onAnchor(f.s.cards[0].id, "123e4567-e89b-12d3-a456-426614174000"); assertTrue(f.s.cards[0].pinned)
        f.saveAsTemplate("阳台花槽")
        val (cards, customs) = CardCodec.decode(CardCodec.encode(f.s.cards, f.s.customs))
        assertEquals(f.s.cards, cards)
        assertEquals(f.s.customs, customs)
    }

    @Test fun replayDoesNotPush() {
        val f = flow()
        f.pick(TemplateId.TANK); f.paste("60x30x30"); f.equals(); f.surfaceTap(pin(0f, 0.5f, -1.75f, SurfaceKind.VIRTUAL))
        f.saveCard(); f.closeSaved()
        val pose = f.s.body!!.pose
        f.next(); assertNull(f.s.body)
        f.replay(f.s.cards[0].id)
        assertEquals(pose, f.s.body!!.pose) // regrown where it was, even though that is in the sight cone
    }

    @Test fun fitBoxGivesThreeChips() {
        val f = flow()
        f.pick(TemplateId.FIT)
        f.fitBoxButton(pin(0f, 0.7f, -1f))
        f.fitBoxFace(0, 1, 0.5f); f.fitBoxFace(1, 1, 0.1f)
        f.fitBoxUse()
        assertEquals(0.8, f.s.slots[0].q!!.v, 1e-4); assertEquals(0.3, f.s.slots[1].q!!.v, 1e-4); assertEquals(0.4, f.s.slots[2].q!!.v, 1e-4)
        assertEquals(Phase.FORMULA, f.s.phase)
    }
}
