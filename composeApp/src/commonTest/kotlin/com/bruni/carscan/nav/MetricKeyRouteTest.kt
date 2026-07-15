package com.bruni.carscan.nav

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SuggestedMetric
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MetricKeyRouteTest {

    /**
     * The tile the user tapped is the series the chart opens on. Both halves of `MetricKey` have
     * to survive the trip, and they are not interchangeable: engine RPM has no `suggestedMetric`
     * at all, so a route that could only carry a metric could not open the app's most important
     * gauge.
     */
    @Test
    fun `both kinds of key round-trip`() {
        val metric = MetricKey.Metric(SuggestedMetric.entries.first())
        val signal = MetricKey.Signal("RPM")

        decodeMetricKeyRoute(metric.encodeForRoute()) shouldBe metric
        decodeMetricKeyRoute(signal.encodeForRoute()) shouldBe signal
    }

    /** A signal id may contain anything OBDb put in it, including the delimiter. */
    @Test
    fun `a signal id containing a colon survives`() {
        val key = MetricKey.Signal("7E0:0142")
        decodeMetricKeyRoute(key.encodeForRoute()) shouldBe key
    }

    /**
     * A route from a newer build, or a metric OBDb has since renamed, must land the user on the
     * live screen with nothing selected — not crash them out of the app.
     */
    @Test
    fun `an unrecognised route decodes to nothing rather than throwing`() {
        decodeMetricKeyRoute("metric:NOT_A_METRIC") shouldBe null
        decodeMetricKeyRoute("nonsense") shouldBe null
        decodeMetricKeyRoute("signal:") shouldBe null
    }
}
