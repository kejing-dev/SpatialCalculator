package io.embodit.spatialcalc.platform

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.spatialcalc.domain.math.Quat4
import io.embodit.spatialcalc.domain.math.Vec3
import kotlin.math.asin

/** Head pose in Stage space. */
data class HeadPose(val position: Vec3, val rotation: Quat4) {
    val forward: Vec3 get() = rotation.rotate(Vec3.AHEAD)
    val right: Vec3 get() = rotation.rotate(Vec3.RIGHT)
    val pitchDeg: Float get() = Math.toDegrees(asin(forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
}

/**
 * HMD pose (HMDTrackingProvider, 空间尺 HeadTracker). Uses (headpose-audit.md): placing the window open request
 * (level gate), the system pointer's line of sight origin (a tap point is all the app gets), the result plate's yaw
 * (D10), head clearance / sight cone of automatic drops (D14 / D20), and headSample buttons (D29). Never to aim, place
 * or select (D1). A pose at the origin with the identity rotation is the tracker's "not yet" and counts as invalid.
 */
class HeadTracker {
    private var provider: HMDTrackingProvider? = null
    private var last: HeadPose? = null
    private var lastValidMs = 0L
    private var validRun = 0

    @Volatile var forceLost = false

    fun start() {
        if (provider != null) return
        val p = HMDTrackingProvider()
        val result = runCatching { p.start() }
        AppLog.i("TRACK hmd start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
        lastValidMs = SystemClock.elapsedRealtime()
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        last = null
        validRun = 0
    }

    val noPermission: Boolean get() = runCatching { provider?.supportState == DataProvider.SupportState.WITHOUT_PERMISSION }.getOrDefault(false)

    fun lost(nowMs: Long): Boolean = forceLost || (provider != null && last != null && nowMs - lastValidMs > LOST_AFTER_MS)

    /** Valid for three frames in a row (container.lifecycle「连续 3 帧非零」). */
    val steady: Boolean get() = validRun >= 3 && !forceLost

    fun pose(): HeadPose? {
        val p = provider ?: return last
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return last
        val q = pose.rotation
        val pos = pose.position
        if (pos.x == 0f && pos.y == 0f && pos.z == 0f && q.w == 1f && q.x == 0f && q.y == 0f && q.z == 0f) { validRun = 0; return last }
        val sample = HeadPose(Vec3(pos.x, pos.y, pos.z), Quat4(q.x, q.y, q.z, q.w).normalized())
        if (!sample.position.isFinite()) return last
        last = sample
        validRun++
        lastValidMs = SystemClock.elapsedRealtime()
        return sample
    }

    private companion object {
        const val LOST_AFTER_MS = 1500L
    }
}
