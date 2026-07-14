package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.obdb.ObdbCommand
import com.bruni.carscan.core.model.obdb.spec
import com.bruni.carscan.core.obd.decode.IsoTpReassembler
import com.bruni.carscan.core.obd.decode.SignalDecoder
import com.bruni.carscan.core.obd.decode.acceptsReplyFrom
import com.bruni.carscan.core.obd.decode.parseElmLine
import com.bruni.carscan.core.obd.decode.stripServiceEcho

/**
 * Turns the lines of one answer into samples, using the decoder committed in `decode/`.
 *
 * The reassembler is reset per exchange rather than kept across them: half-duplex means one
 * command's frames are all that can be in flight, so a buffer left half-built by a truncated
 * answer must not be completed by the *next* command's first frame — that would decode one
 * PID's bytes under another PID's format and produce a number that looks entirely reasonable.
 */
internal class PollDecoder {

    private val reassembler = IsoTpReassembler()

    /** The number of CAN frames the last answer occupied — what `expectedFrames` is learned from. */
    var lastFrameCount: Int = 0
        private set

    fun decode(command: ObdbCommand, lines: List<String>, timestampMs: Long): List<SensorSample> {
        reassembler.reset()
        val spec = command.spec()

        val frames = lines.mapNotNull { parseElmLine(it) }
        lastFrameCount = frames.size

        val out = mutableListOf<SensorSample>()
        for (frame in frames) {
            if (!acceptsReplyFrom(frame.canId, command.rax)) continue
            for (message in reassembler.feed(frame)) {
                val payload = stripServiceEcho(message, spec) ?: continue
                for (signal in command.signals) {
                    val value = SignalDecoder.decode(signal.fmt, payload) ?: continue
                    out += SensorSample(
                        signalId = signal.id,
                        key = signal.suggestedMetric?.let { MetricKey.Metric(it) }
                            ?: MetricKey.Signal(signal.id),
                        value = value,
                        unit = signal.fmt.unit,
                        timestampMs = timestampMs,
                        ecu = message.canId,
                        experimental = command.dbg,
                    )
                }
            }
        }
        return out
    }
}
