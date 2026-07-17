package com.bruni.carscan.feature.trip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bruni.carscan.core.designsystem.ads.BannerAd
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.allStringResources
import com.bruni.carscan.core.designsystem.generated.resources.trip_empty
import com.bruni.carscan.core.designsystem.generated.resources.trip_empty_auto
import com.bruni.carscan.core.designsystem.generated.resources.trip_filter_all
import com.bruni.carscan.core.designsystem.generated.resources.trip_filter_auto
import com.bruni.carscan.core.designsystem.generated.resources.trip_filter_my_car
import com.bruni.carscan.core.designsystem.generated.resources.trip_recording
import com.bruni.carscan.core.designsystem.generated.resources.trip_source_auto
import com.bruni.carscan.core.designsystem.generated.resources.trips_title
import com.bruni.carscan.core.units.Readout
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitReadout
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TripScreen(modifier: Modifier = Modifier, viewModel: TripListViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TripScreen(state, viewModel::onIntent, modifier)
}

@Composable
internal fun TripScreen(
    state: TripListState,
    onIntent: (TripIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One per (locale, preferences), same reasoning as :feature:live's LiveScreen — the platform
    // number formatter inside is expensive to build, and this list can hold dozens of readouts.
    val languageTag = Locale.current.toLanguageTag()
    val units = remember(languageTag, state.units) { UnitReadout(languageTag, state.units) }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(Res.string.trips_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            item { FilterRow(state.filter, onIntent) }

            if (state.trips.isEmpty()) {
                item { EmptyState(state.filter) }
            } else {
                items(state.trips, key = { it.id }) { row -> TripCard(row, units) }
            }
        }

        BannerAd(Modifier.fillMaxWidth())
    }
}

@Composable
private fun FilterRow(filter: TripFilter, onIntent: (TripIntent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = filter == TripFilter.ALL,
            onClick = { onIntent(TripIntent.SetFilter(TripFilter.ALL)) },
            label = { Text(stringResource(Res.string.trip_filter_all)) },
        )
        FilterChip(
            selected = filter == TripFilter.MY_CAR,
            onClick = { onIntent(TripIntent.SetFilter(TripFilter.MY_CAR)) },
            label = { Text(stringResource(Res.string.trip_filter_my_car)) },
        )
        FilterChip(
            selected = filter == TripFilter.AUTO,
            onClick = { onIntent(TripIntent.SetFilter(TripFilter.AUTO)) },
            label = { Text(stringResource(Res.string.trip_filter_auto)) },
        )
    }
}

@Composable
private fun EmptyState(filter: TripFilter) {
    val message = stringResource(
        if (filter == TripFilter.AUTO) Res.string.trip_empty_auto else Res.string.trip_empty,
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TripCard(row: TripRow, units: UnitReadout) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatDateTime(row.startedMs), style = MaterialTheme.typography.titleSmall)
                SourceTag(row.source)
            }

            Text(
                text = "${distanceReadout(row, units)} · ${durationText(row)} · ${maxSpeedReadout(row, units)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            addressLine(row)?.let { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SourceTag(source: String) {
    SuggestionChip(
        onClick = {},
        enabled = false,
        colors = SuggestionChipDefaults.suggestionChipColors(
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            disabledLabelColor = MaterialTheme.colorScheme.primary,
        ),
        label = {
            Text(
                stringResource(
                    if (source == "GPS") Res.string.trip_source_auto else Res.string.trip_filter_my_car,
                ),
            )
        },
    )
}

/** "recording…" while [TripRow.durationMs] is null — the trip has no `endedMs` yet. */
@Composable
private fun durationText(row: TripRow): String =
    row.durationMs?.let { formatDuration(it) } ?: stringResource(Res.string.trip_recording)

@Composable
private fun distanceReadout(row: TripRow, units: UnitReadout): String =
    units.forValue(row.distanceM / 1_000.0, UnitId.KM, decimals = 1).render()

@Composable
private fun maxSpeedReadout(row: TripRow, units: UnitReadout): String =
    units.forValue(row.maxSpeedKmh, UnitId.KMH, decimals = 0).render()

/** "start address → arrival address", trimmed to whichever side is actually known. */
private fun addressLine(row: TripRow): String? =
    listOfNotNull(row.startAddress, row.endAddress).joinToString(" → ").ifBlank { null }

/** The number and its unit label, resolved and joined — same pairing LiveScreen uses. */
@Composable
private fun Readout.render(): String =
    listOfNotNull(text, labelKey?.let { stringLabel(it) }).joinToString(" ")

/**
 * `UnitId.labelKey` is a resource *key* (`"unit_km"`), not a symbol — `:core:units` has no
 * `composeResources` dependency on purpose. Resolving it is the UI's job.
 */
@Composable
private fun stringLabel(key: String): String? =
    Res.allStringResources[key]?.let { stringResource(it) }
