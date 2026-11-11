package io.embodit.spatialcalc.domain.session

import io.embodit.spatialcalc.domain.calc.BodySpec
import io.embodit.spatialcalc.domain.calc.Channel
import io.embodit.spatialcalc.domain.calc.LenUnit
import io.embodit.spatialcalc.domain.calc.Source
import io.embodit.spatialcalc.domain.calc.TemplateId
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.domain.place.PlaceBody
import io.embodit.spatialcalc.domain.place.bodyBox
import io.embodit.spatialcalc.domain.place.flatDir
import io.embodit.spatialcalc.domain.surface.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos

/** QA round 1 findings q1-1 … q1-9, one regression test (at least) each. */
class Round2Test {
    private var t = 1000L
    private val head = Vec3(0f, 1.6f, 0f)
    private val win = Vec3(0f, 1.6f, -1.75f)
    private val env = object : RoomEnv {
        override fun head() = head to Vec3(0f, 0f, -1f)
        override fun window() = win
        override fun floorY() = 0f
        override fun now() = t++
    }
    private val logs = ArrayList<String>()
    private val anchors = ArrayList<Long>()
    private val fx = object : FlowEffects {
        override fun log(line: String) { logs += line }
        override fun createAnchor(cardId: Long, at: Vec3) { anchors += cardId }
    }
    private fun flow(settings: Settings = Settings(welcomeDone = true)) = CalcFlow(env, fx).also { it.start(settings, emptyList(), emptyList()) }
    private fun pin(x: Float, y: Float, z: Float, kind: SurfaceKind = SurfaceKind.TABLE) = PinPoint(Vec3(x, y, z), Vec3.UP, kind, 1.2f, Channel.GAZE)
    private fun measure(f: CalcFlow, a: PinPoint, b: PinPoint) { f.surfaceTap(a); f.surfaceTap(b) }

    // ---------------------------------------------------------------- q1-2 typing round trip

    @Test fun pastedChipSurvivesOpeningAndClosingTheKeyboard() {
        val f = flow()
        f.pick(TemplateId.TANK)
        f.paste("60x30x35 cm")
        val before = f.s.slots.map { it.q!!.v }
        val litres = f.s.result.q!!.v
        for (i in 0..2) {
            f.openTyping(i)
            assertEquals(listOf("60", "30", "35")[i], f.s.typingBuffer) // shown in cm, not metres
            f.closeTyping()
        }
        assertEquals(before, f.s.slots.map { it.q!!.v })
        assertEquals(litres, f.s.result.q!!.v, 1e-12)
        assertEquals(0.35, f.s.slots[2].q!!.v, 1e-9)
    }

    @Test fun pastedChipRetypedKeepsCentimetres() {
        val f = flow()
        f.pick(TemplateId.TANK)
        f.paste("60x30x35 cm")
        f.openTyping(2)
        f.key("⌫"); f.key("⌫"); f.key("4"); f.key("0")
        f.closeTyping()
        assertEquals(0.40, f.s.slots[2].q!!.v, 1e-9)
        f.openTyping(2); f.closeTyping() // and again, unchanged
        assertEquals(0.40, f.s.slots[2].q!!.v, 1e-9)
    }

    @Test fun inchesRoundTripToo() {
        val f = flow()
        f.pick(TemplateId.TANK)
        f.setTypingUnit(LenUnit.IN)
        f.key("2"); f.key("4"); f.closeTyping()
        val v = f.s.slots[0].q!!.v
        f.openTyping(0)
        assertEquals("24", f.s.typingBuffer)
        f.closeTyping()
        assertEquals(v, f.s.slots[0].q!!.v, 1e-12)
    }

    @Test fun defaultNumberSlotsStillShowTheirOwnUnit() {
        val f = flow()
        f.pick(TemplateId.TILE)
        f.openTyping(2) // tile width default 30 cm (SlotUnit.CM)
        assertEquals("30", f.s.typingBuffer)
        f.closeTyping()
        assertEquals(0.30, f.s.slots[2].q!!.v, 1e-9)
        assertEquals(Source.DEFAULT, f.s.slots[2].source)
    }

