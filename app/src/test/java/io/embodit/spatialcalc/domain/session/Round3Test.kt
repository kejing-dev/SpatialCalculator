package io.embodit.spatialcalc.domain.session

import io.embodit.spatialcalc.domain.calc.Channel
import io.embodit.spatialcalc.domain.calc.Source
import io.embodit.spatialcalc.domain.calc.TemplateId
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.domain.place.BodyPose
import io.embodit.spatialcalc.domain.place.PlaceBody
import io.embodit.spatialcalc.domain.place.bodyBox
import io.embodit.spatialcalc.domain.place.yawFacing
import io.embodit.spatialcalc.domain.surface.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA round 3 (v0.1.2) findings q3-1 … q3-3 and the known issues K6–K10, one regression test (at least) each. */
class Round3Test {
    private var t = 1000L
    private val head = Vec3(0f, 1.6f, 0f)
    private val env = object : RoomEnv {
        override fun head() = head to Vec3(0f, 0f, -1f)
        override fun window() = Vec3(0f, 1.6f, -1.75f)
        override fun floorY() = 0f
        override fun now() = t++
    }
    private val logs = ArrayList<String>()
    private var flowRef: CalcFlow? = null
    private val fx = object : FlowEffects {
        override fun log(line: String) { logs += line }
        // the anchor comes straight back (the Stage does this asynchronously)
        override fun createAnchor(cardId: Long, at: Vec3) { flowRef?.onAnchor(cardId, "anchor-$cardId") }
    }
    private fun flow() = CalcFlow(env, fx).also { flowRef = it; it.start(Settings(welcomeDone = true), emptyList(), emptyList()) }
    private fun pin(x: Float, y: Float, z: Float, kind: SurfaceKind = SurfaceKind.TABLE, n: Vec3 = Vec3.UP) = PinPoint(Vec3(x, y, z), n, kind, 1.2f, Channel.GAZE)

    // ---------------------------------------------------------------- q3-2 after [钉在这里]

    @Test fun afterPinHereThePrimaryIsMeasureAnotherAndEqualsDoesNotGrowACopy() {
        for (job in listOf(TemplateId.TANK, TemplateId.TILE)) {
            val f = flow()
            f.pick(job)
            if (job == TemplateId.TANK) f.paste("60x30x35 cm") else {
                val wall = Vec3(0f, 0f, 1f)
                f.measureButton(); f.surfaceTap(pin(-0.5f, 0.2f, -1.5f, SurfaceKind.WALL, wall)); f.surfaceTap(pin(0.5f, 0.2f, -1.5f, SurfaceKind.WALL, wall))
                f.measureButton(); f.surfaceTap(pin(0.5f, 0.2f, -1.5f, SurfaceKind.WALL, wall)); f.surfaceTap(pin(0.5f, 1.2f, -1.5f, SurfaceKind.WALL, wall))
            }
            assertTrue("$job result", f.s.result.ok)
            f.equals()
            if (f.s.phase == Phase.PLACE) f.surfaceTap(pin(0.8f, 0.75f, -1.5f))
            assertTrue("$job body", f.s.body != null)
            f.saveCard()
            f.pinHere()
            assertTrue("$job pinned", f.s.cards.first().pinned)
            assertNull("$job: the pinned copy stands there now", f.s.body)
            assertEquals("$job primary after pin", PrimaryAction.NEXT, Chrome.primary(f.s))
            // the old bug path: = again
            f.equals()
            assertNull("$job: no second body", f.s.body)
            assertEquals(Phase.FORMULA, f.s.phase)
            assertTrue(logs.any { it.startsWith("EQUALS ignored") })
            // editing a number afterwards makes it a new result again: = is back
            f.setSlot(0, 0.9)
            assertEquals(PrimaryAction.PLACE, Chrome.primary(f.s))
        }
    }

    // ---------------------------------------------------------------- q3-3 typing: switching slots

    @Test fun switchingSlotOnTheKeyboardSavesTheFieldFirst() {
        val f = flow()
        f.pick(TemplateId.FIT) // 箱长 箱宽 箱高 … : QA walk was box length / width / height
        val (a, b, c) = f.s.slots.indices.filter { f.s.slots[it].def.unit == io.embodit.spatialcalc.domain.calc.SlotUnit.LENGTH }.take(3)
        f.openTyping(a)
        "20".forEach { f.key(it.toString()) }
        f.typingChipTap(b)
        assertEquals(b, f.s.active)
        assertEquals("", f.s.typingBuffer) // the field starts empty for the empty slot
        "20".forEach { f.key(it.toString()) }
        f.typingChipTap(c)
        "20".forEach { f.key(it.toString()) }
        f.closeTyping()
        for (i in listOf(a, b, c)) {
            assertEquals("slot $i", 0.20, f.s.slots[i].q!!.v, 1e-9)
            assertEquals(Source.TYPED, f.s.slots[i].source)
        }
    }

