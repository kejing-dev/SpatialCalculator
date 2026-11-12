package io.embodit.spatialcalc.domain.place

import io.embodit.spatialcalc.domain.math.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Axis-aligned box in Stage metres. */
data class Aabb(val min: Vec3, val max: Vec3) {
    val center get() = (min + max) * 0.5f
    val size get() = max - min

    fun intersects(o: Aabb, margin: Float = 0f) =
        min.x < o.max.x + margin && max.x > o.min.x - margin && min.y < o.max.y + margin && max.y > o.min.y - margin &&
            min.z < o.max.z + margin && max.z > o.min.z - margin

    fun distanceTo(p: Vec3): Float {
        val dx = max(max(min.x - p.x, 0f), p.x - max.x)
        val dy = max(max(min.y - p.y, 0f), p.y - max.y)
        val dz = max(max(min.z - p.z, 0f), p.z - max.z)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun contains(p: Vec3) = p.x in min.x..max.x && p.y in min.y..max.y && p.z in min.z..max.z

    companion object {
        fun of(points: List<Vec3>): Aabb? {
            if (points.isEmpty()) return null
            var lo = points[0]; var hi = points[0]
            for (p in points) {
                lo = Vec3(min(lo.x, p.x), min(lo.y, p.y), min(lo.z, p.z))
                hi = Vec3(max(hi.x, p.x), max(hi.y, p.y), max(hi.z, p.z))
            }
            return Aabb(lo, hi)
        }
    }
}

/** A result body's pose: [position] is the centre of its bottom face, [yaw] turns its local +Z towards the user. */
data class BodyPose(val position: Vec3, val yaw: Float)

/** The "head → window" sight cone (spatialLayout.clearance ④): ±15° around the window direction, out to the window + 0.3 m. */
data class SightCone(val head: Vec3, val window: Vec3, val halfAngleDeg: Float = 15f, val extraM: Float = 0.3f) {
    fun containsPoint(p: Vec3): Boolean {
        val toW = window - head
        val toP = p - head
        val lw = toW.length; val lp = toP.length
        if (lw < 1e-4f || lp < 1e-4f) return false
        if (lp > lw + extraM) return false
        val c = (toW.dot(toP) / (lw * lp)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c).toDouble()) < halfAngleDeg
    }

    /** True when any of the box's corners or its centre lies in the cone. */
    fun touches(box: Aabb): Boolean {
        val c = corners(box) + box.center
        return c.any { containsPoint(it) }
    }

    /** Angle of [p] off the window direction, degrees. */
    fun angleOff(p: Vec3): Float {
        val toW = window - head; val toP = p - head
        val c = (toW.dot(toP) / (toW.length * toP.length)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c).toDouble()).toFloat()
    }
}

fun corners(b: Aabb): List<Vec3> {
    val out = ArrayList<Vec3>(8)
    for (x in listOf(b.min.x, b.max.x)) for (y in listOf(b.min.y, b.max.y)) for (z in listOf(b.min.z, b.max.z)) out += Vec3(x, y, z)
    return out
}

/** Horizontal unit vector from [from] to [to] (falls back to ahead). */
fun flatDir(from: Vec3, to: Vec3): Vec3 {
    val d = Vec3(to.x - from.x, 0f, to.z - from.z)
    return if (d.length < 1e-4f) Vec3(0f, 0f, -1f) else d.normalized()
}

/** Right-hand axis of a horizontal direction [u] ("user → object" → its right). */
fun rightOf(u: Vec3) = Vec3(-u.z, 0f, u.x)

/** Yaw that turns local +Z from [p] towards [head] (about the vertical only, D9 / D10). */
fun yawFacing(p: Vec3, head: Vec3): Float = atan2(head.x - p.x, head.z - p.z)

/** World AABB of a body of [size] (x width, y height, z depth) standing at [pose] (bottom centre). */
fun bodyBox(pose: BodyPose, size: Vec3): Aabb {
    val c = cos(pose.yaw); val s = sin(pose.yaw)
    val hx = size.x / 2f; val hz = size.z / 2f
    val ex = abs(c) * hx + abs(s) * hz
    val ez = abs(s) * hx + abs(c) * hz
    val p = pose.position
    return Aabb(Vec3(p.x - ex, p.y, p.z - ez), Vec3(p.x + ex, p.y + size.y, p.z + ez))
}