    // ---------------------------------------------------------------- q1-1 snap to pins

    @Test fun snapOnLandsOnTheExistingPin() {
        val f = flow()
        f.pick(TemplateId.TANK)
        measure(f, pin(0f, 0.75f, -1.5f), pin(0.6f, 0.75f, -1.5f))
        f.surfaceTap(pin(0.615f, 0.75f, -1.51f)) // 1.8 cm off the corner
        assertEquals(Vec3(0.6f, 0.75f, -1.5f), f.s.firstPin!!.point)
        assertTrue(logs.any { it.startsWith("SNAP") })
    }

    @Test fun snapOffKeepsTheRawTap() {
        val f = flow(Settings(welcomeDone = true, snapPins = false))
        f.pick(TemplateId.TANK)
        measure(f, pin(0f, 0.75f, -1.5f), pin(0.6f, 0.75f, -1.5f))
        f.surfaceTap(pin(0.615f, 0.75f, -1.51f))
        assertEquals(Vec3(0.615f, 0.75f, -1.51f), f.s.firstPin!!.point)
    }

    @Test fun farTapIsNotSnapped() {
        val f = flow()
        f.pick(TemplateId.TANK)
        measure(f, pin(0f, 0.75f, -1.5f), pin(0.6f, 0.75f, -1.5f))
        f.surfaceTap(pin(0.65f, 0.75f, -1.5f)) // 5 cm
        assertEquals(0.65f, f.s.firstPin!!.point.x, 1e-6f)
    }

    @Test fun secondPinNeverSnapsOntoItsOwnFirstPin() {
        val f = flow()
        f.pick(TemplateId.TANK)
        f.surfaceTap(pin(0f, 0.75f, -1.5f))
        f.surfaceTap(pin(0.02f, 0.75f, -1.5f))
        // a 2 cm tape is kept (not zeroed into「两点太近」by the snap)
        assertEquals(0.02, f.s.slots[0].q!!.v, 1e-4)
    }

    // ---------------------------------------------------------------- q1-3 records [带到面前]

    private fun savedTank(f: CalcFlow): Long {
        f.pick(TemplateId.TANK)
        measure(f, pin(-0.3f, 0.75f, -1.5f), pin(0.3f, 0.75f, -1.5f))
        measure(f, pin(0.3f, 0.75f, -1.35f), pin(0.3f, 0.75f, -1.65f))
        measure(f, pin(0.3f, 0.75f, -1.5f), pin(0.3f, 1.05f, -1.5f))
        f.equals(); f.saveCard()
        return f.s.savedCardId!!
    }

    @Test fun bringActiveCardMovesTheBodyOnScreen() {
        val f = flow()
        val id = savedTank(f)
        val before = f.s.body!!.pose.position
        f.page(Page.RECORDS)
        f.bringCardToFront(id, head to Vec3(-1f, 0f, 0f)) // looking left now
        val b = f.s.body!!
        assertTrue(b.pose.position.distanceTo(before) > 0.5f)
        assertEquals(Page.RECORDS, f.s.page)
        assertEquals(Hint.BROUGHT, f.s.hint)
        assertTrue(b.pose.position.x < -0.8f) // in front of the left-looking head
    }

    @Test fun bringNotShownCardLoadsItInFront() {
        val f = flow()
        val id = savedTank(f)
        f.next()
        assertEquals(null, f.s.body)
        f.page(Page.RECORDS)
        f.bringCardToFront(id, head to Vec3(0f, 0f, -1f))
        val b = assertNotNull(f.s.body).let { f.s.body!! }
        assertEquals(Page.RECORDS, f.s.page)
        assertEquals(0.6f, b.size.x, 1e-3f)
        assertEquals(f.s.cards.first { it.id == id }.pose, b.pose)
    }

    @Test fun bringPinnedCardMovesAndReanchors() {
        val f = flow()
        val id = savedTank(f)
        f.pinHere(); f.onAnchor(id, "a1")
        val n = anchors.size
        f.bringCardToFront(id, head to Vec3(1f, 0f, 0f))
        assertEquals(n + 1, anchors.size)
        assertTrue(f.s.cards.first { it.id == id }.pose!!.position.x > 0.8f)
    }

