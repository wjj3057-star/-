package com.imaxcam.record

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlin.concurrent.thread

/**
 * Real multichannel microphone capture, automatically negotiated with input hardware
 * and an AAC encoder. This is NOT a Dolby Atmos encoder or IMAX 12-channel mix.
 *
 * AudioRecord index masks retain the independent input channels exactly as provided
 * by the HAL / USB interface, without fabricating height/surround tracks. If no
 * compatible multichannel capture path exists, the regular stereo/mono AAC path wins.
 */
class AudioEncoder(
    private val context: Context,
    private val config: Config,
    private val muxer: MuxerGate,
    private val onError: (String, Throwable?) -> Unit
) {
    data class Config(
        val sampleRate: Int = 48_000,
        val lowLatency: Boolean = true,
        val spatialAuto: Boolean = true
    )

    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var worker: Thread? = null
    private var codecStarted = false
    private var channels = 0

    @Volatile private var running = false

    val description: String
        get() = if (channels > 0) AudioChannelPolicy.label(channels) else "Audio unavailable"

    @SuppressLint("MissingPermission")
    fun prepare(): Boolean {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val advertisedCounts = devices.flatMap { device ->
            device.channelCounts.filter { it > 0 } +
                device.channelIndexMasks.map { Integer.bitCount(it) }.filter { it > 0 }
        }

        val aacEncoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter {
                it.isEncoder && it.supportedTypes.any { mime ->
                    mime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
                }
            }
        val maxChannels = aacEncoders.maxOfOrNull { info ->
            runCatching {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    .audioCapabilities.maxInputChannelCount
            }.getOrDefault(0)
        } ?: 0

        val sources = if (config.lowLatency) {
            listOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.MIC
            )
        } else {
            listOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)
        }

        for (count in AudioChannelPolicy.candidates(
            config.spatialAuto, advertisedCounts, maxChannels
        )) {
            val candidates = if (count > 2) {
                // Do not claim 4/6/12 independent channels unless a real input endpoint
                // advertises them. An unknown mic array is never implicitly 12-channel.
                devices.filter { device ->
                    device.channelCounts.contains(count) ||
                        device.channelIndexMasks.any { Integer.bitCount(it) == count }
                }.map { it as AudioDeviceInfo? }
            } else {
                listOf<AudioDeviceInfo?>(null)
            }
            if (candidates.isEmpty()) continue

            val encoder = createEncoder(aacEncoders, count) ?: continue
            val input = createRecorder(count, candidates, sources)
            if (input != null) {
                codec = encoder
                record = input
                channels = count
                Log.i(TAG, "Audio selected: ${description}; spatialAuto=${config.spatialAuto}")
                return true
            }
            runCatching { encoder.release() }
        }

        Log.w(TAG, "No compatible microphone / AAC channel configuration; video only")
        return false
    }

    private fun createEncoder(
        encoders: List<MediaCodecInfo>,
        count: Int
    ): MediaCodec? {
        val bitrate = AudioChannelPolicy.bitrate(count)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, count
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(8192, count * 4096))
        }

        for (info in encoders) {
            val supported = runCatching {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    .audioCapabilities
                caps.maxInputChannelCount >= count &&
                    caps.isSampleRateSupported(config.sampleRate)
            }.getOrDefault(false)
            if (!supported) continue

            var encoder: MediaCodec? = null
            try {
                encoder = MediaCodec.createByCodecName(info.name)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return encoder
            } catch (e: Exception) {
                Log.w(TAG, "AAC ${count}ch unavailable on ${info.name}", e)
                runCatching { encoder?.release() }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(
        count: Int,
        devices: List<AudioDeviceInfo?>,
        sources: List<Int>
    ): AudioRecord? {
        // The two-channel query is only a buffer sizing baseline; multichannel
        // AudioRecord must use an index mask rather than a positional input mask.
        val baseline = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minBytes = if (baseline > 0) baseline else 4096
        val bufferBytes = maxOf(
            minBytes * maxOf(1, (count + 1) / 2) * 2,
            config.sampleRate * count * 2 / 25
        )

        for (device in devices) {
            for (source in sources) {
                var candidate: AudioRecord? = null
                try {
                    val fmt = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .apply {
                            when (count) {
                                1 -> setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                2 -> setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                                else -> setChannelIndexMask(AudioChannelPolicy.indexMask(count))
                            }
                        }
                        .build()
                    candidate = AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(bufferBytes)
                        .build()

                    if (candidate.state == AudioRecord.STATE_INITIALIZED &&
                        candidate.channelCount == count &&
                        (device == null || candidate.setPreferredDevice(device))
                    ) {
                        return candidate
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Capture input ${count}ch rejected", e)
                }
                runCatching { candidate?.release() }
            }
        }
        return null
    }

    /** Return false rather than aborting the video when microphone startup fails. */
    fun start(): Boolean {
        val input = record ?: return false
        val encoder = codec ?: return false
        return try {
            encoder.start()
            codecStarted = true
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "microphone failed to start"
            }
            running = true
            worker = thread(name = "imax-audio", priority = Thread.MAX_PRIORITY) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                runCatching { pump(input, encoder) }
                    .onFailure {
                        if (running) onError("Audio capture failed", it)
                        muxer.dropAudioTrack()
                    }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Audio start failed; falling back to video-only", e)
            running = false
            runCatching { input.stop() }
            if (codecStarted) runCatching { encoder.stop() }
            codecStarted = false
            false
        }
    }

    private fun pump(input: AudioRecord, encoder: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * channels
        val chunkBytes = (config.sampleRate / 50 * bytesPerFrame) // 20 ms PCM
        var totalFrames = 0L
        var startNanos = -1L
        var lastPtsUs = System.nanoTime() / 1000L

        while (running) {
            val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val buffer = encoder.getInputBuffer(inputIndex)
                if (buffer != null) {
                    buffer.clear()
                    val read = input.read(
                        buffer,
                        minOf(chunkBytes, buffer.remaining()),
                        AudioRecord.READ_BLOCKING
                    )
                    if (read < 0) error("AudioRecord read failed: ${read}")
                    if (read > 0) {
                        if (startNanos < 0) {
                            startNanos = System.nanoTime() -
                                (read / bytesPerFrame) * 1_000_000_000L / config.sampleRate
                        }
                        val ptsUs = startNanos / 1000L +
                            totalFrames * 1_000_000L / config.sampleRate
                        totalFrames += read / bytesPerFrame
                        lastPtsUs = ptsUs
                        encoder.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                    } else {
                        encoder.queueInputBuffer(inputIndex, 0, 0, lastPtsUs + 1L, 0)
                    }
                } else {
                    encoder.queueInputBuffer(inputIndex, 0, 0, lastPtsUs + 1L, 0)
                }
            }
            drainOutputs(encoder, bufferInfo, 0)
        }

        // Feed AAC EOS and consume every packet before MediaMuxer is finalized.
        val deadline = System.nanoTime() + AUDIO_DRAIN_TIMEOUT_NANOS
        var eosQueued = false
        var eosReceived = false
        while (System.nanoTime() < deadline && !eosReceived) {
            if (!eosQueued) {
                val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    encoder.queueInputBuffer(
                        index, 0, 0, maxOf(lastPtsUs + 1L, System.nanoTime() / 1000L),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    eosQueued = true
                }
            }
            eosReceived = drainOutputs(encoder, bufferInfo, DEQUEUE_TIMEOUT_US)
        }
        if (!eosReceived) Log.w(TAG, "AAC EOS not received before timeout")
    }

    private fun drainOutputs(
        encoder: MediaCodec, info: MediaCodec.BufferInfo, timeoutUs: Long
    ): Boolean {
        var index = encoder.dequeueOutputBuffer(info, timeoutUs)
        while (index >= 0 || index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxer.addAudioTrack(encoder.outputFormat)
            } else {
                val output = encoder.getOutputBuffer(index)
                if (output != null && info.size > 0) {
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    muxer.writeAudio(output, info)
                }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                encoder.releaseOutputBuffer(index, false)
                if (eos) return true
            }
            index = encoder.dequeueOutputBuffer(info, 0)
        }
        return false
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        worker?.join(AUDIO_JOIN_TIMEOUT_MS)
        if (worker?.isAlive == true) Log.w(TAG, "AAC worker still active after join timeout")
        worker = null
        if (codecStarted) runCatching { codec?.stop() }
        codecStarted = false
        // On a very short clip an AAC codec might never expose an output track.
        // The video must still be saved rather than waiting forever for AAC.
        muxer.dropAudioTrack()
    }

    fun release() {
        stop()
        runCatching { record?.release() }
        runCatching { codec?.release() }
        record = null
        codec = null
        channels = 0
    }

    private companion object {
        const val TAG = "AudioEncoder"
        const val DEQUEUE_TIMEOUT_US = 2_000L
        const val AUDIO_DRAIN_TIMEOUT_NANOS = 1_500_000_000L
        const val AUDIO_JOIN_TIMEOUT_MS = 2_500L
    }
}
