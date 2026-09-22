package com.imaxcam.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Serialises the video and audio encoders onto one [MediaMuxer].
 *
 * A muxer cannot accept samples until every track is added, so each encoder parks its
 * first samples here until both formats have arrived. Without the park, the audio
 * encoder — which produces output long before the video encoder finishes its first
 * keyframe — would either drop its opening frames or crash the muxer.
 */
class MuxerGate(
    descriptor: FileDescriptor,
    orientationHint: Int,
    private var expectAudio: Boolean
) {

    private val muxer = MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
        setOrientationHint(orientationHint)
    }

    private val lock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var stopped = false
    private var firstPtsUs = -1L

    /** Presentation time of the first sample written, for duration reporting. */
    @Volatile
    var lastPtsUs = 0L
        private set

    @Volatile
    var bytesWritten = 0L
        private set

    fun addVideoTrack(format: MediaFormat) = synchronized(lock) {
        if (videoTrack < 0) videoTrack = muxer.addTrack(format)
        maybeStart()
    }

    fun addAudioTrack(format: MediaFormat) = synchronized(lock) {
        if (audioTrack < 0) audioTrack = muxer.addTrack(format)
        maybeStart()
    }

    /**
     * Declares that no audio will arrive after all, so the video track can start on its
     * own instead of waiting for a microphone that is not there.
     */
    fun dropAudioTrack() = synchronized(lock) {
        expectAudio = false
        maybeStart()
    }

    private fun maybeStart() {
        if (started) return
        if (videoTrack < 0) return
        if (expectAudio && audioTrack < 0) return
        muxer.start()
        started = true
    }

    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
        write(videoTrack, buffer, info)

    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
        write(audioTrack, buffer, info)

    private fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!started || stopped || track < 0) return
            if (info.size <= 0) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return

            // Rebase onto the first sample so the clip always starts at zero, whichever
            // encoder happened to deliver first.
            if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
            val rebased = (info.presentationTimeUs - firstPtsUs).coerceAtLeast(0L)
            val adjusted = MediaCodec.BufferInfo().apply {
                set(info.offset, info.size, rebased, info.flags)
            }
            runCatching { muxer.writeSampleData(track, buffer, adjusted) }
                .onFailure { Log.w(TAG, "writeSampleData failed", it) }
            bytesWritten += info.size
            lastPtsUs = rebased
        }
    }

    fun release() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            if (started) {
                runCatching { muxer.stop() }
                    .onFailure { Log.w(TAG, "muxer.stop failed", it) }
            }
            runCatching { muxer.release() }
        }
    }

    private companion object {
        const val TAG = "MuxerGate"
    }
}
