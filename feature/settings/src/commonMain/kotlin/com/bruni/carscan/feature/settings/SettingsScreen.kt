package com.bruni.carscan.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.allStringResources
import com.bruni.carscan.core.designsystem.generated.resources.settings_about
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style_classic_analog
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style_modern_arc
import com.bruni.carscan.core.designsystem.generated.resources.settings_keep_screen_on
import com.bruni.carscan.core.designsystem.generated.resources.settings_quantity_consumption
import com.bruni.carscan.core.designsystem.generated.resources.settings_quantity_distance
import com.bruni.carscan.core.designsystem.generated.resources.settings_quantity_energy_consumption
import com.bruni.carscan.core.designsystem.generated.resources.settings_quantity_power
import com.bruni.carscan.core.designsystem.generated.resources.settings_quantity_pressure
import com.bruni.carscan.core.designsystem.generated.resources.settings_quantity_speed
import com.bruni.carscan.core.designsystem.generated.resources.settings_quantity_temperature
import com.bruni.carscan.core.designsystem.generated.resources.settings_quantity_torque
import com.bruni.carscan.core.designsystem.generated.resources.settings_quantity_volume
import com.bruni.carscan.core.designsystem.generated.resources.settings_record_trips
import com.bruni.carscan.core.designsystem.generated.resources.settings_theme
import com.bruni.carscan.core.designsystem.generated.resources.settings_theme_dark
import com.bruni.carscan.core.designsystem.generated.resources.settings_theme_light
import com.bruni.carscan.core.designsystem.generated.resources.settings_theme_system
import com.bruni.carscan.core.designsystem.generated.resources.settings_title
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.UnitId
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.headlineSmall) }

        // One row per quantity — never a single metric/imperial switch. See UnitPreferences.
        items(Quantity.entries, key = { it }) { quantity ->
            OptionRow(
                label = stringResource(quantity.label()),
                options = UnitId.entries.filter { it.quantity == quantity }.map { unit ->
                    Option(
                        label = unitLabel(unit),
                        selected = state.units[quantity] == unit,
                        onClick = { onIntent(SettingsIntent.SetUnit(quantity, unit)) },
                    )
                },
            )
        }

        item {
            OptionRow(
                label = stringResource(Res.string.settings_gauge_style),
                options = listOf(
                    Option(
                        label = stringResource(Res.string.settings_gauge_style_modern_arc),
                        selected = state.gaugeStyle == "MODERN_ARC",
                        onClick = { onIntent(SettingsIntent.SetGaugeStyle("MODERN_ARC")) },
                    ),
                    Option(
                        label = stringResource(Res.string.settings_gauge_style_classic_analog),
                        selected = state.gaugeStyle == "CLASSIC_ANALOG",
                        onClick = { onIntent(SettingsIntent.SetGaugeStyle("CLASSIC_ANALOG")) },
                    ),
                ),
            )
        }

        item {
            OptionRow(
                label = stringResource(Res.string.settings_theme),
                options = listOf(
                    Option(
                        label = stringResource(Res.string.settings_theme_system),
                        selected = state.themeMode == ThemeMode.SYSTEM,
                        onClick = { onIntent(SettingsIntent.SetThemeMode(ThemeMode.SYSTEM)) },
                    ),
                    Option(
                        label = stringResource(Res.string.settings_theme_light),
                        selected = state.themeMode == ThemeMode.LIGHT,
                        onClick = { onIntent(SettingsIntent.SetThemeMode(ThemeMode.LIGHT)) },
                    ),
                    Option(
                        label = stringResource(Res.string.settings_theme_dark),
                        selected = state.themeMode == ThemeMode.DARK,
                        onClick = { onIntent(SettingsIntent.SetThemeMode(ThemeMode.DARK)) },
                    ),
                ),
            )
        }

        item {
            SwitchRow(
                label = stringResource(Res.string.settings_keep_screen_on),
                checked = state.keepScreenOn,
                onCheckedChange = { onIntent(SettingsIntent.SetKeepScreenOn(it)) },
            )
        }

        item {
            SwitchRow(
                label = stringResource(Res.string.settings_record_trips),
                checked = state.recordTrips,
                onCheckedChange = { onIntent(SettingsIntent.SetRecordTrips(it)) },
            )
        }

        item {
            Text(
                text = stringResource(Res.string.settings_about),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onIntent(SettingsIntent.OpenAbout) }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

private data class Option(val label: String, val selected: Boolean, val onClick: () -> Unit)

@Composable
private fun OptionRow(label: String, options: List<Option>) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (option in options) {
                FilterChip(selected = option.selected, onClick = option.onClick, label = { Text(option.label) })
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun Quantity.label(): StringResource = when (this) {
    Quantity.SPEED -> Res.string.settings_quantity_speed
    Quantity.DISTANCE -> Res.string.settings_quantity_distance
    Quantity.PRESSURE -> Res.string.settings_quantity_pressure
    Quantity.TEMPERATURE -> Res.string.settings_quantity_temperature
    Quantity.VOLUME -> Res.string.settings_quantity_volume
    Quantity.CONSUMPTION -> Res.string.settings_quantity_consumption
    Quantity.ENERGY_CONSUMPTION -> Res.string.settings_quantity_energy_consumption
    Quantity.POWER -> Res.string.settings_quantity_power
    Quantity.TORQUE -> Res.string.settings_quantity_torque
}

/**
 * `UnitId.labelKey` is a resource *key* (`"unit_mph"`), not a symbol — `:core:units` has no
 * `composeResources` dependency on purpose. Resolving it is the UI's job.
 */
@Composable
private fun unitLabel(unit: UnitId): String =
    Res.allStringResources[unit.labelKey]?.let { stringResource(it) } ?: unit.labelKey