    @Test fun switchingToAFilledSlotShowsItsValueAndUnchangedKeepsIt() {
        val f = flow()
        f.pick(TemplateId.TANK)
        f.paste("60x30x35 cm")
        f.openTyping(0)
        assertEquals("60", f.s.typingBuffer)
        f.typingChipTap(2)
        assertEquals("35", f.s.typingBuffer)
        f.key("⌫"); f.key("⌫"); "40".forEach { f.key(it.toString()) }
        f.typingChipTap(1) // 40 goes into slot 2
        assertEquals("30", f.s.typingBuffer)
        f.closeTyping()
        assertEquals(listOf(0.60, 0.30, 0.40), f.s.slots.map { Math.round(it.q!!.v * 100) / 100.0 })
    }

    @Test fun tappingTheSameSlotOrNothingTypedWritesNothing() {
        val f = flow()
        f.pick(TemplateId.TANK)
        f.openTyping(0)
        f.typingChipTap(1)
        f.typingChipTap(2)
        f.closeTyping()
        assertTrue(f.s.slots.all { it.q == null })
    }

    // ---------------------------------------------------------------- K10 [✓ 收起] lands on the plain formula

    @Test fun closingTheKeyboardNeverOpensChipEditMode() {
        val f = flow()
        f.pick(TemplateId.TANK)
        f.paste("60x30x35 cm")
        f.chipTap(0) // chip edit mode
        f.openTyping(0) // 改数
        f.typingChipTap(1)
        f.closeTyping()
        assertNull(f.s.chipMenu)
        assertTrue(Chrome.plainFormula(f.s))
        // free formula: a chip tap on the keyboard closes it on that chip's menu (the one deliberate way in)
        val g = flow()
        g.pick(TemplateId.FREE)
        g.openTyping(); "12".forEach { g.key(it.toString()) }; g.key("×"); "3".forEach { g.key(it.toString()) }
        g.closeTyping()
        assertNull(g.s.chipMenu)
    }

    // ---------------------------------------------------------------- q3-1 mute on the page

    @Test fun muteKeyTogglesTheSameSettingAndIsNotInMore() {
        val f = flow()
        f.pick(TemplateId.TANK)
        f.paste("60x30x35 cm"); f.equals()
        assertFalse(f.settings.value.muted)
        f.toggleMute(); assertTrue(f.settings.value.muted)
        f.toggleMute(); assertFalse(f.settings.value.muted)
        assertFalse(Chrome.more(f.s).any { it.name == "SOUND" })
        assertTrue("with a body: 7 + mute", Chrome.shownCount(f.s) <= 8)
        assertTrue(Chrome.formulaCount(f.s) <= 7)
    }

    // ---------------------------------------------------------------- K7 naming can be cancelled

    @Test fun namingCancelSavesNothing() {
        val f = flow()
        f.pick(TemplateId.TANK); f.paste("60x30x35 cm"); f.saveCard(); f.closeSaved()
        f.startNaming(); assertTrue(f.s.naming)
        f.cancelNaming()
        assertFalse(f.s.naming)
        assertTrue(f.s.customs.isEmpty())
    }

    // ---------------------------------------------------------------- K8 records step one card at a time

    @Test fun recordsStepWrapsThroughEveryCard() {
        val f = flow()
        repeat(4) { f.pick(TemplateId.TANK); f.paste("60x30x${30 + it} cm"); f.saveCard(); f.next() }
        val seen = LinkedHashSet<Long>()
        repeat(4) { f.recordStep(1); seen += f.s.expandedCard!! }
        assertEquals(f.s.cards.map { it.id }.toSet(), seen)
        f.recordStep(1)
        assertEquals(f.s.cards[(f.s.cards.indexOfFirst { it.id == seen.last() } + 1) % 4].id, f.s.expandedCard)
    }

    // ---------------------------------------------------------------- K9 demo block clear of pinned bodies

    @Test fun demoBlockKeepsClearOfARestoredPinnedBody() {
        val h = Vec3(0f, 1.6f, 0f)
        val base = Vec3(0.7f, 0.95f, -1.6f)
        val size = Vec3(0.32f, 0.25f, 0.26f)
        assertEquals(base, PlaceBody.clearOf(base, h, size, emptyList()))
        // a 63 L tank pinned right where the demo would go (QA3 S20-restart)
        val pinned = bodyBox(BodyPose(Vec3(0.65f, 0.9f, -1.55f), 0f), Vec3(0.6f, 0.35f, 0.3f))
        val p = PlaceBody.clearOf(base, h, size, listOf(pinned))
        val box = bodyBox(BodyPose(p, yawFacing(p, h)), size)
        assertFalse(box.intersects(pinned, PlaceBody.OTHERS_GAP_M - 1e-4f))
        assertEquals(base.y, p.y, 1e-6f)
        assertTrue("moved < 1 m", p.distanceTo(base) < 1f)
        assertTrue("slid right, away from the window (x grows)", p.x > base.x)
    }
}
