package com.bruni.carscan.core.obd.decode

import com.bruni.carscan.core.model.obdb.CommandSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceEchoTest {

    private fun msg(vararg v: Int) = IsoTpMessage("7EC", ByteArray(v.size) { v[it].toByte() })

    // ---- rax ---------------------------------------------------------------

    @Test
    fun `a reply from an ECU other than rax is not this command's reply`() {
        // Hyundai-Elantra 22E001 is answered by BOTH 7E8 (the ECU the command addresses)
        // and 7EB. Both replies open with the same `62 E0 01` echo, so the echo check
        // cannot tell them apart — it is the receive address that does.
        //
        // Without this, 7EB's payload overwrites 7E8's and ELANTRA_EOT reads -48.0 instead
        // of 47.25: a confident, plausible, wrong temperature from the wrong module. On the
        // adapter this filtering is what ATCRA does in hardware; the decoder must not assume
        // the adapter was configured.
        assertTrue(acceptsReplyFrom("7E8", rax = "7E8"))
        assertFalse(acceptsReplyFrom("7EB", rax = "7E8"))
    }

    @Test
    fun `a command with no rax accepts every ECU that answers`() {
        // An absent rax means "clear the filter" — a broadcast where whoever answers, answers.
        assertTrue(acceptsReplyFrom("7EB", rax = null))
    }

    @Test
    fun `rax is compared case-insensitively`() {
        assertTrue(acceptsReplyFrom("7ec", rax = "7EC"))
    }

    @Test
    fun `mode 22 drops the service byte and both PID bytes`() {
        val spec = CommandSpec(mode = 0x22, pid = "0101", pidBytes = 2)
        val payload = stripServiceEcho(msg(0x62, 0x01, 0x01, 0xEF, 0xFB, 0xE7), spec)!!
        assertEquals(listOf<Byte>(0xEF.toByte(), 0xFB.toByte(), 0xE7.toByte()), payload.toList())
    }

    @Test
    fun `mode 01 drops the service byte and one PID byte`() {
        val spec = CommandSpec(mode = 0x01, pid = "0C", pidBytes = 1)
        val payload = stripServiceEcho(msg(0x41, 0x0C, 0x1A, 0xF8), spec)!!
        assertEquals(listOf<Byte>(0x1A, 0xF8.toByte()), payload.toList())
    }

    @Test
    fun `mode 21 drops the service byte and one PID byte`() {
        val spec = CommandSpec(mode = 0x21, pid = "01", pidBytes = 1)
        val payload = stripServiceEcho(msg(0x61, 0x01, 0x7B), spec)!!
        assertEquals(listOf<Byte>(0x7B), payload.toList())
    }

    @Test
    fun `a negative response is null, never a payload`() {
        // 7F <service> <NRC>. 0x31 is requestOutOfRange. Stripping three bytes here
        // would hand the decoder an empty-but-valid-looking payload.
        val spec = CommandSpec(mode = 0x22, pid = "0101", pidBytes = 2)
        assertNull(stripServiceEcho(msg(0x7F, 0x22, 0x31), spec))
    }

    @Test
    fun `another service's answer is null`() {
        val spec = CommandSpec(mode = 0x22, pid = "0101", pidBytes = 2)
        assertNull(stripServiceEcho(msg(0x41, 0x01, 0x01, 0xEF), spec))   // mode 01 reply
    }

    @Test
    fun `another PID's answer is null`() {
        // The same ECU answering a different question — which happens whenever a reply
        // arrives late and lands against the next request. Every fmt.bix in the command
        // would land on the wrong field and decode without complaint.
        val spec = CommandSpec(mode = 0x22, pid = "0101", pidBytes = 2)
        assertNull(stripServiceEcho(msg(0x62, 0x01, 0x02, 0xEF, 0xFB, 0xE7), spec))
        assertNull(stripServiceEcho(msg(0x62, 0x02, 0x01, 0xEF, 0xFB, 0xE7), spec))
    }

    @Test
    fun `a message too short to hold the echo is null`() {
        val spec = CommandSpec(mode = 0x22, pid = "0101", pidBytes = 2)
        assertNull(stripServiceEcho(msg(0x62, 0x01), spec))
        assertNull(stripServiceEcho(msg(), spec))
    }

    @Test
    fun `an echo with an empty payload is an empty payload, not null`() {
        // Distinct from "too short": the echo is intact, there is simply nothing after
        // it. That is a well-formed message that decodes to no signals.
        val spec = CommandSpec(mode = 0x22, pid = "0101", pidBytes = 2)
        assertEquals(0, stripServiceEcho(msg(0x62, 0x01, 0x01), spec)!!.size)
    }
}
