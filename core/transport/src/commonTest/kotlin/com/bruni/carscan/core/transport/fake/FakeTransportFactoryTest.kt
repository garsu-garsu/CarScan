package com.bruni.carscan.core.transport.fake

import app.cash.turbine.test
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TestElmClient
import com.bruni.carscan.core.transport.TransportKind
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class FakeTransportFactoryTest {

    @Test
    fun `it claims every kind so the debug build can exercise all three pickers`() {
        FakeTransportFactory().supported shouldBe
            setOf(TransportKind.BLE, TransportKind.SPP, TransportKind.WIFI)
    }

    @Test
    fun `discovery emits the fake adapters of the requested kind, and only those`() = runTest {
        val factory = FakeTransportFactory()

        factory.discover(TransportKind.WIFI).test {
            awaitItem().address shouldBe "192.168.0.10:35000"
            awaitComplete()
        }

        val ble = factory.discover(TransportKind.BLE).toList()
        ble.map { it.name } shouldContainExactly listOf("OBDII", "Vgate iCar Pro")
        ble.all { it.kind == TransportKind.BLE } shouldBe true
        ble.all { it.rssi != null } shouldBe true
    }

    @Test
    fun `a created transport is a live emulator`() = runTest {
        val factory = FakeTransportFactory(clock = ElmClock { testScheduler.currentTime })
        val target = DiscoveredAdapter(TransportKind.WIFI, "192.168.0.10:35000", "V-LINK")

        val transport = factory.create(target)
        transport.open()
        val client = TestElmClient(transport, backgroundScope)

        client.exchangeLines("ATI") shouldContainExactly listOf("ATI", "ELM327 v1.5")
    }
}
