package com.bruni.carscan.core.obd.session

import com.bruni.carscan.core.obd.ElmErrorKind
import com.bruni.carscan.core.obd.ElmResponse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration

class PromptFramerTest {

    private fun PromptFramer.feed(text: String) = feed(text.encodeToByteArray())

    private fun parse(raw: String, request: String = "010C") =
        parseElmResponse(raw, request, Duration.ZERO)

    // --- framing ------------------------------------------------------------

    @Test
    fun `holds bytes until the prompt arrives`() {
        val framer = PromptFramer()

        framer.feed("7E8 04 41 0C 1A F8\r") shouldContainExactly emptyList()
        framer.feed("\r>") shouldContainExactly listOf("7E8 04 41 0C 1A F8\r\r")
    }

    /**
     * The assumption that kills this on real hardware is "one chunk is one line". A BLE
     * notification carries at most 20 bytes, and a loaded clone will happily dribble a
     * response out a byte at a time. It must still be exactly one response.
     */
    @Test
    fun `a response delivered in 37 one-byte chunks is still exactly one response`() {
        val wire = "SEARCHING...\r7E8 05 41 0C 1A F8 00\r\r>"
        wire.length shouldBe 37
        val framer = PromptFramer()

        val completed = wire.encodeToByteArray().flatMap { framer.feed(byteArrayOf(it)) }

        completed.size shouldBe 1
        parse(completed.single()).response.shouldBeInstanceOf<ElmResponse.Ok>()
            .lines shouldContainExactly listOf("7E805410C1AF800")
    }

    @Test
    fun `one chunk carrying two responses yields two`() {
        val framer = PromptFramer()

        val completed = framer.feed("OK\r>OK\r>")

        completed.size shouldBe 2
    }

    @Test
    fun `partial exposes what has arrived since the last prompt`() {
        val framer = PromptFramer()

        framer.feed("41 0C")
        framer.partial shouldBe "41 0C"

        framer.feed(">")
        framer.partial shouldBe ""
    }

    // --- line cleanup -------------------------------------------------------

    @Test
    fun `drops empty lines, uppercases, and strips spaces`() {
        val ok = parse("\r7e8 04 41 0c 1a f8\r\r").response.shouldBeInstanceOf<ElmResponse.Ok>()

        ok.lines shouldContainExactly listOf("7E804410C1AF8")
    }

    @Test
    fun `drops SEARCHING progress chatter`() {
        val ok = parse("SEARCHING...\r7E8 04 41 0C 1A F8\r").response
            .shouldBeInstanceOf<ElmResponse.Ok>()

        ok.lines shouldContainExactly listOf("7E804410C1AF8")
    }

    /** Plenty of clones ignore ATE0. The echo is the first line and is not data. */
    @Test
    fun `drops a leading echo of the request`() {
        val ok = parse("010C\r7E8 04 41 0C 1A F8\r").response.shouldBeInstanceOf<ElmResponse.Ok>()

        ok.lines shouldContainExactly listOf("7E804410C1AF8")
    }

    @Test
    fun `reports whether the command was echoed back`() {
        parse("010C\r7E8 04 41 0C 1A F8\r").echoed shouldBe true
        parse("7E8 04 41 0C 1A F8\r").echoed shouldBe false
    }

    @Test
    fun `matches an echo that differs only in spacing`() {
        val ok = parse("ATSH 7E0\rOK\r", request = "ATSH 7E0").response
            .shouldBeInstanceOf<ElmResponse.Ok>()

        ok.lines shouldContainExactly listOf("OK")
    }

    /** ATL1 clones terminate with CR LF. The extra LF must not become an empty line. */
    @Test
    fun `tolerates CR LF line endings`() {
        val ok = parse("7E8 04 41 0C 1A F8\r\n").response.shouldBeInstanceOf<ElmResponse.Ok>()

        ok.lines shouldContainExactly listOf("7E804410C1AF8")
    }

    // --- errors, matched before any hex parsing -----------------------------

    /**
     * The whole reason error matching happens before whitespace is stripped: `NO DATA`
     * with its space removed is `NODATA`, six perfectly good hex nibbles. Parse hex first
     * and the decoder does not fail — it produces a number, and the number looks fine.
     */
    @Test
    fun `NO DATA is an error and never a payload`() {
        val err = parse("NO DATA\r").response.shouldBeInstanceOf<ElmResponse.Err>()

        err.kind shouldBe ElmErrorKind.NO_DATA
        err.raw shouldBe "NO DATA\r"
    }

    @Test
    fun `every error token maps to its kind`() {
        val expected = mapOf(
            "NO DATA" to ElmErrorKind.NO_DATA,
            "CAN ERROR" to ElmErrorKind.CAN_ERROR,
            "BUS BUSY" to ElmErrorKind.BUS_BUSY,
            "BUS ERROR" to ElmErrorKind.BUS_ERROR,
            "BUS INIT: ERROR" to ElmErrorKind.BUS_INIT,
            "STOPPED" to ElmErrorKind.STOPPED,
            "UNABLE TO CONNECT" to ElmErrorKind.UNABLE_TO_CONNECT,
            "BUFFER FULL" to ElmErrorKind.BUFFER_FULL,
            "?" to ElmErrorKind.QUESTION_MARK,
            "LV RESET" to ElmErrorKind.LV_RESET,
            "ACT ALERT" to ElmErrorKind.ACT_ALERT,
        )

        expected.forEach { (line, kind) -> elmErrorOf(line) shouldBe kind }
    }

    @Test
    fun `a hex payload is not an error`() {
        elmErrorOf("7E8 04 41 0C 1A F8").shouldBeNull()
        elmErrorOf("OK").shouldBeNull()
    }

    /** An error can arrive after the echo, and it is still an error. */
    @Test
    fun `finds an error token on any line`() {
        val err = parse("010C\rNO DATA\r").response.shouldBeInstanceOf<ElmResponse.Err>()

        err.kind shouldBe ElmErrorKind.NO_DATA
    }
}
