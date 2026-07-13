package com.bruni.carscan.core.obd.decode

import com.bruni.carscan.core.model.DecodedValue
import com.bruni.carscan.core.model.isBoolean
import com.bruni.carscan.core.model.isText
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.obdb.Fmt

/**
 * Pulls one signal out of a stripped response payload.
 *
 * The order of operations is the entire contract. Each step below is wrong if moved:
 *
 *  1. read the raw bits MSB-first
 *  2. byte-swap if [Fmt.blsb]
 *  3. sign-extend if [Fmt.sign]
 *  4. **null-check the RAW value** — `nullmax = 255` means "raw 255 is a disconnected
 *     sensor". Checking after scaling compares 255 against 127.5 and reports a
 *     confident reading for a sensor that is not plugged in.
 *  5. scale: `raw * mul / div + add`
 *  6. clamp, but only when a real range was given (`max > min`)
 *
 * `omin` / `omax` / `oval` are deliberately absent: they are the gauge's green zone and
 * redline, not decoder inputs.
 */
object SignalDecoder {

    fun decode(fmt: Fmt, payload: ByteArray): DecodedValue? {
        if (fmt.len <= 0 || fmt.len > 64 || fmt.bix < 0) return null
        // A field running off the end means a truncated or mis-stripped response. Zero-
        // padding it would invent a plausible reading out of bytes we never received.
        if (fmt.bix + fmt.len > payload.size * 8) return null

        var raw = bitsMsbFirst(payload, fmt.bix, fmt.len)
        if (fmt.blsb && fmt.len > 8) raw = swapBytes(raw, fmt.len)
        if (fmt.sign && fmt.len < 64 && (raw shr (fmt.len - 1)) and 1L == 1L) {
            raw -= 1L shl fmt.len
        }

        fmt.nullmin?.let { if (raw <= it) return null }
        fmt.nullmax?.let { if (raw >= it) return null }

        if (fmt.map.isNotEmpty()) {
            // An unmapped raw value is a state this signalset does not describe. Naming
            // it anyway would be an invention.
            val entry = fmt.map[raw.toString()] ?: return null
            return DecodedValue.Enumerated(raw.toInt(), entry.value, entry.description)
        }

        val unit = fmt.unit
        if (unit != null && unit.isText) return DecodedValue.Text(asText(unit, raw, fmt.len))

        var v = raw * fmt.mul / fmt.div + fmt.add
        val max = fmt.max
        if (max != null && max > fmt.min) v = v.coerceIn(fmt.min, max)

        if (unit != null && unit.isBoolean) return DecodedValue.Bool(v != 0.0)
        return DecodedValue.Numeric(v)
    }

    /** `hex` renders the field's own width; `ascii` reads it as characters. */
    private fun asText(unit: ObdUnit, raw: Long, len: Int): String = when (unit) {
        ObdUnit.ASCII -> buildString {
            for (i in len / 8 - 1 downTo 0) append(((raw shr (8 * i)) and 0xFF).toInt().toChar())
        }
        else -> raw.toString(16).uppercase().padStart((len + 3) / 4, '0')
    }

    /** Reinterprets the [len]-bit field's bytes in the opposite order. */
    private fun swapBytes(raw: Long, len: Int): Long {
        var out = 0L
        for (i in 0 until len / 8) {
            out = (out shl 8) or ((raw shr (8 * i)) and 0xFF)
        }
        return out
    }
}

/**
 * Reads [len] bits starting at bit [bix], most-significant bit first.
 *
 * Bit 0 is the top bit of byte 0: within a byte, bit index `i` is at shift `7 - (i % 8)`.
 * Fields are not byte-aligned in general — OBDb addresses single status bits and 4-bit
 * nibbles by bit offset — so this cannot be expressed as a byte slice.
 *
 * The caller is responsible for the field being in bounds; [SignalDecoder.decode] checks.
 */
internal fun bitsMsbFirst(payload: ByteArray, bix: Int, len: Int): Long {
    var v = 0L
    for (i in 0 until len) {
        val bit = bix + i
        val b = payload[bit / 8].toInt() and 0xFF
        v = (v shl 1) or ((b shr (7 - (bit % 8))) and 1).toLong()
    }
    return v
}
