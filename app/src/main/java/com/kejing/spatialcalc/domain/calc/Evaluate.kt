package com.kejing.spatialcalc.domain.calc

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A token of a free formula: a number (measured / typed) or an operator ( + − × ÷ ( ) √ x² π ). */
sealed class Token {
    data class Num(val q: Q, val source: Source, val tapeId: Int? = null) : Token()
    data class Op(val sym: String) : Token()

    companion object {
        val OPS = listOf("+", "−", "×", "÷", "(", ")", "√", "x²", "π")
    }
}

/** Integer count with the range it takes over the tolerance interval ("42 块（41–43）"). */
data class CountRange(val n: Int, val lo: Int, val hi: Int)

/** What the 3D result looks like (resultVisualRules: by template, not by display unit). Sizes in metres. */
sealed class BodySpec {
    abstract val maxSide: Double

    /** Box / water block: x = width (along the user's right), y = height, z = depth. */
    data class Box(val x: Double, val y: Double, val z: Double, val water: Boolean) : BodySpec() {
        override val maxSide get() = maxOf(x, y, z)
    }

    data class Cylinder(val diameter: Double, val height: Double, val water: Boolean) : BodySpec() {
        override val maxSide get() = max(diameter, height)
    }

    /** A wall of tiles: [cols] × [rows] at pitch (tile + gap); the last column / row may be partial (cutAmber). */
    data class Tiles(val width: Double, val height: Double, val tileW: Double, val tileH: Double, val gap: Double) : BodySpec() {
        override val maxSide get() = max(width, height)
        val pitchW get() = tileW + gap
        val pitchH get() = tileH + gap
        val cols get() = ceil(width / pitchW - 1e-9).toInt()
        val rows get() = ceil(height / pitchH - 1e-9).toInt()
        val partialCol get() = width / pitchW - floor(width / pitchW + 1e-9) > 1e-6
        val partialRow get() = height / pitchH - floor(height / pitchH + 1e-9) > 1e-6
    }

    /** One can's band laid along the wall from the bottom (height [band]), the rest of the wall outlined. */
    data class Paint(val width: Double, val height: Double, val band: Double) : BodySpec() {
        override val maxSide get() = max(width, height)
    }

    /** The cell and the boxes packed in it ([box] already in the chosen orientation: x width, y height, z depth). */
    data class Ghosts(val cellX: Double, val cellY: Double, val cellZ: Double, val boxX: Double, val boxY: Double, val boxZ: Double, val nx: Int, val ny: Int, val nz: Int) : BodySpec() {
        override val maxSide get() = maxOf(cellX, cellY, cellZ)
        val fits get() = nx * ny * nz > 0
    }

    /** Rug on the floor: x × z, plus the nearest standard size outline. */
    data class Rug(val x: Double, val z: Double, val specX: Double, val specZ: Double) : BodySpec() {
        override val maxSide get() = maxOf(x, z, specX, specZ)
    }

    data class Tape(val length: Double) : BodySpec() {
        override val maxSide get() = length
    }

    /** A thin slab x × z (free formula m² results). */
    data class Slab(val x: Double, val z: Double) : BodySpec() {
        override val maxSide get() = max(x, z)
    }

    val oversize: Boolean get() = maxSide > OVERSIZE_M

    companion object {
        const val OVERSIZE_M = 2.0
    }
}

/** The best packing of a fit job (calcModel fit: six orientations, max count). */
data class FitPlan(val nx: Int, val ny: Int, val nz: Int, val box: Triple<Double, Double, Double>, val gapM: Double, val overM: Double, val overAxis: Char, val perm: Int = 0) {
    val count get() = nx * ny * nz
}

data class CalcResult(
    val q: Q? = null,
    val count: CountRange? = null,
    /** tile: tiles to buy extra; rug: nearest spec index; fit: spare gap (m) or how much too big (m). */
    val extra: Int? = null,
    val fit: FitPlan? = null,
    val rugSpec: Pair<Int, Int>? = null,
    val partialEdge: Boolean = false,
    val body: BodySpec? = null,
    val error: CalcError? = null,
    val errorDetail: String = "",
) {
    val ok: Boolean get() = error == null && (q != null || count != null)
}

object Evaluate {
    /** True when every slot of a template has a value. */
    fun full(slots: List<SlotValue>): Boolean = slots.isNotEmpty() && slots.all { it.q != null }

