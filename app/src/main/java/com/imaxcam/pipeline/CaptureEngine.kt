package com.imaxcam.pipeline

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import android.util.Size
import android.view.Surface
import com.imaxcam.camera.CameraCapabilities
import com.imaxcam.camera.CameraController
import com.imaxcam.camera.CameraInfo
import com.imaxcam.camera.HdrMode
import com.imaxcam.camera.TransferFunction
import com.imaxcam.core.CropCalc
import com.imaxcam.core.CropMatrix
import com.imaxcam.core.CropSpec
import com.imaxcam.core.FrameRateMeter
import com.imaxcam.core.ImaxFormat
import com.imaxcam.core.LatencyMonitor
import com.imaxcam.gl.CropRenderer
import com.imaxcam.gl.EglCore
import com.imaxcam.gl.FrameAnalyzer
import com.imaxcam.gl.GlColorSpace
import com.imaxcam.gl.GlUtil
import com.imaxcam.hdr.Hdr10PlusBuilder
import com.imaxcam.record.AudioEncoder
import com.imaxcam.record.MuxerGate
import com.imaxcam.record.OutputFile
import com.imaxcam.record.VideoEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the whole capture path: camera -> GPU crop -> HEVC encoder, plus the preview.
 *
 * Design notes on latency, which is the thing this class is organised around:
 *
 *  - There is exactly one camera stream. Preview and the recorded frame are both drawn
 *    from it, so the HAL is never asked to fan out.
 *  - Frames are rendered on the SurfaceTexture callback, not on a VSYNC tick, so a frame
 *    moves as soon as it exists.
 *  - The encoder surface is drawn and swapped *before* the preview. If anything in the
 *    pipeline is going to stall, the recording is not what waits.
 *  - Nothing is ever copied into application memory. The camera buffer is sampled
 *    directly by the GPU and written straight into the encoder's own buffer.
 *  - The only read-back, the HDR10+ histogram, is asynchronous and one frame behind.
 */
