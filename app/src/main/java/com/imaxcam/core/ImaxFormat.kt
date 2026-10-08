package com.imaxcam.core

import android.util.Size
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * IMAX / cinema aspect ratios the recorder can produce.
 *
 * 1.43:1 is the IMAX 15/70 "full frame" ratio (GT laser houses),
 * 1.90:1 is the IMAX Digital / Laser XT ratio.
 */
enum class ImaxFormat(
    val label: String,
    val ratio: Double,
    val note: String
) {
    IMAX_1_43("1.43:1", 1.43, "IMAX 15/70 Full Frame"),
    IMAX_1_90("1.90:1", 1.90, "IMAX Digital / Laser"),
    SCOPE_2_39("2.39:1", 2.39, "Anamorphic Scope"),
    NATIVE_16_9("16:9", 16.0 / 9.0, "Native UHD");

    companion object {
        val DEFAULT = IMAX_1_43
    }
}

/** The selectable camera capture resolution; actual size depends on hardware support. */
enum class VideoQuality(val label: String, val width: Int, val height: Int) {
    HD("720p", 1280, 720),
    FULL_HD("1080p", 1920, 1080),
    UHD("4K UHD", 3840, 2160);

    companion object {
        val DEFAULT = FULL_HD
    }
}

/**
 * A centred crop of [srcW] x [srcH] that hits a target aspect ratio.
 *
 * The crop is performed on the GPU while blitting the camera frame into the encoder
 * surface, so it costs one texture fetch per output pixel and never a memory copy.
 */
data class CropSpec(
    val srcW: Int,
    val srcH: Int,
    val outW: Int,
    val outH: Int
) {
    val achievedRatio: Double get() = outW.toDouble() / outH.toDouble()

    /** Horizontal fraction of the source frame that survives the crop. */
    val scaleX: Float get() = outW.toFloat() / srcW.toFloat()

    /** Vertical fraction of the source frame that survives the crop. */
    val scaleY: Float get() = outH.toFloat() / srcH.toFloat()

    val pixels: Long get() = outW.toLong() * outH.toLong()

    override fun toString(): String =
        "${outW}x$outH (${"%.4f".format(achievedRatio)}:1) from ${srcW}x$srcH"
}

object CropCalc {

    /**
     * Largest centred rectangle inside [srcW] x [srcH] whose aspect ratio is as close as
     * possible to [ratio], with both edges a multiple of [align].
     *
     * Video encoders want even dimensions; some hardware encoders are materially faster
     * with multiples of 16, hence the configurable alignment.
     */
    fun fit(srcW: Int, srcH: Int, ratio: Double, align: Int = 2): CropSpec {
        require(srcW > 0 && srcH > 0) { "source size must be positive" }
        require(align >= 1) { "alignment must be >= 1" }

        val srcRatio = srcW.toDouble() / srcH.toDouble()
        var outW: Int
        var outH: Int

        if (srcRatio > ratio) {
            // Source is wider than the target: keep full height, crop the sides.
            outH = floorTo(srcH, align)
            outW = bestAligned(outH * ratio, align, srcW)
        } else {
            // Source is taller than the target: keep full width, crop top and bottom.
            outW = floorTo(srcW, align)
            outH = bestAligned(outW / ratio, align, srcH)
        }

        outW = outW.coerceIn(align, floorTo(srcW, align))
        outH = outH.coerceIn(align, floorTo(srcH, align))
        return CropSpec(srcW, srcH, outW, outH)
    }

    fun fit(src: Size, format: ImaxFormat, align: Int = 2): CropSpec =
        fit(src.width, src.height, format.ratio, align)

    /**
     * Picks the aligned value nearest [exact] without exceeding [max], so the achieved
     * ratio lands as close to the request as the alignment allows.
     */
    private fun bestAligned(exact: Double, align: Int, max: Int): Int {
        val lower = floorTo(exact.toInt(), align)
        val upper = lower + align
        val cappedUpper = if (upper <= floorTo(max, align)) upper else lower
        return if (abs(exact - lower) <= abs(exact - cappedUpper)) lower else cappedUpper
    }

    private fun floorTo(value: Int, align: Int): Int = (value / align) * align

    /**
     * Chooses the camera output size to crop from.
     *
     * Preference order:
     *  1. sizes that are at least UHD and can satisfy the target ratio without upscaling,
     *  2. among those, the one that wastes the fewest pixels for this ratio,
     *  3. failing that, simply the largest available size.
     */
    fun pickSource(candidates: List<Size>, format: ImaxFormat): Size? {
        if (candidates.isEmpty()) return null
        val uhd = candidates.filter { it.width >= UHD_WIDTH && it.height >= UHD_HEIGHT }
        val pool = uhd.ifEmpty { candidates }
        return pool.maxWithOrNull(
            compareBy<Size> { fit(it, format).pixels }
                .thenBy { it.width.toLong() * it.height.toLong() }
        )
    }

    /**
     * Finds the closest native 16:9 camera output for the selected quality. Never
     * fabricates a size by scaling a larger stream and never requests an unavailable
     * SurfaceTexture resolution. The exact negotiated size is shown on the HUD.
     */
    fun pickSourceForQuality(candidates: List<Size>, quality: VideoQuality): Size? {
        if (candidates.isEmpty()) return null
        val widescreen = candidates.filter {
            val longEdge = maxOf(it.width, it.height).toDouble()
            val shortEdge = minOf(it.width, it.height).toDouble()
            shortEdge > 0 && abs(longEdge / shortEdge - 16.0 / 9.0) < 0.08
        }
        val pool = widescreen.ifEmpty { candidates }
        return pool.minWithOrNull(compareBy<Size> {
            val longEdge = maxOf(it.width, it.height)
            val shortEdge = minOf(it.width, it.height)
            abs(longEdge - quality.width).toLong() * 2 +
                abs(shortEdge - quality.height).toLong()
        }.thenBy { abs(it.width.toLong() * it.height - quality.width.toLong() * quality.height) })
    }

    /**
     * Bitrate target for a 10-bit HEVC stream at [w] x [h] and [fps].
     *
     * Derived from bits-per-pixel-per-frame rather than a fixed table so that the odd
     * IMAX resolutions (e.g. 3088x2160) get a sane allocation instead of a 16:9 number.
     */
    fun suggestedBitrate(w: Int, h: Int, fps: Int, quality: Double = 1.0): Int {
        // ~0.2 bits per pixel per frame sits alongside what phones ship for 10-bit
        // HEVC (UHD30 lands near 50 Mbps) and holds up on the handheld, noisy footage
        // a phone sensor actually produces.
        val bppf = 0.20 * quality
        val bits = w.toDouble() * h.toDouble() * fps.toDouble() * bppf
        return bits.roundToInt().coerceIn(8_000_000, 220_000_000)
    }

    const val UHD_WIDTH = 3840
    const val UHD_HEIGHT = 2160
}
