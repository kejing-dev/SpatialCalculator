package io.embodit.spatialcalc.ui

import io.embodit.spatialcalc.ui.stage.Hit
import io.embodit.spatialcalc.ui.stage.Targets
import io.embodit.spatialcalc.ui.stage.input.DragTotal
import io.embodit.spatialcalc.ui.stage.input.Offset3M
import io.embodit.spatialcalc.ui.stage.input.parseLoggedPath
import io.embodit.spatialcalc.ui.stage.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** board-input 命中回放: real hit paths (the mesh first, then its parents) resolve to the line. */
class HitReplayTest {
    @Test fun everyPartOfTheLineResolves() {
        for (path in listOf(
            "line", "line:tube < line < line:holder < stageRoot", "line:tick < line:tickAt < line < line:holder",
            "line:capR < line < line:holder", "line:break0 < line:break < line", "line:plate < stageRoot",
        )) assertEquals(path, Hit.Line, resolvePath(parseLoggedPath(path), Targets::resolve))
        assertNull(resolvePath(parseLoggedPath("holder:hud < stageRoot"), Targets::resolve))
        assertNull(resolvePath(parseLoggedPath("<none>"), Targets::resolve))
    }

    /** D53: SDK 6.1.9 sends per-event deltas; 60 × 2 mm must add up to 12 cm. */
    @Test fun dragDeltasAddUp() {
        val t = DragTotal()
        var last = Offset3M(0f, 0f, 0f)
        repeat(60) { last = t.add(Offset3M(0.002f, 0f, 0f)) }
        assertEquals(0.12f, last.x, 1e-5f)
        t.reset()
        assertEquals(0.0f, t.add(Offset3M(0f, 0f, 0f)).x, 0f)
    }
}
