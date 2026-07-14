package com.bruni.carscan.feature.dashboard

import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LayoutCodecTest {

    private val speed = DashboardTile("t1", SPEED_KEY, GaugeStyleId.MODERN_ARC, 0.0, 240.0)
    private val rpm = DashboardTile("t2", RPM_KEY, GaugeStyleId.CLASSIC_ANALOG, 0.0, 8000.0)

    @Test
    fun `a layout round-trips through JSON`() {
        LayoutCodec.decode(LayoutCodec.encode(listOf(speed, rpm))) shouldContainExactly
            listOf(speed, rpm)
    }

    /**
     * Order is the layout. A codec that round-trips a *set* would round-trip every assertion in
     * this file and still shuffle the user's dashboard on every restart.
     */
    @Test
    fun `reordering survives a round trip`() {
        LayoutCodec.decode(LayoutCodec.encode(listOf(rpm, speed))) shouldContainExactly
            listOf(rpm, speed)
    }

    @Test
    fun `removing a tile survives a round trip`() {
        LayoutCodec.decode(LayoutCodec.encode(listOf(speed))) shouldContainExactly listOf(speed)
    }

    /**
     * The discriminator is a field name, not a class name. If this ever encodes
     * `com.bruni.carscan.core.model.MetricKey.Metric`, every layout on every phone breaks the
     * next time that class is renamed.
     */
    @Test
    fun `a metric key encodes as OBDb's own wire name`() {
        val json = LayoutCodec.encode(listOf(speed))
        json.contains("\"metric\":\"speed\"") shouldBe true
        json.contains("MetricKey") shouldBe false
    }

    @Test
    fun `a signal key encodes as its signal id`() {
        LayoutCodec.encode(listOf(rpm)).contains("\"signal\":\"RPM\"") shouldBe true
    }

    /** A layout written by a newer build must not brick the screen it describes. */
    @Test
    fun `an unknown gauge style falls back to the default instead of throwing`() {
        val json = """{"version":1,"tiles":[
            {"id":"t1","signal":"RPM","style":"HOLOGRAPHIC","min":0.0,"max":8000.0}]}"""
        LayoutCodec.decode(json) shouldContainExactly
            listOf(DashboardTile("t1", RPM_KEY, GaugeStyleId.MODERN_ARC, 0.0, 8000.0))
    }

    /** An unknown metric cannot be rendered — but it must only cost its own tile. */
    @Test
    fun `an unknown metric drops that tile and keeps the others`() {
        val json = """{"version":1,"tiles":[
            {"id":"t0","metric":"warpCoreTemperature","style":"MODERN_ARC","min":0.0,"max":1.0},
            {"id":"t2","signal":"RPM","style":"CLASSIC_ANALOG","min":0.0,"max":8000.0}]}"""
        LayoutCodec.decode(json) shouldContainExactly listOf(rpm)
    }

    @Test
    fun `malformed JSON decodes to an empty dashboard rather than throwing`() {
        LayoutCodec.decode("{ this is not json") shouldContainExactly emptyList()
        LayoutCodec.decode("") shouldContainExactly emptyList()
    }
}
