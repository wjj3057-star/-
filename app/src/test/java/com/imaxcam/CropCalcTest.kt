package com.imaxcam

import com.imaxcam.core.CropCalc
import com.imaxcam.core.ImaxFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CropCalcTest {

    @Test
    fun `1_43 crop keeps full UHD height and trims the sides`() {
        val crop = CropCalc.fit(3840, 2160, ImaxFormat.IMAX_1_43.ratio)
        assertEquals(2160, crop.outH)
        assertEquals(3088, crop.outW)
        assertTrue(abs(crop.achievedRatio - 1.43) < 0.001)
    }

    @Test
    fun `1_90 crop keeps full UHD width and trims top and bottom`() {
        val crop = CropCalc.fit(3840, 2160, ImaxFormat.IMAX_1_90.ratio)
        assertEquals(3840, crop.outW)
        assertTrue("height must be even", crop.outH % 2 == 0)
        assertTrue(abs(crop.achievedRatio - 1.90) < 0.002)
    }

    @Test
    fun `crop never exceeds the source frame`() {
        for (format in ImaxFormat.entries) {
            val crop = CropCalc.fit(3840, 2160, format.ratio)
            assertTrue("$format width", crop.outW <= 3840)
            assertTrue("$format height", crop.outH <= 2160)
            assertTrue("$format even width", crop.outW % 2 == 0)
            assertTrue("$format even height", crop.outH % 2 == 0)
        }
    }

    @Test
    fun `alignment of 16 still lands close to the requested ratio`() {
        for (format in ImaxFormat.entries) {
            val crop = CropCalc.fit(3840, 2160, format.ratio, align = 16)
            assertEquals("$format width", 0, crop.outW % 16)
            assertEquals("$format height", 0, crop.outH % 16)
            assertTrue(
                "$format ratio drift ${crop.achievedRatio}",
                abs(crop.achievedRatio - format.ratio) / format.ratio < 0.01
            )
        }
    }

    @Test
    fun `bitrate scales with pixel count and stays inside sane bounds`() {
        val uhd = CropCalc.suggestedBitrate(3840, 2160, 30)
        val imax = CropCalc.suggestedBitrate(3088, 2160, 30)
        assertTrue(imax < uhd)
        assertTrue(uhd in 8_000_000..220_000_000)
        assertTrue(CropCalc.suggestedBitrate(7680, 4320, 60) <= 220_000_000)
    }
}
