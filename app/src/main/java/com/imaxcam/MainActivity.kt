package com.imaxcam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.TypedValue
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.imaxcam.camera.HdrMode
import com.imaxcam.core.ImaxFormat
import com.imaxcam.core.OrientationMath
import com.imaxcam.core.VideoQuality
import com.imaxcam.pipeline.CaptureEngine
import com.imaxcam.ui.ViewfinderOverlay
import java.util.Locale

/**
 * Single-screen camera UI.
 *
 * Deliberately framework-only — no AppCompat, no Material Components, no constraint
 * solver. At minSdk 33 every API those libraries back-port is already present, and
 * AppCompat's inflation interceptor would rewrite each of this screen's dozen views for
 * nothing. All the real work happens in [CaptureEngine]; this class just wires controls
 * to it and paints the HUD.
 */
class MainActivity : Activity(), CaptureEngine.Listener {

    private lateinit var engine: CaptureEngine

    private lateinit var preview: SurfaceView
    private lateinit var hudMode: TextView
    private lateinit var hudSize: TextView
    private lateinit var hudAudio: TextView
    private lateinit var hudLatency: TextView
    private lateinit var recordTimer: TextView
    private lateinit var ratioBar: LinearLayout
    private lateinit var recordButton: Button
    private lateinit var switchCamera: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var settingsPanel: LinearLayout
    private lateinit var captureHint: TextView
    private lateinit var viewfinder: ViewfinderOverlay
    private lateinit var orientationButton: Button
    private lateinit var qualityButton: Button
    private lateinit var lowLatency: CheckBox
    private lateinit var stabilization: CheckBox
    private lateinit var audioEnabled: CheckBox
    private lateinit var spatialAudioAuto: CheckBox

    private val ratioButtons = mutableMapOf<ImaxFormat, TextView>()
    private var surfaceReady = false
    private enum class RotationMode { AUTO, LANDSCAPE, PORTRAIT }
    private var rotationMode = RotationMode.AUTO
    private var recordingOrientationLocked = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var queuedQuadrant = -1
    private var orientationTask: Runnable? = null
    private val orientationListener by lazy {
        object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (rotationMode != RotationMode.AUTO ||
                    !::engine.isInitialized || engine.state.recording ||
                    recordingOrientationLocked) return
                val quadrant = OrientationMath.sensorQuadrant(orientation)
                if (quadrant < 0 || quadrant == queuedQuadrant) return
                queuedQuadrant = quadrant
                orientationTask?.let { uiHandler.removeCallbacks(it) }
                val task = Runnable {
                    if (rotationMode != RotationMode.AUTO ||
                        recordingOrientationLocked || engine.state.recording ||
                        queuedQuadrant != quadrant) return@Runnable
                    // Request explicit rotations so the app follows the device even if
                    // the system auto-rotate toggle is disabled (common on Samsung).
                    val requested = when (quadrant) {
                        0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                    if (requestedOrientation != requested) requestedOrientation = requested
                }
                orientationTask = task
                uiHandler.postDelayed(task, 200L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        goFullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Android recreates this Activity on 90-degree turns to select layout-land.
        // Restore capture preferences before starting the Camera2/GL engine.
        val savedFormat = savedInstanceState?.getInt("formatIndex")?.let {
            ImaxFormat.entries.getOrNull(it)
        } ?: ImaxFormat.DEFAULT
        val savedQuality = savedInstanceState?.getInt("qualityIndex")?.let {
            VideoQuality.entries.getOrNull(it)
        } ?: VideoQuality.DEFAULT
        engine = CaptureEngine(this, this, savedFormat, savedQuality)
        rotationMode = savedInstanceState?.getInt("rotationMode")?.let {
            RotationMode.entries.getOrNull(it)
        } ?: RotationMode.AUTO
        buildRatioBar()
        lowLatency.isChecked = savedInstanceState?.getBoolean("lowLatency", true) ?: true
        stabilization.isChecked = savedInstanceState?.getBoolean("stabilization", false) ?: false
        spatialAudioAuto.isChecked = savedInstanceState?.getBoolean("spatialAudioAuto", true) ?: true
        audioEnabled.isChecked = savedInstanceState?.getBoolean("audioEnabled", true) ?: true
        wireControls()
        engine.setLowLatency(lowLatency.isChecked)
        engine.setStabilization(stabilization.isChecked)
        engine.setSpatialAudioAuto(spatialAudioAuto.isChecked)
        applyRotationMode()

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                if (surfaceReady) {
                    engine.updatePreviewSize(width, height)
                    engine.onDisplayRotationChanged()
                } else {
                    surfaceReady = true
                    engine.attachPreview(holder.surface, width, height)
                }
                syncPreviewAfterRotation()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                engine.detachPreview()
            }
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::engine.isInitialized) syncPreviewAfterRotation()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("rotationMode", rotationMode.ordinal)
        outState.putInt("formatIndex", engine.state.format.ordinal)
        outState.putInt("qualityIndex", engine.state.quality.ordinal)
        outState.putBoolean("lowLatency", lowLatency.isChecked)
        outState.putBoolean("stabilization", stabilization.isChecked)
        outState.putBoolean("spatialAudioAuto", spatialAudioAuto.isChecked)
        outState.putBoolean("audioEnabled", audioEnabled.isChecked)
        super.onSaveInstanceState(outState)
    }

