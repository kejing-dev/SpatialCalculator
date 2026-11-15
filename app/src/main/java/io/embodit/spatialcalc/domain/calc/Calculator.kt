package io.embodit.spatialcalc.domain.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** The four operators, with the symbol the display shows. */
enum class Op(val sym: String) { ADD("+"), SUB("−"), MUL("×"), DIV("÷") }

/** The 20 keys of the keypad (design calcModel.keys) in reading order. */
enum class Key(val label: String) {
    CLEAR("C"), BACK("⌫"), PCT("%"), DIV("÷"),
    D7("7"), D8("8"), D9("9"), MUL("×"),
    D4("4"), D5("5"), D6("6"), SUB("−"),
    D1("1"), D2("2"), D3("3"), ADD("+"),
    SIGN("±"), D0("0"), DOT("."), EQ("=");

    val digit: Char? get() = if (name.length == 2 && name[0] == 'D') name[1] else null
    val op: Op? get() = when (this) { ADD -> Op.ADD; SUB -> Op.SUB; MUL -> Op.MUL; DIV -> Op.DIV; else -> null }
    val isOperator: Boolean get() = op != null || this == EQ

    companion object {
        /** debug `type 12*3+4=`: one character → one key (same path as a real key press). */
        fun of(c: Char): Key? = when (c) {
            in '0'..'9' -> valueOf("D$c")
            '.' -> DOT; '+' -> ADD; '-', '−' -> SUB; '*', 'x', '×' -> MUL; '/', '÷' -> DIV
            '=' -> EQ; '%' -> PCT; 'c', 'C' -> CLEAR; '<', '⌫' -> BACK; '~', '±' -> SIGN
            else -> null
        }
    }
}

enum class CalcError { DIV_ZERO, TOO_BIG }

/** A token of the expression being typed. [text] is the number as typed ("-12.5", "0.", "-"); [pct] = a trailing %. */
sealed class Tok {
    data class Num(val text: String, val pct: Boolean = false) : Tok() {
        val value: BigDecimal get() {
            val t = text.removePrefix("-").removeSuffix(".").ifEmpty { "0" }
            val v = BigDecimal(t)
            return if (text.startsWith("-")) v.negate() else v
        }
        val digits: Int get() = text.count { it.isDigit() }
    }
    data class Opr(val op: Op) : Tok()
}

/**
 * Calculator state. While typing: [tokens]. After =: [evaluated] with [result] and the finished expression [exprText]
 * ("12 × 3 + 4"); [repeat] is the last operation for = = (12 × 3 = = → 108).
 */
data class CalcState(
    val tokens: List<Tok> = emptyList(),
    val evaluated: Boolean = false,
    val result: BigDecimal? = null,
    val exprText: String = "",
    val repeat: Pair<Op, BigDecimal>? = null,
    val error: CalcError? = null,
)

class DivideByZero : ArithmeticException("divide by zero")

/**
 * Desktop-calculator arithmetic (design calcModel): decimal (BigDecimal, 34 digits), × ÷ before + −, % in three
 * contexts, ±, ⌫, C, chained / repeated =. Pure: no Android, no time.
 */
object Calculator {
    val MC = MathContext(34, RoundingMode.HALF_EVEN)
    private val LIMIT = BigDecimal("1e100")
    const val MAX_DIGITS = 15

    fun press(s: CalcState, k: Key): CalcState {
        // after an error the next key clears it first (calcModel.errors)
        val st = if (s.error != null) CalcState() else s
        if (s.error != null && (k == Key.CLEAR || k == Key.BACK)) return CalcState()
        k.digit?.let { return digit(st, it) }
        k.op?.let { return operator(st, it) }
        return when (k) {
            Key.DOT -> dot(st)
            Key.EQ -> equals(st)
            Key.PCT -> percent(st)
            Key.SIGN -> sign(st)
            Key.BACK -> back(st)
            Key.CLEAR -> CalcState()
            else -> st
        }
    }

    private fun lastNum(s: CalcState): Tok.Num? = s.tokens.lastOrNull() as? Tok.Num

    private fun replaceLast(s: CalcState, t: Tok?): CalcState =
        s.copy(tokens = s.tokens.dropLast(1) + listOfNotNull(t))

    private fun digit(s: CalcState, d: Char): CalcState {
        if (s.evaluated) return CalcState(tokens = listOf(Tok.Num(d.toString())))
        val n = lastNum(s) ?: return s.copy(tokens = s.tokens + Tok.Num(d.toString()))
        if (n.pct || n.digits >= MAX_DIGITS) return s
        val text = when (n.text) { "0" -> d.toString(); "-0" -> "-$d"; else -> n.text + d }
        return replaceLast(s, n.copy(text = text))
    }

    private fun dot(s: CalcState): CalcState {
        if (s.evaluated) return CalcState(tokens = listOf(Tok.Num("0.")))
        val n = lastNum(s) ?: return s.copy(tokens = s.tokens + Tok.Num("0."))
        if (n.pct || '.' in n.text) return s
        return replaceLast(s, n.copy(text = if (n.text == "-") "-0." else n.text + "."))
    }

    private fun operator(s: CalcState, op: Op): CalcState {
        if (s.evaluated) return CalcState(tokens = listOf(Tok.Num(plain(s.result ?: BigDecimal.ZERO)), Tok.Opr(op)))
        return when (val last = s.tokens.lastOrNull()) {
            null -> s.copy(tokens = listOf(Tok.Num("0"), Tok.Opr(op)))
            is Tok.Opr -> replaceLast(s, Tok.Opr(op))
            is Tok.Num -> if (last.text == "-") s else s.copy(tokens = s.tokens + Tok.Opr(op))
        }
    }

