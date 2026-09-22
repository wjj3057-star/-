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
        running = true
        encoder.start()
        audioRecord.startRecording()

        worker = thread(name = "imax-audio", priority = Thread.MAX_PRIORITY) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runCatching { pump(audioRecord, encoder) }
                .onFailure { if (running) onError("Audio capture failed", it) }
        }
    }

    private fun pump(audioRecord: AudioRecord, encoder: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * config.channelCount
        var totalFrames = 0L
        var startNanos = -1L

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
                        // Derive PTS from the sample count rather than the wall clock, so
                        // drift cannot accumulate over a long take.
                        val ptsUs = (startNanos / 1000) +
                            totalFrames * 1_000_000L / config.sampleRate
                        totalFrames += read / bytesPerFrame
                        encoder.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                    } else {
                        encoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    }
                }
            }

            var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0) {
                val output = encoder.getOutputBuffer(outputIndex)
                if (output != null && bufferInfo.size > 0) {
                    output.position(bufferInfo.offset)
                    output.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeAudio(output, bufferInfo)
                }
                encoder.releaseOutputBuffer(outputIndex, false)
                outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxer.addAudioTrack(encoder.outputFormat)
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        runCatching { record?.stop() }
        runCatching { codec?.stop() }
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
    }
}
