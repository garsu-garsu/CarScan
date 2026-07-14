package com.bruni.carscan.core.designsystem.chart

/**
 * A fixed-size window of the most recent [capacity] samples, oldest first.
 *
 * The live strip shows 30 s of 20 Hz data. A growing `List` would allocate for every sample
 * for as long as the app is connected, and the collections would surface as GC pauses in the
 * one place the user is watching a line move. This allocates its array once and never again;
 * pushing past the end overwrites the oldest sample in place.
 *
 * Not thread-safe: push from the same dispatcher the chart draws on.
 */
class FloatRingBuffer(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val data = FloatArray(capacity)

    /** Index of the oldest sample in [data]. */
    private var start = 0

    /** How many slots are filled — reaches [capacity] and stays there. */
    var size: Int = 0
        private set

    fun push(value: Float) {
        data[(start + size) % capacity] = value
        if (size < capacity) {
            size++
        } else {
            // Full: the write above landed on the oldest sample, so the window slides forward.
            start = (start + 1) % capacity
        }
    }

    /** The sample at [index], counting from the oldest still in the window. */
    operator fun get(index: Int): Float {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("index $index out of bounds for size $size")
        }
        return data[(start + index) % capacity]
    }

    fun clear() {
        start = 0
        size = 0
    }
}