enum class PlaceOutcome { OK, ASK_USER }

data class PlaceResult(val pose: BodyPose, val outcome: PlaceOutcome, val rounds: Int, val log: String)

/**
 * The automatic drop point of a free-standing result body (spatialLayout.resultPlacement + clearance):
 * ① bottom on the support height → ② small (every side < 1 m): 0.1 m to the right of the measured object;
 * big: out towards the user, or to the right when that would end inside the object → ③ head clearance (0.6 m; 0.8 m
 * for big) by sliding along the right axis → ④ out of the head → window sight cone (+0.1 m) along the same axis.
 * At most two rounds; still in the cone or in the object → ask the user to point (S09).
 */
object PlaceBody {
    const val GAP_M = 0.1f
    const val CLEAR_SMALL_M = 0.6f
    const val CLEAR_BIG_M = 0.8f
    const val BIG_SIDE_M = 1.0f

    fun place(head: Vec3, window: Vec3?, objectBox: Aabb, supportY: Float, size: Vec3, others: List<Aabb> = emptyList()): PlaceResult {
        val big = size.x >= BIG_SIDE_M || size.z >= BIG_SIDE_M || size.y >= BIG_SIDE_M
        val clear = if (big) CLEAR_BIG_M else CLEAR_SMALL_M
        val oc = objectBox.center
        val u = flatDir(head, Vec3(oc.x, head.y, oc.z))
        val r = rightOf(u)
        val objHalfR = halfExtentAlong(objectBox, r)
        val objHalfU = halfExtentAlong(objectBox, u)
        val log = StringBuilder()
        // ② first guess
        var p = if (!big) {
            Vec3(oc.x, supportY, oc.z) + r * (objHalfR + GAP_M + size.x / 2f)
        } else {
            Vec3(oc.x, supportY, oc.z) - u * (objHalfU + GAP_M + size.z / 2f)
        }
        var yaw = yawFacing(p, head)
        if (big && (bodyBox(BodyPose(p, yaw), size).distanceTo(head) < clear || bodyBox(BodyPose(p, yaw), size).intersects(objectBox))) {
            p = Vec3(oc.x, supportY, oc.z) + r * (objHalfR + GAP_M + max(size.x, size.z) / 2f)
            yaw = yawFacing(p, head)
            log.append("big->right ")
        }
        val cone = window?.let { SightCone(head, it) }
        var rounds = 0
        while (rounds < 2) {
            rounds++
            var box = bodyBox(BodyPose(p, yaw), size)
            // ② bis: turned to face the user the body can poke into the object's box: slide it out along the right axis
            var out = 0
            while (box.intersects(objectBox, -0.005f) && out < 40) { p += r * 0.05f; yaw = yawFacing(p, head); box = bodyBox(BodyPose(p, yaw), size); out++ }
            if (out > 0) log.append("out+${out * 5}cm ")
            // ② ter (q1-9, D17): bodies already standing (pinned cards) are obstacles too: keep sliding right past them
            var past = 0
            while (others.any { box.intersects(it, OTHERS_GAP_M) } && past < 80) { p += r * 0.05f; yaw = yawFacing(p, head); box = bodyBox(BodyPose(p, yaw), size); past++ }
            if (past > 0) log.append("others+${past * 5}cm ")
            // ③ clearance: slide along the right axis until the nearest face is far enough from the head
            var guard = 0
            while (box.distanceTo(head) < clear && guard < 60) {
                p += r * 0.05f; yaw = yawFacing(p, head); box = bodyBox(BodyPose(p, yaw), size); guard++
            }
            if (guard > 0) log.append("clear+${guard * 5}cm ")
            // ④ out of the sight cone, away from the window side
            if (cone != null && cone.touches(box)) {
                val side = if (r.dot(window - p) > 0f) -1f else 1f
                var n = 0
                while (cone.touches(bodyBox(BodyPose(p, yaw), size)) && n < 80) { p += r * (0.05f * side); yaw = yawFacing(p, head); n++ }
                p += r * (GAP_M * side); yaw = yawFacing(p, head)
                log.append("cone${if (side > 0) "+" else "-"}${n * 5 + 10}cm ")
            }
            box = bodyBox(BodyPose(p, yaw), size)
            val inCone = cone?.touches(box) == true
            val inObject = box.intersects(objectBox, -0.005f) || others.any { box.intersects(it, 0f) }
            if (!inCone && !inObject && box.distanceTo(head) >= clear - 1e-3f) {
                return PlaceResult(BodyPose(p, yaw), PlaceOutcome.OK, rounds, log.toString().trim())
            }
        }
        return PlaceResult(BodyPose(p, yaw), PlaceOutcome.ASK_USER, rounds, (log.toString() + "ask").trim())
    }

