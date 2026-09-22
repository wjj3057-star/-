package com.imaxcam.gl

import android.opengl.GLES20
import android.opengl.GLES30

/**
 * Draws the camera's external texture into whatever surface is currently bound, applying
 * the IMAX crop through the texture matrix.
 *
 * One program, one 4-vertex draw. The crop, the rotation and the mirror all collapse into
 * a single 4x4 matrix uploaded once per frame, so changing aspect ratio mid-session costs
 * nothing at draw time.
 */
class CropRenderer {

    private val quad = GlUtil.floatBuffer(
        floatArrayOf(
            // x, y, u, v
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
    )

    private var passthrough = 0
    private var tonemap = 0
    private var histogram = 0
    private var vao = 0
    private var vbo = 0

    private var uStPassthrough = -1
    private var uCropPassthrough = -1
    private var uStTonemap = -1
    private var uCropTonemap = -1
    private var uTransferTonemap = -1
    private var uPeakTonemap = -1
    private var uStHistogram = -1
    private var uCropHistogram = -1
    private var uTapHistogram = -1
    private var uTransferHistogram = -1

    fun setUp() {
        passthrough = GlUtil.createProgram(Shaders.VERTEX, Shaders.FRAGMENT_PASSTHROUGH)
        tonemap = GlUtil.createProgram(Shaders.VERTEX, Shaders.FRAGMENT_TONEMAP)
        histogram = GlUtil.createProgram(Shaders.VERTEX, Shaders.FRAGMENT_HISTOGRAM)

        uStPassthrough = GLES20.glGetUniformLocation(passthrough, "uStMatrix")
        uCropPassthrough = GLES20.glGetUniformLocation(passthrough, "uCropMatrix")
        uStTonemap = GLES20.glGetUniformLocation(tonemap, "uStMatrix")
        uCropTonemap = GLES20.glGetUniformLocation(tonemap, "uCropMatrix")
        uTransferTonemap = GLES20.glGetUniformLocation(tonemap, "uTransfer")
        uPeakTonemap = GLES20.glGetUniformLocation(tonemap, "uPeakNits")
        uStHistogram = GLES20.glGetUniformLocation(histogram, "uStMatrix")
        uCropHistogram = GLES20.glGetUniformLocation(histogram, "uCropMatrix")
        uTapHistogram = GLES20.glGetUniformLocation(histogram, "uTapOffset")
        uTransferHistogram = GLES20.glGetUniformLocation(histogram, "uTransfer")

        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, quad.capacity() * 4, quad, GLES30.GL_STATIC_DRAW
        )

        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        vao = arrays[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glEnableVertexAttribArray(Shaders.ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(
            Shaders.ATTRIB_POSITION, 2, GLES30.GL_FLOAT, false, 16, 0
        )
        GLES30.glEnableVertexAttribArray(Shaders.ATTRIB_TEXCOORD)
        GLES30.glVertexAttribPointer(
            Shaders.ATTRIB_TEXCOORD, 2, GLES30.GL_FLOAT, false, 16, 8
        )
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GlUtil.checkGlError("CropRenderer.setUp")
    }

    /** Straight HDR blit: the destination surface carries the same colour space. */
    fun drawPassthrough(textureId: Int, stMatrix: FloatArray, cropMatrix: FloatArray) {
        GLES20.glUseProgram(passthrough)
        GLES20.glUniformMatrix4fv(uStPassthrough, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(uCropPassthrough, 1, false, cropMatrix, 0)
        drawQuad(textureId)
    }

    /** SDR preview blit for displays without BT.2020 PQ support. */
    fun drawToneMapped(
        textureId: Int,
        stMatrix: FloatArray,
        cropMatrix: FloatArray,
        transfer: Int,
        peakNits: Float
    ) {
        GLES20.glUseProgram(tonemap)
        GLES20.glUniformMatrix4fv(uStTonemap, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(uCropTonemap, 1, false, cropMatrix, 0)
        GLES20.glUniform1i(uTransferTonemap, transfer)
        GLES20.glUniform1f(uPeakTonemap, peakNits)
        drawQuad(textureId)
    }

    /** Downsample into the currently bound histogram FBO. */
    fun drawHistogram(
        textureId: Int,
        stMatrix: FloatArray,
        cropMatrix: FloatArray,
        tapOffsetX: Float,
        tapOffsetY: Float,
        transfer: Int
    ) {
        GLES20.glUseProgram(histogram)
        GLES20.glUniformMatrix4fv(uStHistogram, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(uCropHistogram, 1, false, cropMatrix, 0)
        GLES20.glUniform2f(uTapHistogram, tapOffsetX, tapOffsetY)
        GLES20.glUniform1i(uTransferHistogram, transfer)
        drawQuad(textureId)
    }

    private fun drawQuad(textureId: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GlUtil.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glBindVertexArray(vao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        if (passthrough != 0) GLES20.glDeleteProgram(passthrough)
        if (tonemap != 0) GLES20.glDeleteProgram(tonemap)
        if (histogram != 0) GLES20.glDeleteProgram(histogram)
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        passthrough = 0
        tonemap = 0
        histogram = 0
        vbo = 0
        vao = 0
    }
}
