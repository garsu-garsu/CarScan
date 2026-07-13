package com.bruni.carscan.core.obd.decode

import com.bruni.carscan.core.model.DecodedValue
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.obdb.Fmt
import com.bruni.carscan.core.model.obdb.MapEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SignalDecoderTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun numeric(fmt: Fmt, payload: ByteArray): Double =
        assertIs<DecodedValue.Numeric>(SignalDecoder.decode(fmt, payload)).value

    // ---- bitsMsbFirst ------------------------------------------------------

    @Test
    fun `bitsMsbFirst reads a byte-aligned field`() {
        val p = bytes(0xEF, 0xFB, 0xE7, 0xEF, 0x6F)
        assertEquals(0xEFL, bitsMsbFirst(p, 0, 8))
        assertEquals(0x6FL, bitsMsbFirst(p, 32, 8))       // the EV6 SOC byte
        assertEquals(0xEFFBL, bitsMsbFirst(p, 0, 16))
        assertEquals(0xEFFBE7EFL, bitsMsbFirst(p, 0, 32))
    }

    @Test
    fun `bitsMsbFirst crosses byte boundaries`() {
        val p = bytes(0xEF, 0xFB)                          // 1110 1111 1111 1011
        assertEquals(0xFFL, bitsMsbFirst(p, 4, 8))         // low nibble of EF + high of FB
        assertEquals(0x0FL, bitsMsbFirst(p, 3, 5))         // bits 3..7 of EF
        assertEquals(0b110L, bitsMsbFirst(p, 1, 3))        // bits 1..3 of EF = 1,1,0
    }

    @Test
    fun `bitsMsbFirst indexes bit 0 as the most significant bit of byte 0`() {
        assertEquals(1L, bitsMsbFirst(bytes(0x80), 0, 1))
        assertEquals(0L, bitsMsbFirst(bytes(0x80), 1, 1))
        assertEquals(1L, bitsMsbFirst(bytes(0x01), 7, 1))
    }

    @Test
    fun `bitsMsbFirst round-trips every byte value`() {
        // kotest-property is not on the classpath, so this walks the space directly.
        for (v in 0..255) {
            val p = bytes(v)
            assertEquals(v.toLong(), bitsMsbFirst(p, 0, 8), "byte $v")
            for (i in 0..7) {
                val expected = ((v shr (7 - i)) and 1).toLong()
                assertEquals(expected, bitsMsbFirst(p, i, 1), "byte $v bit $i")
            }
        }
    }

    @Test
    fun `bitsMsbFirst round-trips every 16-bit value`() {
        for (v in 0..0xFFFF) {
            val p = bytes(v shr 8, v and 0xFF)
            assertEquals(v.toLong(), bitsMsbFirst(p, 0, 16), "word $v")
        }
    }

    // ---- decode order ------------------------------------------------------

    @Test
    fun `the known-good anchor - Kia EV6 state of charge`() {
        // 22 0101 -> 62 01 01 | EF FB E7 EF 6F ...; bix 32 is payload byte 4 = 0x6F = 111.
        val fmt = Fmt(bix = 32, len = 8, mul = 0.5, max = 100.0, unit = ObdUnit.PERCENT)
        assertEquals(55.5, numeric(fmt, bytes(0xEF, 0xFB, 0xE7, 0xEF, 0x6F)))
    }

    @Test
    fun `mul div and add are applied after the raw value, not before`() {
        val fmt = Fmt(len = 8, mul = 100.0, div = 255.0, add = -10.0)
        assertEquals(0xFF * 100.0 / 255.0 - 10.0, numeric(fmt, bytes(0xFF)))
    }

    @Test
    fun `sign is two's complement over len bits, not over the byte`() {
        assertEquals(-1.0, numeric(Fmt(len = 8, sign = true), bytes(0xFF)))
        assertEquals(-128.0, numeric(Fmt(len = 8, sign = true), bytes(0x80)))
        assertEquals(127.0, numeric(Fmt(len = 8, sign = true), bytes(0x7F)))
        // A 4-bit signed field: 0b1111 is -1, not 15 and not 255.
        assertEquals(-1.0, numeric(Fmt(bix = 4, len = 4, sign = true), bytes(0x0F)))
        assertEquals(-8.0, numeric(Fmt(bix = 4, len = 4, sign = true), bytes(0x08)))
        assertEquals(7.0, numeric(Fmt(bix = 4, len = 4, sign = true), bytes(0x07)))
    }

    @Test
    fun `sign is applied before scaling`() {
        // -1 raw * 0.5 = -0.5. Scaling first and then sign-extending would give 127.5.
        assertEquals(-0.5, numeric(Fmt(len = 8, sign = true, mul = 0.5), bytes(0xFF)))
    }

    @Test
    fun `every signed byte value sign-extends correctly`() {
        for (v in 0..255) {
            val expected = if (v >= 0x80) (v - 256).toDouble() else v.toDouble()
            assertEquals(expected, numeric(Fmt(len = 8, sign = true), bytes(v)), "byte $v")
        }
    }

    @Test
    fun `blsb reinterprets the bytes little-endian`() {
        // 0xAABB read MSB-first is 43707; as little-endian it is 0xBBAA = 48042.
        assertEquals(0xAABBL.toDouble(), numeric(Fmt(len = 16), bytes(0xAA, 0xBB)))
        assertEquals(0xBBAAL.toDouble(), numeric(Fmt(len = 16, blsb = true), bytes(0xAA, 0xBB)))
        assertEquals(0xCCBBAAL.toDouble(), numeric(Fmt(len = 24, blsb = true), bytes(0xAA, 0xBB, 0xCC)))
    }

    @Test
    fun `blsb does nothing to a single byte`() {
        // The flag is defined as byte-order, and one byte has no order to reverse.
        assertEquals(0xAAL.toDouble(), numeric(Fmt(len = 8, blsb = true), bytes(0xAA)))
    }

    @Test
    fun `blsb is applied before sign, so the sign bit comes from the swapped value`() {
        // MSB-first 0x01FF -> swapped 0xFF01 -> signed = -255.
        assertEquals(-255.0, numeric(Fmt(len = 16, blsb = true, sign = true), bytes(0x01, 0xFF)))
    }

    @Test
    fun `nullmax is compared against the raw value, not the scaled one`() {
        // This is the whole reason the order is fixed. Raw 255 on a 1-byte sensor means
        // "unplugged". Scaled it is 127.5, which is comfortably under nullmax=255, so an
        // implementation that null-checks after scaling reports a confident 127.5 deg
        // for a sensor that is not there.
        val fmt = Fmt(len = 8, mul = 0.5, nullmax = 255.0)
        assertNull(SignalDecoder.decode(fmt, bytes(0xFF)))
        assertEquals(127.0, numeric(fmt, bytes(0xFE)))
    }

    @Test
    fun `nullmin is compared against the raw value, not the scaled one`() {
        val fmt = Fmt(len = 8, mul = 10.0, add = 5.0, nullmin = 0.0)
        assertNull(SignalDecoder.decode(fmt, bytes(0x00)))
        assertEquals(15.0, numeric(fmt, bytes(0x01)))
    }

    @Test
    fun `null checks are inclusive at the boundary`() {
        assertNull(SignalDecoder.decode(Fmt(len = 8, nullmin = 3.0), bytes(3)))
        assertEquals(4.0, numeric(Fmt(len = 8, nullmin = 3.0), bytes(4)))
        assertNull(SignalDecoder.decode(Fmt(len = 8, nullmax = 200.0), bytes(200)))
        assertEquals(199.0, numeric(Fmt(len = 8, nullmax = 200.0), bytes(199)))
    }

    @Test
    fun `null check sees the signed value when sign is set`() {
        val fmt = Fmt(len = 8, sign = true, nullmin = -1.0)
        assertNull(SignalDecoder.decode(fmt, bytes(0xFF)))     // -1
        assertEquals(1.0, numeric(fmt, bytes(0x01)))
    }

    @Test
    fun `the value is clamped only when max is above min`() {
        assertEquals(100.0, numeric(Fmt(len = 8, mul = 0.5, max = 100.0), bytes(0xFF)))  // 127.5 -> 100
        assertEquals(10.0, numeric(Fmt(len = 8, min = 10.0, max = 100.0), bytes(0x00)))  // 0 -> 10
        // max == min (both defaults, or explicitly equal) means "no range given": no clamp.
        assertEquals(255.0, numeric(Fmt(len = 8, max = 0.0), bytes(0xFF)))
        assertEquals(255.0, numeric(Fmt(len = 8, min = 5.0, max = 5.0), bytes(0xFF)))
    }

    @Test
    fun `omin omax and oval are gauge hints and never touch the value`() {
        val plain = Fmt(len = 8)
        val hinted = Fmt(len = 8, omin = 10.0, omax = 20.0, oval = 15.0)
        assertEquals(numeric(plain, bytes(0xFF)), numeric(hinted, bytes(0xFF)))
    }

    // ---- result type -------------------------------------------------------

    @Test
    fun `a map decodes to Enumerated keyed on the raw value`() {
        val fmt = Fmt(
            len = 8,
            map = mapOf(
                "0" to MapEntry(value = "OFF", description = "Engine is off"),
                "2" to MapEntry(value = "CL", description = "Closed loop"),
            ),
        )
        val e = assertIs<DecodedValue.Enumerated>(SignalDecoder.decode(fmt, bytes(2)))
        assertEquals(2, e.key)
        assertEquals("CL", e.label)
        assertEquals("Closed loop", e.description)
    }

    @Test
    fun `a raw value missing from the map is null, not an invented label`() {
        val fmt = Fmt(len = 8, map = mapOf("0" to MapEntry("OFF")))
        assertNull(SignalDecoder.decode(fmt, bytes(9)))
    }

    @Test
    fun `a map wins over the unit`() {
        // fmt.map and a numeric unit are mutually exclusive in the schema, but if a
        // signalset carries both, the labels are the more specific statement.
        val fmt = Fmt(len = 8, unit = ObdUnit.PERCENT, map = mapOf("1" to MapEntry("ONE")))
        assertIs<DecodedValue.Enumerated>(SignalDecoder.decode(fmt, bytes(1)))
    }

    @Test
    fun `boolean units decode to Bool`() {
        for (u in listOf(ObdUnit.OFFON, ObdUnit.ONOFF, ObdUnit.YESNO, ObdUnit.NOYES)) {
            val fmt = Fmt(len = 1, max = 1.0, unit = u)
            assertEquals(DecodedValue.Bool(true), SignalDecoder.decode(fmt, bytes(0x80)))
            assertEquals(DecodedValue.Bool(false), SignalDecoder.decode(fmt, bytes(0x00)))
        }
    }

    @Test
    fun `text units decode to Text`() {
        // The only text signal in the vendored corpus is a 16-bit `hex` (the DTC that
        // caused freeze-frame storage), so this is where that path is pinned down.
        val hex = assertIs<DecodedValue.Text>(
            SignalDecoder.decode(Fmt(len = 16, unit = ObdUnit.HEX), bytes(0x01, 0x43)),
        )
        assertEquals("0143", hex.value)

        val ascii = assertIs<DecodedValue.Text>(
            SignalDecoder.decode(Fmt(len = 16, unit = ObdUnit.ASCII), bytes(0x4B, 0x4D)),
        )
        assertEquals("KM", ascii.value)
    }

    @Test
    fun `a plain unit decodes to Numeric`() {
        assertIs<DecodedValue.Numeric>(SignalDecoder.decode(Fmt(len = 8, unit = ObdUnit.RPM), bytes(1)))
        assertIs<DecodedValue.Numeric>(SignalDecoder.decode(Fmt(len = 8), bytes(1)))
    }

    // ---- bounds ------------------------------------------------------------

    @Test
    fun `a field running past the end of the payload is null, not zero-padded`() {
        // Short payloads are what a truncated or mis-stripped response looks like.
        // Padding with zeros would report a real-looking 0 for a value we never received.
        assertNull(SignalDecoder.decode(Fmt(bix = 32, len = 8), bytes(0xEF, 0xFB)))
        assertNull(SignalDecoder.decode(Fmt(bix = 0, len = 16), bytes(0xEF)))
        assertNull(SignalDecoder.decode(Fmt(bix = 0, len = 8), ByteArray(0)))
        // Exactly filling the payload is fine.
        assertEquals(0xEFFBL.toDouble(), numeric(Fmt(bix = 0, len = 16), bytes(0xEF, 0xFB)))
    }
}
