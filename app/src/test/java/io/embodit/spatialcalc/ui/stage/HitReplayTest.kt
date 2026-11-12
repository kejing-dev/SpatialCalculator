package io.embodit.spatialcalc.ui.stage

import io.embodit.spatialcalc.ui.stage.input.parseLoggedPath
import io.embodit.spatialcalc.ui.stage.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * board-input 命中回放: every kind of hit — the backstop slabs, the result body's collider and any of its visible child
 * parts, the fit-box face handles and their visible spheres, pinned bodies — resolves to the right target through the
 * same pure parser the Stage uses (Names.resolve); names come only from Names.
 */
class HitReplayTest {
    private fun r(path: String) = resolvePath(parseLoggedPath(path), Names::resolve)

    @Test fun everyHitResolves() {
        assertEquals(Hit.Backstop, r("${Names.BACKSTOP}:3 < ${Names.BACKSTOP} < stageRoot"))
        assertEquals(Hit.Body, r(Names.BODY))
        assertEquals(Hit.Body, r("fill < vis < ${Names.BODY} < stageRoot"))
        assertEquals(Hit.Body, r("edge < vis < ${Names.BODY} < stageRoot"))
        assertEquals(Hit.Body, r("box < vis < ${Names.BODY} < stageRoot"))
        assertEquals(Hit.Handle(0, 1), r("hv < ${Names.handle(0, 1)} < fitbox < stageRoot"))
        assertEquals(Hit.Handle(2, -1), r(Names.handle(2, -1)))
        assertEquals(Hit.Pinned(1731300000000L), r("fill < ${Names.pinned(1731300000000L)} < stageRoot"))
    }

    @Test fun badNamesAreNotTargets() {
        assertNull(r("fh:7:p"))
        assertNull(r("fh:1:x"))
        assertNull(r("pinned:abc"))
        assertNull(r("tape < tapes < stageRoot"))
        assertNull(r("<none>"))
    }

    /** Paths as the emulator logged them (TAP src=… path=…); headset paths go here once a headset run exists. */
    @Test fun replayLoggedPaths() {
        listOf("backstop:3 < backstop < stageRoot" to Hit.Backstop, "fill < vis < body:active < stageRoot" to Hit.Body).forEach { (p, want) -> assertEquals(want, r(p)) }
    }
}
