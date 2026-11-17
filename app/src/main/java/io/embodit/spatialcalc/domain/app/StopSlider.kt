package io.embodit.spatialcalc.domain.app

/**
 * The three-stop volume slider's logic (B20, q8-1): a tap on the track or a drag both change the level as soon as the
 * thumb is nearer another stop; on release the thumb snaps onto the level's stop. Kept in a remembered object (not in
 * the composable's parameters) because SpatialUI Slider keeps the first onValueChange it was given (spatial-ui-skills
 * #41): reading the composition-time `level` there froze it at the level the page opened with.
 */
class StopSlider(level: Int, private val stops: Int = 3) {
    var pos: Float = level.toFloat(); private set
    var level: Int = level; private set

    /** The saved level changed from somewhere else (mute row, debug, restore): follow it. */
    fun sync(external: Int) {
        if (external != level) { level = external.coerceIn(0, stops - 1); pos = level.toFloat() }
    }

    /** onValueChange: returns the new level when it changed (the caller saves it), else null. */
    fun change(v: Float): Int? {
        pos = v.coerceIn(0f, (stops - 1).toFloat())
        val s = Math.round(pos).coerceIn(0, stops - 1)
        if (s == level) return null
        level = s
        return s
    }

    /** onValueChangeFinished: the thumb lands on the stop. */
    fun finish(): Float { pos = level.toFloat(); return pos }
}
