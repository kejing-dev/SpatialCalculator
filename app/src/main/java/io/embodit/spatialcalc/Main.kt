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
 * One Mixed Stage (manifest: pico.spatial.stage.style = 1) holds the 1:1 length line. The calculator itself is one
 * Planar WindowContainer, 400 × 664 dp, fixed size (design windowModel), opened from the Stage through the level gate
 * (WindowCtl). Dragging / closing are the system window controls (D21).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            CalcTheme { StageScreen() }
        }
        WindowContainer(
            Containers.MAIN,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 400.dp, height = 664.dp),
        ) {
            CalcTheme {
                Box(Modifier.windowConstraints(minWidth = 400.dp, minHeight = 664.dp, maxWidth = 400.dp, maxHeight = 664.dp)) { MainWindowUi() }
            }
        }
    }