    private fun syncPreviewAfterRotation() {
        // Window rotation and SurfaceHolder dimensions can update at different times;
        // sample both again after the first layout and the device animation has settled.
        preview.post {
            if (surfaceReady && ::engine.isInitialized) {
                engine.updatePreviewSize(preview.width, preview.height)
                engine.onDisplayRotationChanged()
            }
        }
        preview.postDelayed({
            if (surfaceReady && ::engine.isInitialized) {
                engine.updatePreviewSize(preview.width, preview.height)
                engine.onDisplayRotationChanged()
            }
        }, 160L)
    }

    private fun bindViews() {
        preview = findViewById(R.id.preview)
        settingsButton = findViewById(R.id.settingsButton)
        settingsPanel = findViewById(R.id.settingsPanel)
        captureHint = findViewById(R.id.captureHint)
        viewfinder = findViewById(R.id.viewfinder)
        hudMode = findViewById(R.id.hudMode)
        hudSize = findViewById(R.id.hudSize)
        hudAudio = findViewById(R.id.hudAudio)
        hudLatency = findViewById(R.id.hudLatency)
        recordTimer = findViewById(R.id.recordTimer)
        ratioBar = findViewById(R.id.ratioBar)
        recordButton = findViewById(R.id.recordButton)
        switchCamera = findViewById(R.id.switchCamera)
        orientationButton = findViewById(R.id.orientationButton)
        qualityButton = findViewById(R.id.qualityButton)
        lowLatency = findViewById(R.id.lowLatency)
        stabilization = findViewById(R.id.stabilization)
        audioEnabled = findViewById(R.id.audioEnabled)
        spatialAudioAuto = findViewById(R.id.spatialAudioAuto)
    }

