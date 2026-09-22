package com.imaxcam.hdr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Per-frame statistics harvested from the GPU histogram pass.
 *
 * All luminance values are normalised linear light where 1.0 == 10000 cd/m2,
 * matching the PQ reference the camera stream is encoded in.
 */
data class FrameLuminance(
    /** Per-channel maxima over the frame, normalised linear. */
    val maxR: Double,
    val maxG: Double,
    val maxB: Double,
    /** Mean of per-pixel max(R,G,B), normalised linear. */
    val averageMaxRgb: Double,
    /**
     * Distribution of per-pixel max(R,G,B) at the nine percentages HDR10+ carries,
     * normalised linear, ordered to match [Hdr10PlusBuilder.PERCENTAGES].
     */
    val percentiles: DoubleArray,
    /** Fraction of pixels above 90% of the frame peak, in [0,1]. */
    val fractionBrightPixels: Double
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameLuminance) return false
        return maxR == other.maxR && maxG == other.maxG && maxB == other.maxB &&
            averageMaxRgb == other.averageMaxRgb &&
            fractionBrightPixels == other.fractionBrightPixels &&
            percentiles.contentEquals(other.percentiles)
    }

    override fun hashCode(): Int {
        var r = maxR.hashCode()
        r = 31 * r + maxG.hashCode()
        r = 31 * r + maxB.hashCode()
        r = 31 * r + averageMaxRgb.hashCode()
        r = 31 * r + fractionBrightPixels.hashCode()
        r = 31 * r + percentiles.contentHashCode()
        return r
    }
}

/**
 * Serialises SMPTE ST 2094-40 dynamic metadata into the
 * `user_data_registered_itu_t_t35()` payload that `MediaCodec` expects for
 * [android.media.MediaFormat.KEY_HDR10_PLUS_INFO].
 *
 * Structure (HDR10+ application 4, version 1, single window):
 *
 *   itu_t_t35_country_code                  8  = 0xB5  (USA)
 *   itu_t_t35_terminal_provider_code       16  = 0x003C (Samsung / HDR10+ LLC)
 *   itu_t_t35_terminal_provider_oriented_code 16 = 0x0001
 *   application_identifier                  8  = 4
 *   application_version                     8  = 1
 *   num_windows                             2  = 1
 *   targeted_system_display_maximum_luminance 27
 *   targeted_system_display_actual_peak_luminance_flag 1 = 0
 *   per window:
 *     maxscl[0..2]                         17 each
 *     average_maxrgb                       17
 *     num_distribution_maxrgb_percentiles   4  = 9
 *     per percentile: percentage 7, percentile 17
 *     fraction_bright_pixels               10
 *   mastering_display_actual_peak_luminance_flag 1 = 0
 *   per window:
 *     tone_mapping_flag                     1  = 1
 *       knee_point_x                       12
 *       knee_point_y                       12
 *       num_bezier_curve_anchors            4
 *       bezier_curve_anchors[i]            10 each
 *   color_saturation_mapping_flag           1  = 0
 *
 * The 17-bit luminance fields carry normalised linear light in units of 1/100000,
 * i.e. `code = nits * 10`.
 */
object Hdr10PlusBuilder {

    /** The nine percentages HDR10+ distributes, in the order they are written. */
    val PERCENTAGES = intArrayOf(1, 5, 10, 25, 50, 75, 90, 95, 99)

    private const val COUNTRY_CODE = 0xB5
    private const val TERMINAL_PROVIDER_CODE = 0x003C
    private const val TERMINAL_PROVIDER_ORIENTED_CODE = 0x0001
    private const val APPLICATION_IDENTIFIER = 4
    private const val APPLICATION_VERSION = 1

    private const val LUMINANCE_UNITS = 100_000.0 // 1.0 normalised == 100000 units
    private const val LUMINANCE_MAX = (1 shl 17) - 1
    private const val KNEE_MAX = (1 shl 12) - 1
    private const val ANCHOR_MAX = (1 shl 10) - 1
    private const val FRACTION_MAX = (1 shl 10) - 1

    /** Number of Bezier anchors in the generated tone-mapping curve. */
    private const val ANCHOR_COUNT = 9

