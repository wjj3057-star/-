package com.imaxcam

import com.imaxcam.core.CropMatrix
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * The texture matrix is the only thing that decides what part of the sensor ends up in
 * the file, so it is checked by mapping the quad's corners through it by hand.
 */
class CropMatrixTest {

    private val m = FloatArray(16)

    /** Applies the column-major matrix to a texture coordinate. */
    private fun map(u: Float, v: Float): Pair<Float, Float> =
        (m[0] * u + m[4] * v + m[12]) to (m[1] * u + m[5] * v + m[13])

    private fun assertMaps(u: Float, v: Float, expectedX: Float, expectedY: Float) {
        val (x, y) = map(u, v)
        assertEquals("x for ($u,$v)", expectedX.toDouble(), x.toDouble(), 1e-4)
        assertEquals("y for ($u,$v)", expectedY.toDouble(), y.toDouble(), 1e-4)
    }

    @Test
    fun `1_43 crop trims the sides evenly and keeps full height`() {
        CropMatrix.build(3840, 2160, 3088, 2160, 0, false, m)
        val inset = (1f - 3088f / 3840f) / 2f
        assertMaps(0f, 0f, inset, 0f)
        assertMaps(1f, 1f, 1f - inset, 1f)
        assertMaps(0.5f, 0.5f, 0.5f, 0.5f)
    }

    @Test
    fun `1_90 crop trims top and bottom evenly and keeps full width`() {
        CropMatrix.build(3840, 2160, 3840, 2022, 0, false, m)
        val inset = (1f - 2022f / 2160f) / 2f
        assertMaps(0f, 0f, 0f, inset)
        assertMaps(1f, 1f, 1f, 1f - inset)
    }

    @Test
    fun `half turn maps the frame corner to corner`() {
        CropMatrix.build(3840, 2160, 3088, 2160, 180, false, m)
        val inset = (1f - 3088f / 3840f) / 2f
        assertMaps(0f, 0f, 1f - inset, 1f)
        assertMaps(1f, 1f, inset, 0f)
    }

    @Test
    fun `mirroring flips horizontally about the centre`() {
        CropMatrix.build(3840, 2160, 3088, 2160, 0, true, m)
        val inset = (1f - 3088f / 3840f) / 2f
        assertMaps(0f, 0f, 1f - inset, 0f)
        assertMaps(1f, 1f, inset, 1f)
        assertMaps(0.5f, 0.5f, 0.5f, 0.5f)
    }

    @Test
    fun `quarter turn measures the crop against the rotated frame`() {
        // Portrait-mounted sensor: the 3088-wide output is taken along the source height.
        CropMatrix.build(2160, 3840, 3088, 2160, 90, false, m)
        val (x0, y0) = map(0f, 0f)
        val (x1, y1) = map(1f, 1f)
        assertEquals("source x span", 2160f / 2160f.toDouble(), abs(x1 - x0).toDouble(), 1e-4)
        assertEquals("source y span", (3088f / 3840f).toDouble(), abs(y1 - y0).toDouble(), 1e-4)
    }

    @Test
    fun `an uncropped frame yields the identity`() {
        CropMatrix.build(3840, 2160, 3840, 2160, 0, false, m)
        assertMaps(0f, 0f, 0f, 0f)
        assertMaps(1f, 1f, 1f, 1f)
        assertMaps(0.25f, 0.75f, 0.25f, 0.75f)
    }
}
