package com.imaxcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.TypedValue
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.imaxcam.camera.HdrMode
import com.imaxcam.core.ImaxFormat
import com.imaxcam.databinding.ActivityMainBinding
import com.imaxcam.pipeline.CaptureEngine
import java.util.Locale

class MainActivity : AppCompatActivity(), CaptureEngine.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: CaptureEngine
    private val ratioButtons = mutableMapOf<ImaxFormat, TextView>()
    private var surfaceReady = false
    private var permissionsGranted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result[Manifest.permission.CAMERA] == true
        if (permissionsGranted) {
            startEngine()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = CaptureEngine(this, this)
        buildRatioBar()
        wireControls()

        binding.preview.holder.addCallback(object : SurfaceHolder.Callback {
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

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) {
            permissionsGranted = true
            startEngine()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    override fun onPause() {
        engine.stop()
        super.onPause()
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startEngine() {
        engine.start()
        if (surfaceReady) {
            val holder = binding.preview.holder
            engine.attachPreview(
                holder.surface,
                binding.preview.width,
                binding.preview.height
            )
        }
    }

    // -------------------------------------------------------------------------------
    // Controls
    // -------------------------------------------------------------------------------

    private fun buildRatioBar() {
        binding.ratioBar.removeAllViews()
        ratioButtons.clear()
        ImaxFormat.entries.forEach { format ->
            val chip = TextView(this).apply {
                text = format.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(18), dp(8), dp(18), dp(8))
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                isSelected = format == ImaxFormat.DEFAULT
                setOnClickListener { selectFormat(format) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            binding.ratioBar.addView(chip, params)
            ratioButtons[format] = chip
        }
        selectFormat(ImaxFormat.DEFAULT, notify = false)
    }

    private fun selectFormat(format: ImaxFormat, notify: Boolean = true) {
        ratioButtons.forEach { (key, view) ->
            view.isSelected = key == format
            view.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (key == format) R.color.black else R.color.text_primary
                )
            )
        }
        if (notify) engine.setFormat(format)
    }

    private fun wireControls() {
        binding.recordButton.setOnClickListener {
            if (engine.state.recording) {
                engine.stopRecording()
            } else {
                engine.startRecording(withAudio = binding.audioEnabled.isChecked)
            }
        }
        binding.switchCamera.setOnClickListener { cycleCamera() }
        binding.lowLatency.setOnCheckedChangeListener { _, checked ->
            engine.setLowLatency(checked)
        }
        binding.stabilization.setOnCheckedChangeListener { _, checked ->
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
        val dynamic = if (state.hdr10PlusDynamic) " · dynamic" else ""
        binding.hudMode.text = "${state.hdrMode.label}$dynamic"

        val crop = state.crop
        binding.hudSize.text = if (crop == null) {
            "--"
        } else {
            String.format(
                Locale.US,
                "%dx%d  %.3f:1  %d fps  %d Mbps",
                crop.outW, crop.outH, crop.achievedRatio, state.fps, state.bitrate / 1_000_000
            )
        }

        binding.hudLatency.text = String.format(
            Locale.US,
            "latency %s  ·  %.1f fps",
            state.latency.format(),
            state.renderFps
        )

        binding.recordButton.isSelected = state.recording
        binding.recordButton.contentDescription = getString(
            if (state.recording) R.string.record_stop else R.string.record_start
        )
        binding.recordTimer.visibility = if (state.recording) View.VISIBLE else View.GONE
        if (state.recording) {
            val totalSeconds = state.recordedMs / 1000
            binding.recordTimer.text = String.format(
                Locale.US, "● %02d:%02d", totalSeconds / 60, totalSeconds % 60
            )
        }
        binding.switchCamera.isEnabled = !state.recording && engine.availableCameras.size > 1
        ratioButtons.values.forEach { it.isEnabled = !state.recording }
    }

    override fun onRecordingStarted(name: String) = Unit

    override fun onRecordingStopped(name: String, durationMs: Long, bytes: Long) {
        val seconds = durationMs / 1000.0
        val megabytes = bytes / (1024.0 * 1024.0)
        Toast.makeText(
            this,
            getString(
                R.string.saved,
                name,
                String.format(Locale.US, "%.1fs", seconds),
                String.format(Locale.US, "%.0f MB", megabytes)
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onError(message: String, cause: Throwable?) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
