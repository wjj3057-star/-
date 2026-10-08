package com.imaxcam

import com.imaxcam.core.OrientationMath
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
        assertEquals(-1, OrientationMath.sensorQuadrant(-1))
    }

    @Test fun `sensor rotation follows rear and front camera conventions`() {
        assertEquals(90, OrientationMath.cameraRotation(90, 0, false))
        assertEquals(0, OrientationMath.cameraRotation(90, 90, false))
        assertEquals(180, OrientationMath.cameraRotation(90, 90, true))
        assertEquals(270, OrientationMath.cameraRotation(90, 180, false))
    }

    @Test fun `portrait file has tall crop when sensor axes are exchanged`() {
        val crop = OrientationMath.crop(3840, 2160, 1.43, 90, portrait = true)
        assertEquals(2160, crop.outW)
        assertEquals(3088, crop.outH)
        assertTrue(abs(crop.achievedRatio - 1.0/1.43) < 0.001)
    }

    @Test fun `landscape output stays wide when sensor rotation is zero`() {
        val crop = OrientationMath.crop(3840, 2160, 1.43, 0, portrait = false)
        assertEquals(3088, crop.outW)
        assertEquals(2160, crop.outH)
    }

    @Test fun `orientation crop handles landscape tablet and rotated sensors`() {
        val horizontal = OrientationMath.crop(3840, 2160, 1.90, 90, portrait = false)
        val vertical = OrientationMath.crop(3840, 2160, 1.90, 0, portrait = true)
        assertTrue(horizontal.outW > horizontal.outH)
        assertTrue(vertical.outH > vertical.outW)
        assertTrue(abs(horizontal.achievedRatio - 1.90) < 0.002)
        assertTrue(abs(vertical.achievedRatio - 1.0 / 1.90) < 0.002)
    }
}
