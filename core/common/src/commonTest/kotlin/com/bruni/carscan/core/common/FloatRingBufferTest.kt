package com.bruni.carscan.core.common

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FloatRingBufferTest {

    @Test
    fun `grows up to capacity`() {
        val buffer = FloatRingBuffer(3)
        buffer.size shouldBe 0

        buffer.push(1f)
        buffer.push(2f)

        buffer.size shouldBe 2
        buffer[0] shouldBe 1f
        buffer[1] shouldBe 2f
    }

    @Test
    fun `overwrites the oldest value once full`() {
        val buffer = FloatRingBuffer(3)
        listOf(1f, 2f, 3f, 4f, 5f).forEach(buffer::push)

        buffer.size shouldBe 3
        // 1 and 2 have been overwritten; 3 is now the oldest.
        listOf(buffer[0], buffer[1], buffer[2]) shouldBe listOf(3f, 4f, 5f)
    }

    @Test
    fun `clear drops everything`() {
        val buffer = FloatRingBuffer(2)
        buffer.push(1f)
        buffer.clear()

        buffer.size shouldBe 0
    }

    @Test
    fun `reading past the end fails`() {
        val buffer = FloatRingBuffer(2)
        buffer.push(1f)

        assertFailsWith<IllegalArgumentException> { buffer[1] }
    }
}
