package io.embodit.spatialcalc.domain.session

import io.embodit.spatialcalc.domain.calc.TemplateId
import io.embodit.spatialcalc.domain.calc.Templates
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.domain.place.BodyPose
import io.embodit.spatialcalc.domain.place.PlaceBody
import io.embodit.spatialcalc.domain.place.bodyBox
import io.embodit.spatialcalc.domain.place.yawFacing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA round 4 (v0.1.3): q4-1 keyboard page budget and K11 demo block in view. */
class Round4Test {

    // ---------------------------------------------------------------- q4-1 keyboard page fits for every job

    /**
     * The v0.1.4 typing page (CalcPage.Typing): window 560 × 560 dp, 28 / 20 dp padding → 504 × 520 dp content.
     * Title row 64, gap 12, middle (weight), gap 12, bottom bar 64 (unit · Paste · ✓ Done, fixed, never scrolled).
     * Left column = 504 − 236 keypad − 16 gap = 252 dp: 56 dp field (slot name inside), slot grid of 2 columns ×
     * 64 dp rows with 12 dp gaps, result line. Every job (both shapes) must fit the middle without scrolling, and
     * the bottom bar is outside the scrolled part in any case.
     */
    @Test fun keyboardPageFitsEveryJobWithDoneVisible() {
        val content = 520
        val middle = content - 64 - 12 - 12 - 64
        val keypad = 6 * 48
        assertTrue("keypad $keypad fits the middle $middle", keypad <= middle)
        for (t in TemplateId.entries) for (round in listOf(false, true)) {
            val n = Templates.slots(t, round).size
            val rows = (n + 1) / 2
            val grid = if (rows == 0) 0 else rows * 64 + (rows - 1) * 12
            // field 56 (slot name inside it) + grid + result (2 lines of 22 sp ≈ 54) + 2 gaps of 10
            val left = 56 + grid + 54 + 2 * 10
            assertTrue("$t round=$round: $n slots → left column $left dp ≤ middle $middle dp", left <= middle)
            assertTrue("$t round=$round: at most 3 grid rows", rows <= 3)
        }
        // bottom bar: segments (4 × ≥ 48) + Paste 104 + ✓ Done 132 + 2 gaps of 16 inside 504
        assertTrue(4 * 48 + 104 + 132 + 2 * 16 <= 504)
    }

    // ---------------------------------------------------------------- K11 demo block keeps inside the default view

    @Test fun demoBlockSlidesClearButStaysInView() {
        val h = Vec3(0f, 1.6f, 0f)
        val view = Vec3(0f, 0f, -1f)
        val base = Vec3(0.7f, 0.95f, -1.6f)
        val size = PlaceBodySize
        assertTrue("the default spot itself is in view", PlaceBody.viewOk(base, h, size, view))
        // a 30 L tank pinned at the demo spot (QA4 K9-live: the old slide went to x = 1.25, half out of view)
        val pinned = bodyBox(BodyPose(Vec3(0.70f, 1.0f, -1.60f), yawFacing(Vec3(0.70f, 1.0f, -1.60f), h)), Vec3(0.4f, 0.3f, 0.25f))
        val p = PlaceBody.clearOf(base, h, size, listOf(pinned), view)
        val box = bodyBox(BodyPose(p, yawFacing(p, h)), size)
        assertFalse("clear of the pinned body", box.intersects(pinned, PlaceBody.OTHERS_GAP_M - 1e-4f))
        assertTrue("inside the view band ±${PlaceBody.DEMO_MAX_DEG}° and beside the window: $p", PlaceBody.viewOk(p, h, size, view))
        assertFalse("not hidden behind the pinned body: $p", PlaceBody.hidesBehind(p, h, size, pinned))
        // two pinned bodies side by side on the right: still a spot in view
        val pinned2 = bodyBox(BodyPose(Vec3(1.15f, 1.0f, -1.45f), 0f), Vec3(0.4f, 0.3f, 0.25f))
        val q = PlaceBody.clearOf(base, h, size, listOf(pinned, pinned2), view)
        val qb = bodyBox(BodyPose(q, yawFacing(q, h)), size)
        assertFalse(qb.intersects(pinned, PlaceBody.OTHERS_GAP_M - 1e-4f) || qb.intersects(pinned2, PlaceBody.OTHERS_GAP_M - 1e-4f))
        assertTrue("in view with two pinned: $q", PlaceBody.viewOk(q, h, size, view))
        assertFalse(PlaceBody.hidesBehind(q, h, size, pinned) || PlaceBody.hidesBehind(q, h, size, pinned2))
        // QA4 emulator case: tank pinned exactly on the demo spot → the demo goes to the mirror spot on the left
        val r = PlaceBody.clearOf(base, h, size, listOf(bodyBox(BodyPose(base, yawFacing(base, h)), Vec3(0.4f, 0.25f, 0.3f))), view)
        assertTrue("mirror side (x < 0): $r", r.x < 0f)
    }

    private companion object { val PlaceBodySize = Vec3(0.32f, 0.25f, 0.26f) }
}
