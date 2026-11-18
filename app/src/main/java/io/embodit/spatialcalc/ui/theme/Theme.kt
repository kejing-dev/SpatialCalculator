package io.embodit.spatialcalc.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.spatialcalc.domain.app.Language
import io.embodit.spatialcalc.platform.Graph
import java.util.Locale

/**
 * Skin A「雾面圆键 Frost」(gate B Q1). The system window glass renders light on PICO OS 6.1 (emulator, ui-audit
 * contrast table), so the key fills are the ink color at 8 % / 14 % instead of white 10 % / 18 % and the key glyphs are
 * the glass's ink (deviation, design needsCheck N4). Mist teal only on =, the selected-unit dot and 📏 on.
 */
object CalcColors {
    val Accent = Color(0xFF7CC6D8)
    val OnAccent = Color(0xFF0B2A33)
    val Ink = Color(0xFF1B1F24)
    val InkSecondary = Color(0xFF3A3F45)
    /** Expression line: the secondary ink (design said ink 60 %: 3.9:1 on the light glass, under 4.5:1). */
    val InkDim = Color(0xFF3A3F45)
    /** A greyed unit key: ink 62 % ≈ 4.0:1 on the glass #D7D6D4 (v0.3.3: was 55 % = 3.33:1; still clearly dimmer than ink 11.4:1). */
    val InkOff = Color(0x9E1B1F24)
    val KeyFill = Color(0x141B1F24)
    val OpFill = Color(0x241B1F24)
    /** Error text on the light glass (the design's #FF8A80 is for a dark glass: 1.9:1 here). */
    val ErrorText = Color(0xFF8C2216)
    val PlateInk = Color(0xB31B1F24)
    val Line = Color(0xFFFFFFFF)
}

/** PicoTheme on the system scheme; the app language localises resources below this point (ContextWrapper). */
@Composable
fun CalcTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.app.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        labelPrimary = CalcColors.Ink,
        labelSecondary = CalcColors.InkSecondary,
        interaction = CalcColors.Accent,
        labelPrimaryLight = CalcColors.OnAccent,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