class CaptureEngine(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onReady(state: State)
        fun onStateChanged(state: State)
        fun onRecordingStarted(name: String)
        fun onRecordingStopped(name: String, durationMs: Long, bytes: Long)
        fun onError(message: String, cause: Throwable?)
    }

    /** Everything the UI needs to render its overlay, published on every change. */
    data class State(
        val camera: CameraInfo? = null,
        val hdrMode: HdrMode = HdrMode.SDR,
        val hdr10PlusDynamic: Boolean = false,
        val format: ImaxFormat = ImaxFormat.DEFAULT,
        val sourceSize: Size? = null,
        val crop: CropSpec? = null,
        val fps: Int = 30,
        val bitrate: Int = 0,
        val recording: Boolean = false,
        val lowLatency: Boolean = true,
        val stabilization: Boolean = false,
        val previewIsHdr: Boolean = false,
        val latency: LatencyMonitor.Snapshot = LatencyMonitor.Snapshot(0.0, 0.0, 0.0, 0),
        val renderFps: Double = 0.0,
        val recordedMs: Long = 0
    )

    // Recreated on every start(): a HandlerThread cannot be restarted once quit, and the
    // engine is stopped and started again on every pause/resume cycle.
    private var renderThread: HandlerThread? = null
    private var cameraThread: HandlerThread? = null
    private var codecThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var cameraHandler: Handler? = null
    private var codecHandler: Handler? = null

    private val mainHandler = Handler(context.mainLooper)
    private val started = AtomicBoolean(false)

    // --- GL state, render thread only ---------------------------------------------
    private var egl: EglCore? = null
    private var previewEglSurface: EGLSurface? = null
    private var encoderEglSurface: EGLSurface? = null
    private var renderer: CropRenderer? = null
    private var analyzer: FrameAnalyzer? = null
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val stMatrix = FloatArray(16)
    private val cropMatrix = FloatArray(16)
    private var previewWidth = 0
    private var previewHeight = 0
    private var previewSurface: Surface? = null
    private var previewIsHdr = false
    private var hintSession: PerformanceHintManager.Session? = null

    // --- recording state ------------------------------------------------------------
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var muxer: MuxerGate? = null
    private var outputFile: OutputFile? = null
    private var recordStartNanos = 0L

    @Volatile
    private var recording = false

    private var pendingMetadata: ByteArray? = null
    private var metadataFrameCounter = 0

    // --- configuration --------------------------------------------------------------
    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var controller: CameraController? = null
    private val latency = LatencyMonitor()
    private val frameRate = FrameRateMeter()

    @Volatile
    var state = State()
        private set

    var availableCameras: List<CameraInfo> = emptyList()
        private set

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    fun start() {
        if (!started.compareAndSet(false, true)) return
        renderThread = HandlerThread(
            "imax-render", Process.THREAD_PRIORITY_URGENT_DISPLAY
        ).also { it.start(); renderHandler = Handler(it.looper) }
        cameraThread = HandlerThread(
            "imax-camera", Process.THREAD_PRIORITY_DISPLAY
        ).also { it.start(); cameraHandler = Handler(it.looper) }
        codecThread = HandlerThread(
            "imax-codec", Process.THREAD_PRIORITY_URGENT_DISPLAY
        ).also { it.start(); codecHandler = Handler(it.looper) }

        availableCameras = CameraCapabilities.enumerate(cameraManager)
        val primary = CameraCapabilities.pickPrimary(availableCameras)
        val mode = primary?.bestHdrMode ?: HdrMode.SDR
        publish(
            state.copy(
                camera = primary,
                hdrMode = mode,
                fps = primary?.bestFixedFps(60)?.coerceAtMost(60) ?: 30
            )
        )
        mainHandler.post { listener.onReady(state) }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        // The camera must let go of the SurfaceTexture before GL destroys it, and the
        // recording must reach the muxer before either happens, so each stage is waited
        // on rather than merely posted.
        if (recording) {
            recording = false
            runOnHandler(renderHandler, DRAIN_TIMEOUT_MS) { finishRecording() }
            runOnHandler(codecHandler, DRAIN_TIMEOUT_MS) { }
            runOnHandler(renderHandler, DRAIN_TIMEOUT_MS) { }
        }
        runOnHandler(cameraHandler, SHUTDOWN_TIMEOUT_MS) { controller?.close() }
        runOnHandler(renderHandler, SHUTDOWN_TIMEOUT_MS) { teardownGl() }
        controller = null
        renderThread?.quitSafely()
        cameraThread?.quitSafely()
        codecThread?.quitSafely()
        renderThread = null
        cameraThread = null
        codecThread = null
        renderHandler = null
        cameraHandler = null
        codecHandler = null
    }

    /** Runs [block] on [handler] and waits for it, tolerating an already-quit looper. */
    private fun runOnHandler(handler: Handler?, timeoutMs: Long, block: () -> Unit) {
        val target = handler ?: return
        val latch = CountDownLatch(1)
        val posted = target.post {
            runCatching(block).onFailure { Log.w(TAG, "shutdown step failed", it) }
            latch.countDown()
        }
        if (!posted) return
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /** Posts to the render thread, or does nothing when the engine is not running. */
    private fun postRender(block: () -> Unit) {
        renderHandler?.post(block)
    }

    /** Posts to the camera thread, or does nothing when the engine is not running. */
    private fun postCamera(block: () -> Unit) {
        cameraHandler?.post(block)
    }

    // ---------------------------------------------------------------------------------
    // Preview surface plumbing
    // ---------------------------------------------------------------------------------

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        if (!started.get()) return
        postRender {
            previewSurface = surface
            previewWidth = width
            previewHeight = height
            runCatching { setUpGl() }
                .onFailure { fail("GL setup failed", it) }
        }
    }

    fun updatePreviewSize(width: Int, height: Int) {
        postRender {
            previewWidth = width
            previewHeight = height
        }
    }

    fun detachPreview() {
        postCamera {
            controller?.close()
            postRender {
                previewSurface = null
                teardownGl()
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------------

    fun setFormat(format: ImaxFormat) {
        if (state.format == format) return
        postRender {
            publish(state.copy(format = format))
            recomputeCrop()
        }
    }

    fun setHdrMode(mode: HdrMode) {
        if (state.hdrMode == mode) return
        // The dynamic range profile is baked into the capture session, so it has to be
        // rebuilt. Preview is restarted with the new profile from scratch.
        postRender {
            publish(state.copy(hdrMode = mode))
            restartCamera()
        }
    }

    fun setFps(fps: Int) {
        if (state.fps == fps) return
        postRender {
            publish(state.copy(fps = fps))
            restartCamera()
        }
    }

    fun setCamera(info: CameraInfo) {
        if (state.camera?.id == info.id) return
        postRender {
            val mode = if (info.supportedHdrModes.contains(state.hdrMode)) {
                state.hdrMode
            } else {
                info.bestHdrMode
            }
            publish(state.copy(camera = info, hdrMode = mode))
            restartCamera()
        }
    }

    fun setLowLatency(enabled: Boolean) {
        publish(state.copy(lowLatency = enabled))
        postCamera {
            controller?.updateTuning { it.copy(lowLatency = enabled) }
        }
    }

    fun setStabilization(enabled: Boolean) {
        publish(state.copy(stabilization = enabled))
        postCamera {
            controller?.updateTuning { it.copy(videoStabilization = enabled) }
        }
    }

    fun setZoom(ratio: Float) {
        postCamera { controller?.updateTuning { it.copy(zoomRatio = ratio) } }
    }

    // ---------------------------------------------------------------------------------
    // GL setup, render thread
    // ---------------------------------------------------------------------------------

    private fun setUpGl() {
        teardownGl()
        val info = state.camera ?: run {
            fail("No usable camera found", null)
            return
        }
        val surface = previewSurface ?: return

        val mode = if (info.supportedHdrModes.contains(state.hdrMode)) {
            state.hdrMode
        } else {
            info.bestHdrMode
        }

        val requestedColorSpace = when (mode.transfer) {
            TransferFunction.PQ -> GlColorSpace.BT2020_PQ
            TransferFunction.HLG -> GlColorSpace.BT2020_HLG
            TransferFunction.SDR -> GlColorSpace.SRGB
        }

        val core = EglCore(tenBit = mode.tenBit, requestedColorSpace = requestedColorSpace)
        egl = core

        // The preview only gets an HDR colour space if the EGL implementation granted one;
        // otherwise the frame is tone-mapped to Rec.709 in the shader so what is on screen
        // still resembles what is being written to the file.
        previewIsHdr = core.colorSpace != GlColorSpace.SRGB && mode.isHdr
        previewEglSurface = core.createWindowSurface(
            surface,
            if (previewIsHdr) core.colorSpace else GlColorSpace.SRGB
        )
        core.makeCurrent(previewEglSurface!!)

        renderer = CropRenderer().also { it.setUp() }
        textureId = GlUtil.createExternalTexture()

        val source = CropCalc.pickSource(info.recordSizes, state.format)
            ?: Size(CropCalc.UHD_WIDTH, CropCalc.UHD_HEIGHT)
        val crop = CropCalc.fit(source, state.format)

        val texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(source.width, source.height)
            setOnFrameAvailableListener({ onFrameAvailable() }, renderHandler)
        }
        surfaceTexture = texture
        cameraSurface = Surface(texture)

        publish(
            state.copy(
                hdrMode = mode,
                sourceSize = source,
                crop = crop,
                previewIsHdr = previewIsHdr,
                bitrate = CropCalc.suggestedBitrate(crop.outW, crop.outH, state.fps)
            )
        )
        recomputeCrop()
        acquirePerformanceHint()
        openCamera()
    }

    private fun teardownGl() {
        releaseEncoderSurface()
        analyzer?.release()
        analyzer = null
        renderer?.release()
        renderer = null
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        cameraSurface?.release()
        cameraSurface = null
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        egl?.releaseSurface(previewEglSurface)
        previewEglSurface = null
        egl?.release()
        egl = null
        hintSession?.close()
        hintSession = null
        frameRate.reset()
        latency.reset()
    }

    private fun acquirePerformanceHint() {
        // Tells the scheduler this thread has a hard per-frame deadline, which keeps it on
        // a big core instead of being migrated to a little one mid-frame.
        val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return
        val targetNanos = 1_000_000_000L / state.fps.coerceAtLeast(1)
        hintSession = runCatching {
            manager.createHintSession(intArrayOf(Process.myTid()), targetNanos)
        }.getOrNull()
    }

    private fun recomputeCrop() {
        val info = state.camera ?: return
        val source = state.sourceSize ?: return
        val crop = CropCalc.fit(source, state.format)
        val rotation = ((info.sensorOrientation - displayRotationDegrees() + 360) % 360)
        CropMatrix.build(
            srcW = source.width,
            srcH = source.height,
            cropW = crop.outW,
            cropH = crop.outH,
            rotationDegrees = rotation,
            mirrorX = false,
            out = cropMatrix
        )
        analyzer?.release()
        analyzer = FrameAnalyzer().also { it.setUp(crop.achievedRatio) }
        publish(
            state.copy(
                crop = crop,
                bitrate = CropCalc.suggestedBitrate(crop.outW, crop.outH, state.fps)
            )
        )
    }

    private fun displayRotationDegrees(): Int {
        val display = context.display ?: return 90
        return when (display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun openCamera() {
        val info = state.camera ?: return
        val surface = cameraSurface ?: return
        postCamera {
            val handler = cameraHandler ?: return@postCamera
            val ctrl = controller ?: CameraController(cameraManager, handler).also {
                controller = it
                it.listener = cameraListener
            }
            runCatching {
                ctrl.open(
                    info,
                    state.hdrMode,
                    CameraController.Tuning(
                        fps = state.fps,
                        lowLatency = state.lowLatency,
                        videoStabilization = state.stabilization
                    ),
                    surface
                )
            }.onFailure { fail("Could not open camera ${info.id}", it) }
        }
    }

    private fun restartCamera() {
        postCamera {
            controller?.close()
            postRender {
                if (previewSurface != null) {
                    runCatching { setUpGl() }.onFailure { fail("GL restart failed", it) }
                }
            }
        }
    }

    private val cameraListener = object : CameraController.Listener {
        override fun onSessionReady(info: CameraInfo, mode: HdrMode) {
            publish(state.copy(camera = info, hdrMode = mode))
        }

        override fun onFrameCaptured(sensorTimestampNanos: Long) = Unit

        override fun onError(message: String, cause: Throwable?) = fail(message, cause)
    }

    // ---------------------------------------------------------------------------------
    // The render pass
    // ---------------------------------------------------------------------------------

    private fun onFrameAvailable() {
        val core = egl ?: return
        val texture = surfaceTexture ?: return
        val draw = renderer ?: return
        val crop = state.crop ?: return
        val preview = previewEglSurface ?: return

        val drawTarget = encoderEglSurface ?: preview
        core.makeCurrent(drawTarget)
        runCatching { texture.updateTexImage() }.onFailure { return }
        texture.getTransformMatrix(stMatrix)

        val captureNanos = texture.timestamp
        val startNanos = System.nanoTime()
        val transfer = state.hdrMode.transfer.shaderId

        // ---- recorded frame first: it is the one that must not be made to wait --------
        val encoderSurface = encoderEglSurface
        val encoder = videoEncoder
        if (recording && encoderSurface != null && encoder != null) {
            if (encoder.hdr10PlusActive) {
                updateHdr10PlusMetadata(draw, crop, transfer)
                pendingMetadata?.let { encoder.setHdr10PlusMetadata(it) }
            }
            GLES20.glViewport(0, 0, crop.outW, crop.outH)
            draw.drawPassthrough(textureId, stMatrix, cropMatrix)
            core.setPresentationTime(encoderSurface, captureNanos)
            core.swapBuffers(encoderSurface)
        }

        // ---- preview -------------------------------------------------------------------
        core.makeCurrent(preview)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GlUtil.applyAspectViewport(previewWidth, previewHeight, crop.outW, crop.outH)
        if (previewIsHdr) {
            draw.drawPassthrough(textureId, stMatrix, cropMatrix)
        } else {
            draw.drawToneMapped(textureId, stMatrix, cropMatrix, transfer, SDR_PEAK_NITS)
        }
        core.swapBuffers(preview)

        // ---- telemetry -------------------------------------------------------------------
        if (captureNanos > 0) latency.record(startNanos - captureNanos)
        frameRate.tick(startNanos)
        hintSession?.reportActualWorkDuration(System.nanoTime() - startNanos)
        publishTelemetry()
    }

    /**
     * Refreshes the ST 2094-40 payload from the GPU histogram.
     *
     * The analysis pass runs on a fraction of the frames: the tone curve tracks scene
     * brightness, which does not change meaningfully between adjacent frames, and skipping
     * most of them keeps the extra draw call off the critical path.
     */
    private fun updateHdr10PlusMetadata(draw: CropRenderer, crop: CropSpec, transfer: Int) {
        val analysis = analyzer ?: return
        val source = state.sourceSize ?: return
        metadataFrameCounter++
        if (metadataFrameCounter % METADATA_INTERVAL_FRAMES != 0) return

        analysis.capture(
            draw, textureId, stMatrix, cropMatrix, transfer, source.width, source.height
        )
        analysis.poll()?.let { stats ->
            pendingMetadata = Hdr10PlusBuilder.build(stats, TARGET_DISPLAY_NITS)
        }
    }

    private var lastTelemetryNanos = 0L

    private fun publishTelemetry() {
        val now = System.nanoTime()
        if (now - lastTelemetryNanos < TELEMETRY_INTERVAL_NANOS) return
        lastTelemetryNanos = now
        publish(
            state.copy(
                latency = latency.snapshot(),
                renderFps = frameRate.fps,
                // A codec can reject the metadata mid-take; report what is actually being
                // written rather than what was asked for.
                hdr10PlusDynamic = videoEncoder?.hdr10PlusActive ?: state.hdr10PlusDynamic,
                recordedMs = if (recording) {
                    (now - recordStartNanos) / 1_000_000L
                } else {
                    0L
                }
            )
        )
    }

    // ---------------------------------------------------------------------------------
    // Recording
    // ---------------------------------------------------------------------------------

    fun startRecording(withAudio: Boolean = true) {
        if (recording) return
        postRender {
            runCatching { beginRecording(withAudio) }
                .onFailure {
                    fail("Could not start recording", it)
                    abortRecording()
                }
        }
    }

    private fun beginRecording(withAudio: Boolean) {
        val core = egl ?: error("GL not ready")
        val crop = state.crop ?: error("No crop configured")

        val file = OutputFile.create(
            context, state.format.label, crop.outW, crop.outH, state.hdrMode.label
        )
        outputFile = file

        // The crop matrix already rotates the frame into display orientation, so the
        // container needs no further hint.
        val gate = MuxerGate(file.fileDescriptor, orientationHint = 0, expectAudio = withAudio)
        muxer = gate

        val bitrate = CropCalc.suggestedBitrate(crop.outW, crop.outH, state.fps)
        val encoder = VideoEncoder(
            VideoEncoder.Config(
                width = crop.outW,
                height = crop.outH,
                fps = state.fps,
                bitrate = bitrate,
                hdrMode = state.hdrMode,
                targetDisplayNits = TARGET_DISPLAY_NITS,
                lowLatency = state.lowLatency
            ),
            requireNotNull(codecHandler) { "engine is not running" },
            gate
        ) { message, cause -> fail(message, cause) }
        encoder.prepare()
        videoEncoder = encoder
        encoderEglSurface = core.createWindowSurface(encoder.inputSurface)
        encoder.start()

        if (withAudio) {
            val audio = AudioEncoder(
                AudioEncoder.Config(lowLatency = state.lowLatency),
                gate
            ) { message, cause -> Log.w(TAG, message, cause) }
            if (audio.prepare()) {
                audioEncoder = audio
                audio.start()
            } else {
                // No usable microphone: release the gate's expectation so the video track
                // starts on its own rather than stalling behind audio that never comes.
                gate.dropAudioTrack()
            }
        }

        pendingMetadata = Hdr10PlusBuilder.neutral(TARGET_DISPLAY_NITS)
        metadataFrameCounter = 0
        recordStartNanos = System.nanoTime()
        recording = true
        publish(
            state.copy(
                recording = true,
                bitrate = bitrate,
                hdr10PlusDynamic = encoder.hdr10PlusActive
            )
        )
        mainHandler.post { listener.onRecordingStarted(file.displayName) }
        Log.i(
            TAG,
            "recording $crop at ${state.fps}fps, ${bitrate / 1_000_000}Mbps, " +
                "${state.hdrMode.label}${if (encoder.hdr10PlusActive) " (dynamic)" else ""}"
        )
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        postRender { finishRecording() }
    }

    /**
     * Ends the take without blocking the render thread.
     *
     * The encoder surface is detached first so the preview keeps running at full rate
     * while the codec drains on its own thread; only once the end-of-stream buffer has
     * been written does the file get closed and published.
     */
    private fun finishRecording() {
        val encoder = videoEncoder ?: run { abortRecording(); return }
        val gate = muxer
        val file = outputFile
        val audio = audioEncoder
        val durationMs = if (recordStartNanos > 0) {
            (System.nanoTime() - recordStartNanos) / 1_000_000L
        } else {
            0L
        }

        val surface = encoderEglSurface
        encoderEglSurface = null
        encoder.signalEndOfStream()

        codecHandler?.post {
            audio?.stop()
            encoder.awaitDrain(DRAIN_TIMEOUT_MS)
            postRender {
                // Make sure the encoder surface is not the current draw target before it
                // is destroyed.
                previewEglSurface?.let { egl?.makeCurrent(it) }
                egl?.releaseSurface(surface)
                audio?.release()
                encoder.release()
                audioEncoder = null
                videoEncoder = null
                val bytes = gate?.bytesWritten ?: 0L
                gate?.release()
                muxer = null
                if (bytes > 0) file?.publish() else file?.discard()
                outputFile = null
                recordStartNanos = 0
                publish(state.copy(recording = false, recordedMs = 0))
                if (file != null) {
                    mainHandler.post {
                        listener.onRecordingStopped(file.displayName, durationMs, bytes)
                    }
                }
            }
        }
    }

    /** Tears down a half-built recording, leaving no stray file behind. */
    private fun abortRecording() {
        recording = false
        releaseEncoderSurface()
        runCatching { audioEncoder?.release() }
        runCatching { videoEncoder?.release() }
        audioEncoder = null
        videoEncoder = null
        runCatching { muxer?.release() }
        muxer = null
        outputFile?.discard()
        outputFile = null
        recordStartNanos = 0
        publish(state.copy(recording = false, recordedMs = 0))
    }

    private fun releaseEncoderSurface() {
        encoderEglSurface?.let { egl?.releaseSurface(it) }
        encoderEglSurface = null
    }

    // ---------------------------------------------------------------------------------

    private fun publish(next: State) {
        state = next
        mainHandler.post { listener.onStateChanged(next) }
    }

    private fun fail(message: String, cause: Throwable?) {
        Log.e(TAG, message, cause)
        mainHandler.post { listener.onError(message, cause) }
    }

    private companion object {
        const val TAG = "CaptureEngine"
        const val TARGET_DISPLAY_NITS = 1000
        const val SDR_PEAK_NITS = 203f // ITU-R BT.2408 reference diffuse white
        const val METADATA_INTERVAL_FRAMES = 4
        const val TELEMETRY_INTERVAL_NANOS = 250_000_000L
        const val DRAIN_TIMEOUT_MS = 1_500L
        const val SHUTDOWN_TIMEOUT_MS = 2_000L
    }
}
