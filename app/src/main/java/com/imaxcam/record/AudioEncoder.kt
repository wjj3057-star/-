package com.imaxcam.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlin.concurrent.thread

/**
 * AAC-LC audio captured straight off [AudioRecord] and pushed into a codec.
 *
 * The record buffer is deliberately small — a couple of the driver's minimum blocks —
 * because every extra millisecond of audio buffering is a millisecond the video has to
 * wait for at mux time. Timestamps come from [AudioRecord.getTimestamp] where the driver
 * provides them, so audio and video share the same monotonic clock as the sensor.
 */
class AudioEncoder(
    private val config: Config,
    private val muxer: MuxerGate,
    private val onError: (String, Throwable?) -> Unit
) {

    data class Config(
        val sampleRate: Int = 48_000,
        val channelCount: Int = 2,
        val bitrate: Int = 256_000,
        val lowLatency: Boolean = true
    )

    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    fun prepare(): Boolean {
        val channelMask = if (config.channelCount == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioRecord reports no usable buffer size; recording video only")
            return false
        }
        // Two driver blocks: enough to absorb scheduling jitter, small enough that the
        // audio path never becomes the thing the muxer waits on.
        val bufferBytes = minBuffer * 2

        // UNPROCESSED bypasses the platform's voice-processing chain and is the
        // shortest path off the mic, but it is optional hardware; CAMCORDER is the
        // guaranteed video source and MIC the universal fallback.
        val sources = if (config.lowLatency) {
            listOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.MIC
            )
        } else {
            listOf(
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.MIC
            )
        }

        val audioRecord = sources.firstNotNullOfOrNull { source ->
            val candidate = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(config.sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            }.getOrNull()

            when {
                candidate == null -> null
                candidate.state == AudioRecord.STATE_INITIALIZED -> candidate
                else -> {
                    Log.d(TAG, "audio source $source unavailable")
                    candidate.release()
                    null
                }
            }
        }

        if (audioRecord == null) {
            Log.w(TAG, "no usable audio source; recording video only")
            return false
        }
        record = audioRecord

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferBytes)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        codec = runCatching {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }.getOrElse {
            onError("Audio encoder unavailable", it)
            audioRecord.release()
            record = null
            return false
        }
        return true
    }

    fun start() {
        val audioRecord = record ?: return
        val encoder = codec ?: return
        encoder.start()
        audioRecord.startRecording()
        running = true

        worker = thread(name = "imax-audio", priority = Thread.MAX_PRIORITY) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runCatching { pump(audioRecord, encoder) }
                .onFailure {
                    if (running) onError("Audio capture failed", it)
                    // A failed microphone must never indefinitely block the video muxer.
                    muxer.dropAudioTrack()
                }
        }
    }

    private fun pump(audioRecord: AudioRecord, encoder: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * config.channelCount
        var totalFrames = 0L
        var startNanos = -1L
        var lastPtsUs = System.nanoTime() / 1000L

        while (running) {
            val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val input = encoder.getInputBuffer(inputIndex)
                if (input != null) {
                    input.clear()
                    val read = audioRecord.read(input, input.capacity())
                    if (read > 0) {
                        if (startNanos < 0) {
                            startNanos = System.nanoTime() -
                                (read / bytesPerFrame) * 1_000_000_000L / config.sampleRate
                        }
                        // AAC and EGL video timestamps both use CLOCK_MONOTONIC here.
                        val ptsUs = startNanos / 1000L +
                            totalFrames * 1_000_000L / config.sampleRate
                        totalFrames += read / bytesPerFrame
                        lastPtsUs = ptsUs
                        encoder.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                    } else {
                        encoder.queueInputBuffer(
                            inputIndex, 0, 0, lastPtsUs + 1L, 0
                        )
                    }
                } else {
                    encoder.queueInputBuffer(inputIndex, 0, 0, lastPtsUs + 1L, 0)
                }
            }
            drainOutputs(encoder, bufferInfo, 0)
        }

        // Queue a proper AAC EOS after the last PCM sample. Stopping MediaCodec before
        // this drain used to truncate audio and could leave the MP4 unfinalizable.
        val eosDeadline = System.nanoTime() + AUDIO_DRAIN_TIMEOUT_NANOS
        var eosQueued = false
        var eosReceived = false
        while (System.nanoTime() < eosDeadline && !eosReceived) {
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

    /** Returns true after the codec's final output buffer has been received. */
    private fun drainOutputs(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        timeoutUs: Long
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
        // Interrupt a blocked read before joining; then let the worker send/drain EOS.
        runCatching { record?.stop() }
        worker?.join(AUDIO_JOIN_TIMEOUT_MS)
        if (worker?.isAlive == true) Log.w(TAG, "Audio worker did not finish in time")
        worker = null
        runCatching { codec?.stop() }
        // A zero-length take may never emit an AAC output format. Avoid holding
        // video samples hostage waiting for a track that will never appear.
        muxer.dropAudioTrack()
    }

    fun release() {
        stop()
        runCatching { record?.release() }
        runCatching { codec?.release() }
        record = null
        codec = null
    }

    private companion object {
        const val TAG = "AudioEncoder"
        const val DEQUEUE_TIMEOUT_US = 2_000L
        const val AUDIO_DRAIN_TIMEOUT_NANOS = 1_500_000_000L
        const val AUDIO_JOIN_TIMEOUT_MS = 2_000L
    }
}
