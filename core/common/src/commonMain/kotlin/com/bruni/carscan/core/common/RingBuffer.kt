package com.bruni.carscan.core.common

/**
 * Fixed-capacity circular buffer of primitives, pre-allocated once.
 *
 * The live chart writes into this at 10-20 Hz on every sample, so it must not
 * allocate per sample. When full, the oldest value is overwritten.
 */
class FloatRingBuffer(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val values = FloatArray(capacity)
    private var head = 0

    /** Number of values currently held, never greater than [capacity]. */
    var size: Int = 0
        private set

    fun push(value: Float) {
        values[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** [index] 0 is the oldest retained value, [size] - 1 the newest. */
    operator fun get(index: Int): Float {
        require(index in 0 until size) { "index $index out of bounds for size $size" }
        val start = if (size < capacity) 0 else head
        return values[(start + index) % capacity]
    }

    fun clear() {
        head = 0
        size = 0
    }
}
