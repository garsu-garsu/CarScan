package com.bruni.carscan.core.obd.session

import com.bruni.carscan.core.obd.ElmErrorKind
import com.bruni.carscan.core.obd.ElmResponse
import kotlin.time.Duration

/**
 * Cuts the byte stream into responses at the `>` prompt.
 *
 * The prompt is the *only* delimiter an ELM327 offers. Nothing else says "I am done":
 * not a line count, not a length, not a terminator. So the framer accumulates whatever
 * the transport hands it and hands back a response the moment it sees `>`.
 *
 * **One chunk is not one line, and one chunk is not one response.** A BLE notification
 * carries at most 20 bytes; a TCP read may carry three responses and half of a fourth.
 * Code that reads a chunk and treats it as a line works on the developer's adapter and
 * fails on the user's, which is why [feed] returns a *list* and why the transport fakes
 * can deliver a response one byte at a time.
 *
 * Single-threaded by construction: only the coroutine holding the session mutex touches
 * it, so it needs no synchronization of its own.
 */
internal class PromptFramer {

    private val pending = StringBuilder()

    /** Whatever has arrived since the last prompt. Non-empty here before a write means a desync. */
    val partial: String get() = pending.toString()

    /** The responses this chunk completed — usually one, sometimes none, occasionally two. */
    fun feed(chunk: ByteArray): List<String> {
        val completed = mutableListOf<String>()
        for (byte in chunk) {
            val char = (byte.toInt() and 0xFF).toChar()
            if (char == PROMPT) {
                completed += pending.toString()
                pending.clear()
            } else {
                pending.append(char)
            }
        }
        return completed
    }

    fun reset() {
        pending.clear()
    }

    private companion object {
        const val PROMPT = '>'
    }
}

/** An ELM327 says no in a fixed vocabulary, and it says it where a payload would be. */
private val ERROR_TOKENS: List<Pair<String, ElmErrorKind>> = listOf(
    "NO DATA" to ElmErrorKind.NO_DATA,
    "CAN ERROR" to ElmErrorKind.CAN_ERROR,
    "BUS BUSY" to ElmErrorKind.BUS_BUSY,
    "BUS ERROR" to ElmErrorKind.BUS_ERROR,
    "BUS INIT" to ElmErrorKind.BUS_INIT,
    "STOPPED" to ElmErrorKind.STOPPED,
    "UNABLE TO CONNECT" to ElmErrorKind.UNABLE_TO_CONNECT,
    "BUFFER FULL" to ElmErrorKind.BUFFER_FULL,
    "LV RESET" to ElmErrorKind.LV_RESET,
    "ACT ALERT" to ElmErrorKind.ACT_ALERT,
)

/**
 * The error this line announces, or null if it is a payload.
 *
 * [line] must still have its spaces: `NO DATA` is one token, and the moment you strip
 * the space it becomes `NODATA`, which is eight perfectly good hex nibbles. Matching
 * errors *after* stripping whitespace is how a decoder invents a reading out of a
 * failure, and the reading looks plausible.
 */
internal fun elmErrorOf(line: String): ElmErrorKind? {
    if (line == "?") return ElmErrorKind.QUESTION_MARK
    return ERROR_TOKENS.firstOrNull { (token, _) -> line.startsWith(token) }?.second
}

/** One framed response, plus the one thing [ElmResponse] cannot carry. */
internal class ElmReply(
    val response: ElmResponse,
    /**
     * The adapter echoed the command back.
     *
     * Checked after a command that follows `ATE0`, never after `ATE0` itself: an ELM327
     * mirrors characters as they arrive, so `ATE0` always echoes its own name no matter
     * what it does with the setting. If the *next* command still echoes, the adapter
     * ignored `ATE0` — and plenty of clones do.
     */
    val echoed: Boolean,
)

/**
 * Turns the raw text between two prompts into an [ElmResponse].
 *
 * The order is the contract:
 *  1. split on CR (and LF, because `ATL1` clones exist), trim, drop the empties
 *  2. drop `SEARCHING...` — progress chatter, not an answer
 *  3. drop a leading echo of [request], because some clones ignore `ATE0`
 *  4. **classify the error tokens, while the spaces are still there**
 *  5. only then strip spaces, leaving hex a decoder can read
 */
internal fun parseElmResponse(raw: String, request: String, roundTrip: Duration): ElmReply {
    var lines = raw.split('\r', '\n')
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() && !it.startsWith(SEARCHING) }

    val echoed = lines.firstOrNull()?.let { normalizeElm(it) == normalizeElm(request) } == true
    if (echoed) lines = lines.drop(1)

    val error = lines.firstNotNullOfOrNull(::elmErrorOf)
    val response = when (error) {
        null -> ElmResponse.Ok(lines.map(::normalizeElm), roundTrip)
        else -> ElmResponse.Err(error, raw)
    }
    return ElmReply(response, echoed)
}

/** Progress chatter while the adapter walks the protocols. Not an answer, not an error. */
private const val SEARCHING = "SEARCHING"

/** Case, spaces and line endings are noise to an ELM327 and must be noise to us. */
internal fun normalizeElm(text: String): String =
    text.filterNot { it == ' ' || it == '\r' || it == '\n' || it == '\t' }.uppercase()
