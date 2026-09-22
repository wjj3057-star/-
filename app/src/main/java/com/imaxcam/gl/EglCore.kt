package com.imaxcam.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * Colour space an EGL window surface renders into.
 *
 * The camera hands us BT.2020 frames already encoded with the transfer function the
 * dynamic range profile selected, so the render pass is a pure blit as long as the
 * destination surface is tagged with the matching colour space. Tagging it wrongly is
 * what turns HDR captures into washed-out or crushed footage.
 */
enum class GlColorSpace(val eglValue: Int) {
    SRGB(EglCore.EGL_GL_COLORSPACE_SRGB_KHR),
    BT2020_PQ(EglCore.EGL_GL_COLORSPACE_BT2020_PQ_EXT),
    BT2020_HLG(EglCore.EGL_GL_COLORSPACE_BT2020_HLG_EXT)
}

/**
 * Minimal EGL 1.4 wrapper specialised for a 10-bit HDR record path.
 *
 * Deliberately avoids GLSurfaceView: we drive the context from the camera's
 * frame-available callback so a frame is drawn the instant it arrives instead of waiting
 * for the next VSYNC tick, which is worth roughly one display refresh of latency.
 */
class EglCore(tenBit: Boolean, requestedColorSpace: GlColorSpace) {

    companion object {
        private const val TAG = "EglCore"

        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_SRGB_KHR = 0x3089
        const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
        const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540

        private const val EXT_PQ = "EGL_EXT_gl_colorspace_bt2020_pq"
        private const val EXT_HLG = "EGL_EXT_gl_colorspace_bt2020_hlg"
    }

    val display: EGLDisplay
    val context: EGLContext
    private val config: EGLConfig

    /** The colour space actually granted, which may fall back from what was requested. */
    val colorSpace: GlColorSpace

    /** True when a 10-bit (RGB10_A2) config was obtained. */
    val isTenBit: Boolean

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
        colorSpace = when {
            requestedColorSpace == GlColorSpace.BT2020_PQ && extensions.contains(EXT_PQ) ->
                GlColorSpace.BT2020_PQ
            requestedColorSpace == GlColorSpace.BT2020_HLG && extensions.contains(EXT_HLG) ->
                GlColorSpace.BT2020_HLG
            requestedColorSpace == GlColorSpace.BT2020_PQ && extensions.contains(EXT_HLG) ->
                GlColorSpace.BT2020_HLG
            requestedColorSpace == GlColorSpace.SRGB -> GlColorSpace.SRGB
            else -> {
                Log.w(TAG, "HDR colour space unavailable ($extensions); falling back to sRGB")
                GlColorSpace.SRGB
            }
        }

        val tenBitConfig = if (tenBit) chooseConfig(true) else null
        config = tenBitConfig ?: requireNotNull(chooseConfig(false)) { "no usable EGLConfig" }
        isTenBit = tenBitConfig != null
        if (tenBit && tenBitConfig == null) {
            Log.w(TAG, "10-bit EGLConfig unavailable; falling back to 8-bit")
        }

        val attribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attribs, 0)
        checkEglError("eglCreateContext")
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext returned EGL_NO_CONTEXT" }
    }

    private fun chooseConfig(tenBit: Boolean): EGLConfig? {
        val bits = if (tenBit) 10 else 8
        val alpha = if (tenBit) 2 else 8
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, bits,
            EGL14.EGL_GREEN_SIZE, bits,
            EGL14.EGL_BLUE_SIZE, bits,
            EGL14.EGL_ALPHA_SIZE, alpha,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        return if (ok && num[0] > 0) configs[0] else null
    }

    /**
     * Wraps a native window (encoder input surface or a SurfaceView) in an EGL window
     * surface tagged with [colorSpace].
     */
    fun createWindowSurface(surface: Any, colorSpaceOverride: GlColorSpace? = null): EGLSurface {
        require(surface is Surface || surface is SurfaceTexture) {
            "unsupported window type: ${surface.javaClass}"
        }
        val cs = colorSpaceOverride ?: colorSpace
        val attribs = if (cs == GlColorSpace.SRGB) {
            intArrayOf(EGL14.EGL_NONE)
        } else {
            intArrayOf(EGL_GL_COLORSPACE_KHR, cs.eglValue, EGL14.EGL_NONE)
        }
        var eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            // Some drivers reject the colour-space attribute on particular window types.
            Log.w(TAG, "window surface with $cs rejected; retrying without colour space")
            EGL14.eglGetError()
            eglSurface = EGL14.eglCreateWindowSurface(
                display, config, surface, intArrayOf(EGL14.EGL_NONE), 0
            )
        }
        checkEglError("eglCreateWindowSurface")
        check(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            "eglCreateWindowSurface failed"
        }
        return eglSurface
    }

    fun makeCurrent(draw: EGLSurface, read: EGLSurface = draw) {
        check(EGL14.eglMakeCurrent(display, draw, read, context)) { "eglMakeCurrent failed" }
    }

    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    /**
     * Stamps the frame about to be swapped with the camera's capture timestamp.
     *
     * Passing the sensor timestamp straight through keeps encoder PTS locked to real
     * capture time, so audio stays in sync without a correction pass and the recorded
     * cadence survives any render-thread jitter.
     */
    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun releaseSurface(surface: EGLSurface?) {
        if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface)
        }
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            makeNothingCurrent()
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
    }

    private fun checkEglError(op: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            Log.w(TAG, "$op: EGL error 0x${Integer.toHexString(error)}")
        }
    }
}
