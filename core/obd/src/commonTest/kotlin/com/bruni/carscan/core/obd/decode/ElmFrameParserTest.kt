package com.bruni.carscan.core.obd.decode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ElmFrameParserTest {

    @Test
    fun `parses an 11-bit frame`() {
        // The exact first line of the Kia-EV6 22 0101 fixture.
        val f = parseElmLine("7EC103E620101EFFBE7")!!
        assertEquals("7EC", f.canId)
        assertEquals(bytes(0x10, 0x3E, 0x62, 0x01, 0x01, 0xEF, 0xFB, 0xE7).toList(), f.bytes.toList())
    }

    @Test
    fun `parses a 29-bit frame`() {
        val f = parseElmLine("18DAF11003410C1AF8")!!
        assertEquals("18DAF110", f.canId)
        assertEquals(bytes(0x03, 0x41, 0x0C, 0x1A, 0xF8).toList(), f.bytes.toList())
    }

    @Test
    fun `tolerates embedded spaces from adapters that ignore ATS0`() {
        val f = parseElmLine("7E8 03 41 0C 1A F8")!!
        assertEquals("7E8", f.canId)
        assertEquals(bytes(0x03, 0x41, 0x0C, 0x1A, 0xF8).toList(), f.bytes.toList())
    }

    @Test
    fun `lowercase hex is accepted and normalized`() {
        val f = parseElmLine("7ec103e620101effbe7")!!
        assertEquals("7EC", f.canId)
        assertEquals(0x10.toByte(), f.bytes[0])
    }

    @Test
    fun `junk is null, never a guess`() {
        // Every one of these is something a real ELM327 prints on the same stream as
        // frames. Decoding any of them as hex would produce a plausible wrong number.
        assertNull(parseElmLine(""))
        assertNull(parseElmLine("   "))
        assertNull(parseElmLine(">"))
        assertNull(parseElmLine("NO DATA"))
        assertNull(parseElmLine("SEARCHING..."))
        assertNull(parseElmLine("CAN ERROR"))
        assertNull(parseElmLine("BUFFER FULL"))
        assertNull(parseElmLine("STOPPED"))
        assertNull(parseElmLine("UNABLE TO CONNECT"))
        assertNull(parseElmLine("ELM327 v2.1"))
        assertNull(parseElmLine("?"))
    }

    @Test
    fun `an id with no payload bytes is not a frame`() {
        assertNull(parseElmLine("7EC"))
        assertNull(parseElmLine("18DAF110"))
    }

    @Test
    fun `a half byte can only ever be dropped by dropping the whole line`() {
        // Parity is the only thing separating the two id widths, so an 11-bit line
        // with a torn-off nibble is bit-for-bit a well-formed 29-bit line and cannot
        // be distinguished from one. We do not pretend otherwise: nothing here is
        // truncated, and a torn line is caught downstream by the ISO-TP length check
        // rather than guessed at here.
        val torn = parseElmLine("7EC103E62010")!!  // was "7EC" + 4.5 bytes
        assertEquals("7EC103E6", torn.canId)       // reads as a 29-bit id, not a broken 11-bit one
        assertEquals(2, torn.bytes.size)
    }

    @Test
    fun `length parity is what distinguishes an 11-bit id from a 29-bit one`() {
        // 3 + 2n is always odd, 8 + 2n is always even, so the split is total and
        // unambiguous — there is no third id width to guess at.
        assertEquals("7E8", parseElmLine("7E803410C1AF8")!!.canId)      // 13 chars, odd
        assertEquals("18DAF110", parseElmLine("18DAF11003410C1AF8")!!.canId) // 18 chars, even

        // Too short to be either.
        assertNull(parseElmLine("7E"))
        assertNull(parseElmLine("7EC1"))
        assertNull(parseElmLine("18DAF1"))
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
