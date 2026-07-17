package com.bruni.carscan.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.allStringResources
import com.bruni.carscan.core.designsystem.generated.resources.settings_about
import com.bruni.carscan.core.designsystem.generated.resources.settings_acq_dashboard
import com.bruni.carscan.core.designsystem.generated.resources.settings_acq_hud
import com.bruni.carscan.core.designsystem.generated.resources.settings_acq_monitoring
import com.bruni.carscan.core.designsystem.generated.resources.settings_acquisition_source
import com.bruni.carscan.core.designsystem.generated.resources.settings_auto_reconnect
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style_classic_analog
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style_modern_arc
import com.bruni.carscan.core.designsystem.generated.resources.settings_keep_screen_on
import com.bruni.carscan.core.designsystem.generated.resources.settings_premium
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
import com.bruni.carscan.core.designsystem.generated.resources.settings_vehicle
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(Res.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            SettingsCard {
                NavigationRow(
                    icon = Icons.Rounded.Star,
                    label = stringResource(Res.string.settings_premium),
                    onClick = { onIntent(SettingsIntent.OpenPremium) },
                )
            }
        }

        item {
            SettingsCard {
                // One row per quantity — never a single metric/imperial switch. See UnitPreferences.
                Quantity.entries.forEachIndexed { index, quantity ->
                    OptionRow(
                        icon = Icons.Rounded.Straighten,
                        label = stringResource(quantity.label()),
                        options = UnitId.entries.filter { it.quantity == quantity }.map { unit ->
                            Option(
                                label = unitLabel(unit),
                                selected = state.units[quantity] == unit,
                                onClick = { onIntent(SettingsIntent.SetUnit(quantity, unit)) },
                            )
                        },
                    )
                    if (index != Quantity.entries.lastIndex) RowDivider()
                }
            }
        }

        item {
            SettingsCard {
                OptionRow(
                    icon = Icons.Rounded.Speed,
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
                RowDivider()
                OptionRow(
                    icon = Icons.Rounded.DarkMode,
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
        }

        item {
            SettingsCard {
                SwitchRow(
                    icon = Icons.Rounded.Visibility,
                    label = stringResource(Res.string.settings_keep_screen_on),
                    checked = state.keepScreenOn,
                    onCheckedChange = { onIntent(SettingsIntent.SetKeepScreenOn(it)) },
                )
                RowDivider()
                SwitchRow(
                    icon = Icons.Rounded.FiberManualRecord,
                    label = stringResource(Res.string.settings_record_trips),
                    checked = state.recordTrips,
                    onCheckedChange = { onIntent(SettingsIntent.SetRecordTrips(it)) },
                )
                RowDivider()
                SwitchRow(
                    icon = Icons.Rounded.Bluetooth,
                    label = stringResource(Res.string.settings_auto_reconnect),
                    checked = state.autoReconnect,
                    onCheckedChange = { onIntent(SettingsIntent.SetAutoReconnect(it)) },
                )
            }
        }

        item {
            SettingsCard {
                // Keeps the poller — and trip recording with it — alive on this screen's signals
                // once the user navigates off it. See AcquisitionController.
                OptionRow(
                    icon = Icons.AutoMirrored.Rounded.ShowChart,
                    label = stringResource(Res.string.settings_acquisition_source),
                    options = listOf(
                        Option(
                            label = stringResource(Res.string.settings_acq_dashboard),
                            selected = state.acquisitionSource == AcquisitionSource.DASHBOARD,
                            onClick = {
                                onIntent(SettingsIntent.SetAcquisitionSource(AcquisitionSource.DASHBOARD))
                            },
                        ),
                        Option(
                            label = stringResource(Res.string.settings_acq_monitoring),
                            selected = state.acquisitionSource == AcquisitionSource.MONITORING,
                            onClick = {
                                onIntent(SettingsIntent.SetAcquisitionSource(AcquisitionSource.MONITORING))
                            },
                        ),
                        Option(
                            label = stringResource(Res.string.settings_acq_hud),
                            selected = state.acquisitionSource == AcquisitionSource.HUD,
                            onClick = {
                                onIntent(SettingsIntent.SetAcquisitionSource(AcquisitionSource.HUD))
                            },
                        ),
                    ),
                )
            }
        }

        item {
            SettingsCard {
                NavigationRow(
                    icon = Icons.Rounded.DirectionsCar,
                    label = stringResource(Res.string.settings_vehicle),
                    onClick = { onIntent(SettingsIntent.OpenVehicle) },
                )
                RowDivider()
                NavigationRow(
                    icon = Icons.Rounded.Info,
                    label = stringResource(Res.string.settings_about),
                    onClick = { onIntent(SettingsIntent.OpenAbout) },
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

/** A colour-lit icon badge matching the home launcher's tile icons. */
@Composable
private fun IconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

private data class Option(val label: String, val selected: Boolean, val onClick: () -> Unit)

@Composable
private fun OptionRow(icon: ImageVector, label: String, options: List<Option>) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (option in options) {
                    FilterChip(selected = option.selected, onClick = option.onClick, label = { Text(option.label) })
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Spacer(Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NavigationRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Spacer(Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A hairline separating stacked rows inside one card, indented past the icon badge. */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 76.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
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