    /** [带到面前]: 1.2 m ahead of the head sample on the support height, then the same clearance / cone rules. */
    fun bringToFront(head: Vec3, headYawForward: Vec3, window: Vec3?, supportY: Float, size: Vec3, others: List<Aabb> = emptyList()): PlaceResult {
        val f = Vec3(headYawForward.x, 0f, headYawForward.z).normalized()
        val spot = head + f * 1.2f
        val fake = Aabb(Vec3(spot.x - 0.01f, supportY, spot.z - 0.01f), Vec3(spot.x + 0.01f, supportY + 0.01f, spot.z + 0.01f))
        // treat the spot as a tiny "object" whose right side we land on: keeps one placement chain for every case
        val p0 = Vec3(spot.x, supportY, spot.z)
        val cone = window?.let { SightCone(head, it) }
        val box0 = bodyBox(BodyPose(p0, yawFacing(p0, head)), size)
        val clear = if (size.x >= BIG_SIDE_M || size.z >= BIG_SIDE_M) CLEAR_BIG_M else CLEAR_SMALL_M
        if ((cone == null || !cone.touches(box0)) && box0.distanceTo(head) >= clear && others.none { box0.intersects(it, OTHERS_GAP_M) }) return PlaceResult(BodyPose(p0, yawFacing(p0, head)), PlaceOutcome.OK, 0, "front")
        return place(head, window, fake, supportY, size, others)
    }

    /**
     * K9 (QA3): the 10 L demo block on the job page must not stand inside a pinned body that came back after a restart.
     * From [base] it slides sideways (right of [head] → [base], up to 2 m; only if that fails, left) in 5 cm steps until its box keeps
     * [OTHERS_GAP_M] from every one of [others]; the height stays.
     */
    fun clearOf(base: Vec3, head: Vec3, size: Vec3, others: List<Aabb>): Vec3 {
        fun free(p: Vec3) = others.none { bodyBox(BodyPose(p, yawFacing(p, head)), size).intersects(it, OTHERS_GAP_M) }
        if (free(base)) return base
        val r = rightOf(flatDir(head, base))
        // right first (away from the window the demo stands beside), only then left, so it never slides under the window
        for (k in 1..40) { val right = base + r * (0.05f * k); if (free(right)) return right }
        for (k in 1..40) { val left = base - r * (0.05f * k); if (free(left)) return left }
        return base
    }

    const val OTHERS_GAP_M = 0.10f
    /** The fit box swings at least this far (about the head, horizontally) off the head → window direction (q1-4). */
    const val FIT_SIDE_DEG = 35f

