package com.kejing.spatialcalc.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** acceptance.unitTests: tolerance propagation, unit algebra, fit, tile, paste. */
class CalcTest {
    private fun len(m: Double, tol: Double = 0.0) = Q.length(m, tol)
    private fun close(e: Double, a: Double, eps: Double = 1e-9) = assertEquals(e, a, eps)

    // ---------------- ComputeTolerance: + − × ÷ √ x², 3 cases each
    @Test fun plusAddsAbsolute() {
        close(0.003, (len(1.0, 0.001) + len(2.0, 0.002)).tol)
        close(0.0, (len(1.0) + len(2.0)).tol)
        close(0.010, (len(0.5, 0.005) + len(0.5, 0.005)).tol)
    }

    @Test fun minusAddsAbsolute() {
        close(0.003, (len(2.0, 0.001) - len(1.0, 0.002)).tol)
        close(1.0, (len(2.0, 0.001) - len(1.0, 0.002)).v)
        close(0.004, (len(1.0, 0.002) - len(1.0, 0.002)).tol)
    }

    @Test fun timesAddsRelative() {
        val a = len(0.6, 0.006) * len(0.3, 0.003) // 1 % + 1 %
        close(0.18 * 0.02, a.tol, 1e-12); assertEquals(2, a.dim)
        val b = len(2.0, 0.0) * len(3.0, 0.03)
        close(0.06, b.tol, 1e-12)
        val c = Q.count(4.0) * len(0.5, 0.005)
        close(0.02, c.tol, 1e-12); assertEquals(1, c.dim)
    }

    @Test fun divAddsRelative() {
        val a = (len(2.0, 0.02) * len(1.0)) / len(1.0, 0.01) // m² ÷ m = m
        assertEquals(1, a.dim); close(2.0, a.v); close(0.04, a.tol, 1e-12)
        val b = len(3.0, 0.03) / len(1.5)
        assertEquals(0, b.dim); close(0.02, b.tol, 1e-12)
        val c = Q(10.0, 0.0, 2) / Q.count(2.0)
        close(5.0, c.v); assertEquals(2, c.dim)
    }

    @Test fun sqrtHalvesRelative() {
        val a = Q(4.0, 0.08, 2).sqrtQ() // 2 % -> 1 %
        close(2.0, a.v); close(0.02, a.tol, 1e-12); assertEquals(1, a.dim)
        val b = Q(9.0, 0.0, 0).sqrtQ(); close(3.0, b.v)
        val c = Q(0.25, 0.005, 2).sqrtQ(); close(0.5, c.v); close(0.005, c.tol, 1e-12)
    }

    @Test fun squareDoublesRelative() {
        val a = len(2.0, 0.02).squared() // 1 % -> 2 %
        close(4.0, a.v); close(0.08, a.tol, 1e-12); assertEquals(2, a.dim)
        val b = Q.count(3.0).squared(); close(9.0, b.v); assertEquals(0, b.dim)
        val c = len(0.1, 0.001).squared(); close(0.01, c.v, 1e-12); close(0.0002, c.tol, 1e-12)
    }

    // ---------------- unit algebra: 12 cases (legal / illegal)
    private fun tok(vararg t: Any): List<Token> = t.map { if (it is String) Token.Op(it) else Token.Num(it as Q, Source.TYPED) }

    @Test fun unitAlgebra() {
        val m = len(1.0); val m2 = Q(1.0, 0.0, 2); val m3 = Q(1.0, 0.0, 3); val n = Q.count(2.0)
        assertEquals(2, Evaluate.free(tok(m, "×", m)).q!!.dim)
        assertEquals(3, Evaluate.free(tok(m, "×", m, "×", m)).q!!.dim)
        assertEquals(1, Evaluate.free(tok(m3, "÷", m2)).q!!.dim)
        assertEquals(1, Evaluate.free(tok("√", m2)).q!!.dim)
        assertEquals(0, Evaluate.free(tok(m, "÷", m)).q!!.dim)
        assertEquals(2, Evaluate.free(tok(m, "x²")).q!!.dim)
        assertEquals(1, Evaluate.free(tok(n, "×", m)).q!!.dim)
        assertEquals(2, Evaluate.free(tok("π", "×", "(", m, "÷", n, ")", "x²")).q!!.dim)
        assertEquals(CalcError.UNIT_MISMATCH, Evaluate.free(tok(m2, "+", m)).error)
        assertEquals(CalcError.BAD_DIM, Evaluate.free(tok(m3, "×", m)).error)
        assertEquals(CalcError.BAD_DIM, Evaluate.free(tok("√", m)).error)
        assertEquals(CalcError.BAD_DIM, Evaluate.free(tok(m2, "x²")).error)
    }