    private fun goFullscreen() {
        // The theme's translucent status/navigation flags already let the window lay out
        // behind the system bars, so only the controller call is needed here.
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (hasCameraPermission()) {
            startEngine()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                REQUEST_PERMISSIONS
            )
        }
    }

    override fun onPause() {
        orientationListener.disable()
        orientationTask?.let { uiHandler.removeCallbacks(it) }
        queuedQuadrant = -1
        engine.stop()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (hasCameraPermission()) {
            startEngine()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startEngine() {
        engine.start()
        if (surfaceReady) {
            engine.attachPreview(preview.holder.surface, preview.width, preview.height)
        }
    }

    // -------------------------------------------------------------------------------
    // Controls
    // -------------------------------------------------------------------------------

    private fun buildRatioBar() {
        ratioBar.removeAllViews()
        ratioButtons.clear()
        ImaxFormat.entries.forEach { format ->
            val chip = TextView(this).apply {
                text = format.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                minWidth = dp(74)
                minHeight = dp(40)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setBackgroundResource(R.drawable.bg_chip)
                contentDescription = "${format.label} · ${format.note}"
                setOnClickListener {
                    selectFormat(format)
                    settingsPanel.visibility = View.GONE
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            ratioBar.addView(chip, params)
            ratioButtons[format] = chip
        }
        selectFormat(engine.state.format, notify = false)
    }

    private fun selectFormat(format: ImaxFormat, notify: Boolean = true) {
        ratioButtons.forEach { (key, view) ->
            val selected = key == format
            view.isSelected = selected
            view.setTextColor(getColor(if (selected) R.color.black else R.color.text_primary))
        }
        if (notify) engine.setFormat(format)
    }

    private fun wireControls() {
        recordButton.setOnClickListener {
            settingsPanel.visibility = View.GONE
            if (engine.state.recording) {
                engine.stopRecording()
            } else {
                engine.startRecording(withAudio = audioEnabled.isChecked)
            }
        }
        settingsButton.setOnClickListener {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        switchCamera.setOnClickListener {
            settingsPanel.visibility = View.GONE
            cycleCamera()
        }
        orientationButton.setOnClickListener {
            if (engine.state.recording || recordingOrientationLocked) return@setOnClickListener
            rotationMode = when (rotationMode) {
                RotationMode.AUTO -> RotationMode.LANDSCAPE
                RotationMode.LANDSCAPE -> RotationMode.PORTRAIT
                RotationMode.PORTRAIT -> RotationMode.AUTO
            }
            applyRotationMode()
        }
        qualityButton.setOnClickListener {
            if (engine.state.recording) return@setOnClickListener
            val options = VideoQuality.entries
            val index = options.indexOf(engine.state.quality)
            engine.setQuality(options[(index + 1) % options.size])
        }
        refreshOrientationButton()
        lowLatency.setOnCheckedChangeListener { _, checked -> engine.setLowLatency(checked) }
        stabilization.setOnCheckedChangeListener { _, checked ->
            engine.setStabilization(checked)
        }
        spatialAudioAuto.setOnCheckedChangeListener { _, checked ->
            engine.setSpatialAudioAuto(checked)
        }
    }

    private fun applyRotationMode() {
        orientationTask?.let { uiHandler.removeCallbacks(it) }
        queuedQuadrant = -1
        requestedOrientation = when (rotationMode) {
            RotationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            RotationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            RotationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        refreshOrientationButton()
        if (::engine.isInitialized) syncPreviewAfterRotation()
    }

    private fun refreshOrientationButton() {
        orientationButton.text = when (rotationMode) {
            RotationMode.AUTO -> getString(R.string.rotation_auto)
            RotationMode.LANDSCAPE -> getString(R.string.rotation_landscape)
            RotationMode.PORTRAIT -> getString(R.string.rotation_portrait)
        }
    }

    private fun cycleCamera() {
        val cameras = engine.availableCameras
        if (cameras.size < 2) return
        val current = engine.state.camera ?: return
        val index = cameras.indexOfFirst { it.id == current.id }
        engine.setCamera(cameras[(index + 1) % cameras.size])
    }

    // -------------------------------------------------------------------------------
    // CaptureEngine.Listener
    // -------------------------------------------------------------------------------

    override fun onReady(state: CaptureEngine.State) {
        if (state.hdrMode != HdrMode.HDR10_PLUS) {
            Toast.makeText(
                this,
                getString(R.string.no_hdr10_plus, state.hdrMode.label),
                Toast.LENGTH_LONG
            ).show()
        }
        renderState(state)
    }

    override fun onStateChanged(state: CaptureEngine.State) = renderState(state)

    private fun renderState(state: CaptureEngine.State) {
        // A single MP4 track cannot change width/height mid-take. Freeze the display
        // orientation until stop, then resume the user's chosen rotation mode.
        if (state.recording && !recordingOrientationLocked) {
            recordingOrientationLocked = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else if (!state.recording && recordingOrientationLocked) {
            recordingOrientationLocked = false
            applyRotationMode()
        }
        hudMode.text = if (state.hdr10PlusDynamic) {
            "${state.hdrMode.label} · dynamic"
        } else {
            state.hdrMode.label
        }

        val crop = state.crop
        hudSize.text = if (crop == null) {
            "PREPARING CAMERA"
        } else {
            String.format(
                Locale.US, "%d × %d   •   %d FPS   •   %d Mb/s",
                crop.outW, crop.outH, state.fps, state.bitrate / 1_000_000
            )
        }
        if (crop != null) viewfinder.frameAspect = crop.achievedRatio.toFloat()

        hudAudio.text = getString(R.string.audio_actual, state.audioMode)

        hudLatency.text = String.format(
            Locale.US, "LATENCY  %s    /    %.1f FPS", state.latency.format(), state.renderFps
        )
        captureHint.text = getString(
            if (state.recording) R.string.capture_recording else R.string.capture_ready
        )
        captureHint.setTextColor(
            getColor(if (state.recording) R.color.record else R.color.text_secondary)
        )

        if (recordButton.isSelected != state.recording) {
            recordButton.animate().cancel()
            recordButton.animate()
                .scaleX(if (state.recording) 0.94f else 1f)
                .scaleY(if (state.recording) 0.94f else 1f)
                .setDuration(170L)
                .start()
        }
        recordButton.isSelected = state.recording
        if (state.recording) settingsPanel.visibility = View.GONE
        recordButton.contentDescription = getString(
            if (state.recording) R.string.record_stop else R.string.record_start
        )
        recordTimer.visibility = if (state.recording) View.VISIBLE else View.GONE
        if (state.recording) {
            val seconds = state.recordedMs / 1000
            recordTimer.text =
                String.format(Locale.US, "● %02d:%02d", seconds / 60, seconds % 60)
        }
        switchCamera.isEnabled = !state.recording && engine.availableCameras.size > 1
        orientationButton.isEnabled = !state.recording
        qualityButton.isEnabled = !state.recording
        spatialAudioAuto.isEnabled = !state.recording
        qualityButton.text = getString(R.string.quality_value, state.quality.label)
        ratioButtons.values.forEach { it.isEnabled = !state.recording }
    }

    override fun onRecordingStarted(name: String) = Unit

    override fun onRecordingStopped(name: String, durationMs: Long, bytes: Long) {
        Toast.makeText(
            this,
            getString(
                R.string.saved,
                name,
                String.format(Locale.US, "%.1fs", durationMs / 1000.0),
                String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onError(message: String, cause: Throwable?) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
