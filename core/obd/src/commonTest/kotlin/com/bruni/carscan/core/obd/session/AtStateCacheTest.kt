package com.bruni.carscan.core.obd.session

import com.bruni.carscan.core.model.obdb.ObdProtocol
import com.bruni.carscan.core.obd.ElmRequest
import com.bruni.carscan.core.transport.fake.FakeObdTransport
import com.bruni.carscan.core.transport.fake.FakeObdTransportBuilder
import com.bruni.carscan.core.transport.fake.FakeReply
import com.bruni.carscan.core.transport.fake.ElmFault
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AtStateCacheTest {

    private fun clone(configure: FakeObdTransportBuilder.() -> Unit = {}) = FakeObdTransport {
        fallback = FakeReply.Lines(listOf("OK"))
        on("ATZ") respond "ELM327 v1.5"
        on("ATI") respond "ELM327 v1.5"
        on("STI") fail ElmFault.UNKNOWN_COMMAND
        on("ATDPN") respond "A6"
        on("0100") respond "7E8 06 41 00 88 18 80 01"
        on("01001") respond "7E8 06 41 00 88 18 80 01"
        configure()
    }

    /** Connect, then forget everything the ladder wrote — we only care what polling costs. */
    private suspend fun TestScope.ready(transport: FakeObdTransport): Pair<ElmSession, Int> {
        val session = ElmSession(transport, backgroundScope, sessionConfig())
        session.connect()
        return session to transport.requests.size
    }

    private fun FakeObdTransport.since(base: Int) = requests.drop(base)

    // --- the one that pays for the dashboard --------------------------------

    /**
     * Six commands, two ECUs, one `ATSH` each.
     *
     * Re-applying the addressing before every request is the obvious implementation and
     * it costs three to five extra round trips per reading. A clone manages fifteen
     * exchanges a second in total; spend four of every five re-sending `ATSH 7E0` when
     * the header is *already* `7E0` and the useful rate is three a second. That is not a
     * slow dashboard, it is a broken one.
     *
     * **Delete the cache comparison in `applyContext` and this test reports 6.**
     */
    @Test
    fun `six commands across two headers cost exactly two ATSH`() = runTest {
        val transport = clone()
        val (session, base) = ready(transport)
        val cache = session.atCache

        val commands = listOf(
            obdbCommand("7E0", "0C"), obdbCommand("7E0", "0D"), obdbCommand("7E0", "05"),
            obdbCommand("7E4", "0101", mode = "22"),
            obdbCommand("7E4", "0105", mode = "22"),
            obdbCommand("7E4", "010A", mode = "22"),
        )

        for (command in commands) {
            session.applyContext(command, cache)
            session.exchange(ElmRequest(command.cmd.entries.single().let { it.key + it.value }))
        }

        transport.since(base).count { it.startsWith("ATSH") } shouldBe 2
        transport.since(base).count { it.startsWith("ATSH 7E0") } shouldBe 1
        transport.since(base).count { it.startsWith("ATSH 7E4") } shouldBe 1
    }

    @Test
    fun `a repeated command sends no AT commands at all`() = runTest {
        val transport = clone()
        val (session, _) = ready(transport)
        val command = obdbCommand("7E0", "0C")

        session.applyContext(command, session.atCache)
        val base = transport.requests.size
        repeat(5) { session.applyContext(command, session.atCache) }

        transport.since(base) shouldContainExactly emptyList()
    }

    // --- the field-to-AT mapping --------------------------------------------

    @Test
    fun `hdr becomes ATSH and rax becomes ATCRA`() = runTest {
        val transport = clone()
        val (session, base) = ready(transport)

        session.applyContext(obdbCommand("7E4", "0101", mode = "22", rax = "7EC"), session.atCache)

        transport.since(base) shouldContainInOrder listOf("ATSH 7E4", "ATCRA 7EC")
        session.atCache.header shouldBe "7E4"
        session.atCache.rxFilter shouldBe "7EC"
    }

    /** An absent `rax` is not "leave the old filter alone" — it is "clear it". */
    @Test
    fun `a command without rax clears the filter with a bare ATCRA`() = runTest {
        val transport = clone()
        val (session, _) = ready(transport)

        session.applyContext(obdbCommand("7E4", "0101", mode = "22", rax = "7EC"), session.atCache)
        val base = transport.requests.size
        session.applyContext(obdbCommand("7E0", "0C"), session.atCache)

        transport.since(base) shouldContain "ATCRA"
        session.atCache.rxFilter shouldBe null
    }

    @Test
    fun `eax becomes ATCEA`() = runTest {
        val transport = clone()
        val (session, base) = ready(transport)

        session.applyContext(obdbCommand("7E0", "0C", eax = "F1"), session.atCache)

        transport.since(base) shouldContain "ATCEA F1"
        session.atCache.extAddr shouldBe "F1"
    }

    /**
     * Flow control must be set *after* the header. Many firmwares reset the flow-control
     * registers when `ATSH` changes, so `ATFCSH` sent first is silently undone and the
     * multi-frame response never completes.
     */
    @Test
    fun `fcm1 sends the flow-control triple after ATSH, never before`() = runTest {
        val transport = clone()
        val (session, base) = ready(transport)

        session.applyContext(obdbCommand("7E4", "0101", mode = "22", fcm1 = true), session.atCache)

        transport.since(base) shouldContainInOrder
            listOf("ATSH 7E4", "ATFCSH 7E4", "ATFCSD 300000", "ATFCSM1")
        session.atCache.fc shouldBe FcState("7E4", "300000", 1)
    }

    @Test
    fun `changing the header re-sends flow control, because the header reset it`() = runTest {
        val transport = clone()
        val (session, _) = ready(transport)

        session.applyContext(obdbCommand("7E4", "0101", mode = "22", fcm1 = true), session.atCache)
        val base = transport.requests.size
        session.applyContext(obdbCommand("7E6", "0101", mode = "22", fcm1 = true), session.atCache)

        transport.since(base) shouldContainInOrder listOf("ATSH 7E6", "ATFCSH 7E6", "ATFCSM1")
    }

    @Test
    fun `ATFCSM0 is sent only when we are actually in mode 1`() = runTest {
        val transport = clone()
        val (session, base) = ready(transport)

        // Never entered mode 1, so there is nothing to leave.
        session.applyContext(obdbCommand("7E0", "0C"), session.atCache)
        transport.since(base) shouldNotContain "ATFCSM0"

        session.applyContext(obdbCommand("7E0", "0D", fcm1 = true), session.atCache)
        val afterFc = transport.requests.size
        session.applyContext(obdbCommand("7E0", "05"), session.atCache)

        transport.since(afterFc) shouldContain "ATFCSM0"
        session.atCache.fc?.mode shouldBe 0
    }

    @Test
    fun `tmo becomes ATAT0 plus ATST, and ATAT1 comes back afterwards`() = runTest {
        val transport = clone()
        val (session, base) = ready(transport)

        session.applyContext(obdbCommand("7E0", "0C", tmo = "64"), session.atCache)

        transport.since(base) shouldContainInOrder listOf("ATAT0", "ATST 64")
        session.atCache.stTimeout shouldBe 0x64

        val afterTmo = transport.requests.size
        session.applyContext(obdbCommand("7E0", "0D"), session.atCache)

        transport.since(afterTmo) shouldContain "ATAT1"
        session.atCache.stTimeout shouldBe null
    }

    @Test
    fun `proto maps to the ELM protocol number`() = runTest {
        ObdProtocol.ISO_9141_2.atNumber() shouldBe 3
        ObdProtocol.ISO_14230.atNumber() shouldBe 5
        ObdProtocol.ISO_15765_4_11BIT.atNumber() shouldBe 6
        ObdProtocol.ISO_15765_4_29BIT.atNumber() shouldBe 7
    }

    @Test
    fun `din enters a UDS session once per ECU`() = runTest {
        val transport = clone()
        val (session, base) = ready(transport)

        val command = obdbCommand("7E4", "0101", mode = "22", din = "03")
        session.applyContext(command, session.atCache)
        session.applyContext(command, session.atCache)

        transport.since(base).count { it == "1003" } shouldBe 1
        session.atCache.ecuSession["7E4"] shouldBe "03"
    }

    // --- the cache must never claim more than the adapter confirmed ---------

    /**
     * A rejected command did not take effect. Recording it as if it had is how the next
     * reading comes back from the wrong ECU — and a reading from the wrong ECU is a
     * plausible number, not an error. Plenty of clones have no `ATCEA` at all.
     */
    @Test
    fun `a rejected AT command is not recorded as applied`() = runTest {
        val transport = clone { on("ATCEA F1") fail ElmFault.UNKNOWN_COMMAND }
        val (session, _) = ready(transport)

        session.applyContext(obdbCommand("7E0", "0C", eax = "F1"), session.atCache)

        session.atCache.extAddr shouldBe null
        session.atCache.header shouldBe "7E0"
    }
}
