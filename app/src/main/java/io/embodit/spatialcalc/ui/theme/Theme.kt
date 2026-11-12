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
import io.embodit.spatialcalc.domain.session.Language
import io.embodit.spatialcalc.platform.Graph
import java.util.Locale

/** design-package palette: 量算绿 primary, 量值黄 secondary (input), 结果蓝 tertiary (output), 装不下橙红 error. */
object CalcColors {
    val Primary = Color(0xFF2F7F74)
    val PrimaryDeep = Color(0xFF256A61)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFCDEDE6)
    val OnPrimaryContainer = Color(0xFF0F3B35)
    val Secondary = Color(0xFFC98A1B)
    val OnSecondary = Color(0xFF1F1600)
    val SecondaryContainer = Color(0xFFF6E3B4)
    val OnSecondaryContainer = Color(0xFF3D2B00)
    val Tertiary = Color(0xFF3F8FD2)
    val TertiaryText = Color(0xFF0F3F69)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFD3E7F8)
    val OnTertiaryContainer = Color(0xFF0E2E4A)
    val Error = Color(0xFFB5441F)
    val OnError = Color(0xFFFFFFFF)
    /** Error text on the window glass (the brighter Error stays for fills, D13 contrast table). */
    val ErrorText = Color(0xFF6E200C)
    val Ink = Color(0xFF1B1F24)
    val InkSecondary = Color(0xFF3A3F45)
    val OpChip = Color(0xFFE4E6E9)
    val NumberChip = Color(0xFFFFFFFF)

    // brand tokens (3D)
    val ResultBlue = Color(0xFF4FA3E0)
    val ResultEdge = Color(0xFFBFE3FF)
    val WaterBlue = Color(0xFF3C8FD6)
    val WaterLine = Color(0xFFBFE3FF)
    val TapeYellow = Color(0xFFF2C230)
    val TapeInk = Color(0xFF1B1F24)
    val CutAmber = Color(0xFFF08A24)
    val PlateInk = Color(0xEB1B1F24)
    val GhostBox = Color(0xFF7FB7E8)
    val Grout = Color(0xFF3A4048)
    val Tile = Color(0xFFE9EEF2)
    val Paint = Color(0xFF7FC8A9)
    val Rug = Color(0xFFC9A27A)
    val Steel = Color(0xFFBABDBF)
    val IconBg = Color(0xFF1F4D3A)
}

/**
 * PicoTheme with the 16 roles (Cloud Air pattern). The app language localises resources below this point through a
 * ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun CalcTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.flow.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        labelPrimaryLight = CalcColors.OnPrimary,
        labelPrimary = CalcColors.Ink,
        labelSecondary = CalcColors.InkSecondary,
        error = CalcColors.Error,
        alert = CalcColors.Secondary,
        passable = CalcColors.Primary,
        interaction = CalcColors.PrimaryDeep,
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
