package com.imaxcam.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * One MP4 muxer shared by both encoders.
 *
 * Camera and microphone timestamps MUST use the same System.nanoTime() time base;
 * [originUs] is captured at the beginning of the take. In particular, never mix a
 * SurfaceTexture sensor timestamp (which can use BOOTTIME on some devices) with the
 * microphone's MONOTONIC clock: their offset can look like hours of recorded video.
 *
 * The muxer must also preserve samples received before both output formats arrive.
 * Dropping those samples can drop the first video IDR and produce an unplayable file.
 */
class MuxerGate(
    descriptor: FileDescriptor,
    orientationHint: Int,
    private var expectAudio: Boolean,
    private val originUs: Long
) {
    private val muxer = MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
        setOrientationHint(orientationHint)
    }
    private val lock = Any()
    private data class QueuedSample(
        val video: Boolean,
        val data: ByteArray,
        val ptsUs: Long,
        val flags: Int
    )
    private val pending = ArrayDeque<QueuedSample>()
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var stopped = false
    private var failed = false
    private var videoSamples = 0
    private var videoSyncSamples = 0
    private var videoLastPts = -1L
    private var audioLastPts = -1L

    @Volatile var lastPtsUs = 0L
        private set
    @Volatile var bytesWritten = 0L
        private set

    fun addVideoTrack(format: MediaFormat) = synchronized(lock) {
        if (!stopped && videoTrack < 0) videoTrack = muxer.addTrack(format)
        maybeStart()
    }

    fun addAudioTrack(format: MediaFormat) = synchronized(lock) {
        if (!stopped && audioTrack < 0) audioTrack = muxer.addTrack(format)
        maybeStart()
    }

    fun dropAudioTrack() = synchronized(lock) {
        expectAudio = false
        maybeStart()
    }

    private fun maybeStart() {
        if (started || stopped || videoTrack < 0 || (expectAudio && audioTrack < 0)) return
        try {
            muxer.start()
            started = true
            while (pending.isNotEmpty()) {
                val sample = pending.removeFirst()
                writeNow(sample.video, ByteBuffer.wrap(sample.data),
                    sample.data.size, sample.ptsUs, sample.flags)
            }
        } catch (e: Exception) {
            failed = true
            Log.e(TAG, "Could not start/write MP4", e)
        }
    }

    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
        write(true, buffer, info)

    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
        write(false, buffer, info)

    private fun write(video: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (stopped || failed || info.size <= 0 ||
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            // Same MONOTONIC clock for both tracks. Never use the first arriving sample
            // from one track as an epoch for the other track.
            val pts = (info.presentationTimeUs - originUs).coerceAtLeast(0L)
            if (!started) {
                // MediaCodec owns the original buffer; copy before returning it.
                val src = buffer.duplicate()
                src.position(info.offset)
                src.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                src.get(bytes)
                pending.addLast(QueuedSample(video, bytes, pts, info.flags))
                if (pending.size > MAX_PENDING_SAMPLES) {
                    failed = true
                    Log.e(TAG, "Encoder did not provide both track formats in time")
                }
                return
            }
            writeNow(video, buffer, info.size, pts, info.flags, info.offset)
        }
    }

    private fun writeNow(
        video: Boolean,
        buffer: ByteBuffer,
        size: Int,
        ptsUs: Long,
        flags: Int,
        offset: Int = 0
    ) {
        val track = if (video) videoTrack else audioTrack
        if (track < 0) return
        val last = if (video) videoLastPts else audioLastPts
        val monotonic = maxOf(ptsUs, last + 1)
        val adjusted = MediaCodec.BufferInfo().apply { set(offset, size, monotonic, flags) }
        try {
            muxer.writeSampleData(track, buffer, adjusted)
            if (video) {
                videoLastPts = monotonic
                videoSamples++
                if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) videoSyncSamples++
            } else {
                audioLastPts = monotonic
            }
            bytesWritten += size
            lastPtsUs = maxOf(lastPtsUs, monotonic)
        } catch (e: Exception) {
            failed = true
            Log.e(TAG, "MP4 writeSampleData failed", e)
        }
    }

    /** Returns false when the MP4 could not be finalized or contains no video keyframe. */
    fun release(): Boolean = synchronized(lock) {
        if (stopped) return@synchronized !failed && videoSamples > 0 && videoSyncSamples > 0
        stopped = true
        if (started) {
            try {
                muxer.stop()
            } catch (e: Exception) {
                failed = true
                Log.e(TAG, "MP4 finalization failed", e)
            }
        } else {
            failed = true
        }
        runCatching { muxer.release() }
        pending.clear()
        !failed && videoSamples > 0 && videoSyncSamples > 0
    }

    private companion object {
        const val TAG = "MuxerGate"
        const val MAX_PENDING_SAMPLES = 240
    }
}
