package io.embodit.spatialcalc.ui.window

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import androidx.compose.foundation.selection.toggleable
import com.pico.spatial.ui.design.Text
import io.embodit.spatialcalc.platform.TAG
import io.embodit.spatialcalc.platform.SoundPlayer
import io.embodit.spatialcalc.ui.theme.CalcColors

/** B31 (gate button-height): the one button height of the app. */
internal val BTN_H: Dp = 64.dp

/** B31 (gate button-gap): the gap between buttons standing side by side. */
internal val BTN_GAP: Dp = 16.dp

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; ui-audit runs read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = BTN_H) {
    // B31 (2026-11-12 gate button-height): every button is at least 64 dp tall, whatever the caller asks
    val h = maxOf(height, BTN_H)
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = h, minWidth = 96.dp),
        shape = RoundedCornerShape(h / 2),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
internal fun SecondaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, tall: Boolean = false, minWidth: Dp = 96.dp) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = BTN_H, minWidth = minWidth),
        shape = RoundedCornerShape(BTN_H / 2),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) CalcColors.Error else PicoTheme.colorScheme.fillSecondary,
            contentColor = if (danger) CalcColors.OnError else PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = if (danger) CalcColors.OnError else PicoTheme.colorScheme.labelPrimary)
    }
}

/** A switch-like button (D19): its on / off state is the fill, and a small lamp on the left. */
@Composable
internal fun ToggleButton(text: String, tag: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = BTN_H, minWidth = 96.dp),
        shape = RoundedCornerShape(BTN_H / 2),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) CalcColors.PrimaryContainer else Color.Transparent).border(1.5.dp, fg, CircleShape))
        Box(Modifier.width(8.dp))
        // design-style: inherited-content-color Button
        Txt(text, "$tag/toggle", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A labelled row holding a same-page option switch (SegmentControl is never used to change pages). */
@Composable
internal fun OptionRow(label: String, tag: String, labelWidth: Dp = 112.dp, rowHeight: Dp = 56.dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(labelWidth))
        content()
    }
}

@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T, tag: String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    // B19: the control itself is >= 48 dp tall (the default track is ~37 dp).
    SegmentControl(modifier = modifier.height(52.dp)) {
        options.forEach { (value, label) ->
            SegmentItem(
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    Txt(label, "$tag/segment", style = if (small) PicoTheme.typography.labelMedium else PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary)
                },
            )
        }
    }
}

/** A whole >= 48 dp row that toggles (label + Switch); the bare SpatialUI Switch is only ~32 x 20 dp (QA q1-4, D44). */
@Composable
internal fun SwitchRow(label: String, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A draggable slider that snaps to three stops (小 / 中 / 大) and shows the percentage (B20). */
@Composable
internal fun VolumeSlider(level: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val label = androidx.compose.ui.res.stringResource(io.embodit.spatialcalc.R.string.set_volume)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = level.toFloat(),
            onValueChange = { v -> val snapped = kotlin.math.round(v).toInt().coerceIn(0, 2); if (snapped != level) onChange(snapped) },
            modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = label },
            valueRange = 0f..2f,
        )
        Box(Modifier.width(12.dp))
        Txt("${SoundPlayer.VOLUME_PERCENT[level.coerceIn(0, 2)]}%", "settings/volume-pct", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
    }
}


/**
 * q3-1 (D15, user 2026-11-12「常显静音」): the mute key that stands on every Calc page screen (title row). Icon shows
 * the current sound state, the text says what a tap does. [compact] = icon only (typing page, narrow column).
 */
@Composable
internal fun MuteKey(muted: Boolean, onClick: () -> Unit, compact: Boolean = false) {
    val on = muted
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    val label = androidx.compose.ui.res.stringResource(if (on) io.embodit.spatialcalc.R.string.unmute else io.embodit.spatialcalc.R.string.mute)
    Button(
        onClick = onClick,
        size = ButtonDefaults.Regular.copy(height = BTN_H, minWidth = BTN_H),
        shape = RoundedCornerShape(BTN_H / 2),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        com.pico.spatial.ui.design.Icon(
            painter = androidx.compose.ui.res.painterResource(if (on) io.embodit.spatialcalc.R.drawable.ic_volume_off else io.embodit.spatialcalc.R.drawable.ic_volume_up),
            contentDescription = label, tint = fg, modifier = Modifier.size(24.dp),
        )
        if (!compact) {
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            // design-style: inherited-content-color Button
            Txt(label, "calc/mute", style = PicoTheme.typography.labelLarge, color = fg)
        }
    }
}
