package io.embodit.spatialcalc.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.spatialcalc.domain.app.AppController
import io.embodit.spatialcalc.domain.app.AppEffects
import io.embodit.spatialcalc.domain.app.HistoryCodec
import io.embodit.spatialcalc.domain.app.HistoryItem
import io.embodit.spatialcalc.domain.app.Language
import io.embodit.spatialcalc.domain.app.Settings
import io.embodit.spatialcalc.domain.app.Sfx
import io.embodit.spatialcalc.domain.calc.LenUnit
import io.embodit.spatialcalc.mainApp
import io.embodit.spatialcalc.ui.stage.input.BoardInputLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        BoardInputLog.attach(this)
        AppLog.i("BOOT process start")
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** Process-wide objects shared by the Stage and the window (native-window-move.md §3.4). */
class Graph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sounds = SoundPlayer(context)
    val window = WindowCtl()
    private val historyFile = File(context.filesDir, "history.json")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val effects = object : AppEffects {
        override fun sound(s: Sfx) = sounds.play(s)
        override fun saveSettings(s: Settings) = writeSettings(s)
        override fun saveHistory(items: List<HistoryItem>) = writeHistory(items)
        override fun log(line: String) = AppLog.i(line)
        override fun now() = System.currentTimeMillis()
    }

    val app = AppController(effects)

    /** Stops the controller listener and the head tracker (exit, D26). Set by the Stage scene. */
    @Volatile var stageRelease: (() -> Unit)? = null

    fun release() {
        runCatching { stageRelease?.invoke() }
        runCatching { sounds.release() }
        window.detach()
    }

    init {
        val history = runCatching { if (historyFile.exists()) HistoryCodec.decode(historyFile.readText()) else emptyList() }.getOrDefault(emptyList())
        app.start(readSettings(), history)
        scope.launch { app.settings.collect { s -> sounds.muted = s.muted; sounds.volumeLevel = s.volume } }
    }

    private fun writeHistory(items: List<HistoryItem>) {
        val text = HistoryCodec.encode(items)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(historyFile.parentFile, "history.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(historyFile)) { historyFile.delete(); tmp.renameTo(historyFile) }
            }.onFailure { Log.e(TAG, "HISTORY save failed", it) }
        }
    }

    private fun readSettings(): Settings = runCatching {
        Settings(
            volume = prefs.getInt("volume", Settings().volume).coerceIn(0, 2),
            muted = prefs.getBoolean("muted", false),
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            unit = LenUnit.valueOf(prefs.getString("unit", LenUnit.NONE.name)!!),
            firstHintShown = prefs.getBoolean("firstHintShown", false),
        )
    }.getOrElse { Settings() }

    private fun writeSettings(s: Settings) {
        prefs.edit().putInt("volume", s.volume).putBoolean("muted", s.muted).putString("language", s.language.name)
            .putString("unit", s.unit.name).putBoolean("firstHintShown", s.firstHintShown).apply()
    }

    /** Debug `wipe`: no history, no settings (first-run state on the next launch). */
    fun wipe() {
        historyFile.delete()
        prefs.edit().clear().commit()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Event sounds from assets/sounds (profile D51; apps/spatial-calc/build/sound-sources.md lists every file): key = a CC0
 * Kenney click recording (3 variants, ±5 % pitch), = the same click 3 semitones up, the line's tape pull / return = a
 * CC0 freesound tape-measure recording. One instance per kind (a new one stops the previous), so fast typing never
 * stacks. Mute (D15, always on screen) + three volume stops 0 / 50 / 100 % (B20).
 */
class SoundPlayer(context: Context) {
    @Volatile var muted = false
    @Volatile var volumeLevel = 2

    private val pool = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(7)
    private val lastVariant = HashMap<String, Int>()
    private val lastStream = HashMap<String, Int>()

    private fun fileOf(s: Sfx): String = when (s) { Sfx.KEY, Sfx.TICK -> "key"; Sfx.EQUALS -> "equals"; Sfx.TAPE_OUT -> "tape"; Sfx.TAPE_IN -> "tape_in" }

    /** The rotate tick is the key click 6 dB down (design T4). */
    private fun gainOf(s: Sfx): Float = if (s == Sfx.TICK) 0.5f else 1.0f

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (base in Sfx.entries.map { fileOf(it) }.distinct()) {
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav")) {
                runCatching { context.assets.openFd("sounds/$name").use { fd -> found += pool.load(fd, 1) } }
            }
            if (found.isNotEmpty()) ids[base] = found else Log.w(TAG, "no sound for $base")
        }
    }

    fun play(s: Sfx) {
        if (muted) return
        val base = fileOf(s)
        val variants = ids[base] ?: return
        val vol = VOLUME_PERCENT[volumeLevel.coerceIn(0, 2)] / 100f * gainOf(s)
        if (vol <= 0f) return
        var v = if (variants.size > 1) random.nextInt(variants.size) else 0
        if (variants.size > 1 && v == lastVariant[base]) v = (v + 1) % variants.size
        lastVariant[base] = v
        val rate = 0.95f + random.nextFloat() * 0.10f
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        lastStream[base]?.let { pool.stop(it) }
        lastStream[base] = pool.play(id, vol, vol, 1, 0, rate)
    }

    fun release() = pool.release()

    companion object {
        /** The three stops of the volume slider (design sound.master). */
        val VOLUME_PERCENT = intArrayOf(0, 50, 100)
    }
}
