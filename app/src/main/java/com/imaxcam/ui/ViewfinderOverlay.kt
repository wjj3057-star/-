package com.imaxcam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Non-interactive cinematic framing marks, letterboxed to the same aspect used by the
 * GLES preview. All drawing stays on this overlay; it never touches the video encoder.
 */
class ViewfinderOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var frameAspect: Float = 16f / 9f
        set(value) {
            if (value > 0f && field != value) {
                field = value
                invalidate()
            }
        }

    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(36, 230, 244, 249)
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val corners = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 143, 235, 216)
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0 || frameAspect <= 0f) return
        val actualRatio = width.toFloat() / height
        val frameW = if (frameAspect > actualRatio) width.toFloat()
                     else height * frameAspect
        val frameH = if (frameAspect > actualRatio) width / frameAspect
                     else height.toFloat()
        val left = (width - frameW) / 2f
        val top = (height - frameH) / 2f
        val inset = min(dp(16f), min(frameW, frameH) * 0.035f)
        val x0 = left + inset
        val y0 = top + inset
        val x1 = left + frameW - inset
        val y1 = top + frameH - inset
        if (x1 - x0 < dp(80f) || y1 - y0 < dp(80f)) return

        // A low-opacity rule-of-thirds guide that adapts to IMAX and scope crops.
        for (third in 1..2) {
            val x = x0 + (x1 - x0) * third / 3f
            val y = y0 + (y1 - y0) * third / 3f
            canvas.drawLine(x, y0, x, y1, hairline)
            canvas.drawLine(x0, y, x1, y, hairline)
        }

        val reach = min(dp(20f), min(x1 - x0, y1 - y0) * 0.085f)
        for (x in listOf(x0, x1)) {
            val directionX = if (x == x0) 1 else -1
            for (y in listOf(y0, y1)) {
                val directionY = if (y == y0) 1 else -1
                canvas.drawLine(x, y, x + directionX * reach, y, corners)
                canvas.drawLine(x, y, x, y + directionY * reach, corners)
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}