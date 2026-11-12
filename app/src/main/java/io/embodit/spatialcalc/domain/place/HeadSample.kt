package io.embodit.spatialcalc.domain.place

import io.embodit.spatialcalc.domain.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2

/**
 * headSample (D29): a button that places something "in front of you" must not use the head pose of the moment it was
 * pressed (the user is looking at the button). After the press the app waits until the head has held still for
 * [dwellMs] (yaw within [toleranceDeg]) and takes that pose: position + yaw only, pitch / roll dropped (D9).
 * Pure Kotlin; the caller feeds every frame.
 */
class HeadSampler(val dwellMs: Long = 300L, val toleranceDeg: Float = 2f, val timeoutMs: Long = 6000L) {
    data class Sample(val position: Vec3, val yawDeg: Float, val forward: Vec3)

    private var startedMs = -1L
    private var stillSinceMs = -1L
    private var refYaw = 0f
    var pending = false
        private set

    fun start(now: Long) { pending = true; startedMs = now; stillSinceMs = -1L }

    fun cancel() { pending = false }

    /** Returns the sample once the head has been still long enough (or the timeout passed), else null. */
    fun step(now: Long, position: Vec3, forward: Vec3): Sample? {
        if (!pending) return null
        val yaw = yawDeg(forward)
        if (stillSinceMs < 0L || abs(angleDiff(yaw, refYaw)) > toleranceDeg) { stillSinceMs = now; refYaw = yaw }
        val timedOut = now - startedMs > timeoutMs
        // the first dwell window after the press does not count: the press itself was made looking at the button
        if ((now - stillSinceMs >= dwellMs && now - startedMs >= dwellMs) || timedOut) {
            pending = false
            val f = Vec3(forward.x, 0f, forward.z).normalized()
            return Sample(position, yaw, f)
        }
        return null
    }

    companion object {
        fun yawDeg(forward: Vec3): Float = Math.toDegrees(atan2(-forward.x.toDouble(), -forward.z.toDouble())).toFloat()
        fun angleDiff(a: Float, b: Float): Float { var d = a - b; while (d > 180f) d -= 360f; while (d < -180f) d += 360f; return d }
    }
}
