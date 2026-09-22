package com.imaxcam.core

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Builds the texture matrix that turns a raw camera frame into an IMAX-framed one.
 *
 * The crop, the sensor rotation and the front-camera mirror all collapse into a single
 * 4x4 matrix that the vertex shader applies to the texture coordinate. Nothing is copied
 * and no intermediate buffer exists: the sampler simply reads a rotated sub-rectangle of
 * the camera buffer, which is what makes an arbitrary aspect ratio free at capture time.
 *
 * Kept free of any framework dependency so the framing maths can be tested on the JVM —
 * a sign error here would silently misframe every shot.
 */
object CropMatrix {

    /**
     * @param srcW,srcH size of the camera buffer, in sensor orientation.
     * @param cropW,cropH size of the desired output, in display orientation.
     * @param rotationDegrees clockwise rotation from sensor to display orientation.
     * @param mirrorX true to mirror horizontally, as a front camera preview expects.
     * @param out receives a column-major 4x4 matrix; must hold at least 16 floats.
     */
    fun build(
        srcW: Int,
        srcH: Int,
        cropW: Int,
        cropH: Int,
        rotationDegrees: Int,
        mirrorX: Boolean,
        out: FloatArray
    ) {
        require(out.size >= 16) { "matrix must have 16 elements" }
        require(srcW > 0 && srcH > 0) { "source size must be positive" }

        val rot = ((rotationDegrees % 360) + 360) % 360
        val swapped = rot % 180 != 0

        // Size of the whole source frame once rotated into display orientation. For a
        // sensor mounted at 90 or 270 degrees the axes trade places, so the crop fraction
        // has to be measured against the rotated frame, not the raw buffer.
        val rotatedW = if (swapped) srcH else srcW
        val rotatedH = if (swapped) srcW else srcH

        // Scaling happens before the rotation, so these fractions are in output axes; the
        // rotation then carries them onto the source axes.
        val sx = (cropW.toFloat() / rotatedW).coerceIn(0f, 1f) * if (mirrorX) -1f else 1f
        val sy = (cropH.toFloat() / rotatedH).coerceIn(0f, 1f)

        // The full transform is T(0.5) . R(-rot) . S(sx, sy) . T(-0.5), written out rather
        // than composed step by step so it stays exact for the four right angles.
        val radians = Math.toRadians(-rot.toDouble())
        val c = snap(cos(radians))
        val s = snap(sin(radians))

        val a00 = c * sx
        val a01 = -s * sy
        val a10 = s * sx
        val a11 = c * sy

        java.util.Arrays.fill(out, 0, 16, 0f)
        out[0] = a00
        out[1] = a10
        out[4] = a01
        out[5] = a11
        out[10] = 1f
        out[12] = 0.5f - 0.5f * (a00 + a01)
        out[13] = 0.5f - 0.5f * (a10 + a11)
        out[15] = 1f
    }

    /** Keeps the four right angles exactly 0 or +/-1 instead of 6.1e-17. */
    private fun snap(value: Double): Float {
        val rounded = value.roundToInt()
        return if (kotlin.math.abs(value - rounded) < 1e-9) rounded.toFloat() else value.toFloat()
    }
}
