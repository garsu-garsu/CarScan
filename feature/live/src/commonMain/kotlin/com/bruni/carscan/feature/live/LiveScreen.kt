package com.bruni.carscan.feature.live

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bruni.carscan.core.designsystem.chart.LivePlot
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.allStringResources
import com.bruni.carscan.core.designsystem.generated.resources.common_no_reading
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

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SeriesPicker(state, onIntent)

        // Nothing charted yet. The chips above are populated from the vehicle's signalset, so this
        // is a prompt rather than an error — there is nothing wrong, the user just has not picked.
        if (state.charted.isEmpty()) {
            Text(
                text = stringResource(Res.string.live_no_series_selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Keyed on the MetricKey, so selecting a second series does not tear down the first one's
        // plot and throw away the 30 s of trace it is holding.
        state.charted.forEachIndexed { index, series ->
            key(series.key) { SeriesCard(series, units, seriesColour(index)) }
        }

        state.history?.let { history ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = listOfNotNull(history.label, history.displayUnit?.labelKey?.let { stringLabel(it) })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                )
                HistoryChart(
                    history = history,
                    colour = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }
        }
    }
}

@Composable
private fun SeriesPicker(state: LiveUiState, onIntent: (LiveIntent) -> Unit) {
    val charted = remember(state.charted) { state.charted.mapTo(HashSet()) { it.key } }

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.available.forEach { option ->
            FilterChip(
                selected = option.key in charted,
                onClick = { onIntent(LiveIntent.ToggleSeries(option.key)) },
                label = { Text(option.label) },
            )
        }
    }
}

@Composable
private fun SeriesCard(series: LiveSeries, units: UnitReadout, colour: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(series.label, style = MaterialTheme.typography.labelLarge)
            Readout(series, units)
        }

        LivePlot(
            state = series.plot,
            min = series.min,
            max = series.max,
            color = colour,
            modifier = Modifier.fillMaxWidth().height(96.dp),
        )
    }
}

/**
 * The only composable that reads [LiveSeries.latest], and it is its own function for that reason.
 *
 * `latest` is snapshot state written on every sample, so whatever composable reads it recomposes at
 * the sample rate. Confined here, that costs one `Text` twenty times a second. Read it one level
 * up — in [SeriesCard] — and it would recompose the `LivePlot` beside it just as often, undoing
 * from the outside the very thing `LivePlot` reads its revision counter inside a draw lambda to
 * avoid.
 *
 * **No test enforces this.** It needs a Compose UI test, which cannot live in `commonTest` (it dies
 * in `androidHostTest` on a null `Build.FINGERPRINT`), and a `skikoTest` source set was ruled out:
 * a `jvm()` target on a feature module drags one onto `:core:data` → `:core:transport` → Kable,
 * whose JVM support is unverified. So the invariant is a comment, and this comment is all there is.
 * If you hoist the `series.latest` read out of this function, nothing will go red — and the live
 * chart will silently recompose 600 times in 30 seconds. `LivePlotUiTest` in `:core:designsystem`
 * is the gate for the layer below; there is no gate for this one.
 */
@Composable
private fun Readout(series: LiveSeries, units: UnitReadout) {
    val numbers = LocalNumberFormatter.current
    val noReading = stringResource(Res.string.common_no_reading)

    // A signal spanning 8000 rpm does not want a decimal place; one spanning 5 volts needs one.
    val decimals = remember(series) { if (series.max - series.min >= 100f) 0 else 1 }

    // Everything above is hoisted deliberately: the read of `latest` is the last thing that
    // happens, so a sample invalidates this Text and nothing around it.
    val value = series.latest

    val text = when {
        // Nothing has arrived yet. An em dash, not a zero the car never reported.
        value == null -> noReading
        // Converted and formatted in one call — the only supported way to put a converted number
        // on screen. Six of our eight locales write 13,8 rather than 13.8.
        series.nativeUnit != null -> units.forSample(value, series.nativeUnit, decimals).render()
        // No unit at all: formatted, never converted, and no suffix to hang on it.
        else -> numbers.format(value, decimals)
    }

    Text(text, style = MaterialTheme.typography.titleMedium)
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

/** Chart colours, cycled. Theme colours, so the strip follows the light/dark scheme. */
@Composable
private fun seriesColour(index: Int): Color {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.error)
    return palette[index % palette.size]
}
