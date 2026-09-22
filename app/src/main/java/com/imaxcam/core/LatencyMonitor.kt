package com.imaxcam.core

import kotlin.math.roundToLong

/**
 * Rolling latency statistics for the capture path.
 *
 * The number that matters is the gap between the sensor timestamp stamped on a frame and
 * the moment the GPU hands that frame to the encoder. Both come from the same monotonic
 * clock, so the difference is the real in-app delay — camera queueing plus the render
 * pass — with none of the guesswork a wall-clock estimate would carry.
 */
class LatencyMonitor(private val window: Int = 120) {

    private val samples = LongArray(window)
    private var count = 0
    private var cursor = 0

    @Volatile
    var lastNanos = 0L
        private set

    @Synchronized
    fun record(nanos: Long) {
        if (nanos < 0) return
        lastNanos = nanos
        samples[cursor] = nanos
        cursor = (cursor + 1) % window
        if (count < window) count++
    }

    @Synchronized
    fun snapshot(): Snapshot {
        if (count == 0) return Snapshot(0.0, 0.0, 0.0, 0)
        val active = samples.copyOf(count)
        active.sort()
        val mean = active.sum().toDouble() / count
        return Snapshot(
            averageMs = mean / 1_000_000.0,
            p50Ms = active[count / 2] / 1_000_000.0,
            p95Ms = active[((count - 1) * 95 / 100)] / 1_000_000.0,
            samples = count
        )
    }

    @Synchronized
    fun reset() {
        count = 0
        cursor = 0
        lastNanos = 0
    }

    data class Snapshot(
        val averageMs: Double,
        val p50Ms: Double,
        val p95Ms: Double,
        val samples: Int
    ) {
        fun format(): String =
            if (samples == 0) "--" else "${p50Ms.roundTo(1)}ms p50 / ${p95Ms.roundTo(1)}ms p95"

        private fun Double.roundTo(decimals: Int): String {
            val factor = Math.pow(10.0, decimals.toDouble())
            return ((this * factor).roundToLong() / factor).toString()
        }
    }
}

/** Frame-rate counter for the render thread. */
class FrameRateMeter {
    private var lastNanos = 0L
    private var frames = 0
    private var windowStart = 0L

    @Volatile
    var fps = 0.0
        private set

    fun tick(nanos: Long = System.nanoTime()) {
        if (windowStart == 0L) {
            windowStart = nanos
            lastNanos = nanos
            return
        }
        frames++
        val elapsed = nanos - windowStart
        if (elapsed >= 500_000_000L) {
            fps = frames * 1_000_000_000.0 / elapsed
            frames = 0
            windowStart = nanos
        }
        lastNanos = nanos
    }

    fun reset() {
        lastNanos = 0
        frames = 0
        windowStart = 0
        fps = 0.0
    }
}
