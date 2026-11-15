package io.embodit.spatialcalc.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.spatialcalc.domain.app.Language
import io.embodit.spatialcalc.domain.app.Page
import io.embodit.spatialcalc.domain.calc.Key
import io.embodit.spatialcalc.domain.calc.LenUnit
import io.embodit.spatialcalc.domain.math.Vec3
import io.embodit.spatialcalc.ui.stage.ActiveScene

/**
 * Debug builds only (design verification.debugHooks). Every command calls the same controller / scene entry the real
 * input uses (keys → AppController.press, the line drag → StageScene.onDragStart/Move/End).
 *
 *   adb shell am broadcast -a io.embodit.spatialcalc.DEBUG -p io.embodit.spatialcalc --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto calc|history|settings|howto|exitConfirm · type 12*3+4= (c = C, < = ⌫, ~ = ±) · unit none|mm|cm|m|in|ft
 *   · ruler on|off|toggle · ruler drag dx,dy,dz · ruler turn ±n · ruler stand|lay · seedHistory n · recall i
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
                    "turn" -> { val n = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 1; repeat(kotlin.math.abs(n)) { app.lineTurn(if (n > 0) 1 else -1) } }
                    "stand" -> app.lineStand(true)
                    "lay" -> app.lineStand(false)
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
