package com.imaxcam

import com.imaxcam.record.AudioChannelPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChannelPolicyTest {
    @Test
    fun `picks highest independently advertised input channels first`() {
        val actual = AudioChannelPolicy.candidates(
            spatialAuto = true,
            advertisedInputCounts = listOf(2, 6, 12, 4),
            maxEncoderChannels = 12
        )
        assertEquals(listOf(12, 6, 4, 2, 1), actual)
    }

    @Test
    fun `never fabricates twelve channels on stereo-only phone`() {
        val actual = AudioChannelPolicy.candidates(true, listOf(1, 2), 12)
        assertEquals(listOf(2, 1), actual)
        assertFalse(actual.contains(12))
    }

    @Test
    fun `encoder limit blocks unsupported layouts`() {
        assertEquals(
            listOf(4, 2, 1),
            AudioChannelPolicy.candidates(true, listOf(4, 6, 12), 4)
        )
    }

    @Test
    fun `disabling auto forces conventional AAC stereo`() {
        assertEquals(
            listOf(2, 1),
            AudioChannelPolicy.candidates(false, listOf(4, 6, 12), 12)
        )
    }

    @Test
    fun `index masks preserve individual input indices`() {
        assertEquals(0xF, AudioChannelPolicy.indexMask(4))
        assertEquals(0xFFF, AudioChannelPolicy.indexMask(12))
        assertEquals(1_536_000, AudioChannelPolicy.bitrate(12))
        assertTrue(AudioChannelPolicy.label(12).contains("discrete"))
        assertFalse(AudioChannelPolicy.label(12).contains("Atmos"))
    }
}
