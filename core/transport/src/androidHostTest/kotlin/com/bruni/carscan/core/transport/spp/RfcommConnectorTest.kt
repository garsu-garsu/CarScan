package com.bruni.carscan.core.transport.spp

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith

private const val ADDRESS = "00:1D:A5:68:98:8B"

class RfcommConnectorTest {

    @Test
    fun `the secure rung is used when it works, and nothing else is tried`() {
        val host = FakeBluetoothHost()

        val socket = RfcommConnector(ADDRESS, host).connect()

        (socket as FakeRfcommSocket).connected shouldBe true
        host.log shouldContainExactly listOf(
            "cancelDiscovery",
            "socketFactory:$ADDRESS",
            "create:secure",
            "connect:secure",
        )
    }

    @Test
    fun `discovery is cancelled before the first connect attempt`() {
        // If a scan is running, connect() times out. This is the single most common
        // "Bluetooth just doesn't work" bug in Android code, so it gets its own test.
        val log = mutableListOf<String>()
        val host = FakeBluetoothHost(log, FakeRfcommSocketFactory(log))

        RfcommConnector(ADDRESS, host).connect()

        log.first() shouldBe "cancelDiscovery"
        log.indexOf("cancelDiscovery") shouldBe 0
    }

    @Test
    fun `a failing secure rung falls back to insecure`() {
        val log = mutableListOf<String>()
        val factory = FakeRfcommSocketFactory(
            log,
            onConnect = { rung -> if (rung == SppConnectRung.SECURE) throw IOException("read failed, socket might closed") },
        )
        val host = FakeBluetoothHost(log, factory)

        val socket = RfcommConnector(ADDRESS, host).connect()

        (socket as FakeRfcommSocket).connected shouldBe true
        log shouldContainExactly listOf(
            "cancelDiscovery",
            "socketFactory:$ADDRESS",
            "create:secure",
            "connect:secure",
            // The half-open socket is closed before the next rung — leaving it open keeps
            // the RFCOMM channel busy and makes the next attempt fail too.
            "close:secure",
            "create:insecure",
            "connect:insecure",
        )
    }

    @Test
    fun `a failing secure and insecure rung falls back to reflection`() {
        val log = mutableListOf<String>()
        val factory = FakeRfcommSocketFactory(
            log,
            onConnect = { rung -> if (rung != SppConnectRung.REFLECTION) throw IOException("service discovery failed") },
        )
        val host = FakeBluetoothHost(log, factory)

        val socket = RfcommConnector(ADDRESS, host).connect()

        (socket as FakeRfcommSocket).connected shouldBe true
        log.filter { it.startsWith("connect:") } shouldContainExactly
            listOf("connect:secure", "connect:insecure", "connect:reflection")
    }

    @Test
    fun `all three rungs failing gives an actionable error, not a raw IOException`() {
        val log = mutableListOf<String>()
        val factory = FakeRfcommSocketFactory(log, onConnect = { throw IOException("read failed") })
        val host = FakeBluetoothHost(log, factory)

        val failure = assertFailsWith<SppConnectFailedException> { RfcommConnector(ADDRESS, host).connect() }

        failure.address shouldBe ADDRESS
        failure.attempts.map { it.rung } shouldContainExactly
            listOf(SppConnectRung.SECURE, SppConnectRung.INSECURE, SppConnectRung.REFLECTION)
        // The message has to name the adapter, name every rung that was tried, and tell
        // the user what to do — a bare "read failed, socket might closed" does none of that.
        val message = failure.message.orEmpty()
        message shouldContain ADDRESS
        message shouldContain "secure"
        message shouldContain "insecure"
        message shouldContain "reflection"
        message shouldContain "re-pair"
        // Every socket it opened is closed: three attempts must not leak three sockets.
        factory.created.forEach { it.closeCount shouldBe 1 }
    }

    @Test
    fun `a missing BLUETOOTH_CONNECT permission stops the ladder immediately`() {
        // All three rungs throw SecurityException without the permission, so walking the
        // ladder just buries the real cause under two more failures.
        val log = mutableListOf<String>()
        val factory = FakeRfcommSocketFactory(log, onCreate = { throw SecurityException("Need BLUETOOTH_CONNECT permission") })
        val host = FakeBluetoothHost(log, factory)

        val failure = assertFailsWith<SppPermissionDeniedException> { RfcommConnector(ADDRESS, host).connect() }

        failure.message.orEmpty() shouldContain "BLUETOOTH_CONNECT"
        log.none { it == "create:insecure" || it == "create:reflection" } shouldBe true
    }

    @Test
    fun `a rung whose socket cannot even be created is recorded and the next one is tried`() {
        // Some ROMs have removed the hidden createRfcommSocket(int) entry point: the
        // reflection rung then dies in getMethod, before there is any socket to connect.
        val log = mutableListOf<String>()
        val factory = FakeRfcommSocketFactory(
            log,
            onCreate = { rung -> if (rung == SppConnectRung.SECURE) throw IllegalStateException("no SDP record") },
            onConnect = { rung -> if (rung == SppConnectRung.INSECURE) throw IOException("read failed") },
        )
        val host = FakeBluetoothHost(log, factory)

        val socket = RfcommConnector(ADDRESS, host).connect()

        (socket as FakeRfcommSocket).connected shouldBe true
        log.filter { it.startsWith("create:") } shouldContainExactly
            listOf("create:secure", "create:insecure", "create:reflection")
    }
}
