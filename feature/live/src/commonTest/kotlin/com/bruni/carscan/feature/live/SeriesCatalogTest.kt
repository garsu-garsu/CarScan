package com.bruni.carscan.feature.live

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SuggestedMetric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesCatalogTest {

    /**
     * The range comes from the signal, not from the samples.
     *
     * `LivePlot` is drawn against `fmt.min`/`fmt.max`, and this is where they come from.
     * Autoscaling a tachometer to the values it has seen so far makes idle look like redline.
     */
    @Test
    fun `a spec carries the signal's own range, name and native unit`() {
        val signalset = signalset(
            signal("RPM", name = "Engine RPM", min = 0.0, max = 16383.75, unit = ObdUnit.RPM),
        )

        val spec = signalset.seriesSpec(MetricKey.Signal("RPM"))!!

        assertEquals("Engine RPM", spec.label)
        assertEquals(0f, spec.min)
        assertEquals(16383.75f, spec.max)
        assertEquals(ObdUnit.RPM, spec.unit)
    }

    /**
     * A signal that OBDb gives a metric is offered under the metric, and *only* under it — even
     * though the signalset indexes it under both. Offering both is offering the same series twice
     * with the same name, and the user picks one at random.
     */
    @Test
    fun `a signal with a metric is offered once, under its metric`() {
        val signalset = signalset(
            signal("VSS", name = "Speed", max = 255.0, unit = ObdUnit.KILOMETERS_PER_HOUR, metric = SuggestedMetric.SPEED),
        )

        val keys = signalset.seriesOptions(unsupported = emptySet()).map { it.key }

        assertEquals(listOf(MetricKey.Metric(SuggestedMetric.SPEED)), keys)
    }

    /** Engine RPM has no `suggestedMetric` — the reason MetricKey exists — so it goes by id. */
    @Test
    fun `a signal without a metric is offered under its signal id`() {
        val signalset = signalset(signal("RPM", name = "Engine RPM", max = 16383.75))

        val keys = signalset.seriesOptions(unsupported = emptySet()).map { it.key }

        assertEquals(listOf(MetricKey.Signal("RPM")), keys)
    }

    /**
     * A PID the car has stopped answering is a tile that is stale forever, and the user blames
     * the app rather than the car.
     */
    @Test
    fun `a signal whose only command is unsupported is not offered`() {
        val signalset = signalset(
            signal("RPM", name = "Engine RPM", max = 16383.75),
            signal("MAF", name = "Mass Air Flow", max = 655.35, command = "7E0.0110"),
        )

        val options = signalset.seriesOptions(unsupported = setOf("7E0.0110"))

        assertEquals(listOf(MetricKey.Signal("RPM")), options.map { it.key })
    }

    /**
     * A chart needs a y-range, and `fmt.max` is optional in OBDb. Charting a signal without one
     * would mean inventing the scale — and an invented scale is indistinguishable from a real one
     * once there is a line drawn against it.
     */
    @Test
    fun `a signal with no declared maximum cannot be charted`() {
        val signalset = signalset(signal("ODO", name = "Odometer", max = null))

        assertNull(signalset.seriesSpec(MetricKey.Signal("ODO")))
        assertTrue(signalset.seriesOptions(unsupported = emptySet()).isEmpty())
    }

    /** The VIN is a string and the MIL is a flag. Neither is a line on a chart. */
    @Test
    fun `a non-numeric signal is not offered`() {
        val signalset = signalset(
            signal("VIN", name = "VIN", max = null, unit = ObdUnit.ASCII),
            signal("MIL", name = "Check engine", max = 1.0, unit = ObdUnit.OFFON),
        )

        assertTrue(signalset.seriesOptions(unsupported = emptySet()).isEmpty())
    }
}