    private fun percent(s: CalcState): CalcState {
        if (s.evaluated) return CalcState(tokens = listOf(Tok.Num(plain(s.result ?: BigDecimal.ZERO), pct = true)))
        val n = lastNum(s) ?: return s
        if (n.pct || n.text == "-") return s
        return replaceLast(s, n.copy(pct = true))
    }

    private fun sign(s: CalcState): CalcState {
        if (s.evaluated) {
            val r = s.result ?: return s
            return s.copy(result = r.negate(), exprText = "−(" + s.exprText + ")")
        }
        val n = lastNum(s) ?: return s.copy(tokens = s.tokens + Tok.Num("-"))
        return replaceLast(s, n.copy(text = if (n.text.startsWith("-")) n.text.removePrefix("-") else "-" + n.text))
    }

    private fun back(s: CalcState): CalcState {
        if (s.evaluated) return s // like a desktop calculator: ⌫ does nothing on a result
        return when (val last = s.tokens.lastOrNull()) {
            null -> s
            is Tok.Opr -> replaceLast(s, null)
            is Tok.Num -> when {
                last.pct -> replaceLast(s, last.copy(pct = false))
                else -> {
                    val t = last.text.dropLast(1)
                    replaceLast(s, if (t.isEmpty() || t == "-") null else last.copy(text = t))
                }
            }
        }
    }

    private fun equals(s: CalcState): CalcState {
        if (s.evaluated) {
            val (op, b) = s.repeat ?: return s
            val r = s.result ?: return s
            return try {
                val v = check(apply(r, op, b))
                s.copy(result = v, exprText = Fmt.number(r) + " " + op.sym + " " + Fmt.number(b))
            } catch (e: DivideByZero) { s.copy(error = CalcError.DIV_ZERO) } catch (e: TooBig) { s.copy(error = CalcError.TOO_BIG) }
        }
        val toks = trimTrailingOp(s.tokens)
        if (toks.isEmpty()) return s
        val text = Fmt.tokens(toks)
        return try {
            val v = check(evaluate(toks))
            val repeat = if (toks.size >= 3) {
                val op = (toks[toks.size - 2] as Tok.Opr).op
                op to resolvedLast(toks)
            } else null
            CalcState(evaluated = true, result = v, exprText = text, repeat = repeat)
        } catch (e: DivideByZero) {
            CalcState(tokens = toks, exprText = text, error = CalcError.DIV_ZERO)
        } catch (e: TooBig) {
            CalcState(tokens = toks, exprText = text, error = CalcError.TOO_BIG)
        }
    }

    private class TooBig : ArithmeticException("too big")

    private fun check(v: BigDecimal): BigDecimal { if (v.abs() > LIMIT) throw TooBig(); return v }

    fun trimTrailingOp(t: List<Tok>): List<Tok> {
        var r = t
        while (r.isNotEmpty() && (r.last() is Tok.Opr || (r.last() as? Tok.Num)?.text == "-")) r = r.dropLast(1)
        return r
    }

    /** The value of the last number after % is resolved (the operand that = = repeats). */
    private fun resolvedLast(t: List<Tok>): BigDecimal = resolvePercents(t).last()

    /** Numbers with % resolved: after + / − it is that percent of everything to its left (200 + 10% = 220), else ÷ 100. */
    private fun resolvePercents(t: List<Tok>): List<BigDecimal> {
        val out = ArrayList<BigDecimal>()
        for (i in t.indices) {
            val n = t[i] as? Tok.Num ?: continue
            var v = n.value
            if (n.pct) {
                val op = (t.getOrNull(i - 1) as? Tok.Opr)?.op
                v = if ((op == Op.ADD || op == Op.SUB) && i >= 2) evaluate(t.subList(0, i - 1)).multiply(v, MC).divide(HUNDRED, MC)
                else v.divide(HUNDRED, MC)
            }
            out += v
        }
        return out
    }

    private val HUNDRED = BigDecimal(100)

    fun apply(a: BigDecimal, op: Op, b: BigDecimal): BigDecimal = when (op) {
        Op.ADD -> a.add(b, MC)
        Op.SUB -> a.subtract(b, MC)
        Op.MUL -> a.multiply(b, MC)
        Op.DIV -> { if (b.signum() == 0) throw DivideByZero(); a.divide(b, MC) }
    }

    /** × ÷ before + −, left to right. Throws [DivideByZero]. */
    fun evaluate(tokens: List<Tok>): BigDecimal {
        val t = trimTrailingOp(tokens)
        if (t.isEmpty()) return BigDecimal.ZERO
        val nums = resolvePercents(t)
        val ops = t.filterIsInstance<Tok.Opr>().map { it.op }
        // pass 1: × ÷
        val terms = ArrayList<BigDecimal>(); val addOps = ArrayList<Op>()
        var acc = nums[0]
        for (i in ops.indices) {
            val op = ops[i]; val b = nums[i + 1]
            if (op == Op.MUL || op == Op.DIV) acc = apply(acc, op, b) else { terms += acc; addOps += op; acc = b }
        }
        terms += acc
        var r = terms[0]
        for (i in addOps.indices) r = apply(r, addOps[i], terms[i + 1])
        return r
    }

    /** Grey live preview while typing (null when there is nothing to preview or it would be an error). */
    fun preview(s: CalcState): BigDecimal? {
        if (s.evaluated || s.error != null) return null
        val t = trimTrailingOp(s.tokens)
        if (t.isEmpty()) return null
        return try { check(evaluate(t)) } catch (e: ArithmeticException) { null }
    }

    fun plain(v: BigDecimal): String = v.stripTrailingZeros().let { if (it.signum() == 0) "0" else it.toPlainString() }
}
