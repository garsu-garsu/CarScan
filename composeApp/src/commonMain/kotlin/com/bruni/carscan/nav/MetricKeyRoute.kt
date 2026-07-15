package com.bruni.carscan.nav

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SuggestedMetric

/**
 * A [MetricKey] as a navigation argument.
 *
 * Hand-written, and **not** `kotlinx.serialization`'s default for a sealed class: that
 * discriminates on the fully-qualified class name, so `MetricKey.Metric` would travel as
 * `"com.bruni.carscan.core.model.MetricKey.Metric"` — and every link the user could ever save or
 * share would break the day the class moved package. The same reasoning already governs the saved
 * dashboard layout.
 *
 * An unknown metric name decodes to null rather than throwing. A route from a newer build, or a
 * metric OBDb has since renamed, must land the user on the live screen with nothing selected —
 * not crash them out of the app.
 */
internal fun MetricKey.encodeForRoute(): String = when (this) {
    is MetricKey.Metric -> "$METRIC:${metric.name}"
    is MetricKey.Signal -> "$SIGNAL:$signalId"
}

internal fun decodeMetricKeyRoute(encoded: String): MetricKey? {
    val prefix = encoded.substringBefore(':', missingDelimiterValue = "")
    val value = encoded.substringAfter(':', missingDelimiterValue = "")
    if (value.isEmpty()) return null

    return when (prefix) {
        METRIC -> SuggestedMetric.entries.firstOrNull { it.name == value }?.let(MetricKey::Metric)
        SIGNAL -> MetricKey.Signal(value)
        else -> null
    }
}

private const val METRIC = "metric"
private const val SIGNAL = "signal"
