package com.imaxcam.hdr

import kotlin.math.pow

/**
 * SMPTE ST 2084 (PQ) transfer function.
 *
 * Normalised code values in [0,1] map to display light in [0, 10000] cd/m2.
 */
object Pq {
    private const val M1 = 2610.0 / 16384.0
    private const val M2 = 2523.0 / 4096.0 * 128.0
    private const val C1 = 3424.0 / 4096.0
    private const val C2 = 2413.0 / 4096.0 * 32.0
    private const val C3 = 2392.0 / 4096.0 * 32.0

    const val PEAK_NITS = 10000.0

    /** PQ code value -> normalised linear light (1.0 == 10000 nits). */
    fun eotf(code: Double): Double {
        if (code <= 0.0) return 0.0
        val c = code.coerceAtMost(1.0)
        val p = c.pow(1.0 / M2)
        val num = (p - C1).coerceAtLeast(0.0)
        val den = C2 - C3 * p
        if (den <= 0.0) return 1.0
        return (num / den).pow(1.0 / M1)
    }

    /** Normalised linear light -> PQ code value. */
    fun oetf(linear: Double): Double {
        if (linear <= 0.0) return 0.0
        val l = linear.coerceAtMost(1.0)
        val p = l.pow(M1)
        return ((C1 + C2 * p) / (1.0 + C3 * p)).pow(M2)
    }

    /** Convenience: PQ code value straight to cd/m2. */
    fun nits(code: Double): Double = eotf(code) * PEAK_NITS

    /** Pre-computed 8-bit PQ decode table, used on the metadata hot path. */
    val LUT8: DoubleArray = DoubleArray(256) { eotf(it / 255.0) }
}
