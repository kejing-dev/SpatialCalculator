package io.embodit.spatialcalc.platform

import android.util.Log

const val TAG = "SpatialCalc"

/**
 * One line per event under the tag `SpatialCalc` with stable prefixes: STATE KEY UNIT LINE HISTORY SETTINGS WINDOW
 * DRAG STICK CMD DUMP. Every line is also mirrored into files/input-log.txt by BoardInputLog (headset logcat can drop
 * app lines, board-input README).
 */
object AppLog {
    fun i(line: String) { io.embodit.spatialcalc.ui.stage.input.BoardInputLog.write(TAG, line) }
    fun w(line: String, error: Throwable? = null) { Log.w(TAG, line, error) }
    fun e(line: String, error: Throwable? = null) { Log.e(TAG, line, error) }
}
