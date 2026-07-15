package com.bruni.carscan.feature.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bruni.carscan.core.designsystem.gauge.Gauge
import com.bruni.carscan.core.designsystem.gauge.ModernArcGauge
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.allStringResources
import com.bruni.carscan.core.designsystem.generated.resources.gauge_engine_speed
import com.bruni.carscan.core.designsystem.generated.resources.gauge_vehicle_speed
import com.bruni.carscan.core.designsystem.theme.GaugeThemes
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.asIsLabelKey
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The windshield HUD. Self-contained: resolves its own [HudViewModel] and forces the display's
 * keep-awake/max-brightness/landscape condition for as long as it is composed (via
 * [HudDisplayEffect]) — `:composeApp` only has to navigate here, there is nothing else to wire.
 *
 * A HUD is read in the windshield's *reflection*, so the whole tile is mirrored — one
 * `graphicsLayer` flip around the entire content, not one per gauge — on a plain black
 * [Surface], since [Gauge] renderers fill no background of their own.
 */
@Composable
fun HudScreen() {
    HudDisplayEffect()

    val viewModel: HudViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .graphicsLayer { scaleX = -1f },
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.readings.forEach { reading ->
                Gauge(
                    spec = reading.spec.copy(
                        label = hudMetricLabel(reading.key),
                        unitLabel = hudUnitLabel(reading.displayUnit, reading.nativeUnit),
                    ),
                    theme = GaugeThemes.Hud,
                    renderer = ModernArcGauge(),
                    // Speed is the gauge that matters most at a glance — twice the RPM gauge's share.
                    modifier = Modifier.weight(if (reading.key == HUD_SPEED_KEY) 2f else 1f).fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun hudMetricLabel(key: MetricKey): String = when (key) {
    HUD_SPEED_KEY -> stringResource(Res.string.gauge_vehicle_speed)
    HUD_RPM_KEY -> stringResource(Res.string.gauge_engine_speed)
    else -> ""
}

/**
 * `UnitId.labelKey` / `ObdUnit.asIsLabelKey` are resource *keys*, not symbols — `:core:units` has
 * no `composeResources` dependency on purpose. Resolved the same way `feature/settings`'s
 * `SettingsScreen.unitLabel` does, rather than duplicated as a compile-checked `when` the way
 * `feature/dashboard`'s `UnitLabels` does.
 */
@Composable
private fun hudUnitLabel(displayUnit: UnitId?, nativeUnit: ObdUnit?): String {
    val key = displayUnit?.labelKey ?: nativeUnit?.asIsLabelKey ?: return ""
    return Res.allStringResources[key]?.let { stringResource(it) } ?: key
}
