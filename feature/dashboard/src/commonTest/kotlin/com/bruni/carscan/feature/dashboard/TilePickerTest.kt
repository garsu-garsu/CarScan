package com.bruni.carscan.feature.dashboard

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.model.obdb.MapEntry
import com.bruni.carscan.core.model.obdb.ObdbCommand
import com.bruni.carscan.core.model.obdb.commandId
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TilePickerTest {

    private fun picker(
        vararg commands: ObdbCommand,
        unsupported: Set<String> = emptySet(),
    ) = availableTiles(
        EffectiveSignalset.of(
            standard = signalset(*commands),
            vehicle = signalset(),
            modelYear = 2023,
        ),
        unsupported,
    )

    @Test
    fun `there is nothing to offer before a vehicle is connected`() {
        availableTiles(null, emptySet()) shouldContainExactly emptyList()
    }

    /**
     * A gauge is a numeric dial. The VIN is an `ascii` signal — it decodes to text, it has no
     * scale, and a needle pointing at it means nothing.
     */
    @Test
    fun `text signals are not offered as gauges`() {
        val entries = picker(
            command(
                pid = "02",
                signals = arrayOf(
                    signal("VIN", unit = ObdUnit.ASCII),
                    signal("RPM", unit = ObdUnit.RPM, max = 8000.0),
                ),
            ),
        )
        entries.map { it.label } shouldContainExactly listOf("RPM")
    }

    /** Likewise an enumerated signal: `fmt.map` turns a raw integer into a labeled state. */
    @Test
    fun `enumerated signals are not offered as gauges`() {
        val entries = picker(
            command(
                pid = "03",
                signals = arrayOf(
                    signal("FUELSYS", map = mapOf("1" to MapEntry("Open loop"))),
                    signal("RPM", unit = ObdUnit.RPM, max = 8000.0),
                ),
            ),
        )
        entries.map { it.label } shouldContainExactly listOf("RPM")
    }

    /** OBDb marks internal signals `hidden`. They are plumbing, not something to put on a dial. */
    @Test
    fun `hidden signals are not offered`() {
        val entries = picker(
            command(
                pid = "04",
                signals = arrayOf(
                    signal("INTERNAL", unit = ObdUnit.SCALAR, max = 1.0, hidden = true),
                    signal("RPM", unit = ObdUnit.RPM, max = 8000.0),
                ),
            ),
        )
        entries.map { it.label } shouldContainExactly listOf("RPM")
    }

    /**
     * The exclusion the brief asks for. A command the poller has struck off after repeated
     * NO_DATA can never answer again, so a tile on it would be stale forever — and the user reads
     * a permanently stale tile as a broken app, not as a sensor their trim level does not have.
     */
    @Test
    fun `a signal whose only command is unsupported is not offered`() {
        val boost = command(
            hdr = "7E0",
            pid = "70",
            signals = arrayOf(signal("BOOST", unit = ObdUnit.KILOPASCAL, max = 300.0)),
        )
        val rpm = command(
            hdr = "7E0",
            pid = "0C",
            signals = arrayOf(signal("RPM", unit = ObdUnit.RPM, max = 8000.0)),
        )
        picker(boost, rpm).map { it.label } shouldContainExactly listOf("BOOST", "RPM")

        // Header-qualified, exactly as PidScheduler.unsupported reports it.
        picker(boost, rpm, unsupported = setOf(boost.commandId())).map { it.label } shouldContainExactly
            listOf("RPM")
    }

    @Test
    fun `an experimental signal is offered but flagged`() {
        val entries = picker(
            command(
                pid = "0C",
                dbg = true,
                signals = arrayOf(signal("RPM", unit = ObdUnit.RPM, max = 8000.0)),
            ),
        )
        entries.single().experimental shouldBe true
    }

    /** A metric-keyed signal is offered under its metric, so the tile follows the metric across cars. */
    @Test
    fun `a signal with a suggestedMetric is keyed on the metric`() {
        val entries = picker(
            command(
                pid = "0D",
                signals = arrayOf(
                    signal(
                        "VSS",
                        metric = SuggestedMetric.SPEED,
                        unit = ObdUnit.KILOMETERS_PER_HOUR,
                        max = 255.0,
                    ),
                ),
            ),
        )
        entries.single().key shouldBe MetricKey.Metric(SuggestedMetric.SPEED)
    }

    /**
     * ...and one without is keyed on its signal id. Engine RPM has no `suggestedMetric` in OBDb —
     * a metric-only picker could not offer the single most important gauge in the app.
     */
    @Test
    fun `a signal with no suggestedMetric is keyed on its signal id`() {
        val entries = picker(
            command(pid = "0C", signals = arrayOf(signal("RPM", unit = ObdUnit.RPM, max = 8000.0))),
        )
        entries.single().key shouldBe MetricKey.Signal("RPM")
    }

    @Test
    fun `a new tile's default range comes from the signal's own format`() {
        val entries = picker(
            command(pid = "0C", signals = arrayOf(signal("RPM", unit = ObdUnit.RPM, max = 8000.0))),
        )
        entries.single().defaultMin shouldBe 0.0
        entries.single().defaultMax shouldBe 8000.0
    }
}
