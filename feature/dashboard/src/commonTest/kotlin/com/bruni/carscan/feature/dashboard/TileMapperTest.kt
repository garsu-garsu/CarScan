package com.bruni.carscan.feature.dashboard

import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.model.DecodedValue
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class TileMapperTest {

    private val metric = UnitPreferences.METRIC
    private val us = UnitPreferences.defaultsFor("en-US")
    private val uk = UnitPreferences.defaultsFor("en-GB")

    // 0D is SAE J1979's vehicle speed: km/h, tagged `speed`, declared at freq 0.25 = 4 Hz.
    private val speedSource = EffectiveSignalset.of(
        standard = signalset(
            command(
                pid = "0D",
                freq = 0.25,
                signals = arrayOf(
                    signal(
                        "VSS",
                        name = "Vehicle speed",
                        metric = SuggestedMetric.SPEED,
                        unit = ObdUnit.KILOMETERS_PER_HOUR,
                        max = 255.0,
                    ),
                ),
            ),
        ),
        vehicle = signalset(),
        modelYear = 2023,
    )[SPEED_KEY].single()

    private val speedTile = DashboardTile("t1", SPEED_KEY, GaugeStyleId.MODERN_ARC, 0.0, 240.0)
    private val binding = bind(speedSource)

    private fun render(
        sample: SensorSample?,
        nowMs: Long = 0,
        units: UnitPreferences = metric,
    ) = resolve(speedTile, binding, sample, nowMs, units)

    /** [atMs] is wall time, the same base the poller stamps a sample with. */
    private fun speedSample(kmh: Double, atMs: Long = 0) =
        sample(SPEED_KEY, kmh, ObdUnit.KILOMETERS_PER_HOUR, timestampMs = atMs)

    // --- unit conversion happens exactly once, here -----------------------------

    @Test
    fun `a km per hour sample under a metric preference is not converted`() {
        val rendered = render(speedSample(100.0))
        rendered.spec.value shouldBe 100f
        rendered.displayUnit shouldBe UnitId.KMH
    }

    @Test
    fun `a km per hour sample with an mph preference reaches state as mph`() {
        val s = speedSample(100.0)
        val rendered = render(s, units = us)

        // 1 mile = 1.609344 km exactly. The 1.6 "everyone knows" is 0.6% low — a whole mph at
        // motorway speed.
        rendered.spec.value shouldBe (62.137f plusOrMinus 0.001f)
        rendered.displayUnit shouldBe UnitId.MPH
        rendered.nativeUnit shouldBe ObdUnit.KILOMETERS_PER_HOUR

        // The sample stays the car's, in the car's unit, forever. If the mapper ever mutated it,
        // changing a display preference would corrupt recorded history.
        (s.value as DecodedValue.Numeric).value shouldBe 100.0
        s.unit shouldBe ObdUnit.KILOMETERS_PER_HOUR
    }

    /**
     * The end stops move with the needle. Converting the value but not the range draws 62 mph
     * against a 0–240 dial and reports a car at motorway speed as barely moving.
     */
    @Test
    fun `the range is converted with the value`() {
        val rendered = render(speedSample(100.0), units = us)
        rendered.spec.min shouldBe 0f
        rendered.spec.max shouldBe (149.129f plusOrMinus 0.01f)
    }

    /**
     * The UK is the whole reason unit preferences are per-quantity. A British driver reads **miles
     * and °C at the same time**, so a single metric/imperial switch is wrong for an entire country.
     */
    @Test
    fun `a British user gets mph, and still gets celsius`() {
        render(speedSample(100.0), units = uk).spec.value shouldBe (62.137f plusOrMinus 0.001f)
        uk[Quantity.TEMPERATURE] shouldBe UnitId.CELSIUS
        uk[Quantity.SPEED] shouldBe UnitId.MPH
    }

    /**
     * Temperature is **affine**, and this is the tile that proves the mapper never hand-rolls a
     * conversion. 90 °C is 194 °F. A converter that treated it as a pure scaling — as every other
     * unit in this app is — would put a coolant gauge at 162 °F and call an overheating engine
     * merely warm.
     */
    @Test
    fun `a coolant temperature reaches an American user as fahrenheit`() {
        val coolant = EffectiveSignalset.of(
            standard = signalset(
                command(
                    pid = "05",
                    freq = 1.0,
                    signals = arrayOf(
                        signal(
                            "ECT",
                            name = "Coolant temperature",
                            metric = SuggestedMetric.ENGINE_COOLANT_TEMPERATURE,
                            unit = ObdUnit.CELSIUS,
                            min = -40.0,
                            max = 215.0,
                        ),
                    ),
                ),
            ),
            vehicle = signalset(),
            modelYear = 2023,
        ).sourcesOf(SuggestedMetric.ENGINE_COOLANT_TEMPERATURE).single()

        val key = MetricKey.Metric(SuggestedMetric.ENGINE_COOLANT_TEMPERATURE)
        val tile = DashboardTile("t2", key, GaugeStyleId.MODERN_ARC, -40.0, 215.0)
        val rendered = resolve(tile, bind(coolant), sample(key, 90.0, ObdUnit.CELSIUS), 0, us)

        rendered.spec.value shouldBe (194f plusOrMinus 0.001f)          // not 162 — affine, not scaled
        rendered.spec.min shouldBe (-40f plusOrMinus 0.001f)            // the one place the scales meet
        rendered.spec.max shouldBe (419f plusOrMinus 0.001f)
        rendered.displayUnit shouldBe UnitId.FAHRENHEIT
    }

    /**
     * **Every one of the six numbers on a dial converts, or none of them may.**
     *
     * The value, the two end stops, the two edges of the green band and the redline are all drawn
     * against the same scale. `ClassicAnalogGauge` also prints its tick labels from
     * `min + fraction * span`, so converting the value but not the bounds gives a needle at the
     * wrong angle on a dial *printed in the wrong unit* — while the number in the middle stays
     * right, which is exactly what would carry it through a review.
     *
     * The green band and the redline are the easiest to forget, because nothing about them is
     * wrong until you look at a real car: an overheating engine sitting inside a green zone that
     * was never converted.
     */
    @Test
    fun `the optimal band and the redline are converted too, not just the value`() {
        val coolant = EffectiveSignalset.of(
            standard = signalset(
                command(
                    pid = "05",
                    freq = 1.0,
                    signals = arrayOf(
                        signal(
                            "ECT",
                            name = "Coolant temperature",
                            metric = SuggestedMetric.ENGINE_COOLANT_TEMPERATURE,
                            unit = ObdUnit.CELSIUS,
                            min = -40.0,
                            max = 215.0,
                            omin = 80.0,    // healthy band, in celsius
                            omax = 100.0,
                            oval = 110.0,   // redline
                        ),
                    ),
                ),
            ),
            vehicle = signalset(),
            modelYear = 2023,
        ).sourcesOf(SuggestedMetric.ENGINE_COOLANT_TEMPERATURE).single()

        val key = MetricKey.Metric(SuggestedMetric.ENGINE_COOLANT_TEMPERATURE)
        val tile = DashboardTile("t5", key, GaugeStyleId.CLASSIC_ANALOG, -40.0, 215.0)
        val spec = resolve(tile, bind(coolant), sample(key, 90.0, ObdUnit.CELSIUS), 0, us).spec

        spec.optimalFrom!! shouldBe (176f plusOrMinus 0.001f)   // 80 °C, not 80 °F
        spec.optimalTo!! shouldBe (212f plusOrMinus 0.001f)     // 100 °C
        spec.redlineFrom!! shouldBe (230f plusOrMinus 0.001f)   // 110 °C
    }

    /**
     * **OBDb's `degrees` is an ANGLE, not a temperature** — ignition timing advance, steering
     * angle. It has no [UnitId], so it must never be converted and must never be labelled `°C`.
     *
     * This is the quietest bug in the codebase's whole failure class: `displayUnitFor` correctly
     * declines to convert an angle, so **the number stays right** — only the unit beside it would
     * lie. 14° of timing advance reading as "14 °C" is entirely plausible on a gauge, and nothing
     * about it looks wrong.
     *
     * (It is not hypothetical: `:core:units`' `ObdUnit.asIsLabelKey` maps `DEGREES` to
     * `"unit_celsius"` today, in a `when` branch whose own comment says it must not. The dashboard
     * does not use that helper — `UnitLabels.kt` resolves `DEGREES` to `Res.string.unit_degrees`
     * through a compile-checked `when` — and this test is what keeps it that way.)
     */
    @Test
    fun `a timing-advance signal in degrees is not a temperature`() {
        val timing = EffectiveSignalset.of(
            standard = signalset(
                command(
                    pid = "0E",
                    signals = arrayOf(
                        signal(
                            "TIMING_ADV",
                            name = "Timing advance",
                            unit = ObdUnit.DEGREES,
                            min = -64.0,
                            max = 64.0,
                        ),
                    ),
                ),
            ),
            vehicle = signalset(),
            modelYear = 2023,
        ).sourcesOf("TIMING_ADV").single()

        val key = MetricKey.Signal("TIMING_ADV")
        val tile = DashboardTile("t4", key, GaugeStyleId.MODERN_ARC, -64.0, 64.0)
        val rendered = resolve(tile, bind(timing), sample(key, 14.0, ObdUnit.DEGREES), 0, us)

        // 14 degrees of advance stays 14 — not 57.2, which is what °C→°F would make of it.
        rendered.spec.value shouldBe 14f
        rendered.displayUnit shouldBe null
        rendered.displayUnit shouldNotBe UnitId.CELSIUS
        rendered.displayUnit shouldNotBe UnitId.FAHRENHEIT
        rendered.nativeUnit shouldBe ObdUnit.DEGREES
    }

    /** A unit the app offers no choice in is shown exactly as the car decoded it. */
    @Test
    fun `a signal with no display unit is left alone`() {
        val rpmSource = EffectiveSignalset.of(
            standard = signalset(
                command(
                    pid = "0C",
                    signals = arrayOf(signal("RPM", unit = ObdUnit.RPM, max = 8000.0)),
                ),
            ),
            vehicle = signalset(),
            modelYear = 2023,
        ).sourcesOf("RPM").single()

        val tile = DashboardTile("t3", RPM_KEY, GaugeStyleId.MODERN_ARC, 0.0, 8000.0)
        val rendered = resolve(tile, bind(rpmSource), sample(RPM_KEY, 1726.0, ObdUnit.RPM), 0, us)
        rendered.spec.value shouldBe 1726f
        rendered.displayUnit shouldBe null
    }

    // --- stale versus zero -------------------------------------------------------

    @Test
    fun `a tile with no sample at all is stale, not zero`() {
        val rendered = render(sample = null)
        rendered.spec.isStale shouldBe true
        rendered.spec.value shouldBe 0f   // the needle rests, but the tile says it has no reading
    }

    @Test
    fun `a tile is not stale immediately after a fresh sample`() {
        val rendered = render(speedSample(0.0, atMs = 1_000), nowMs = 1_000)
        rendered.spec.isStale shouldBe false
        // Zero km/h is a reading. A car stopped at a light is not a broken app.
        rendered.spec.value shouldBe 0f
    }

    @Test
    fun `a tile goes stale once the last sample is older than the window`() {
        val s = speedSample(90.0, atMs = 1_000)

        render(s, nowMs = 1_000 + binding.stalenessWindowMs).spec.isStale shouldBe false
        render(s, nowMs = 1_000 + binding.stalenessWindowMs + 1).spec.isStale shouldBe true
    }

    /**
     * The sentinel case, which is invisible from this layer and is why the age check exists.
     *
     * An OBDb `nullmin`/`nullmax` sentinel means the ECU is reporting an unplugged sensor.
     * `SignalDecoder.decode` returns null for it and `PollDecoder` emits **no sample** — so from
     * the dashboard the sensor failing is indistinguishable from silence, and the last good
     * reading stays in `latest` forever. Without the window the tile would go on showing 90 km/h,
     * confidently, from a sensor that is unplugged.
     */
    @Test
    fun `a sentinel keeps the last good value on screen only until the window expires`() {
        val lastGood = speedSample(90.0, atMs = 1_000)
        val rendered = render(lastGood, nowMs = 1_000 + binding.stalenessWindowMs + 1)

        rendered.spec.isStale shouldBe true
        // Not zero: a gauge that snapped to 0 would read as "the car stopped", which is a
        // different and much more alarming lie than "no reading".
        rendered.spec.value shouldBe 90f
    }

    /**
     * A window must not be a constant. OBDb declares the odometer at `freq: 3600` — once an hour —
     * and a two-second window would render it permanently stale on a perfectly healthy car.
     */
    @Test
    fun `the staleness window comes from the command's declared period`() {
        val hourly = EffectiveSignalset.of(
            standard = signalset(
                command(
                    pid = "A6",
                    freq = 3600.0,
                    signals = arrayOf(
                        signal("ODO", metric = SuggestedMetric.ODOMETER, unit = ObdUnit.KILOMETERS),
                    ),
                ),
            ),
            vehicle = signalset(),
            modelYear = 2023,
        ).sourcesOf(SuggestedMetric.ODOMETER).single()

        bind(hourly).stalenessWindowMs shouldBe 3_600_000L * STALENESS_PERIODS
        // ...while a 4 Hz signal gets the floor, not 750 ms of jitter.
        binding.stalenessWindowMs shouldBe MIN_STALENESS_WINDOW_MS
    }

    // --- what the signalset supplies ----------------------------------------------

    @Test
    fun `the binding takes its label, unit and period from the signalset, never from the layout`() {
        binding.label shouldBe "Vehicle speed"
        binding.nativeUnit shouldBe ObdUnit.KILOMETERS_PER_HOUR
        binding.periodMs shouldBe 250L   // freq 0.25 is SECONDS BETWEEN REQUESTS, not hertz
    }

    /** A tile whose signal this vehicle does not have still renders — as stale, not as a crash. */
    @Test
    fun `a tile with no binding renders stale rather than throwing`() {
        resolve(speedTile, null, null, 0, metric).spec.isStale shouldBe true
    }
}
