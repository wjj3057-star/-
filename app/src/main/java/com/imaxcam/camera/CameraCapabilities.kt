package com.imaxcam.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.util.Range
import android.util.Size

/**
 * Opto-electronic transfer function a stream is encoded with.
 *
 * [shaderId] is the value handed to the preview and histogram shaders, which need to know
 * how to linearise what they sample.
 */
enum class TransferFunction(val shaderId: Int) {
    PQ(0),
    HLG(1),
    SDR(2)
}

/**
 * Dynamic range the capture session will run in, best first.
 *
 * HDR10+ is the goal: it is the only one of these that carries per-frame ST 2094-40
 * metadata, which is what lets a display tone-map each shot on its own terms instead of
 * applying one static curve to the whole clip.
 */
enum class HdrMode(
    val label: String,
    val profile: Long,
    val transfer: TransferFunction,
    val tenBit: Boolean
) {
    HDR10_PLUS("HDR10+", DynamicRangeProfiles.HDR10_PLUS, TransferFunction.PQ, true),
    HDR10("HDR10", DynamicRangeProfiles.HDR10, TransferFunction.PQ, true),
    HLG10("HLG10", DynamicRangeProfiles.HLG10, TransferFunction.HLG, true),
    SDR("SDR", DynamicRangeProfiles.STANDARD, TransferFunction.SDR, false);

    val isHdr: Boolean get() = this != SDR
}

/** Everything the pipeline needs to know about one camera, resolved once at start-up. */
data class CameraInfo(
    val id: String,
    val facing: Int,
    val sensorOrientation: Int,
    val supportedHdrModes: List<HdrMode>,
    val recordSizes: List<Size>,
    val fpsRanges: List<Range<Int>>,
    val supportsVideoStreamUseCase: Boolean,
    val supportsZsl: Boolean,
    val maxZoom: Float,
    val stabilizationModes: IntArray
) {
    val isFront: Boolean get() = facing == CameraCharacteristics.LENS_FACING_FRONT

    val bestHdrMode: HdrMode get() = supportedHdrModes.firstOrNull() ?: HdrMode.SDR

    /** Highest frame rate the sensor can hold as a fixed range, capped at [limit]. */
    fun bestFixedFps(limit: Int = 60): Int =
        fpsRanges.filter { it.lower == it.upper && it.upper <= limit }
            .maxOfOrNull { it.upper }
            ?: fpsRanges.filter { it.upper <= limit }.maxOfOrNull { it.upper }
            ?: 30

    override fun equals(other: Any?): Boolean = other is CameraInfo && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

object CameraCapabilities {

    private const val CAPABILITY_TEN_BIT =
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT

    fun enumerate(manager: CameraManager): List<CameraInfo> =
        manager.cameraIdList.mapNotNull { id ->
            runCatching { describe(manager, id) }.getOrNull()
        }

    fun describe(manager: CameraManager, id: String): CameraInfo {
        val chars = manager.getCameraCharacteristics(id)
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: IntArray(0)

        val hdrModes = mutableListOf<HdrMode>()
        if (capabilities.contains(CAPABILITY_TEN_BIT)) {
            val profiles = chars.get(
                CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
            )
            val supported = profiles?.supportedProfiles ?: emptySet()
            HdrMode.entries.forEach { mode ->
                if (mode.tenBit && supported.contains(mode.profile)) hdrModes += mode
            }
        }
        hdrModes += HdrMode.SDR

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            .sortedByDescending { it.width.toLong() * it.height.toLong() }

        val useCases = chars.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
            ?: LongArray(0)

        return CameraInfo(
            id = id,
            facing = chars.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            supportedHdrModes = hdrModes,
            recordSizes = sizes,
            fpsRanges = chars.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )?.toList().orEmpty(),
            supportsVideoStreamUseCase = useCases.contains(
                CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()
            ),
            supportsZsl = capabilities.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING
            ),
            maxZoom = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f,
            stabilizationModes = chars.get(
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
            ) ?: IntArray(0)
        )
    }

    /** The rear camera with the richest HDR support, falling back to whatever exists. */
    fun pickPrimary(cameras: List<CameraInfo>): CameraInfo? {
        if (cameras.isEmpty()) return null
        val back = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val pool = back.ifEmpty { cameras }
        return pool.minByOrNull { info ->
            val rank = HdrMode.entries.indexOf(info.bestHdrMode)
            // Prefer richer HDR, then the camera that can hand us the most pixels.
            rank * 1_000_000_000L - (info.recordSizes.firstOrNull()?.let {
                it.width.toLong() * it.height.toLong()
            } ?: 0L)
        }
    }
}
