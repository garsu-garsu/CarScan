package com.bruni.carscan.feature.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bruni.carscan.core.data.GpsPoint
import com.bruni.carscan.core.data.HarshEventType
import com.bruni.carscan.core.data.TripEvent
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.allStringResources
import com.bruni.carscan.core.designsystem.generated.resources.trip_event_harsh_accel
import com.bruni.carscan.core.designsystem.generated.resources.trip_event_harsh_brake
import com.bruni.carscan.core.designsystem.generated.resources.trip_event_harsh_corner
import com.bruni.carscan.core.designsystem.generated.resources.trip_event_harsh_start
import com.bruni.carscan.core.designsystem.generated.resources.trip_event_harsh_stop
import com.bruni.carscan.core.designsystem.generated.resources.trip_recording
import com.bruni.carscan.core.units.Readout
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitReadout
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TripDetailScreen(modifier: Modifier = Modifier, viewModel: TripDetailViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TripDetailScreen(state, modifier)
}

@Composable
internal fun TripDetailScreen(state: TripDetailState, modifier: Modifier = Modifier) {
    // One per (locale, preferences) — same reasoning as TripScreen's.
    val languageTag = Locale.current.toLanguageTag()
    val units = remember(languageTag, state.units) { UnitReadout(languageTag, state.units) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                formatDateTime(state.startedMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = listOfNotNull(
                    distanceReadout(state, units),
                    durationText(state),
                    maxSpeedReadout(state, units),
                    consumptionReadout(state, units),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            addressLine(state)?.let { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // The GPS route already starts and ends at the trip's start/end, but a trip recorded
        // with no fix at all (e.g. a garage with no signal) has nothing to draw — fall back to
        // the two points the summary knows about, if any, so the map is never just empty water.
        val mapRoute = remember(state.route, state.startLat, state.startLon, state.endLat, state.endLon) {
            state.route.ifEmpty {
                listOfNotNull(
                    pointOrNull(state.startLat, state.startLon),
                    pointOrNull(state.endLat, state.endLon),
                )
            }
        }
        TripMap(route = mapRoute, events = state.events, modifier = Modifier.weight(1f).fillMaxWidth())

        EventLegend(state.events)
    }
}

private fun pointOrNull(lat: Double?, lon: Double?): GpsPoint? =
    if (lat != null && lon != null) GpsPoint(lat, lon) else null

@Composable
private fun EventLegend(events: List<TripEvent>) {
    if (events.isEmpty()) return
    val counts = events.groupingBy { it.type }.eachCount()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for ((type, count) in counts) {
            Text("${type.label()} $count", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun HarshEventType.label(): String = stringResource(
    when (this) {
        HarshEventType.HARSH_ACCEL -> Res.string.trip_event_harsh_accel
        HarshEventType.HARSH_BRAKE -> Res.string.trip_event_harsh_brake
        HarshEventType.HARSH_START -> Res.string.trip_event_harsh_start
        HarshEventType.HARSH_STOP -> Res.string.trip_event_harsh_stop
        HarshEventType.HARSH_CORNER -> Res.string.trip_event_harsh_corner
    },
)

/** "recording…" while [TripDetailState.durationMs] is null — the trip has no `endedMs` yet. */
@Composable
private fun durationText(state: TripDetailState): String =
    state.durationMs?.let { formatDuration(it) } ?: stringResource(Res.string.trip_recording)

@Composable
private fun distanceReadout(state: TripDetailState, units: UnitReadout): String =
    units.forValue(state.distanceM / 1_000.0, UnitId.KM, decimals = 1).render()

@Composable
private fun maxSpeedReadout(state: TripDetailState, units: UnitReadout): String =
    units.forValue(state.maxSpeedKmh, UnitId.KMH, decimals = 0).render()

/**
 * Null — the segment is dropped, not drawn as a dash — when the trip has no fuel figure. The label
 * is the preferred unit's own (L/100km, km/L, mpg), which every locale already carries.
 */
@Composable
private fun consumptionReadout(state: TripDetailState, units: UnitReadout): String? =
    state.consumptionL100km?.let { units.forValue(it, UnitId.L_PER_100KM, decimals = 1).render() }

/** "start address → arrival address", trimmed to whichever side is actually known. */
private fun addressLine(state: TripDetailState): String? =
    listOfNotNull(state.startAddress, state.endAddress).joinToString(" → ").ifBlank { null }

/** The number and its unit label, resolved and joined — same pairing TripScreen uses. */
@Composable
private fun Readout.render(): String =
    listOfNotNull(text, labelKey?.let { stringLabel(it) }).joinToString(" ")

@Composable
private fun stringLabel(key: String): String? =
    Res.allStringResources[key]?.let { stringResource(it) }
