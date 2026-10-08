package com.imaxcam.record

/**
 * Selects independently captured microphone channels, never synthesised speaker feeds.
 *
 * Having N PCM inputs is NOT Dolby Atmos or IMAX theatrical N-channel audio:
 * no object metadata, calibrated speaker mapping, or proprietary bitstream is created.
 */
object AudioChannelPolicy {
    const val MAX_CHANNELS = 12

    /** Only choose >2 channels if an input device explicitly advertises them. */
    fun candidates(
        spatialAuto: Boolean,
        advertisedInputCounts: Collection<Int>,
        maxEncoderChannels: Int
    ): List<Int> {
        val multi = if (spatialAuto) {
            advertisedInputCounts
                .filter { it in 3..MAX_CHANNELS && it <= maxEncoderChannels }
                .distinct()
                .sortedDescending()
        } else emptyList()
        return multi + listOf(2, 1).filter { it <= maxEncoderChannels }
    }

    /** Channel-index input masks avoid inventing spatial microphone positions. */
    fun indexMask(channels: Int): Int {
        require(channels in 1..MAX_CHANNELS)
        return (1 shl channels) - 1
    }

    fun bitrate(channels: Int): Int {
        require(channels in 1..MAX_CHANNELS)
        return (channels * 128_000).coerceIn(128_000, 1_536_000)
    }

    fun label(channels: Int): String = when (channels) {
        1 -> "AAC mono · 48 kHz"
        2 -> "AAC stereo · 48 kHz"
        else -> "AAC ${channels}ch discrete · 48 kHz"
    }
}
