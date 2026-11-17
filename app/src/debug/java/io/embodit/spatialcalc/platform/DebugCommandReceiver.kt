package io.embodit.spatialcalc.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.spatialcalc.domain.app.Language
import io.embodit.spatialcalc.domain.app.Page
import io.embodit.spatialcalc.domain.calc.Key
import io.embodit.spatialcalc.domain.calc.LenUnit
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.domain.place.Axis
import io.embodit.spatialcalc.ui.stage.ActiveScene

/**
 * Debug builds only (design verification.debugHooks). Every command calls the same controller / scene entry the real
 * input uses (keys → AppController.press, the line drag → StageScene.onDragStart/Move/End).
 *
 *   adb shell am broadcast -a io.embodit.spatialcalc.DEBUG -p io.embodit.spatialcalc --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto calc|history|settings|howto|exitConfirm · type 12*3+4= (c = C, < = ⌫, ~ = ±)
 *   · unit none|mm|cm|m|in|ft|ml|l|cm3|m3|ft3|gal
 *   · ruler on|off|toggle ([摆出来]) · ruler drag dx,dy,dz · ruler dragdeltas n dx,dy,dz · ruler turn ±n · ruler stand|lay
 *   · turn x|y|z ±n (the [−15°] [+15°] keys) · turn reset ([回正]) · stick right|left|up|down [trigger] (grip held)
 *   · twist x|y|z deg (two-hand twist, then release) · seedHistory n · recall i
 *   · clearAsk · clearHistory · lang zh|en|system · mute on|off · volume 0|1|2 · bring · hint reset · wipe · dump
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val app = graph.app
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        AppLog.i("CMD $cmd $a")
        when (cmd) {
            "goto" -> when (a.lowercase()) {
                "calc" -> { app.exitAsk(false); app.page(Page.CALC) }
                "history" -> app.page(Page.HISTORY)
                "settings" -> { app.page(Page.SETTINGS); app.exitAsk(false) }
                "howto" -> app.page(Page.HOWTO)
                "exitconfirm" -> app.exitAsk(true)
            }
            "type" -> { app.page(Page.CALC); a.forEach { c -> Key.of(c)?.let(app::press) } }
            "unit" -> LenUnit.of(a)?.let(app::unit)
            "ruler" -> {
                val p = a.split(' ', limit = 2)
                when (p[0]) {
                    "on" -> if (!app.s.line.on) app.ruler()
                    "off" -> if (app.s.line.on) app.ruler()
                    "toggle", "" -> app.ruler()
                    "drag" -> {
                        val n = p.getOrNull(1).orEmpty().split(',').mapNotNull { it.trim().toFloatOrNull() }
                        if (n.size == 3) ActiveScene.scene?.debugDrag(Vec3(n[0], n[1], n[2]))
                    }
                    "dragdeltas" -> {
                        val q = p.getOrNull(1).orEmpty().trim().split(' ', limit = 2)
                        val count = q.getOrNull(0)?.toIntOrNull() ?: 0
                        val n = q.getOrNull(1).orEmpty().split(',').mapNotNull { it.trim().toFloatOrNull() }
                        if (count > 0 && n.size == 3) ActiveScene.scene?.debugDragDeltas(count, Vec3(n[0], n[1], n[2])) else AppLog.w("CMD ruler dragdeltas needs: n dx,dy,dz")
                    }
                    "turn" -> { val n = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 1; repeat(kotlin.math.abs(n)) { app.lineTurn(if (n > 0) 1 else -1) } }
                    "stand" -> app.lineStand(true)
                    "lay" -> app.lineStand(false)
                }
            }
            "turn" -> {
                val p = a.split(' ')
                if (p[0] == "reset") app.gaugeReset() else {
                    val axis = when (p[0].lowercase()) { "x" -> Axis.X; "y" -> Axis.Y; "z" -> Axis.Z; else -> null }
                    val n = p.getOrNull(1)?.toIntOrNull() ?: 1
                    if (axis == null) AppLog.w("CMD turn needs x|y|z ±n or reset") else repeat(kotlin.math.abs(n)) { app.gaugeTurn(axis, if (n > 0) 1 else -1) }
                }
            }
            "stick" -> {
                val p = a.split(' ')
                val dir = when (p[0]) { "right" -> 1; "left" -> 2; "up" -> 3; "down" -> 4; else -> 0 }
                ActiveScene.scene?.stickTurn(dir, p.getOrNull(1) == "trigger")
            }
            "twist" -> {
                val p = a.split(' ')
                val axis = when (p[0].lowercase()) { "x" -> Axis.X; "y" -> Axis.Y; else -> Axis.Z }
                val deg = p.getOrNull(1)?.toFloatOrNull() ?: 0f
                // the same scene entry the two-hand rotate gesture uses: start → twist in 5° events → release (snap)
                val sc = ActiveScene.scene
                if (sc != null && sc.onTwistStart(io.embodit.spatialcalc.ui.stage.Hit.Gauge)) {
                    val v = when (axis) { Axis.X -> Vec3(1f, 0f, 0f); Axis.Y -> Vec3(0f, 1f, 0f); Axis.Z -> Vec3(0f, 0f, 1f) }
                    val steps = maxOf(1, (kotlin.math.abs(deg) / 5f).toInt())
                    for (i in 0..steps) sc.onTwist(v, deg * i / steps)
                    sc.onTwistEnd()
                }
            }
            "seedHistory" -> app.seedHistory(a.toIntOrNull() ?: 6)
            "recall" -> app.recall(a.toIntOrNull() ?: 0)
            "clearAsk" -> app.clearHistoryAsk(true)
            "clearHistory" -> app.clearHistory()
            "lang" -> app.language(when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM })
            "mute" -> app.mute(on)
            "volume" -> app.volume(a.toIntOrNull() ?: 2)
            "bring" -> graph.window.recall()
            "wipe" -> graph.wipe()
            "dump" -> {
                val s = app.s
                AppLog.i("DUMP state=${s.stateId} page=${s.page} unit=${s.unit} expr=\"${s.calc.exprText}\" result=${s.calc.result?.toPlainString()} " +
                    "err=${s.calc.error} history=${s.history.size} line=${s.line} window=${graph.window.panelState} settings=${app.settings.value}")
                AppLog.i("DUMP ${ActiveScene.scene?.dump()}")
            }
            else -> AppLog.w("CMD unknown $cmd")
        }
    }
}