    /**
     * Builds the T.35 payload for one frame.
     *
     * @param targetNits the reference display the curve is authored for. 1000 nits is the
     *   usual consumer target and what most HDR10+ TVs and phones expect.
     */
    fun build(stats: FrameLuminance, targetNits: Int = 1000): ByteArray {
        val w = BitWriter(64)

        w.writeBits(COUNTRY_CODE, 8)
        w.writeBits(TERMINAL_PROVIDER_CODE, 16)
        w.writeBits(TERMINAL_PROVIDER_ORIENTED_CODE, 16)
        w.writeBits(APPLICATION_IDENTIFIER, 8)
        w.writeBits(APPLICATION_VERSION, 8)

        // Single processing window covering the whole frame; per-window geometry is only
        // written when num_windows > 1, so nothing else is needed here.
        w.writeBits(1, 2)
        w.writeBits(targetNits.coerceIn(1, (1 shl 27) - 1), 27)
        w.writeFlag(false) // targeted_system_display_actual_peak_luminance_flag

        w.writeBits(luminanceCode(stats.maxR), 17)
        w.writeBits(luminanceCode(stats.maxG), 17)
        w.writeBits(luminanceCode(stats.maxB), 17)
        w.writeBits(luminanceCode(stats.averageMaxRgb), 17)

        val percentileCount = min(PERCENTAGES.size, stats.percentiles.size)
        w.writeBits(percentileCount, 4)
        for (i in 0 until percentileCount) {
            w.writeBits(PERCENTAGES[i], 7)
            w.writeBits(luminanceCode(stats.percentiles[i]), 17)
        }

        w.writeBits(
            (stats.fractionBrightPixels.coerceIn(0.0, 1.0) * FRACTION_MAX).roundToInt(),
            10
        )

        w.writeFlag(false) // mastering_display_actual_peak_luminance_flag

        // Tone mapping curve for this window.
        val frameMax = max(stats.maxR, max(stats.maxG, stats.maxB))
        val curve = toneCurve(frameMax, stats.averageMaxRgb, targetNits)
        w.writeFlag(true) // tone_mapping_flag
        w.writeBits(curve.kneeX, 12)
        w.writeBits(curve.kneeY, 12)
        w.writeBits(curve.anchors.size, 4)
        curve.anchors.forEach { w.writeBits(it, 10) }

        w.writeFlag(false) // color_saturation_mapping_flag

        return w.toByteArray()
    }

    private fun luminanceCode(normalisedLinear: Double): Int =
        (normalisedLinear.coerceIn(0.0, 1.0) * LUMINANCE_UNITS)
            .roundToInt()
            .coerceIn(0, LUMINANCE_MAX)

    internal data class ToneCurve(val kneeX: Int, val kneeY: Int, val anchors: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is ToneCurve && kneeX == other.kneeX && kneeY == other.kneeY &&
                anchors.contentEquals(other.anchors)

        override fun hashCode(): Int = (kneeX * 31 + kneeY) * 31 + anchors.contentHashCode()
    }

    /**
     * Derives a Bezier tone-mapping curve from the frame statistics.
     *
     * Below the knee the curve is linear, so the mid-tones that carry most of the picture
     * are reproduced exactly; above it a rational roll-off compresses the highlights into
     * the target display's headroom. The knee tracks the frame's average brightness so
     * that dark scenes keep their highlight detail and bright scenes roll off sooner.
     */
    internal fun toneCurve(frameMaxLinear: Double, avgLinear: Double, targetNits: Int): ToneCurve {
        val srcPeak = max(frameMaxLinear * Pq.PEAK_NITS, 1.0)
        val dstPeak = targetNits.toDouble()

        if (srcPeak <= dstPeak * 1.02) {
            // Everything already fits: identity curve, no highlight compression.
            val anchors = IntArray(ANCHOR_COUNT) { i ->
                val t = (i + 1).toDouble() / (ANCHOR_COUNT + 1)
                (t * ANCHOR_MAX).roundToInt().coerceIn(0, ANCHOR_MAX)
            }
            return ToneCurve(KNEE_MAX, KNEE_MAX, anchors)
        }

        // Normalise source light to the target display, in PQ space so the knee lands
        // where it is perceptually meaningful.
        val srcPeakPq = Pq.oetf(frameMaxLinear)
        val dstPeakPq = Pq.oetf(dstPeak / Pq.PEAK_NITS)
        val avgPq = Pq.oetf(avgLinear.coerceAtLeast(1e-6))

        // Put the knee above the average so typical content stays untouched, but never so
        // high that there is no room left to roll off.
        val kneeXn = (avgPq * 1.35).coerceIn(0.35, 0.90)
        val kneeYn = (kneeXn * dstPeakPq / srcPeakPq).coerceIn(0.05, 0.98)

        val anchors = IntArray(ANCHOR_COUNT) { i ->
            val t = (i + 1).toDouble() / (ANCHOR_COUNT + 1)
            // Map the normalised segment [knee, peak] with a Reinhard-style shoulder.
            val x = kneeXn + t * (1.0 - kneeXn)
            val headroom = (srcPeakPq - kneeXn).coerceAtLeast(1e-6)
            val over = (x - kneeXn) / headroom
            val compressed = over / (1.0 + over)
            val y = kneeYn + compressed * (dstPeakPq - kneeYn).coerceAtLeast(0.0)
            // Anchors are expressed relative to the [knee, 1.0] span of the curve.
            val rel = ((y - kneeYn) / (1.0 - kneeYn).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            (rel * ANCHOR_MAX).roundToInt().coerceIn(0, ANCHOR_MAX)
        }

        return ToneCurve(
            kneeX = (kneeXn * KNEE_MAX).roundToInt().coerceIn(0, KNEE_MAX),
            kneeY = (kneeYn * KNEE_MAX).roundToInt().coerceIn(0, KNEE_MAX),
            anchors = anchors
        )
    }

    /**
     * Neutral metadata used to seed the encoder before the first histogram read-back
     * lands, so that even the very first GOP carries a valid HDR10+ message.
     */
    fun neutral(targetNits: Int = 1000): ByteArray = build(
        FrameLuminance(
            maxR = 0.1, maxG = 0.1, maxB = 0.1,
            averageMaxRgb = 0.01,
            percentiles = DoubleArray(PERCENTAGES.size) { i ->
                0.001 * (i + 1)
            },
            fractionBrightPixels = 0.0
        ),
        targetNits
    )
}
