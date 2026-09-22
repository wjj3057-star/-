package com.imaxcam

import com.imaxcam.hdr.FrameLuminance
import com.imaxcam.hdr.Hdr10PlusBuilder
import com.imaxcam.hdr.Pq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Hdr10PlusTest {

    private fun stats(peak: Double, avg: Double) = FrameLuminance(
        maxR = peak, maxG = peak * 0.9, maxB = peak * 0.8,
        averageMaxRgb = avg,
        percentiles = DoubleArray(Hdr10PlusBuilder.PERCENTAGES.size) { i ->
            avg * (i + 1) / Hdr10PlusBuilder.PERCENTAGES.size
        },
        fractionBrightPixels = 0.02
    )

    @Test
    fun `payload carries the HDR10+ T_35 header`() {
        val payload = Hdr10PlusBuilder.build(stats(0.1, 0.01))
        assertEquals(0xB5, payload[0].toInt() and 0xFF)          // country code: USA
        assertEquals(0x00, payload[1].toInt() and 0xFF)          // provider code 0x003C
        assertEquals(0x3C, payload[2].toInt() and 0xFF)
        assertEquals(0x00, payload[3].toInt() and 0xFF)          // oriented code 0x0001
        assertEquals(0x01, payload[4].toInt() and 0xFF)
        assertEquals(4, payload[5].toInt() and 0xFF)             // application_identifier
        assertEquals(1, payload[6].toInt() and 0xFF)             // application_version
    }

    @Test
    fun `payload length matches the single-window bit budget`() {
        val payload = Hdr10PlusBuilder.build(stats(0.1, 0.01))
        // 56 header + 2 + 27 + 1 + 4*17 + 4 + 9*(7+17) + 10 + 1 + 1 + 12 + 12 + 4
        // + 9*10 + 1 = 505 bits, padded to 64 bytes.
        assertEquals(64, payload.size)
    }

    @Test
    fun `neutral metadata is valid and non-empty`() {
        val payload = Hdr10PlusBuilder.neutral()
        assertTrue(payload.isNotEmpty())
        assertEquals(0xB5, payload[0].toInt() and 0xFF)
    }

    @Test
    fun `tone curve is an identity when the frame already fits the target display`() {
        val curve = Hdr10PlusBuilder.toneCurve(
            frameMaxLinear = 500.0 / Pq.PEAK_NITS,
            avgLinear = 50.0 / Pq.PEAK_NITS,
            targetNits = 1000
        )
        assertEquals(4095, curve.kneeX)
        assertEquals(4095, curve.kneeY)
    }

    @Test
    fun `tone curve rolls off when the frame is brighter than the target display`() {
        val curve = Hdr10PlusBuilder.toneCurve(
            frameMaxLinear = 4000.0 / Pq.PEAK_NITS,
            avgLinear = 100.0 / Pq.PEAK_NITS,
            targetNits = 1000
        )
        assertTrue("knee must sit below the peak", curve.kneeX < 4095)
        assertEquals(9, curve.anchors.size)
        curve.anchors.forEach { assertTrue(it in 0..1023) }
        // Anchors describe a monotonically rising shoulder.
        curve.anchors.reduce { previous, next ->
            assertTrue("anchors must not decrease", next >= previous)
            next
        }
    }

    @Test
    fun `PQ round trips through both transfer directions`() {
        for (nits in listOf(0.1, 1.0, 100.0, 203.0, 1000.0, 4000.0, 10000.0)) {
            val linear = nits / Pq.PEAK_NITS
            val roundTrip = Pq.eotf(Pq.oetf(linear))
            assertTrue("$nits nits drifted to ${roundTrip * Pq.PEAK_NITS}",
                abs(roundTrip - linear) < 1e-4)
        }
    }

    @Test
    fun `8-bit PQ table matches the analytic transfer function`() {
        for (code in 0..255) {
            assertEquals(Pq.eotf(code / 255.0), Pq.LUT8[code], 1e-12)
        }
    }
}
