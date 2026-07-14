package com.bruni.carscan.core.database

import app.cash.sqldelight.ColumnAdapter

/**
 * Packs the columnar 1 Hz arrays into BLOBs, little-endian IEEE-754.
 *
 * This is the app's on-disk format for every recorded trip. It is written by hand
 * rather than via a platform ByteBuffer because it has to run unchanged on
 * Kotlin/Native, and because the byte order must be pinned: change it and every
 * previously recorded trip decodes to plausible garbage, with no way to tell.
 *
 * `toRawBits` rather than `toBits`: NaN is the gap marker for "this signal produced
 * no sample in this second", and it has to survive the round trip as NaN.
 */
object FloatArrayColumnAdapter : ColumnAdapter<FloatArray, ByteArray> {

    override fun encode(value: FloatArray): ByteArray {
        val out = ByteArray(value.size * 4)
        var o = 0
        for (f in value) {
            val bits = f.toRawBits()
            out[o++] = bits.toByte()
            out[o++] = (bits ushr 8).toByte()
            out[o++] = (bits ushr 16).toByte()
            out[o++] = (bits ushr 24).toByte()
        }
        return out
    }

    override fun decode(databaseValue: ByteArray): FloatArray {
        require(databaseValue.size % 4 == 0) {
            "float blob is ${databaseValue.size} bytes, not a multiple of 4"
        }
        val out = FloatArray(databaseValue.size / 4)
        var o = 0
        for (i in out.indices) {
            val bits = (databaseValue[o].toInt() and 0xFF) or
                ((databaseValue[o + 1].toInt() and 0xFF) shl 8) or
                ((databaseValue[o + 2].toInt() and 0xFF) shl 16) or
                ((databaseValue[o + 3].toInt() and 0xFF) shl 24)
            out[i] = Float.fromBits(bits)
            o += 4
        }
        return out
    }
}

/**
 * Same format, 8 bytes per element. Used for GPS latitude and longitude only.
 *
 * float32 carries about 7 significant decimal digits. A latitude such as 37.5665
 * spends two of them before the decimal point, leaving roughly a metre of residual
 * error — enough that a zoomed-in playback route visibly snakes off the road.
 * Altitude, speed and bearing have no such problem and stay float32.
 */
object DoubleArrayColumnAdapter : ColumnAdapter<DoubleArray, ByteArray> {

    override fun encode(value: DoubleArray): ByteArray {
        val out = ByteArray(value.size * 8)
        var o = 0
        for (d in value) {
            val bits = d.toRawBits()
            for (shift in 0 until 64 step 8) {
                out[o++] = (bits ushr shift).toByte()
            }
        }
        return out
    }

    override fun decode(databaseValue: ByteArray): DoubleArray {
        require(databaseValue.size % 8 == 0) {
            "double blob is ${databaseValue.size} bytes, not a multiple of 8"
        }
        val out = DoubleArray(databaseValue.size / 8)
        var o = 0
        for (i in out.indices) {
            var bits = 0L
            for (b in 7 downTo 0) {
                bits = (bits shl 8) or (databaseValue[o + b].toLong() and 0xFF)
            }
            out[i] = Double.fromBits(bits)
            o += 8
        }
        return out
    }
}
