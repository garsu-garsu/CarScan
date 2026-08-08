package com.bruni.carscan.core.obd.decode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IsoTpReassemblerTest {

    private val rx = IsoTpReassembler()

    /** Feeds ELM lines and returns everything that completed. */
    private fun feed(vararg lines: String): List<IsoTpMessage> =
        lines.flatMap { rx.feed(parseElmLine(it)!!) }

    @Test
    fun `single frame completes on its own line`() {
        val out = feed("7E804410C1AF8")            // PCI 04 -> 4 bytes
        assertEquals(1, out.size)
        assertEquals("7E8", out[0].canId)
        assertEquals("41 0C 1A F8", out[0].data.hex())
    }

    @Test
    fun `single frame drops the padding the adapter added`() {
        // Adapters pad to 8 bytes with 0x00 or 0xAA. Length comes from the PCI, never
        // from the frame size, or every value would carry a tail of junk.
        val out = feed("7E8024100AAAAAAAA")        // PCI 02 -> 2 bytes, rest is padding
        assertEquals("41 00", out[0].data.hex())
    }

    @Test
    fun `first plus consecutive frames reassemble to the declared length`() {
        // Verbatim from OBDb Kia-EV6 tests/test_cases/2022/commands/7E4.7EC.220101|fc=1.yaml
        val out = feed(
            "7EC103E620101EFFBE7",                 // declares 0x03E = 62, carries 6
            "7EC21EF6F0000000000",
            "7EC2200071BF501FF00",
            "7EC23010001000024BA",
            "7EC2413BA7100008000",
            "7EC25013C0D0001374A",
            "7EC260000EC0D0000E4",
            "7EC2765010080770002",
            "7EC28CD110B00000684",                 // 6 + 8*7 = 62 exactly
        )
        assertEquals(1, out.size)
        assertEquals("7EC", out[0].canId)
        assertEquals(62, out[0].data.size)
        assertTrue(out[0].data.hex().startsWith("62 01 01 EF FB E7 EF 6F"))
    }

    @Test
    fun `the tail of the last frame is truncated to the declared length`() {
        // Declares 13; the final consecutive frame carries 7 more on top of 6, so one
        // byte of it is padding and must not survive into the payload.
        val out = feed("7EC100D620101AABBCC", "7EC21DDEEFF00112233")
        assertEquals(13, out[0].data.size)
        assertEquals("62 01 01 AA BB CC DD EE FF 00 11 22 33", out[0].data.hex())
    }

    @Test
    fun `nothing is emitted until the message is actually complete`() {
        assertEquals(0, feed("7EC103E620101EFFBE7").size)
        assertEquals(0, feed("7EC21EF6F0000000000").size)
    }

    @Test
    fun `two ECUs answering at once do not corrupt each other`() {
        // This is why the buffer is keyed by canId. One shared buffer would splice
        // 7EA's bytes into 7EC's message and decode plausible garbage from both.
        val out = feed(
            "7EC1010620101AABBCC",                 // 7EC declares 0x010 = 16, carries 6
            "7EA100C620102DDEEFF",                 // 7EA declares 0x00C = 12, carries 6
            "7EC21DDEEFF00112233",                 // 7EC seq 1: +7 -> 13
            "7EA2100112233445566",                 // 7EA seq 1: +7 -> 13 >= 12, completes
            "7EC22445566778899AA",                 // 7EC seq 2: +7 -> 20 >= 16, completes
        )
        assertEquals(2, out.size)

        val ea = out.first { it.canId == "7EA" }
        assertEquals(12, ea.data.size)
        assertEquals("62 01 02 DD EE FF 00 11 22 33 44 55", ea.data.hex())

        val ec = out.first { it.canId == "7EC" }
        assertEquals(16, ec.data.size)
        assertEquals("62 01 01 AA BB CC DD EE FF 00 11 22 33 44 55 66", ec.data.hex())
    }

    @Test
    fun `a sequence gap discards the message rather than emitting corrupt data`() {
        // The gap frame would complete the declared length, so a reassembler that only
        // counted bytes would happily emit here — a message of exactly the right size
        // and entirely the wrong contents. Dropped frames are the classic cheap-clone
        // symptom, and no reading beats a confident wrong one.
        val out = feed(
            "7EC100D620101AABBCC",                 // declares 13, carries 6
            "7EC22DDEEFF00112233",                 // seq 2 — seq 1 was lost. 6 + 7 = 13.
        )
        assertEquals(0, out.size)
    }

    @Test
    fun `after a gap the reassembler recovers on the next message`() {
        feed("7EC100D620101AABBCC", "7EC22DDEEFF00112233")   // gap: buffer dropped
        val out = feed("7E804410C1AF8")
        assertEquals(1, out.size)
        assertEquals("41 0C 1A F8", out[0].data.hex())
    }

    @Test
    fun `sequence number wraps past 15`() {
        val lines = mutableListOf("7EC1076620101EFFBE7")     // declares 0x076 = 118, carries 6
        for (i in 1..16) {                                   // seq 1..15 then 0: 16 * 7 = 112
            val seq = "0123456789ABCDEF"[i % 16]
            lines += "7EC2$seq" + "00000000000000"
        }
        val out = lines.flatMap { rx.feed(parseElmLine(it)!!) }
        assertEquals(1, out.size)
        assertEquals(118, out[0].data.size)                  // 6 + 112
    }

    @Test
    fun `a flow control frame on the same id is tolerated and disturbs nothing`() {
        // ATCAF1 makes the ELM send flow control for us, but adapters echo frames back
        // onto the same stream. Seeing one must not close or corrupt the open buffer.
        val out = feed(
            "7EC100D620101AABBCC",
            "7EC3000000000000000",                 // PCI 0x30 — flow control
            "7EC21DDEEFF00112233",
        )
        assertEquals(1, out.size)
        assertEquals("62 01 01 AA BB CC DD EE FF 00 11 22 33", out[0].data.hex())
    }

    @Test
    fun `an orphan consecutive frame is ignored`() {
        // Arrives when we tuned in mid-message. There is no header to decode it against.
        assertEquals(0, feed("7EC21DDEEFF00112233").size)
    }

    @Test
    fun `a new first frame abandons a half-built message on the same id`() {
        feed("7EC103E620101EFFBE7")                // never finished
        val out = feed("7EC100D620101AABBCC", "7EC21DDEEFF00112233")
        assertEquals(1, out.size)
        assertEquals(13, out[0].data.size)
    }

    /** Feeds lines from a command that declares `eax`, so every frame carries an extension byte. */
    private fun feedExtended(vararg lines: String): List<IsoTpMessage> =
        lines.flatMap { rx.feed(parseElmLine(it)!!, extendedAddressing = true) }

    @Test
    fun `extended addressing skips the address extension before the PCI`() {
        // Verbatim from OBDb Toyota-Prius 2016 750.758.2116|e=2A,fc=1,f=-2023.yaml.
        //   ext 2A | 10 07 | 61 16 00 2F 2F
        //   ext 2A | 21    | 2F 00 …
        val out = feedExtended("7582A10076116002F2F", "7582A212F0000000000")
        assertEquals(1, out.size)
        assertEquals("758", out[0].canId)
        assertEquals("61 16 00 2F 2F 2F 00", out[0].data.hex())
    }

    @Test
    fun `the extension byte is skipped, not matched against eax`() {
        // Verbatim from OBDb BMW-3-Series 2013 6F1.60D.22D240|e=0D,fc=1,f=2012-.yaml. The
        // command declares eax 0D — the ECU's address — and the reply carries F1, the
        // tester's. A reassembler that checked the byte against `eax` would drop every one.
        val out = feedExtended("60DF10562D2400052")
        assertEquals("62 D2 40 00 52", out[0].data.hex())
    }

    @Test
    fun `without the declaration an extended frame is dropped rather than misread`() {
        // The fail-safe that must survive: 0x2A read as a PCI is a consecutive frame with
        // sequence 10, and there is no way to know it is not one. No message beats a message
        // assembled one byte out of step, so nothing may be inferred from the bytes.
        assertEquals(0, feed("7582A10076116002F2F", "7582A212F0000000000").size)
    }

    @Test
    fun `reset drops every open buffer`() {
        feed("7EC103E620101EFFBE7")
        rx.reset()
        assertEquals(0, feed("7EC21EF6F0000000000").size)
    }

    private fun ByteArray.hex() = joinToString(" ") {
        val v = it.toInt() and 0xFF
        "${"0123456789ABCDEF"[v shr 4]}${"0123456789ABCDEF"[v and 15]}"
    }
}
