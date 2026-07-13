package com.bruni.carscan.core.model.obdb

import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.model.SuggestedMetricGroup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An OBDb `signalsets/v3` document, mirrored field for field.
 *
 * Schema: https://raw.githubusercontent.com/OBDb/.schemas/main/signals.json
 * Data:   https://github.com/OBDb — CC BY-SA 4.0
 *
 * The JSON is the licensed artifact and stays an opaque asset at all times: it is
 * bundled as a resource or cached as a TEXT column, and is deserialized into these
 * classes at runtime. It is never code-generated into Kotlin source, because a
 * generated source file containing the signal tables would be an adaptation of
 * CC BY-SA data and would encumber the app's own source.
 *
 * The schema itself declares no defaults; the defaults below come from OBDb's
 * Python reference implementation and are load-bearing for decoding.
 */
@Serializable
data class Signalset(
    val commands: List<ObdbCommand>,
    val signalGroups: List<SignalGroup> = emptyList(),
    val synthetics: List<Synthetic> = emptyList(),
    /** Global diagnostic session to enter after connecting, e.g. "03". */
    val diagnosticLevel: String? = null,
)

/**
 * One request/response transaction: what to send, how to address it, how often,
 * and how to pull signals back out of the reply.
 */
@Serializable
data class ObdbCommand(
    /** Request CAN id, e.g. "7E0" (11-bit) or "18DB33F1" (29-bit). Becomes ATSH. */
    val hdr: String,
    /** Receive-address filter, e.g. "7E8". Becomes ATCRA; absent means clear the filter. */
    val rax: String? = null,
    /** ISO-TP extended address. Becomes ATCEA. */
    val eax: String? = null,
    /** CAN priority, folded into the 29-bit header. */
    val pri: String? = null,
    /** Tester address, folded into the 29-bit header. */
    val tst: String? = null,
    /** Per-command timeout. Becomes ATAT0 + ATST. */
    val tmo: String? = null,
    /** Requires explicit flow control: ATFCSH + ATFCSD + ATFCSM1. */
    val fcm1: Boolean = false,
    /** Unverified everywhere — surface as experimental, never rely on it. */
    val dbg: Boolean = false,
    /** Diagnostic session to enter before this command (UDS `10 xx`). */
    val din: String? = null,
    /** Diagnostic session to restore after this command. */
    val dout: String? = null,
    /**
     * Exactly one entry: service -> PID. `{"01": "0C"}` is mode 01 with a 1-byte
     * PID; `{"22": "E003"}` is mode 22 with a 2-byte PID; `{"21": "01"}` likewise.
     * Modeled as a map because that is literally what the schema says, and because
     * a service we have never seen must not silently decode as one we have.
     */
    val cmd: Map<String, String>,
    /**
     * SECONDS BETWEEN REQUESTS — not hertz. `0.25` means four times a second,
     * `3600` means hourly. Reading this as a frequency makes every poll 16x too
     * fast at best and divides by zero at worst.
     */
    val freq: Double,
    val proto: ObdProtocol? = null,
    /** Model years this command applies to. Absent means all years. */
    val filter: YearFilter? = null,
    /** Model years for which this command is unverified. */
    val dbgfilter: YearFilter? = null,
    val signals: List<ObdbSignal>,
)

@Serializable
enum class ObdProtocol {
    @SerialName("9141-2") ISO_9141_2,
    @SerialName("14230") ISO_14230,
    @SerialName("15765-4-11bit") ISO_15765_4_11BIT,
    @SerialName("15765-4-29bit") ISO_15765_4_29BIT,
}

/** Inclusive year range plus an explicit list; any match includes the year. */
@Serializable
data class YearFilter(
    val from: Int? = null,
    val to: Int? = null,
    val years: List<Int> = emptyList(),
)

@Serializable
data class ObdbSignal(
    /** Unique within the vehicle, e.g. "RPM" or "EV6_HVBAT_SOC". */
    val id: String,
    val name: String,
    val description: String? = null,
    val hidden: Boolean = false,
    /** Dot-separated hierarchy, e.g. "Battery" or "DTCs.Generic.Status". */
    val path: String? = null,
    /** Present only where OBDb has a canonical cross-vehicle metric for this signal. */
    val suggestedMetric: SuggestedMetric? = null,
    val fmt: Fmt,
)

/**
 * How to pull one value out of the response payload.
 *
 * The payload here is what remains AFTER the echoed service and PID bytes are
 * stripped: mode 01 drops `0x41` + 1 PID byte, mode 22 drops `0x62` + 2 PID bytes.
 * [bix] is an offset into that remainder, not into the raw frame.
 *
 * Decoding order is not negotiable:
 *   1. raw = bits [bix, bix+len) of the payload, MSB-first
 *   2. if [blsb] and len > 8, reinterpret those bytes little-endian
 *   3. if [sign], two's-complement over len bits
 *   4. null out if raw <= [nullmin] or raw >= [nullmax]
 *   5. value = raw * [mul] / [div] + [add]
 *   6. if [max] > [min], clamp to that range
 *
 * [omin], [omax] and [oval] are not decoder inputs at all — they describe the
 * "optimal" band and ride through to the gauge as the green zone / redline.
 */
@Serializable
data class Fmt(
    /** Bit offset into the stripped payload. MSB-first: bit = 7 - (i % 8) within each byte. */
    val bix: Int = 0,
    /** Bit length. The only required field. */
    val len: Int,
    /** Byte order is little-endian when true and len > 8. */
    val blsb: Boolean = false,
    /** Two's-complement over [len] bits. */
    val sign: Boolean = false,
    val min: Double = 0.0,
    val max: Double? = null,
    val add: Double = 0.0,
    val mul: Double = 1.0,
    val div: Double = 1.0,
    val unit: ObdUnit? = null,
    /** Raw values at or below this mean "no reading", not zero. */
    val nullmin: Double? = null,
    /** Raw values at or above this mean "no reading". 255 on a 1-byte sensor usually means unplugged. */
    val nullmax: Double? = null,
    val omin: Double? = null,
    val omax: Double? = null,
    val oval: Double? = null,
    /** Enumerated decode: raw integer (as a string key) -> label. Mutually exclusive with a numeric unit. */
    val map: Map<String, MapEntry> = emptyMap(),
)

@Serializable
data class MapEntry(
    val value: String,
    val description: String? = null,
)

/** Collapses an array of per-cell signals (`…Module3.Cell7.SoC`) into one logical group. */
@Serializable
data class SignalGroup(
    val id: String,
    val name: String? = null,
    val path: String? = null,
    val matchingRegex: String? = null,
    val suggestedMetricGroup: SuggestedMetricGroup? = null,
)

/** A signal computed from other signals rather than read from the bus. */
@Serializable
data class Synthetic(
    val id: String,
    val name: String? = null,
    val path: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val unit: ObdUnit? = null,
    val suggestedMetric: SuggestedMetric? = null,
    val formula: SyntheticFormula? = null,
)

@Serializable
data class SyntheticFormula(
    /** OBDb defines exactly one operation today. */
    val op: SyntheticOp,
    /** Signal id of the numerator. */
    val a: String? = null,
    /** Signal id of the denominator. */
    val b: String? = null,
)

@Serializable
enum class SyntheticOp {
    @SerialName("ratio") RATIO,
}
