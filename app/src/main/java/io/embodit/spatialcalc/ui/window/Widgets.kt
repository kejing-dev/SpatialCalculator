package io.embodit.spatialcalc.ui.window

import android.util.Log
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.layout.zOffset
import io.embodit.spatialcalc.R
import io.embodit.spatialcalc.domain.app.StopSlider
import io.embodit.spatialcalc.domain.calc.Key
import io.embodit.spatialcalc.platform.SoundPlayer
import io.embodit.spatialcalc.platform.TAG
import io.embodit.spatialcalc.ui.theme.CalcColors

/** B31 (gate button-height): the height of every text button. */
internal val BTN_H: Dp = 64.dp

/** B31 (gate button-gap): the gap between buttons standing side by side. */
internal val BTN_GAP: Dp = 16.dp

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; the overflow scan reads the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
    size: TextUnit = TextUnit.Unspecified,
    lineHeight: Float = 1.2f,
) {
    Text(
        text = text, style = if (size != TextUnit.Unspecified) style.copy(fontSize = size, lineHeight = size * lineHeight) else style,
        color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

/** A 48 dp icon key (top row, back arrow, 📏). [on] = filled with the accent (📏 while the line is out). */
@Composable
internal fun IconKey(icon: Int, label: String, tag: String, onClick: () -> Unit, on: Boolean = false, enabled: Boolean = true, dim: Boolean = false) {
    val fg = when { on -> CalcColors.OnAccent; dim -> CalcColors.InkSecondary.copy(alpha = 0.8f); else -> CalcColors.Ink }
    IconButton(
        onClick = { Log.i(TAG, "UI tap $tag"); onClick() },
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        enabled = enabled,
        size = IconButtonDefaults.iconButtonSize(48.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.iconButtonColors(if (on) CalcColors.Accent else CalcColors.KeyFill, fg),
    ) {
        Icon(painter = painterResource(icon), contentDescription = label, tint = fg, modifier = Modifier.size(24.dp))
    }
}

/**
 * One of the 20 keys: a 64 dp round SpatialUI Button (skin A). Digits on the light fill, operators / functions on the
 * stronger fill, = in mist teal. Pressing sinks it 4 dp toward the glass (design T1; SpatialUI zOffset) for 80 ms+.
 */
@Composable
internal fun CalcKey(key: Key, onPress: (Key) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val isEq = key == Key.EQ
    val isDigit = key.digit != null || key == Key.DOT
    val fill = when { isEq -> CalcColors.Accent; isDigit -> CalcColors.KeyFill; else -> CalcColors.OpFill }
    val fg = if (isEq) CalcColors.OnAccent else CalcColors.Ink
    val label = if (key == Key.BACK) stringResource(R.string.backspace) else key.label
    Button(
        onClick = { Log.i(TAG, "UI tap key/${key.name}"); onPress(key) },
        modifier = Modifier.size(64.dp).zOffset { if (pressed) -4.dp.toPx() else 0f }.semantics { contentDescription = label },
        size = ButtonDefaults.Regular.copy(height = BTN_H, minWidth = 64.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        interactionSource = src,
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = fg),
    ) {
        if (key == Key.BACK) {
            Icon(painter = painterResource(R.drawable.ic_backspace), contentDescription = label, tint = fg, modifier = Modifier.size(26.dp))
        } else {
            // design-style: inherited-content-color Button
            Txt(key.label, "key/${key.name}", size = KEY_SP, color = fg, align = TextAlign.Center)
        }
    }
}

private val KEY_SP = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp)

/** A full-width 64 dp text button (settings rows, history clear, confirm pairs). */
@Composable
internal fun RowButton(
    text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
    icon: Int? = null, trailing: Int? = null, danger: Boolean = false, accent: Boolean = false,
) {
    val fg = when { danger -> CalcColors.ErrorText; accent -> CalcColors.OnAccent; else -> CalcColors.Ink }
    Button(
        onClick = { Log.i(TAG, "UI tap $tag"); onClick() }, modifier = modifier.height(BTN_H),
        size = ButtonDefaults.Regular.copy(height = BTN_H, minWidth = 96.dp),
        shape = RoundedCornerShape(BTN_H / 2),
        colors = ButtonDefaults.buttonColors(containerColor = if (accent) CalcColors.Accent else CalcColors.KeyFill, contentColor = fg),
    ) {
        if (icon != null) { Icon(painter = painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.titleSmall, color = fg, modifier = if (trailing != null) Modifier.weight(1f) else Modifier)
        if (trailing != null) Icon(painter = painterResource(trailing), contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
    }
}

/** A labelled 64 dp row holding a same-page option switch (SegmentControl never changes pages). */
@Composable
internal fun OptionRow(label: String, tag: String, labelWidth: Dp = 96.dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(BTN_H), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "$tag/label", style = PicoTheme.typography.titleSmall, modifier = Modifier.width(labelWidth))
        content()
    }
}

@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T, tag: String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    // B19: the control itself is >= 48 dp tall (the default track is ~37 dp).
    SegmentControl(modifier = modifier.height(52.dp)) {
        options.forEach { (value, label) ->
            SegmentItem(
                selected = value == selected,
                onClick = { Log.i(TAG, "UI tap $tag/$value"); onSelect(value) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    Txt(label, "$tag/segment", style = PicoTheme.typography.labelLarge, color = CalcColors.Ink)
                },
            )
        }
    }
}

/**
 * Volume: three stops 0 / 50 / 100 % (B20). A tap on the track and a small drag both move it; the level follows the
 * nearest stop at once and the thumb snaps there on release. q8-1 (SpatialUI Slider keeps its first onValueChange,
 * spatial-ui-skills #41): the callback only touches remembered objects — [StopSlider], the thumb state, and the latest
 * onChange through rememberUpdatedState — never the composition-time level.
 */
@Composable
internal fun VolumeSlider(level: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.volume)
    val model = remember { StopSlider(level) }
    val save by rememberUpdatedState(onChange)
    val thumb = remember { mutableFloatStateOf(level.toFloat()) }
    LaunchedEffect(level) { model.sync(level); thumb.floatValue = model.pos }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = thumb.floatValue,
            onValueChange = { v -> model.change(v)?.let { save(it) }; thumb.floatValue = model.pos },
            onValueChangeFinished = { thumb.floatValue = model.finish(); Log.i(TAG, "UI volume release level=${model.level}") },
            modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = label },
            valueRange = 0f..2f,
        )
        Box(Modifier.width(12.dp))
        Txt("${SoundPlayer.VOLUME_PERCENT[level.coerceIn(0, 2)]}%", "settings/volume-pct", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(52.dp), align = TextAlign.End)
    }
}