    /**
     * q1-4 (D41 / D8): the fit box (and its three readings, ≤ 0.3 m beside it) must not stand under / behind the window
     * and the system Toolbar below it. If [p] is closer than [FIT_SIDE_DEG] to the head → window direction it is turned
     * about the head (same distance, same height) out to that side (the right when dead ahead).
     */
    fun besideWindow(head: Vec3, window: Vec3?, p: Vec3): Vec3 {
        if (window == null) return p
        val w = flatDir(head, window)
        val to = Vec3(p.x - head.x, 0f, p.z - head.z)
        val dist = to.length
        if (dist < 1e-3f) return p
        val d = to * (1f / dist)
        val off = Math.toDegrees(acos(w.dot(d).coerceIn(-1f, 1f).toDouble())).toFloat()
        if (off >= FIT_SIDE_DEG) return p
        val side = if (rightOf(w).dot(d) >= 0f) 1f else -1f
        val a = Math.toRadians((FIT_SIDE_DEG * side).toDouble())
        // turn w by a towards rightOf(w): w·cos a + right·sin a
        val r = rightOf(w)
        val nd = w * cos(a).toFloat() + r * sin(a).toFloat()
        return Vec3(head.x + nd.x * dist, p.y, head.z + nd.z * dist)
    }

    fun halfExtentAlong(b: Aabb, dir: Vec3): Float {
        val s = b.size * 0.5f
        return abs(dir.x) * s.x + abs(dir.y) * s.y + abs(dir.z) * s.z
    }
}

/**
 * The result plate (panelGeometryCheck): it stands 0.05 m outside the body's top edge on the side facing the user,
 * its bottom level with the top face, turned about the vertical only. Returns the plate centre and its yaw.
 */
object ResultPlate {
    const val OUT_M = 0.05f
    const val DP_M = 0.0008f
    const val WIDTH_DP = 304f
    const val HEIGHT_DP = 96f
    const val MAX_DOWN_DEG = 39.5f

    fun pose(body: BodyPose, size: Vec3, head: Vec3): Pair<Vec3, Float> {
        val box = bodyBox(body, size)
        val toUser = flatDir(box.center, head)
        val reach = PlaceBody.halfExtentAlong(box, toUser)
        val h = HEIGHT_DP * DP_M
        var c = Vec3(box.center.x, box.max.y + h / 2f + 0.01f, box.center.z) + toUser * (reach + OUT_M)
        // D34: a reading on a low body must not sit more than 40° below the eye: lift it (never lower it)
        val flat = sqrt((c.x - head.x) * (c.x - head.x) + (c.z - head.z) * (c.z - head.z))
        val minY = head.y - flat * kotlin.math.tan(Math.toRadians(MAX_DOWN_DEG.toDouble())).toFloat()
        if (c.y < minY) c = Vec3(c.x, minY, c.z)
        return c to yawFacing(c, head)
    }

    /** The four corners of a plate at [center] with [yaw]. */
    fun corners(center: Vec3, yaw: Float): List<Vec3> {
        val w = WIDTH_DP * DP_M / 2f; val h = HEIGHT_DP * DP_M / 2f
        val right = Vec3(cos(yaw), 0f, -sin(yaw))
        return listOf(center + right * w + Vec3.UP * h, center - right * w + Vec3.UP * h, center + right * w - Vec3.UP * h, center - right * w - Vec3.UP * h)
    }

    /** Pitch of the plate as seen from [eye], degrees below the horizontal (D34: ≤ 40). */
    fun downAngle(center: Vec3, eye: Vec3): Float {
        val d = center - eye
        val flat = sqrt(d.x * d.x + d.z * d.z)
        return Math.toDegrees(atan2(-d.y.toDouble(), flat.toDouble())).toFloat()
    }
}

/** Segment / box test (eye → plate corner must not pass through the body). */
fun segmentHitsBox(a: Vec3, b: Vec3, box: Aabb): Boolean {
    var t0 = 0f; var t1 = 1f
    val d = b - a
    val lo = floatArrayOf(box.min.x, box.min.y, box.min.z); val hi = floatArrayOf(box.max.x, box.max.y, box.max.z)
    val o = floatArrayOf(a.x, a.y, a.z); val dd = floatArrayOf(d.x, d.y, d.z)
    for (i in 0..2) {
        if (abs(dd[i]) < 1e-9f) {
            if (o[i] < lo[i] || o[i] > hi[i]) return false
        } else {
            var ta = (lo[i] - o[i]) / dd[i]; var tb = (hi[i] - o[i]) / dd[i]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = max(t0, ta); t1 = min(t1, tb)
            if (t0 > t1) return false
        }
    }
    return true
}
