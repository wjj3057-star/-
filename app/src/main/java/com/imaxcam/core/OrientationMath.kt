package com.imaxcam.core

/**
 * Camera2 sensor buffers are normally exposed in sensor/native orientation. Determine
 * both GPU texture rotation and output crop from the same rotation/frame dimensions,
 * rather than assuming that ROTATION_0 always means a portrait window.
 */
object OrientationMath {
    /**
     * OrientationEventListener angle measured clockwise from the natural portrait
     * position. Return -1 while the phone lies between the four stable quadrants.
     */
    fun sensorQuadrant(angle: Int, tolerance: Int = 30): Int {
        if (angle !in 0..359) return -1
        val snapped = ((angle + 45) / 90 * 90) % 360
        val deviation = minOf((angle - snapped + 360) % 360, (snapped - angle + 360) % 360)
        return if (deviation <= tolerance) snapped else -1
    }

    /**
     * Camera2 SurfaceTexture's transform puts the sensor frame into the device's
     * natural orientation. Applying SENSOR_ORIENTATION again turns the image sideways.
     * Our texture-space matrix therefore corrects only the display's extra rotation.
     */
    fun textureRotation(displayDegrees: Int): Int =
        (360 - (displayDegrees % 360 + 360) % 360) % 360

    fun naturalSourceSize(width: Int, height: Int, sensorDegrees: Int): Pair<Int, Int> {
        val swap = (sensorDegrees % 180 + 180) % 180 != 0
        return if (swap) height to width else width to height
    }

    fun surfaceOutputCrop(
        srcW: Int,
        srcH: Int,
        sensorDegrees: Int,
        displayDegrees: Int,
        aspect: Double,
        portrait: Boolean
    ): CropSpec {
        val (naturalW, naturalH) = naturalSourceSize(srcW, srcH, sensorDegrees)
        val quarterTurn = textureRotation(displayDegrees) % 180 != 0
        val displayW = if (quarterTurn) naturalH else naturalW
        val displayH = if (quarterTurn) naturalW else naturalH
        val target = if (portrait) 1.0 / aspect else aspect
        val fit = CropCalc.fit(displayW, displayH, target)
        return CropSpec(srcW, srcH, fit.outW, fit.outH)
    }

    /** Output is a real portrait file when the window is tall, a landscape file otherwise. */
    fun crop(
        srcW: Int,
        srcH: Int,
        videoRatio: Double,
        rotationDegrees: Int,
        portrait: Boolean
    ): CropSpec {
        val rotated = (rotationDegrees % 180 + 180) % 180 == 90
        val rotatedW = if (rotated) srcH else srcW
        val rotatedH = if (rotated) srcW else srcH
        val ratio = if (portrait) 1.0 / videoRatio else videoRatio
        val fitted = CropCalc.fit(rotatedW, rotatedH, ratio)
        return CropSpec(srcW, srcH, fitted.outW, fitted.outH)
    }
}
