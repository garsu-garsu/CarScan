package com.bruni.carscan.core.database

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The columnar store lives or dies on this codec: every recorded trip is a pile of
 * BLOBs, and a packing bug is not a crash but a *plausible wrong number* on a chart
 * six months of driving later, with no way to recover the original.
 */
class BlobCodecTest {

    @Test
    fun `float array survives a round trip exactly`() {
        val values = floatArrayOf(0f, 1f, -1f, 0.1f, 1726.25f, 3.4028235e38f, 1.4e-45f)
        val decoded = FloatArrayColumnAdapter.decode(FloatArrayColumnAdapter.encode(values))
        assertContentEquals(values, decoded)
    }

    @Test
    fun `float array packs to exactly four bytes per element`() {
        val values = FloatArray(600) { it.toFloat() }
        assertEquals(2400, FloatArrayColumnAdapter.encode(values).size)
    }

    /**
     * NaN is the gap marker for "this signal produced no sample in this second".
     * If it does not survive the round trip, every gap silently becomes a real
     * reading — and 0.0 km/h is a *believable* reading, which is what makes it bad.
     */
    @Test
    fun `NaN and the infinities survive, and NaN stays NaN`() {
        val values = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.0f)
        val decoded = FloatArrayColumnAdapter.decode(FloatArrayColumnAdapter.encode(values))

        assertTrue(decoded[0].isNaN(), "NaN decoded as ${decoded[0]}")
        assertEquals(Float.POSITIVE_INFINITY, decoded[1])
        assertEquals(Float.NEGATIVE_INFINITY, decoded[2])
        // -0.0 and 0.0 compare equal, so compare the bits.
        assertEquals((-0.0f).toRawBits(), decoded[3].toRawBits())
    }

    @Test
    fun `empty float array round trips to an empty blob`() {
        val blob = FloatArrayColumnAdapter.encode(FloatArray(0))
        assertEquals(0, blob.size)
        assertEquals(0, FloatArrayColumnAdapter.decode(blob).size)
    }

    @Test
    fun `one thousand random floats survive a round trip exactly`() {
        val rng = Random(0xC0FFEE)
        val values = FloatArray(1000) { Float.fromBits(rng.nextInt()) }
        val decoded = FloatArrayColumnAdapter.decode(FloatArrayColumnAdapter.encode(values))
        // Compare bits: two NaNs are not `==` each other, but they must still round trip.
        for (i in values.indices) {
            assertEquals(values[i].toRawBits(), decoded[i].toRawBits(), "index $i")
        }
    }

    @Test
    fun `a blob whose length is not a multiple of four is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            FloatArrayColumnAdapter.decode(ByteArray(7))
        }
    }

    @Test
    fun `double array survives a round trip exactly`() {
        // A real route: float32 would lose about a metre here, which is why lat/lon are doubles.
        val values = doubleArrayOf(37.56653782, 126.97796919, -0.0, Double.NaN, 1.7976931348623157e308)
        val decoded = DoubleArrayColumnAdapter.decode(DoubleArrayColumnAdapter.encode(values))
        for (i in values.indices) {
            assertEquals(values[i].toRawBits(), decoded[i].toRawBits(), "index $i")
        }
    }

    @Test
    fun `double array packs to exactly eight bytes per element`() {
        assertEquals(4800, DoubleArrayColumnAdapter.encode(DoubleArray(600)).size)
    }

    @Test
    fun `a double blob whose length is not a multiple of eight is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DoubleArrayColumnAdapter.decode(ByteArray(12))
        }
    }

    /**
     * The byte order is part of the on-disk format. If it is ever changed, every
     * previously recorded trip decodes to garbage — so pin it, rather than trusting
     * that nobody will "tidy up" the shifts later.
     */
    @Test
    fun `the wire format is little-endian IEEE-754`() {
        // 1.0f == 0x3F800000
        assertContentEquals(
            byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F),
            FloatArrayColumnAdapter.encode(floatArrayOf(1.0f)),
        )
    }
}