    // ---------------------------------------------------------------- q1-4 fit box beside the window

    private fun offDeg(p: Vec3): Float {
        val w = flatDir(head, win); val d = flatDir(head, p)
        return Math.toDegrees(acos(w.dot(d).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    @Test fun fitBoxWithoutRayOpensBesideTheWindow() {
        val f = flow()
        f.pick(TemplateId.FIT)
        f.fitBoxButton(null)
        val fb = f.s.fitBox!!
        assertTrue("off=${offDeg(fb.pose.position)}", offDeg(fb.pose.position) >= PlaceBody.FIT_SIDE_DEG - 0.5f)
        // its three readings (≤ 0.3 m beside the 0.3 m box) and the box stay ≥ 10° clear of the window half-width (0.224 m at 1.75 m ≈ 7.3°)
        val corner = fb.pose.position + Vec3(-0.30f, 0f, 0f)
        assertTrue(offDeg(corner) > 7.3f + 10f)
    }

    @Test fun fitBoxRayHitUnderTheWindowIsSwungOut() {
        val f = flow()
        f.pick(TemplateId.FIT)
        f.fitBoxButton(pin(0f, 0.75f, -1.2f))
        assertTrue(offDeg(f.s.fitBox!!.pose.position) >= PlaceBody.FIT_SIDE_DEG - 0.5f)
        f.fitBoxCancel()
        f.fitBoxButton(pin(-0.3f, 0.75f, -1.2f)) // left of the window → swung out to the left
        assertTrue(f.s.fitBox!!.pose.position.x < 0f)
        f.fitBoxCancel()
        f.fitBoxButton(pin(1.2f, 0.75f, -1.0f)) // already well to the side: untouched
        assertEquals(1.2f, f.s.fitBox!!.pose.position.x, 1e-5f)
    }

    // ---------------------------------------------------------------- q1-8 ghosts into the measured cell

    @Test fun fitBoxCellGetsTheGhostBoxes() {
        val f = flow()
        f.pick(TemplateId.FIT)
        f.fitBoxButton(pin(0.9f, 0.75f, -1.0f))
        f.fitBoxFace(0, 1, 0.15f); f.fitBoxFace(2, 1, 0.05f); f.fitBoxFace(1, 1, 0.10f) // 0.45 × 0.40 × 0.35
        val cell = f.s.fitBox!!
        f.fitBoxUse()
        f.paste("20x15x10 cm")
        assertTrue(f.s.result.ok)
        f.equals()
        assertTrue(logs.none { "PLACE ask" in it })
        val b = f.s.body!!
        assertEquals(cell.pose.position, b.pose.position)
        assertEquals(cell.pose.yaw, b.pose.yaw, 1e-6f)
        assertTrue(f.s.result.body is BodySpec.Ghosts)
        assertEquals(cell.size.x, b.size.x, 1e-3f); assertEquals(cell.size.y, b.size.y, 1e-3f); assertEquals(cell.size.z, b.size.z, 1e-3f)
    }

    // ---------------------------------------------------------------- q1-9 no overlap with pinned bodies

    @Test fun newBodyDoesNotOverlapPinnedOnes() {
        val f = flow()
        val id = savedTank(f)
        f.pinHere(); f.onAnchor(id, "a1")
        val pinned = f.s.cards.first { it.id == id }
        val pinnedBox = bodyBox(pinned.pose!!, pinned.size!!)
        // same object measured again → would land on the same spot
        f.next()
        f.pick(TemplateId.TANK)
        measure(f, pin(-0.3f, 0.75f, -1.5f), pin(0.3f, 0.75f, -1.5f))
        measure(f, pin(0.3f, 0.75f, -1.35f), pin(0.3f, 0.75f, -1.65f))
        measure(f, pin(0.3f, 0.75f, -1.5f), pin(0.3f, 1.05f, -1.5f))
        f.equals()
        val b = f.s.body!!
        assertFalse(bodyBox(b.pose, b.size).intersects(pinnedBox))
    }
}
