package io.embodit.spatialcalc

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.spatialcalc.ui.Containers
import io.embodit.spatialcalc.ui.stage.StageScreen
import io.embodit.spatialcalc.ui.theme.CalcTheme
import io.embodit.spatialcalc.ui.window.MainWindowUi

/**
 * One Mixed Stage (manifest: pico.spatial.stage.style = 1) holds the room content: pins, tapes, result bodies, plates.
 * All 2D UI lives in one Planar WindowContainer (design windowModel single_system_window: 560 × 480 dp, up to 560 high),
 * opened from the Stage through the level gate (WindowCtl). Dragging / closing are the system window controls (D21).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            CalcTheme { StageScreen() }
        }
        WindowContainer(
            Containers.MAIN,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 560.dp),
        ) {
            CalcTheme {
                Box(Modifier.windowConstraints(minWidth = 560.dp, minHeight = 480.dp, maxWidth = 760.dp, maxHeight = 640.dp)) { MainWindowUi() }
            }
        }
    }
