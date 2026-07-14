package com.bruni.carscan.core.designsystem.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The live strip is 30 s of 20 Hz samples. A growing List would allocate forever and the GC
 * would show up as jank in the one place the user is staring at. This is the fixed-size array
 * that replaces it, so its wrap-around is worth more tests than the drawing code.
 */
class FloatRingBufferTest {

    @Test
    fun startsEmpty() {
        val buffer = FloatRingBuffer(capacity = 4)
        assertEquals(0, buffer.size)
        assertEquals(4, buffer.capacity)
    }

    @Test
    fun readsBackAPartialFillInOrder() {
        val buffer = FloatRingBuffer(capacity = 4)
        buffer.push(10f)
        buffer.push(20f)

        assertEquals(2, buffer.size)
        assertEquals(10f, buffer[0])
        assertEquals(20f, buffer[1])
    }

    @Test
    fun readsBackAnExactFillOldestFirst() {
        val buffer = FloatRingBuffer(capacity = 3)
        buffer.push(1f)
        buffer.push(2f)
        buffer.push(3f)

        assertEquals(3, buffer.size)
        assertEquals(listOf(1f, 2f, 3f), (0 until buffer.size).map { buffer[it] })
    }

    @Test
    fun oneOverCapacityEvictsTheOldest() {
        val buffer = FloatRingBuffer(capacity = 3)
        listOf(1f, 2f, 3f, 4f).forEach(buffer::push)

        assertEquals(3, buffer.size, "size must never exceed capacity")
        assertEquals(listOf(2f, 3f, 4f), (0 until buffer.size).map { buffer[it] })
    }

    @Test
    fun the601stPushIntoA600SlotBufferEvictsTheOldest() {
        val buffer = FloatRingBuffer(capacity = 600)
        for (i in 1..600) buffer.push(i.toFloat())
        assertEquals(1f, buffer[0])

        buffer.push(601f)

        assertEquals(600, buffer.size)
        assertEquals(2f, buffer[0], "oldest sample was not evicted")
        assertEquals(601f, buffer[buffer.size - 1])
    }

    @Test
    fun wrapAroundPreservesOrderOverManyRevolutions() {
        val buffer = FloatRingBuffer(capacity = 600)
        // 1000 samples through a 600-slot buffer: 401..1000 must survive, in order.
        for (i in 1..1000) buffer.push(i.toFloat())

        assertEquals(600, buffer.size)
        val read = (0 until buffer.size).map { buffer[it] }
        assertEquals((401..1000).map { it.toFloat() }, read)
    }

    @Test
    fun indexingOutsideTheFilledRangeThrows() {
        val buffer = FloatRingBuffer(capacity = 4)
        buffer.push(1f)

        assertFailsWith<IndexOutOfBoundsException> { buffer[1] }
        assertFailsWith<IndexOutOfBoundsException> { buffer[-1] }
    }

    @Test
    fun clearEmptiesTheBufferAndResetsTheWrapPoint() {
        val buffer = FloatRingBuffer(capacity = 3)
        listOf(1f, 2f, 3f, 4f, 5f).forEach(buffer::push)

        buffer.clear()
        assertEquals(0, buffer.size)

        buffer.push(9f)
        assertEquals(1, buffer.size)
        assertEquals(9f, buffer[0], "a cleared buffer must not read stale samples back")
    }

    @Test
    fun aCapacityOfZeroOrLessIsRejected() {
        assertFailsWith<IllegalArgumentException> { FloatRingBuffer(capacity = 0) }
        assertFailsWith<IllegalArgumentException> { FloatRingBuffer(capacity = -1) }
    }

    @Test
    fun pushingNeverAllocatesBeyondTheFixedArray() {
        // Not a memory assertion — a behavioural one: a million pushes still hold exactly
        // `capacity` samples and still read back in order.
        val buffer = FloatRingBuffer(capacity = 8)
        for (i in 1..1_000_000) buffer.push(i.toFloat())

        assertEquals(8, buffer.size)
        assertEquals((999_993..1_000_000).map { it.toFloat() }, (0 until 8).map { buffer[it] })
        assertTrue(buffer.size <= buffer.capacity)
    }
}