    @Test fun syntaxErrors() {
        assertEquals(CalcError.SYNTAX, Evaluate.free(tok(len(1.0), "×")).error)
        assertEquals(CalcError.SYNTAX, Evaluate.free(tok("(", len(1.0))).error)
    }

    // ---------------- templates
    @Test fun tankBoxLiters() {
        val slots = Templates.TANK_BOX.zip(listOf(0.6, 0.3, 0.3)).map { (d, m) -> SlotValue(d, len(m, 0.004), Source.TAPE) }
        val r = Evaluate.template(TemplateId.TANK, false, slots)
        close(0.054, r.q!!.v, 1e-12)
        assertEquals("54.0 ± 1.8 L", Fmt.result(r.q!!, ResultUnit.L))
        val b = r.body as BodySpec.Box
        close(0.6, b.x); close(0.3, b.y); close(0.3, b.z); assertTrue(b.water)
    }

    @Test fun tankRound() {
        val slots = Templates.TANK_ROUND.zip(listOf(0.4, 0.5)).map { (d, m) -> SlotValue(d, len(m), Source.TYPED) }
        val r = Evaluate.template(TemplateId.TANK, true, slots)
        close(Math.PI * 0.04 * 0.5, r.q!!.v, 1e-12)
        assertTrue(r.body is BodySpec.Cylinder)
    }

    @Test fun tileCountAndEdge() {
        val slots = Templates.TILE.map { SlotValue(it) }.toMutableList()
        slots[0] = SlotValue(Templates.TILE[0], len(2.5, 0.01), Source.TAPE)
        slots[1] = SlotValue(Templates.TILE[1], len(2.4, 0.01), Source.TAPE)
        val r = Evaluate.template(TemplateId.TILE, false, slots)
        // pitch 0.302 × 0.602: ceil(2.5/0.302)=9, ceil(2.4/0.602)=4 -> 36
        assertEquals(36, r.count!!.n)
        assertTrue(r.partialEdge)
        assertEquals(4, r.extra)
        val t = r.body as BodySpec.Tiles
        assertEquals(9, t.cols); assertEquals(4, t.rows); assertTrue(t.partialCol)
        // an exact fit has no partial edge
        assertEquals(6, Evaluate.tileCount(0.6, 0.9, 0.3, 0.3))
    }

    @Test fun paintCans() {
        val slots = Templates.PAINT.map { SlotValue(it) }.toMutableList()
        slots[0] = SlotValue(Templates.PAINT[0], len(4.0, 0.01), Source.TAPE)
        slots[1] = SlotValue(Templates.PAINT[1], len(2.6, 0.01), Source.TAPE)
        val r = Evaluate.template(TemplateId.PAINT, false, slots)
        assertEquals(3, r.count!!.n) // 4 × 2.6 × 2 / 10 = 2.08 -> 3
        val p = r.body as BodySpec.Paint
        close(10.0 / 2 / 4.0, p.band)
    }

    @Test fun fitSixOrientations() {
        // cell 80 × 40 × 35, box 38 × 28 × 24: standing 2 × 1 × 1; on its side (24 wide, 38 deep, 28 high) 3 × 1 × 1 = 3
        val p = Evaluate.bestFit(0.80, 0.40, 0.35, 0.38, 0.28, 0.24)
        assertEquals(3, p.count)
        // 60 × 40 × 30 cell, 20 × 20 × 10 boxes: 3 × 2 × 3 = 18
        assertEquals(18, Evaluate.bestFit(0.60, 0.40, 0.30, 0.20, 0.20, 0.10).count)
        // orientation matters: 30 × 30 × 10 cell, box 10 × 10 × 30 must lie down -> 3 × 3 = 9 with one turn
        assertEquals(3, Evaluate.bestFit(0.30, 0.30, 0.10, 0.10, 0.10, 0.30).count)
        // gap: 100 cm wide, boxes 30 cm -> 3 fit, 10 cm spare along the width
        val g = Evaluate.bestFit(1.0, 0.3, 0.3, 0.3, 0.3, 0.3)
        assertEquals(3, g.count); close(0.0, g.gapM, 1e-9)
    }

