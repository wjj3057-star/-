package com.imaxcam.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Camera2 front end tuned for the lowest capture-to-encoder latency the device allows.
 *
 * The session deliberately has a *single* output — one SurfaceTexture. Preview and the
 * encoder are both fed from it on the GPU, so the camera HAL only ever produces one
 * stream. Every extra stream costs ISP bandwidth and adds queueing delay, and a second
 * output would also force the session into a mixed dynamic-range configuration that many
 * devices refuse outright when HDR10+ is requested.
 */
class CameraController(
    private val manager: CameraManager,
    private val handler: Handler
) {

    /** Tuning knobs that trade image processing against pipeline delay. */
    data class Tuning(
        val fps: Int = 30,
        val lowLatency: Boolean = true,
        val videoStabilization: Boolean = false,
        val zoomRatio: Float = 1f
    )

    interface Listener {
        fun onSessionReady(info: CameraInfo, mode: HdrMode)
        fun onFrameCaptured(sensorTimestampNanos: Long)
        fun onError(message: String, cause: Throwable?)
    }

    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null

    var listener: Listener? = null
    var info: CameraInfo? = null
        private set
    var mode: HdrMode = HdrMode.SDR
        private set
    private var tuning = Tuning()

    @SuppressLint("MissingPermission")
    fun open(target: CameraInfo, requestedMode: HdrMode, tuning: Tuning, surface: Surface) {
        close()
        info = target
        this.tuning = tuning
        mode = if (target.supportedHdrModes.contains(requestedMode)) {
            requestedMode
        } else {
            target.bestHdrMode
        }

        manager.openCamera(target.id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                runCatching { configure(camera, surface) }
                    .onFailure { listener?.onError("Capture session failed", it) }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (device === camera) device = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (device === camera) device = null
                listener?.onError("Camera error $error", null)
            }
        })
    }

    private fun configure(camera: CameraDevice, surface: Surface) {
        val target = info ?: return
        val output = OutputConfiguration(surface).apply {
            if (mode.isHdr) {
                dynamicRangeProfile = mode.profile
            }
            if (target.supportsVideoStreamUseCase) {
                // Tells the HAL this is a record stream, which on most devices selects a
                // shorter, lower-latency ISP path than the general-purpose preview one.
                streamUseCase =
                    CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()
            }
        }

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(output),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    runCatching { startRepeating(camera, configured, surface) }
                        .onFailure { listener?.onError("Repeating request failed", it) }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    listener?.onError("Camera session configuration failed", null)
                }
            }
        )
        camera.createCaptureSession(config)
    }

    private fun startRepeating(
        camera: CameraDevice,
        session: CameraCaptureSession,
        surface: Surface
    ) {
        val target = info ?: return
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            applyTuning(this, target)
        }
        requestBuilder = builder
        session.setRepeatingRequest(builder.build(), captureCallback, handler)
        listener?.onSessionReady(target, mode)
    }

    private fun applyTuning(builder: CaptureRequest.Builder, target: CameraInfo) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        )
        builder.set(
            CaptureRequest.CONTROL_AWB_MODE,
            CameraMetadata.CONTROL_AWB_MODE_AUTO
        )

        // A fixed range stops the AE from dropping to half rate in low light, which would
        // otherwise double the interval between frames and with it the capture latency.
        builder.set(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            pickFpsRange(target, tuning.fps)
        )

        if (target.maxZoom > 1f) {
            builder.set(
                CaptureRequest.CONTROL_ZOOM_RATIO,
                tuning.zoomRatio.coerceIn(1f, target.maxZoom)
            )
        }

        val stabilization = when {
            !tuning.videoStabilization -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            target.stabilizationModes.contains(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            ) -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilization)

        if (tuning.lowLatency) {
            // Electronic stabilisation, zero-shutter-lag and the heavier noise-reduction
            // and sharpening passes all work by holding frames in a queue. Each one buys
            // image quality with delay, so the low-latency path turns them down.
            if (target.supportsZsl) {
                builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
            }
            builder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_FAST
            )
            builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST
            )
            builder.set(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_OFF
            )
            builder.set(
                CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
            )
        } else {
            builder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
            builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        }
    }

    private fun pickFpsRange(target: CameraInfo, fps: Int): Range<Int> {
        target.fpsRanges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        target.fpsRanges.filter { it.upper == fps }.minByOrNull { it.upper - it.lower }
            ?.let { return it }
        return target.fpsRanges.maxByOrNull { it.upper } ?: Range(fps, fps)
    }

    /** Re-applies tuning without tearing the session down. */
    fun updateTuning(update: (Tuning) -> Tuning) {
        val target = info ?: return
        val builder = requestBuilder ?: return
        val active = session ?: return
        tuning = update(tuning)
        applyTuning(builder, target)
        runCatching { active.setRepeatingRequest(builder.build(), captureCallback, handler) }
            .onFailure { Log.w(TAG, "failed to update repeating request", it) }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            result.get(CaptureResult.SENSOR_TIMESTAMP)?.let {
                listener?.onFrameCaptured(it)
            }
        }
    }

    fun close() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        requestBuilder = null
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
