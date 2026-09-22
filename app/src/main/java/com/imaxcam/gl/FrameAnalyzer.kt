package com.imaxcam.gl

import android.opengl.GLES20
import android.opengl.GLES30
import com.imaxcam.hdr.Hdr10PlusBuilder
import com.imaxcam.hdr.Pq
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Per-frame luminance analysis for HDR10+ dynamic metadata.
 *
 * The frame is reduced on the GPU to a small PQ-coded grid, copied into a pixel buffer
 * object and read back one frame later. Deferring the read-back is the whole point: a
 * synchronous `glReadPixels` would stall the render thread until the GPU drained, costing
 * several milliseconds on every frame. Reading last frame's result instead costs nothing
 * and leaves the metadata one frame behind the picture, which no tone-mapping display can
 * resolve.
 */
class FrameAnalyzer(private val gridWidth: Int = 128) {

    private var gridHeight = 72
    private var fbo = 0
    private var texture = 0
    private val pbos = IntArray(PBO_COUNT)
    private val fences = arrayOfNulls<Long>(PBO_COUNT)
    private var writeIndex = 0
    private var pending = 0

    private lateinit var scratch: ByteArray
    private val histogram = IntArray(256)

    /** Most recent statistics, or null until the first read-back completes. */
    @Volatile
    var latest: com.imaxcam.hdr.FrameLuminance? = null
        private set

    fun setUp(aspectRatio: Double) {
        gridHeight = (gridWidth / aspectRatio).toInt().coerceIn(16, gridWidth)
        scratch = ByteArray(gridWidth * gridHeight * 4)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        fbo = fbos[0]

        val texes = IntArray(1)
        GLES20.glGenTextures(1, texes, 0)
        texture = texes[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES30.glTexStorage2D(
            GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, gridWidth, gridHeight
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST
        )

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texture, 0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "histogram FBO incomplete: 0x${Integer.toHexString(status)}"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        GLES30.glGenBuffers(PBO_COUNT, pbos, 0)
        for (pbo in pbos) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo)
            GLES30.glBufferData(
                GLES30.GL_PIXEL_PACK_BUFFER, scratch.size, null, GLES30.GL_STREAM_READ
            )
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GlUtil.checkGlError("FrameAnalyzer.setUp")
    }

    /**
     * Renders the reduction pass and kicks off an asynchronous read-back.
     *
     * Must be called with a valid GL context current; the caller's framebuffer and
     * viewport are left unbound, so it should be invoked before the surface draws.
     */
    fun capture(
        renderer: CropRenderer,
        textureId: Int,
        stMatrix: FloatArray,
        cropMatrix: FloatArray,
        transfer: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, gridWidth, gridHeight)
        renderer.drawHistogram(
            textureId, stMatrix, cropMatrix,
            1f / sourceWidth, 1f / sourceHeight, transfer
        )

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[writeIndex])
        GLES30.glReadPixels(
            0, 0, gridWidth, gridHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0
        )
        fences[writeIndex] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        writeIndex = (writeIndex + 1) % PBO_COUNT
        pending = minOf(pending + 1, PBO_COUNT)
    }

    /**
     * Consumes the oldest completed read-back, if any, and refreshes [latest].
     *
     * Never blocks: an unfinished fence simply means the statistics stay one more frame
     * old, which is far cheaper than making the render thread wait.
     */
    fun poll(): com.imaxcam.hdr.FrameLuminance? {
        if (pending == 0) return null
        val readIndex = (writeIndex - pending + PBO_COUNT) % PBO_COUNT
        val fence = fences[readIndex] ?: return null

        val status = GLES30.glClientWaitSync(fence, 0, 0L)
        if (status != GLES30.GL_ALREADY_SIGNALED && status != GLES30.GL_CONDITION_SATISFIED) {
            return null
        }
        GLES30.glDeleteSync(fence)
        fences[readIndex] = null
        pending--

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[readIndex])
        val mapped = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER, 0, scratch.size, GLES30.GL_MAP_READ_BIT
        ) as? ByteBuffer
        if (mapped != null) {
            mapped.order(ByteOrder.nativeOrder()).get(scratch)
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        if (mapped == null) return null

        val stats = analyse(scratch)
        latest = stats
        return stats
    }

    private fun analyse(pixels: ByteArray): com.imaxcam.hdr.FrameLuminance {
        java.util.Arrays.fill(histogram, 0)
        var maxRCode = 0
        var maxGCode = 0
        var maxBCode = 0
        var linearSum = 0.0
        val count = gridWidth * gridHeight

        var i = 0
        while (i < pixels.size) {
            val m = pixels[i].toInt() and 0xFF
            val r = pixels[i + 1].toInt() and 0xFF
            val g = pixels[i + 2].toInt() and 0xFF
            val b = pixels[i + 3].toInt() and 0xFF
            histogram[m]++
            if (r > maxRCode) maxRCode = r
            if (g > maxGCode) maxGCode = g
            if (b > maxBCode) maxBCode = b
            linearSum += Pq.LUT8[m]
            i += 4
        }

        val percentiles = DoubleArray(Hdr10PlusBuilder.PERCENTAGES.size)
        var cumulative = 0
        var bin = 0
        for ((index, percentage) in Hdr10PlusBuilder.PERCENTAGES.withIndex()) {
            val threshold = (count.toLong() * percentage / 100L).toInt()
            while (bin < 255 && cumulative < threshold) {
                cumulative += histogram[bin]
                bin++
            }
            percentiles[index] = Pq.LUT8[bin]
        }

        // Fraction of the frame sitting within 10% (in PQ code) of its own peak: the
        // specular highlights a tone-mapping display most wants to know the size of.
        val peakCode = maxOf(maxRCode, maxGCode, maxBCode)
        val brightThreshold = (peakCode * 0.9).toInt()
        var bright = 0
        for (code in brightThreshold..255) bright += histogram[code]

        return com.imaxcam.hdr.FrameLuminance(
            maxR = Pq.LUT8[maxRCode],
            maxG = Pq.LUT8[maxGCode],
            maxB = Pq.LUT8[maxBCode],
            averageMaxRgb = if (count > 0) linearSum / count else 0.0,
            percentiles = percentiles,
            fractionBrightPixels = if (count > 0) bright.toDouble() / count else 0.0
        )
    }

    fun release() {
        fences.forEachIndexed { index, fence ->
            if (fence != null) GLES30.glDeleteSync(fence)
            fences[index] = null
        }
        if (pbos[0] != 0) GLES30.glDeleteBuffers(PBO_COUNT, pbos, 0)
        GlUtil.deleteFramebuffer(fbo, texture)
        fbo = 0
        texture = 0
        pending = 0
        writeIndex = 0
        latest = null
    }

    private companion object {
        const val PBO_COUNT = 3
    }
}
