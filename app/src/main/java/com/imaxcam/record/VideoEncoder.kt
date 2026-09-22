package com.imaxcam.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Surface
import com.imaxcam.camera.HdrMode
import com.imaxcam.camera.TransferFunction
import com.imaxcam.hdr.Hdr10PlusBuilder
import com.imaxcam.hdr.HdrStaticInfo
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HEVC Main 10 encoder with an input Surface, configured for HDR10+ and real-time use.
 *
 * Surface input is what makes the pipeline zero-copy: the GPU renders the cropped frame
 * straight into the encoder's buffer, so no frame ever crosses into application memory.
 */
class VideoEncoder(
    private val config: Config,
    private val handler: Handler,
    private val muxer: MuxerGate,
    private val onError: (String, Throwable?) -> Unit
) {

    data class Config(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val hdrMode: HdrMode,
        val keyFrameIntervalSeconds: Float = 1f,
        val targetDisplayNits: Int = 1000,
        val lowLatency: Boolean = true
    )

    private lateinit var codec: MediaCodec

    /** The surface the render thread draws into. Valid between [prepare] and [release]. */
    lateinit var inputSurface: Surface
        private set

    @Volatile
    private var running = false

    @Volatile
    private var endOfStream = false

    @Volatile
    var framesEncoded = 0L
        private set

    /** True when the codec actually accepted the HDR10+ profile. */
    var hdr10PlusActive = false
        private set

    private val parameterBundle = Bundle()
    private val drained = CountDownLatch(1)

    fun prepare() {
        val format = buildFormat()
        val codecName = selectCodec()
        codec = if (codecName != null) {
            MediaCodec.createByCodecName(codecName)
        } else {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        }

        codec.setCallback(callback, handler)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Most commonly the device advertises Main10 but not the HDR10+ profile.
            // Dropping to plain Main10 keeps the 10-bit PQ picture and the static
            // metadata; only the per-frame curve is lost.
            Log.w(TAG, "HDR10+ configure failed, retrying as Main10", e)
            hdr10PlusActive = false
            codec.reset()
            codec.setCallback(callback, handler)
            codec.configure(
                buildFormat(forceHdr10Plus = false), null, null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
        }
        inputSurface = codec.createInputSurface()
    }

    private fun buildFormat(forceHdr10Plus: Boolean = true): MediaFormat {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_HEVC, config.width, config.height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyFrameIntervalSeconds)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )

            // Encode no faster than needed but tell the codec we are real-time so it gets
            // the clocks it needs; PRIORITY 0 is the realtime class.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, config.fps)

            if (config.lowLatency) {
                // B-frames are the single biggest source of encoder delay: they force the
                // codec to hold frames back until a later one arrives. Zero of them means
                // output order equals input order and a frame leaves as soon as it is
                // coded.
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }

        if (config.hdrMode.isHdr) {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            format.setInteger(
                MediaFormat.KEY_COLOR_TRANSFER,
                if (config.hdrMode.transfer == TransferFunction.HLG) {
                    MediaFormat.COLOR_TRANSFER_HLG
                } else {
                    MediaFormat.COLOR_TRANSFER_ST2084
                }
            )
            format.setByteBuffer(
                MediaFormat.KEY_HDR_STATIC_INFO,
                HdrStaticInfo.build(maxDisplayNits = config.targetDisplayNits)
            )

            val wantsHdr10Plus = forceHdr10Plus && config.hdrMode == HdrMode.HDR10_PLUS
            format.setInteger(
                MediaFormat.KEY_PROFILE,
                if (wantsHdr10Plus) {
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                } else {
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                }
            )
            format.setInteger(
                MediaFormat.KEY_LEVEL,
                levelFor(config.width, config.height, config.fps)
            )
            if (wantsHdr10Plus) {
                // Seed the stream so even the opening GOP carries a valid ST 2094-40
                // message, before the first histogram read-back lands.
                format.setByteBuffer(
                    MediaFormat.KEY_HDR10_PLUS_INFO,
                    ByteBuffer.wrap(Hdr10PlusBuilder.neutral(config.targetDisplayNits))
                )
                hdr10PlusActive = true
            }
        }
        return format
    }

    private fun levelFor(width: Int, height: Int, fps: Int): Int {
        val lumaSamples = width.toLong() * height.toLong()
        val lumaRate = lumaSamples * fps
        return when {
            lumaRate <= 534_773_760L -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5
            lumaRate <= 1_069_547_520L -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
            lumaRate <= 2_139_095_040L -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52
            else -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6
        }
    }

    /**
     * Finds a hardware encoder that can actually do this resolution.
     *
     * The IMAX crops are unusual sizes — 3088x2160 or 3840x2022 — and a codec that
     * happily reports UHD support may still reject them, so the size is checked against
     * the codec's own capabilities rather than assumed.
     */
    private fun selectCodec(): String? {
        var hdr10PlusHardware: String? = null
        var main10Hardware: String? = null
        var anyHardware: String? = null
        var anySoftware: String? = null

        for (codecInfo in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (!codecInfo.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                }
            ) continue

            val caps = runCatching {
                codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            }.getOrNull() ?: continue

            val video = caps.videoCapabilities ?: continue
            if (!runCatching { video.isSizeSupported(config.width, config.height) }
                    .getOrDefault(false)
            ) continue

            val profiles = caps.profileLevels.map { it.profile }
            val hdr10Plus = profiles.contains(
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            )
            val main10 = hdr10Plus || profiles.any {
                it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
            }
            if (config.hdrMode.isHdr && !main10) continue

            if (codecInfo.isHardwareAccelerated) {
                if (hdr10Plus && hdr10PlusHardware == null) hdr10PlusHardware = codecInfo.name
                if (main10 && main10Hardware == null) main10Hardware = codecInfo.name
                if (anyHardware == null) anyHardware = codecInfo.name
            } else if (anySoftware == null) {
                anySoftware = codecInfo.name
            }
        }

        // A codec that advertises HDR10+ wins, then any 10-bit hardware encoder; software
        // HEVC at these resolutions is a last resort and will not hold real time.
        return hdr10PlusHardware ?: main10Hardware ?: anyHardware ?: anySoftware
    }

    fun start() {
        running = true
        codec.start()
    }

    /**
     * Attaches ST 2094-40 metadata to the next frame submitted through the input surface.
     *
     * Called from the render thread immediately before the frame is swapped, so the
     * metadata and the picture it describes stay together through the codec.
     */
    fun setHdr10PlusMetadata(payload: ByteArray) {
        if (!running || !hdr10PlusActive) return
        parameterBundle.clear()
        parameterBundle.putByteArray(MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO, payload)
        runCatching { codec.setParameters(parameterBundle) }
            .onFailure {
                Log.w(TAG, "HDR10+ metadata rejected; disabling dynamic metadata", it)
                hdr10PlusActive = false
            }
    }

    fun requestKeyFrame() {
        if (!running) return
        val bundle = Bundle()
        bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        runCatching { codec.setParameters(bundle) }
    }

    /** Signals the codec that no more frames are coming and waits for it to drain. */
    fun signalEndOfStream() {
        if (!running || endOfStream) return
        endOfStream = true
        runCatching { codec.signalEndOfInputStream() }
    }

    /**
     * Blocks until the codec has emitted its end-of-stream buffer, so the trailing frames
     * reach the muxer before the file is closed.
     */
    fun awaitDrain(timeoutMs: Long): Boolean =
        runCatching { drained.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)

    fun release() {
        running = false
        drained.countDown()
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (::inputSurface.isInitialized) inputSurface.release() }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface input: the codec pulls frames itself, nothing to do here.
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            val buffer = runCatching { codec.getOutputBuffer(index) }.getOrNull()
            if (buffer != null && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                muxer.writeVideo(buffer, info)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) framesEncoded++
            }
            runCatching { codec.releaseOutputBuffer(index, false) }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                running = false
                drained.countDown()
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            onError("Video encoder error: ${e.diagnosticInfo}", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            muxer.addVideoTrack(format)
        }
    }

    private companion object {
        const val TAG = "VideoEncoder"
    }
}
