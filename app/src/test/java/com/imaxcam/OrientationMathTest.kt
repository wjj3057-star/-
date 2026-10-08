package com.imaxcam

import com.imaxcam.core.OrientationMath
import com.imaxcam.core.CropMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OrientationMathTest {
    @Test fun `physical orientation quantizes only stable angles`() {
        assertEquals(0, OrientationMath.sensorQuadrant(359))
        assertEquals(90, OrientationMath.sensorQuadrant(95))
        assertEquals(180, OrientationMath.sensorQuadrant(185))
        assertEquals(270, OrientationMath.sensorQuadrant(275))
        assertEquals(-1, OrientationMath.sensorQuadrant(45))
    }

    @Test fun `SurfaceTexture includes sensor 90 degree rotation in native portrait`() {
        assertEquals(1080 to 1920, OrientationMath.naturalSourceSize(1920, 1080, 90))
        assertEquals(1080 to 1920, OrientationMath.naturalSourceSize(1920, 1080, 270))
        assertEquals(1920 to 1080, OrientationMath.naturalSourceSize(1920, 1080, 0))
        assertEquals(1920 to 1080, OrientationMath.naturalSourceSize(1920, 1080, 180))
    }

    @Test fun `no second quarter turn should be applied to portrait camera frames`() {
        assertEquals(0, OrientationMath.textureRotation(0))
        val crop = OrientationMath.surfaceOutputCrop(1920, 1080, 90, 0, 16.0 / 9.0, true)
        assertEquals(1080, crop.outW)
        assertEquals(1920, crop.outH)
        val matrix = FloatArray(16)
        CropMatrix.build(1080, 1920, crop.outW, crop.outH, 0, false, matrix)
        // Identity after ST: a portrait native frame remains portrait, upright.
        assertEquals(1.0, matrix[0].toDouble(), 0.0001)
        assertEquals(1.0, matrix[5].toDouble(), 0.0001)
        assertEquals(0.0, matrix[1].toDouble(), 0.0001)
        assertEquals(0.0, matrix[4].toDouble(), 0.0001)
    }

    @Test fun `landscape rotates native portrait exactly one quarter turn`() {
        assertEquals(270, OrientationMath.textureRotation(90))
        val crop = OrientationMath.surfaceOutputCrop(1920, 1080, 90, 90, 16.0 / 9.0, false)
        assertEquals(1920, crop.outW)
        assertEquals(1080, crop.outH)
        val matrix = FloatArray(16)
        CropMatrix.build(1080, 1920, crop.outW, crop.outH, 270, false, matrix)
        assertEquals(0.0, matrix[0].toDouble(), 0.0001)
        assertEquals(0.0, matrix[5].toDouble(), 0.0001)
        assertEquals(1.0, abs(matrix[1]).toDouble(), 0.0001)
        assertEquals(1.0, abs(matrix[4]).toDouble(), 0.0001)
    }

    @Test fun `reverse landscape is also full-frame and upright`() {
        assertEquals(90, OrientationMath.textureRotation(270))
        val crop = OrientationMath.surfaceOutputCrop(1920, 1080, 90, 270, 16.0 / 9.0, false)
        assertEquals(1920, crop.outW)
        assertEquals(1080, crop.outH)
        assertEquals(180, OrientationMath.textureRotation(180))
    }

    @Test fun `IMAX crop keeps expected orientation without stretch`() {
        val portrait = OrientationMath.surfaceOutputCrop(3840, 2160, 90, 0, 1.43, true)
        val landscape = OrientationMath.surfaceOutputCrop(3840, 2160, 90, 90, 1.43, false)
        assertEquals(2160, portrait.outW)
        assertEquals(3088, portrait.outH)
        assertEquals(3088, landscape.outW)
        assertEquals(2160, landscape.outH)
        assertTrue(abs(portrait.achievedRatio - 1.0 / 1.43) < 0.001)
        assertTrue(abs(landscape.achievedRatio - 1.43) < 0.001)
    }
}
