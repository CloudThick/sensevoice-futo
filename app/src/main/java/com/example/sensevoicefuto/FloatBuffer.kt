package com.example.sensevoicefuto

class FloatBuffer(initialCapacity: Int = 16000 * 10) {
    private var data = FloatArray(initialCapacity)
    private var size = 0

    fun add(samples: FloatArray, count: Int = samples.size) {
        ensure(size + count)
        samples.copyInto(data, size, 0, count)
        size += count
    }

    fun toArray(): FloatArray = data.copyOf(size)

    private fun ensure(required: Int) {
        if (required <= data.size) return
        var n = data.size.coerceAtLeast(1)
        while (n < required) n *= 2
        data = data.copyOf(n)
    }
}
