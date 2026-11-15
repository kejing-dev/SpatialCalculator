package io.embodit.spatialcalc.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.domain.place.LevelGate
import io.embodit.spatialcalc.domain.place.LinePlace
import io.embodit.spatialcalc.domain.place.WindowPose

/**
 * Opens / reopens the main Planar window from the Stage (native-window-move.md §3.3, §3.5, §7 item 9). Every open first
 * asks the [LevelGate]; the Stage scene feeds it the head pitch every frame and shows「平视前方」while it waits.
 * Reopen = close, wait for ON_DESTROY, open again: the system then puts the window ahead of the current head (it cannot
 * be placed by code). [estimate] is where the app believes the window is: LinePlace.DISTANCE_M (≈ 0.82 m) ahead of the head, level, at open time
 * (the system default, LinePlace.window) — the length line starts beside it and stays out of its sight cone.
 */
class WindowCtl {
    val gate = LevelGate()
    var levelHint by mutableStateOf(false)
        private set
    var panelState by mutableStateOf("never")
        private set
    val open: Boolean get() = panelState in setOf("ON_CREATE", "ON_START", "ON_RESUME", "ON_PAUSE")

    /** Set by [exitApp]: the Stage HUD stays empty while both containers close (q1-10: it flashed「Open panel」). */
    var exiting by mutableStateOf(false)

    @Volatile var estimate: WindowPose? = null
        private set

    /** Metres per dp of the window, reported by the window's own LocalPhysicalLengthConverter (needsCheck N3). */
    @Volatile var mPerDp: Float = LinePlace.DEFAULT_M_PER_DP

    /** Bumped on every open: the Stage re-places a line the user has not moved. */
    @Volatile var opens = 0
        private set

    /** Closes the Stage (set by the Stage screen; exit closes both containers, D44). */
    var closeStage: (() -> Unit)? = null

    private var openFn: (() -> Unit)? = null
    private var closeFn: (() -> Unit)? = null
    private var recallPending = false
    private var lastStepMs = 0L
    private var lastHead: Pair<Vec3, Vec3>? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(open: () -> Unit, close: () -> Unit) { openFn = open; closeFn = close }

    fun detach() {
        openFn = null; closeFn = null
        gate.cancel()
        main.removeCallbacks(watchdog)
    }

    fun request(why: String) {
        gate.ask(why, SystemClock.elapsedRealtime())
        AppLog.i("WINDOW ask why=$why state=$panelState")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3000)
    }

    /** Settings [重开窗口]: close, wait for ON_DESTROY, then a level-gated open ahead of the head. */
    fun recall() {
        if (!open) request("recall")
        else { recallPending = true; AppLog.i("WINDOW recall close"); closeFn?.invoke() }
    }

    fun close() { runCatching { closeFn?.invoke() } }

    fun onPanelLifecycle(event: String) {
        panelState = event
        AppLog.i("WINDOW lifecycle $event")
        if (recallPending && event == "ON_DESTROY") { recallPending = false; request("recall") }
    }

    /** Every Stage frame: head position + forward (null when not valid), and the pitch in degrees. */
    fun stepFrame(head: Pair<Vec3, Vec3>?, pitchDeg: Float?) {
        val now = SystemClock.elapsedRealtime()
        lastStepMs = now
        if (head != null) lastHead = head
        if (!gate.waiting) return
        levelHint = gate.hintWanted(pitchDeg)
        gate.step(now, pitchDeg)?.let { why -> levelHint = false; openNow(why, pitchDeg) }
    }

    private fun openNow(why: String, pitch: Float?) {
        main.removeCallbacks(watchdog)
        lastHead?.let { (p, f) -> estimate = LinePlace.window(p, f, mPerDp) }
        opens++
        AppLog.i("WINDOW open why=$why pitch=$pitch estimate=${estimate?.center}")
        openFn?.invoke()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gate.waiting) return
            val now = SystemClock.elapsedRealtime()
            val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000 else now - lastStepMs > 3000
            if (stalled) { val why = gate.pending!!; gate.cancel(); levelHint = false; openNow("$why/noframes", null) } else main.postDelayed(this, 3000)
        }
    }
}
