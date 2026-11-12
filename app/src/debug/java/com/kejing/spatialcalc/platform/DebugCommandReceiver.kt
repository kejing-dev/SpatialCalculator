package com.kejing.spatialcalc.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kejing.spatialcalc.domain.calc.TemplateId
import com.kejing.spatialcalc.domain.math.Vec3
import com.kejing.spatialcalc.domain.session.Language
import com.kejing.spatialcalc.domain.session.Page
import com.kejing.spatialcalc.domain.session.Phase
import com.kejing.spatialcalc.ui.stage.ActiveScene
import com.kejing.spatialcalc.ui.stage.input.InputSource

/**
 * Debug builds only (verification.debugHooks). Every command calls the same flow / scene entry the real input uses.
 *
 *   adb shell am broadcast -a com.kejing.spatialcalc.DEBUG -p com.kejing.spatialcalc --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S01…S26 · template tank|tile|paint|fit|rug|free [round] · ray x,y,z (a pointing direction from the eye, or
 *   "yaw,pitch" in degrees) · tap (along that ray: the backstop path → analytic surface → flow) · type 62cm · paste "60x30x35"
 *   · equals · chip i set <num>|remeasure|delete · drag dx,dy,dz (body drag, same as onDragMove) · stick x · freeze on|off
 *   · head (start a headSample now) · noplanes on|off · lost on|off · noPermission · anchorFail on|off · err <kind> · unit
 *   · save · pin · next · clear · confirm · cancel · page calc|records|settings · lang zh|en|system · imperial on|off
 *   · opacity 0-2 · volume 0-2 · mute on|off · recall · exitask · wipe · dump · evidence · fitbox · face axis,sign,d · use
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val scene = ActiveScene.scene
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        AppLog.i("CMD $cmd $a")
        fun nums() = a.split(',', ' ').mapNotNull { it.trim().toFloatOrNull() }
        when (cmd) {
            "goto" -> goto(a.uppercase(), graph)
            "template" -> { val p = a.split(' '); TemplateId.of(p[0])?.let { flow.pick(it, p.getOrNull(1) == "round") } }
            "ray" -> {
                val n = nums()
                ray = when (n.size) {
                    3 -> Vec3(n[0], n[1], n[2]).normalized()
                    2 -> { val y = Math.toRadians(n[0].toDouble()); val p = Math.toRadians(n[1].toDouble()); Vec3((-Math.sin(y) * Math.cos(p)).toFloat(), Math.sin(p).toFloat(), (-Math.cos(y) * Math.cos(p)).toFloat()) }
                    else -> ray
                }
                AppLog.i("CMD ray dir=$ray")
            }
            "tap" -> {
                val s = scene ?: return
                val (o, _) = flow.s.let { s.head() } ?: return
                val origin = o + Vec3(SystemPointer.eyeOffsetM ?: 0f, 0f, 0f) * 0f
                s.surfaceTapAlong(origin, ray, com.kejing.spatialcalc.domain.calc.Channel.GAZE, InputSource.UNKNOWN)
            }
            "type" -> {
                val m = Regex("""([\d.]+)\s*([a-z]*)""").find(a) ?: return
                com.kejing.spatialcalc.domain.calc.LenUnit.parse(m.groupValues[2])?.let { flow.setTypingUnit(it) }
                flow.openTyping()
                m.groupValues[1].forEach { flow.key(it.toString()) }
                flow.closeTyping()
            }
            "key" -> flow.key(a)
            "measure" -> flow.measureButton()
            "welcome" -> flow.welcomeDone()
            "typing" -> if (on) flow.openTyping() else flow.closeTyping()
            "paste" -> flow.paste(a)
            "equals" -> flow.equals()
            "chip" -> {
                val p = a.split(' ')
                val i = p.getOrNull(0)?.toIntOrNull() ?: return
                when (p.getOrNull(1)) {
                    "set" -> p.getOrNull(2)?.toDoubleOrNull()?.let { flow.setSlot(i, it) }
                    "remeasure" -> flow.chipRemeasure(i)
                    "delete" -> flow.chipDelete(i)
                    "menu" -> flow.chipTap(i)
                }
            }
            "drag" -> {
                val s = scene ?: return
                val n = nums(); if (n.size < 3) return
                if (s.onDragStart(com.kejing.spatialcalc.ui.stage.Hit.Body)) { s.onDragMove(Vec3(n[0], n[1], n[2])); s.onDragEnd() }
            }
            "stick" -> nums().firstOrNull()?.let { x -> repeat(10) { scene?.applyStick(x, 0.05f) } }
            "freeze" -> scene?.freeze = on
            "head" -> graph.bringBody()
            "bring" -> graph.bringBody()
            "noplanes" -> { scene?.forceNoPlanes = on; flow.setNoPlanes(on) }
            "lost" -> scene?.head?.forceLost = on
            "noPermission" -> flow.setNoPermission(true)
            "anchorFail" -> graph.anchorFail = on
            "err" -> flow.debugError(a)
            "unit" -> flow.cycleUnit()
            "save" -> flow.saveCard()
            "pin" -> flow.pinHere()
            "justsave" -> flow.closeSaved()
            "template-save" -> flow.saveAsTemplate(a)
            "next" -> flow.next()
            "clear" -> flow.clearAsk()
            "confirm" -> flow.confirmYes()
            "cancel" -> { flow.confirmNo(); flow.cancelMeasure(); flow.cancelPlace(); flow.fitBoxCancel() }
            "collapse" -> flow.collapseBody()
            "resultmenu" -> flow.resultMenu(on)
            "turn" -> flow.turnBoxes()
            "replay" -> flow.s.cards.getOrNull(a.toIntOrNull() ?: 0)?.let { flow.replay(it.id) }
            "expand" -> flow.s.cards.getOrNull(a.toIntOrNull() ?: 0)?.let { flow.expand(it.id) }
            "page" -> flow.page(when (a) { "records" -> Page.RECORDS; "settings" -> Page.SETTINGS; else -> Page.CALC })
            "lang" -> flow.updateSettings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "imperial" -> flow.updateSettings { it.copy(imperial = on) }
            "opacity" -> a.toIntOrNull()?.let { v -> flow.updateSettings { it.copy(opacity = v.coerceIn(0, 2)) } }
            "volume" -> a.toIntOrNull()?.let { v -> flow.updateSettings { it.copy(volume = v.coerceIn(0, 2)) } }
            "mute" -> flow.updateSettings { it.copy(muted = on) }
            "recall" -> graph.window.recall()
            "closewindow" -> graph.window.close() // like the system ×: the Stage HUD offers [打开面板]
            "bringcard" -> flow.s.cards.getOrNull(a.toIntOrNull() ?: 0)?.let { graph.bringCard(it.id) } // records [带到面前] (same call as the button)
            "exitask" -> flow.exitAsk()
            "wipe" -> graph.wipe()
            "fitbox" -> graph.fitBox()
            "face" -> { val n = nums(); if (n.size >= 3) flow.fitBoxFace(n[0].toInt(), n[1].toInt(), n[2]) }
            "use" -> flow.fitBoxUse()
            "sound" -> com.kejing.spatialcalc.domain.session.Sfx.entries.firstOrNull { it.name.equals(a, true) }?.let { graph.sounds.play(it) }
            "dump", "state" -> AppLog.i("DUMP ${flow.dump()} window=${graph.window.panelState} scene=${scene?.dump()}")
            "evidence" -> AppLog.i(scene?.evidence() ?: "EVIDENCE no scene")
            else -> AppLog.w("unknown debug command: $cmd")
        }
    }

    private fun goto(id: String, graph: Graph) {
        val flow = graph.flow
        val tableRay = { scene: com.kejing.spatialcalc.ui.stage.StageScene? -> scene }
        when (id) {
            "S00" -> AppLog.i("S00 is the first seconds after launch: force-stop and start, then capture at once")
            "S01" -> { flow.updateSettings { it.copy(welcomeDone = false) }; flow.next(); flow.start(flow.settings.value, flow.s.cards, flow.s.customs) }
            "S02" -> { flow.next(); flow.page(Page.CALC) }
            "S03" -> { flow.pick(TemplateId.TANK); flow.page(Page.CALC) }
            "S04" -> { if (flow.s.template == null) flow.pick(TemplateId.TANK); flow.measureButton() }
            "S07" -> { if (flow.s.template == null) flow.pick(TemplateId.TANK); flow.openTyping() }
            "S08" -> { flow.pick(TemplateId.TANK); flow.paste("60x30x30 cm"); flow.equals() }
            "S09" -> { flow.pick(TemplateId.TANK); flow.paste("60x30x30 cm"); flow.equals() }
            "S12" -> { if (flow.s.body == null) goto("S08", graph); flow.saveCard() }
            "S13" -> flow.page(Page.RECORDS)
            "S14" -> flow.cycleUnit()
            "S15" -> flow.page(Page.SETTINGS)
            "S16" -> flow.exitAsk()
            "S17" -> { ActiveScene.scene?.forceNoPlanes = true; flow.setNoPlanes(true); if (flow.s.template == null) flow.pick(TemplateId.TANK); flow.measureButton() }
            "S18" -> ActiveScene.scene?.head?.forceLost = true
            "S19" -> flow.setNoPermission(true)
            "S22" -> { flow.pick(TemplateId.FIT); flow.paste("30x30x20 cm"); flow.paste("25x25x25 cm"); flow.equals() }
            "S23" -> flow.clearAsk()
            "S26" -> graph.fitBox()
            else -> AppLog.w("goto $id: reach it with the commands in AGENTS.md")
        }
        tableRay(null)
        if (flow.s.phase == Phase.LOADING) AppLog.i("still loading")
    }

    companion object {
        /** The debug pointing direction (Stage), from the eye. Default: 30° down straight ahead. */
        @Volatile var ray: Vec3 = Vec3(0f, -0.5f, -0.866f)
    }
}
