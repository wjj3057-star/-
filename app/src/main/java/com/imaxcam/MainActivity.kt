package com.imaxcam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.TypedValue
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.imaxcam.camera.HdrMode
import com.imaxcam.core.ImaxFormat
import com.imaxcam.pipeline.CaptureEngine
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
    private lateinit var hudLatency: TextView
    private lateinit var recordTimer: TextView
    private lateinit var ratioBar: LinearLayout
    private lateinit var recordButton: Button
    private lateinit var switchCamera: Button
    private lateinit var lowLatency: CheckBox
    private lateinit var stabilization: CheckBox
    private lateinit var audioEnabled: CheckBox

    private val ratioButtons = mutableMapOf<ImaxFormat, TextView>()
    private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        goFullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = CaptureEngine(this, this)
        buildRatioBar()
        wireControls()

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                surfaceReady = true
                engine.attachPreview(holder.surface, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                engine.detachPreview()
            }
        })
    }

    private fun bindViews() {
        preview = findViewById(R.id.preview)
        hudMode = findViewById(R.id.hudMode)
        hudSize = findViewById(R.id.hudSize)
        hudLatency = findViewById(R.id.hudLatency)
        recordTimer = findViewById(R.id.recordTimer)
        ratioBar = findViewById(R.id.ratioBar)
        recordButton = findViewById(R.id.recordButton)
        switchCamera = findViewById(R.id.switchCamera)
        lowLatency = findViewById(R.id.lowLatency)
        stabilization = findViewById(R.id.stabilization)
        audioEnabled = findViewById(R.id.audioEnabled)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(18), dp(8), dp(18), dp(8))
                setBackgroundResource(R.drawable.bg_chip)
                setOnClickListener { selectFormat(format) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            ratioBar.addView(chip, params)
            ratioButtons[format] = chip
        }
        selectFormat(ImaxFormat.DEFAULT, notify = false)
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
            if (engine.state.recording) {
                engine.stopRecording()
            } else {
                engine.startRecording(withAudio = audioEnabled.isChecked)
            }
        }
        switchCamera.setOnClickListener { cycleCamera() }
        lowLatency.setOnCheckedChangeListener { _, checked -> engine.setLowLatency(checked) }
        stabilization.setOnCheckedChangeListener { _, checked ->
            engine.setStabilization(checked)
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
        hudMode.text = if (state.hdr10PlusDynamic) {
            "${state.hdrMode.label} · dynamic"
        } else {
            state.hdrMode.label
        }

        val crop = state.crop
        hudSize.text = if (crop == null) {
            "--"
        } else {
            String.format(
                Locale.US,
                "%dx%d  %.3f:1  %d fps  %d Mbps",
                crop.outW, crop.outH, crop.achievedRatio, state.fps, state.bitrate / 1_000_000
            )
        }

        hudLatency.text = String.format(
            Locale.US, "latency %s  ·  %.1f fps", state.latency.format(), state.renderFps
        )

        recordButton.isSelected = state.recording
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