    @Test fun fitDoesNotFit() {
        val p = Evaluate.bestFit(0.30, 0.30, 0.20, 0.25, 0.25, 0.25)
        assertEquals(0, p.count)
        close(0.05, p.overM, 1e-9)
        assertEquals('h', p.overAxis)
    }

    @Test fun fitRangeAndForcedTurn() {
        val slots = Templates.FIT.zip(listOf(0.80, 0.40, 0.35, 0.38, 0.28, 0.24)).map { (d, m) -> SlotValue(d, len(m, 0.004), Source.TAPE) }
        val r = Evaluate.template(TemplateId.FIT, false, slots)
        assertEquals(3, r.count!!.n)
        assertTrue(r.count!!.lo <= 3 && r.count!!.hi >= 3)
        val turned = Evaluate.template(TemplateId.FIT, false, slots, fitPerm = (r.fit!!.perm + 1) % 6)
        assertNotNull(turned.fit)
    }

    @Test fun rugNearestSpec() {
        val slots = Templates.RUG.zip(listOf(2.3, 1.4)).map { (d, m) -> SlotValue(d, len(m), Source.TAPE) }
        val r = Evaluate.template(TemplateId.RUG, false, slots)
        close(3.22, r.q!!.v, 1e-9)
        assertEquals(160 to 230, r.rugSpec)
    }

    @Test fun freeBodies() {
        assertTrue(Evaluate.free(tok(len(3.0))).body is BodySpec.Tape)
        val box = Evaluate.free(tok(len(3.0), "×", len(3.0), "×", len(2.5))).body as BodySpec.Box
        assertTrue(box.oversize)
        assertNull(Evaluate.free(tok(Q.count(3.0), "×", Q.count(2.0))).body)
    }

    // ---------------- paste: 10 formats
    @Test fun pasteFormats() {
        fun p(s: String) = Paste.parse(s, LenUnit.CM)
        assertEquals(listOf(0.6, 0.3, 0.35), p("60×30×35 cm")!!.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(3, p("60x30x35cm")!!.size)
        assertEquals(3, p("60 X 30 X 35")!!.size)
        assertEquals(0.6, p("60*30*35")!![0], 1e-9)
        assertEquals(0.23622 * 0 + 23.6 / 39.3700787, p("23.6 x 11.8 in")!![0], 1e-6)
        assertEquals(listOf(1.2, 0.8), p("1.2m x 0.8m"))
        assertEquals(3, p("60，30，35")!!.size)
        assertEquals(3, p("长60 宽30 高35")!!.size)
        assertEquals(0.6, p("600×300×350 mm")!![0], 1e-9)
        assertEquals(0.6, p("60 × 30 × 35 厘米")!![0], 1e-9)
        assertNull(p("about sixty"))
        assertNull(p(""))
    }

    @Test fun formatFollowsTolerance() {
        assertEquals("62.0 ± 0.4 cm", Fmt.length(0.62, 0.004, LenUnit.CM))
        assertEquals("0.620 ± 0.004 m", Fmt.length(0.62, 0.004, LenUnit.M))
        assertEquals("48 cm", Fmt.length(0.48, 0.0, LenUnit.CM))
        assertEquals("30.5 cm", Fmt.length(0.305, 0.0, LenUnit.CM))
    }

    @Test fun toleranceLadder() {
        assertEquals(20, Tolerance.tapeMm(Channel.GAZE, 1.0f, 1.0f)) // 13 mm each end -> 18.4 -> 20
        assertEquals(8, Tolerance.tapeMm(Channel.RAY, 1.0f, 1.0f)) // 4.5 each -> 6.4 -> 8
        assertEquals(4, Tolerance.tapeMm(Channel.CONTACT, 0.5f, 0.5f)) // 2.5 each -> 3.5 -> 4
    }
}