    fun template(t: TemplateId, round: Boolean, slots: List<SlotValue>, fitPerm: Int? = null): CalcResult {
        if (!full(slots)) return CalcResult(error = CalcError.EMPTY)
        val v = slots.map { it.q!! }
        return try {
            when (t) {
                TemplateId.TANK -> tank(round, v)
                TemplateId.TILE -> tile(v)
                TemplateId.PAINT -> paint(v)
                TemplateId.FIT -> fit(v, fitPerm)
                TemplateId.RUG -> rug(v)
                else -> CalcResult(error = CalcError.EMPTY)
            }
        } catch (e: CalcException) {
            CalcResult(error = e.error, errorDetail = e.detail)
        }
    }

    private fun tank(round: Boolean, v: List<Q>): CalcResult = if (round) {
        val r = v[0].times(0.5)
        val q = Q.PI * r.squared() * v[1]
        CalcResult(q = q, body = BodySpec.Cylinder(v[0].v, v[1].v, water = true))
    } else {
        val q = v[0] * v[1] * v[2]
        CalcResult(q = q, body = BodySpec.Box(v[0].v, v[2].v, v[1].v, water = true))
    }

    fun tileCount(w: Double, h: Double, pw: Double, ph: Double): Int =
        if (w <= 0 || h <= 0) 0 else (ceil(w / pw - 1e-9) * ceil(h / ph - 1e-9)).toInt()

    private fun tile(v: List<Q>): CalcResult {
        val (w, h, tw, th, g) = v
        if (tw.v <= 0 || th.v <= 0) throw CalcException(CalcError.DIV_ZERO)
        val pw = tw.v + g.v
        val ph = th.v + g.v
        val n = tileCount(w.v, h.v, pw, ph)
        val lo = tileCount(w.v - w.tol, h.v - h.tol, pw, ph)
        val hi = tileCount(w.v + w.tol, h.v + h.tol, pw, ph)
        val spec = BodySpec.Tiles(w.v, h.v, tw.v, th.v, g.v)
        return CalcResult(
            q = w * h, count = CountRange(n, min(lo, n), max(hi, n)), extra = ceil(n * 0.1).toInt().coerceAtLeast(1),
            partialEdge = spec.partialCol || spec.partialRow, body = spec,
        )
    }

    fun cans(w: Double, h: Double, coats: Double, cover: Double): Int = if (w <= 0 || h <= 0) 0 else ceil(w * h * coats / cover - 1e-9).toInt()

    private fun paint(v: List<Q>): CalcResult {
        val (w, h, cover, coats) = v
        if (cover.v <= 0 || coats.v <= 0) throw CalcException(CalcError.DIV_ZERO)
        val n = cans(w.v, h.v, coats.v, cover.v)
        val lo = cans(w.v - w.tol, h.v - h.tol, coats.v, cover.v)
        val hi = cans(w.v + w.tol, h.v + h.tol, coats.v, cover.v)
        val band = min(h.v, cover.v / coats.v / w.v)
        return CalcResult(q = w * h, count = CountRange(n, min(lo, n), max(hi, n)), body = BodySpec.Paint(w.v, h.v, band))
    }

    /** Six orientations, the one that fits the most (ties: the least wasted gap). */
    fun bestFit(cw: Double, cd: Double, ch: Double, l: Double, w: Double, h: Double, only: Int? = null): FitPlan {
        val dims = listOf(l, w, h)
        val perms = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        var best: FitPlan? = null
        for ((pi, p) in perms.withIndex()) {
            if (only != null && pi != only) continue
            // box x along the cell width, z along the depth, y up
            val bx = dims[p[0]]; val bz = dims[p[1]]; val by = dims[p[2]]
            val nx = floor(cw / bx + 1e-9).toInt(); val nz = floor(cd / bz + 1e-9).toInt(); val ny = floor(ch / by + 1e-9).toInt()
            val gap = listOf(cw - nx * bx, cd - nz * bz, ch - ny * by).filter { it >= 0 }.minOrNull() ?: 0.0
            val overs = listOf(Triple(bx - cw, 'w', 0), Triple(bz - cd, 'd', 1), Triple(by - ch, 'h', 2)).filter { it.first > 0 }
            val over = overs.maxByOrNull { it.first }
            val plan = FitPlan(nx, ny, nz, Triple(bx, by, bz), gap, over?.first ?: 0.0, over?.second ?: ' ', pi)
            val b = best
            best = when {
                b == null -> plan
                plan.count > b.count -> plan
                plan.count == b.count && plan.count > 0 && plan.gapM < b.gapM - 1e-9 -> plan
                plan.count == 0 && b.count == 0 && plan.overM < b.overM - 1e-9 -> plan
                else -> b
            }
        }
        return best!!
    }

