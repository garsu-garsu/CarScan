package com.bruni.carscan.feature.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bruni.carscan.core.designsystem.chart.LivePlot
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.allStringResources
import com.bruni.carscan.core.designsystem.generated.resources.common_no_reading
import com.bruni.carscan.core.designsystem.generated.resources.live_avg
import com.bruni.carscan.core.designsystem.generated.resources.live_max
import com.bruni.carscan.core.designsystem.generated.resources.live_min
import com.bruni.carscan.core.designsystem.generated.resources.live_no_series_selected
import com.bruni.carscan.core.designsystem.theme.LocalNumberFormatter
import com.bruni.carscan.core.units.Readout
import com.bruni.carscan.core.units.UnitReadout
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LiveScreen(modifier: Modifier = Modifier, viewModel: LiveViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiveScreen(state, viewModel::onIntent, modifier)
}

@Composable
internal fun LiveScreen(
    state: LiveUiState,
    onIntent: (LiveIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One per (locale, preferences), not one per call: the platform number formatter inside is
    // expensive to build and this screen formats a readout per sample. The locale comes from the
    // same place `CarScanTheme` takes it, so the readouts here cannot disagree with the gauges
    // there about whether a number is written 13.8 or 13,8.
    val languageTag = Locale.current.toLanguageTag()
    val units = remember(languageTag, state.units) { UnitReadout(languageTag, state.units) }

    // "Poll everything while this screen is foreground" — see LiveViewModel's KDoc. This fires on
    // every fresh composition of this screen (a re-entry after another screen took the poller's
    // attention) *and* whenever `available` changes content, which covers the reactive case too —
    // the VM's own combine collector already handles that half, so this is deliberately redundant
    // there and load-bearing only on re-entry.
    LaunchedEffect(state.available) { onIntent(LiveIntent.ScreenVisible) }

    val detail = state.detail
    if (detail != null) {
        DetailScreen(detail, units, onIntent, modifier)
    } else {
        SeriesList(state, units, onIntent, modifier)
    }
}

@Composable
private fun SeriesList(
    state: LiveUiState,
    units: UnitReadout,
    onIntent: (LiveIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        // Nothing to show yet — no vehicle connected, or its signalset has not loaded. A prompt
        // rather than an error: there is nothing wrong, the car just has not answered yet.
        if (state.rows.isEmpty()) {
            Text(
                text = stringResource(Res.string.live_no_series_selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Keyed on the MetricKey, so a row further down the list keeps its own identity (and
            // its accumulated stats) as the list is scrolled and recomposed.
            // A String key, not the MetricKey itself: LazyColumn persists item keys in a Bundle
            // for scroll-state restoration, and a MetricKey (a sealed data class) is not Bundleable
            // — passing it crashes the moment the list is measured. toString() is stable and unique.
            items(state.rows, key = { it.key.toString() }) { row ->
                SeriesRow(
                    row = row,
                    bookmarked = row.key in state.bookmarked,
                    units = units,
                    onTap = { onIntent(LiveIntent.Select(row.key)) },
                    onToggleBookmark = { onIntent(LiveIntent.ToggleBookmark(row.key)) },
                )
            }
        }
    }
}

/**
 * One row: label and running stats on the left, the live readout and a bookmark star on the
 * right. No graph — see LiveViewModel's KDoc for why only the detail signal gets one.
 */
@Composable
private fun SeriesRow(
    row: LiveSeries,
    bookmarked: Boolean,
    units: UnitReadout,
    onTap: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            StatsLine(row, units)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatText(row, units, MaterialTheme.typography.titleMedium) { it.latest }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    imageVector = if (bookmarked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = null,
                    tint = if (bookmarked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** The running min / avg / max, in the same reduced style throughout a row. */
@Composable
private fun StatsLine(row: LiveSeries, units: UnitReadout) {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LabelledStat(stringResource(Res.string.live_min), row, units, style, color) { it.min }
        LabelledStat(stringResource(Res.string.live_avg), row, units, style, color) { it.avg }
        LabelledStat(stringResource(Res.string.live_max), row, units, style, color) { it.max }
    }
}

@Composable
private fun LabelledStat(
    label: String,
    row: LiveSeries,
    units: UnitReadout,
    style: TextStyle,
    color: Color,
    select: (LiveSeries) -> Double?,
) {
    Row {
        Text("$label ", style = style, color = color)
        StatText(row, units, style, color, select)
    }
}

/**
 * The only place any of [LiveSeries]'s four snapshot fields is read, parameterised by which one.
 *
 * Each call site is its own composable invocation — a separate recomposition scope — so a sample
 * invalidates exactly the one `Text` whose [select] reads a field that changed, never the row
 * around it. That is the same discipline the old chart's `Readout` documented at length; see this
 * file's git history (or `SeriesRow`/`DetailScreen`, its two call sites) for why it still matters
 * with no graph on this screen: thirty rows updating their own `latest`/`avg` twenty times a
 * second must not recompose the `LazyColumn` they live in.
 */
@Composable
private fun StatText(
    row: LiveSeries,
    units: UnitReadout,
    style: TextStyle,
    color: Color = Color.Unspecified,
    select: (LiveSeries) -> Double?,
) {
    val numbers = LocalNumberFormatter.current
    val noReading = stringResource(Res.string.common_no_reading)

    // The read of `select(row)` is the last thing that happens, so a sample invalidates this
    // `Text` and nothing else.
    val value = select(row)

    val text = when {
        // Nothing has arrived yet. An em dash, not a zero the car never reported.
        value == null -> noReading
        // Converted and formatted in one call — the only supported way to put a converted number
        // on screen. Six of our eight locales write 13,8 rather than 13.8.
        row.nativeUnit != null -> units.forSample(value, row.nativeUnit, row.decimals).render()
        // No unit at all: formatted, never converted, and no suffix to hang on it.
        else -> numbers.format(value, row.decimals)
    }

    Text(text, style = style, color = color)
}

/** The number and its unit label, resolved and joined. */
@Composable
private fun Readout.render(): String =
    listOfNotNull(text, labelKey?.let { stringLabel(it) }).joinToString(" ")

/**
 * `UnitId.labelKey` and `ObdUnit.asIsLabelKey` are resource *keys* (`"unit_mph"`), not symbols —
 * `:core:units` has no `composeResources` dependency on purpose. Resolving them is the UI's job,
 * and drawing the key instead would put the literal text "unit_mph" on screen next to the number.
 */
@Composable
private fun stringLabel(key: String): String? =
    Res.allStringResources[key]?.let { stringResource(it) }

/**
 * One signal, full-screen: its live plot and its running stats. The only screen that keeps a
 * [LivePlot] alive — see [LiveViewModel.onSample].
 */
@Composable
private fun DetailScreen(
    detail: DetailUiState,
    units: UnitReadout,
    onIntent: (LiveIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onIntent(LiveIntent.CloseDetail) }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = detail.series.label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
        }

        StatText(detail.series, units, MaterialTheme.typography.headlineMedium) { it.latest }
        StatsLine(detail.series, units)

        LivePlot(
            state = detail.plot,
            min = detail.min,
            max = detail.max,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().height(220.dp),
        )
    }
}
