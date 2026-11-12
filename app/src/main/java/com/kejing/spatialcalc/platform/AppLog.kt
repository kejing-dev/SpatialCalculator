package com.kejing.spatialcalc.platform

import android.util.Log

const val TAG = "SpatialCalc"

/**
 * One line per event under the tag `SpatialCalc` with stable prefixes (verification.debugHooks): STATE MEASURE CHIP
 * CALC BODY PLACE ANCHOR WINDOW CMD DUMP PLANES TAP ... Every line is also mirrored into files/input-log.txt by
 * BoardInputLog (headset logcat drops app lines, board-input README).
 */
object AppLog {
    fun i(line: String) { com.kejing.spatialcalc.ui.stage.input.BoardInputLog.write(TAG, line) }
    fun w(line: String, error: Throwable? = null) { Log.w(TAG, line, error) }
    fun e(line: String, error: Throwable? = null) { Log.e(TAG, line, error) }
}
