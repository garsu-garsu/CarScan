package com.bruni.carscan.core.transport.ble

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class BleWriteChunkingTest {

    private fun bytes(n: Int) = ByteArray(n) { it.toByte() }

    @Test
    fun `a short command is a single write`() {
        chunkForWrite(bytes(5)) shouldHaveSize 1
    }

    @Test
    fun `19 bytes fit in one chunk`() {
        chunkForWrite(bytes(19)).map { it.size } shouldBe listOf(19)
    }

    @Test
    fun `20 bytes exactly fill one chunk and do not spill`() {
        chunkForWrite(bytes(20)).map { it.size } shouldBe listOf(20)
    }

    @Test
    fun `21 bytes spill a single byte into a second chunk`() {
        chunkForWrite(bytes(21)).map { it.size } shouldBe listOf(20, 1)
    }

    @Test
    fun `40 bytes split evenly`() {
        chunkForWrite(bytes(40)).map { it.size } shouldBe listOf(20, 20)
    }

    @Test
    fun `a 60-byte command becomes three writes`() {
        chunkForWrite(bytes(60)).map { it.size } shouldBe listOf(20, 20, 20)
    }

    @Test
    fun `chunks concatenate back to the original bytes`() {
        val original = bytes(57)

        val rejoined = chunkForWrite(original).fold(ByteArray(0)) { acc, chunk -> acc + chunk }

        rejoined.toList() shouldBe original.toList()
    }

    @Test
    fun `an empty payload produces no writes at all`() {
        chunkForWrite(ByteArray(0)) shouldBe emptyList()
    }

    /** A negotiated MTU raises the ceiling; the shape of the split must follow it. */
    @Test
    fun `a negotiated maximum widens the chunks`() {
        chunkForWrite(bytes(60), maxChunkSize = 244).map { it.size } shouldBe listOf(60)
    }

    @Test
    fun `a nonsensical maximum falls back to the 20-byte ATT floor`() {
        chunkForWrite(bytes(25), maxChunkSize = 0).map { it.size } shouldBe listOf(20, 5)
    }
}