    private fun fit(v: List<Q>, perm: Int?): CalcResult {
        val (cw, cd, ch, l, w) = v
        val h = v[5]
        listOf(l, w, h).forEach { if (it.v < 0.01) throw CalcException(CalcError.BOX_THIN) }
        val plan = bestFit(cw.v, cd.v, ch.v, l.v, w.v, h.v, perm)
        // range: a tighter cell and bigger boxes (lo) vs a roomier cell and smaller boxes (hi)
        val lo = bestFit(cw.v - cw.tol, cd.v - cd.tol, ch.v - ch.tol, l.v + l.tol, w.v + w.tol, h.v + h.tol, perm).count
        val hi = bestFit(cw.v + cw.tol, cd.v + cd.tol, ch.v + ch.tol, l.v - l.tol, w.v - w.tol, h.v - h.tol, perm).count
        val (bx, by, bz) = plan.box
        val body = BodySpec.Ghosts(cw.v, ch.v, cd.v, bx, by, bz, plan.nx, plan.ny, plan.nz)
        return CalcResult(count = CountRange(plan.count, min(lo, plan.count), max(hi, plan.count)), fit = plan, body = body)
    }

    private fun rug(v: List<Q>): CalcResult {
        val q = v[0] * v[1]
        val spec = Templates.RUG_SPECS.minByOrNull { (a, b) -> abs(a * b / 10000.0 - q.v) }!!
        // the spec outline shares the rug's corner; its long side follows the rug's long side
        val long = max(v[0].v, v[1].v) == v[0].v
        val sx = (if (long) spec.second else spec.first) / 100.0
        val sz = (if (long) spec.first else spec.second) / 100.0
        return CalcResult(q = q, rugSpec = spec, body = BodySpec.Rug(v[0].v, v[1].v, sx, sz))
    }

    // ------------------------------------------------------------------ free formula

    fun free(tokens: List<Token>): CalcResult {
        if (tokens.isEmpty()) return CalcResult(error = CalcError.EMPTY)
        return try {
            val p = Parser(tokens)
            val q = p.expression()
            if (!p.done) throw CalcException(CalcError.SYNTAX)
            CalcResult(q = q, body = freeBody(q, tokens))
        } catch (e: CalcException) {
            CalcResult(error = e.error, errorDetail = e.detail)
        }
    }

    /** m¹ tape; m² slab on the two measured sides (else 3:2); m³ box on the three measured sides (else a cube); m⁰ none. */
    fun freeBody(q: Q, tokens: List<Token>): BodySpec? {
        val lens = tokens.filterIsInstance<Token.Num>().filter { it.q.dim == 1 }.map { it.q.v }
        val v = abs(q.v)
        if (v <= 0.0) return null
        return when (q.dim) {
            1 -> BodySpec.Tape(v)
            2 -> if (lens.size >= 2 && abs(lens[0] * lens[1] - v) / v < 0.02) BodySpec.Slab(lens[0], lens[1])
                 else { val b = kotlin.math.sqrt(v / 1.5); BodySpec.Slab(b * 1.5, b) }
            3 -> if (lens.size >= 3 && abs(lens[0] * lens[1] * lens[2] - v) / v < 0.02) BodySpec.Box(lens[0], lens[2], lens[1], water = false)
                 else { val s = Math.cbrt(v); BodySpec.Box(s, s, s, water = false) }
            else -> null
        }
    }

    private class Parser(val t: List<Token>) {
        var i = 0
        val done get() = i >= t.size
        private fun peekOp(): String? = (t.getOrNull(i) as? Token.Op)?.sym

        fun expression(): Q {
            var q = term()
            while (true) {
                when (peekOp()) {
                    "+" -> { i++; q = q + term() }
                    "−" -> { i++; q = q - term() }
                    else -> return q
                }
            }
        }

        fun term(): Q {
            var q = factor()
            while (true) {
                when (peekOp()) {
                    "×" -> { i++; q = q * factor() }
                    "÷" -> { i++; q = q / factor() }
                    else -> return q
                }
            }
        }

        fun factor(): Q {
            if (peekOp() == "√") { i++; return factor().sqrtQ() }
            if (peekOp() == "−") { i++; return -factor() }
            var q = primary()
            while (peekOp() == "x²") { i++; q = q.squared() }
            return q
        }

        fun primary(): Q {
            val tok = t.getOrNull(i) ?: throw CalcException(CalcError.SYNTAX)
            i++
            return when {
                tok is Token.Num -> tok.q
                tok is Token.Op && tok.sym == "π" -> Q.PI
                tok is Token.Op && tok.sym == "(" -> {
                    val q = expression()
                    if (peekOp() != ")") throw CalcException(CalcError.SYNTAX)
                    i++
                    q
                }
                else -> throw CalcException(CalcError.SYNTAX)
            }
        }
    }
}
