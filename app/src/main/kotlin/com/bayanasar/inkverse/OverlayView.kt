package com.bayanasar.inkverse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import kotlin.math.max

/**
 * Draws the captured window bitmap through a colour matrix.
 *
 * No GL. Window screenshots arrive as Bitmaps, so a ColorMatrixColorFilter on the
 * ordinary draw path is enough — which also sidesteps the EGL alpha config that made
 * an earlier GL overlay composite semi-transparently.
 */
class OverlayView(context: Context) : View(context) {

    companion object {
        const val MODE_PASSTHROUGH = 0
        const val MODE_RGB_INVERT = 1
        const val MODE_LUMA_INVERT = 2
        const val MODE_SHAPED = 3

        /** Rec. 601 luma weights, negated so the matrix inverts as it desaturates. */
        private const val LR = -0.299f
        private const val LG = -0.587f
        private const val LB = -0.114f
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    private var frame: Bitmap? = null

    var mode: Int = MODE_LUMA_INVERT
        set(value) {
            field = value
            applyFilter()
            postInvalidate()
        }

    private var black = 0f
    private var white = 1f

    init {
        setBackgroundColor(Color.BLACK)
        applyFilter()
    }

    fun setFrame(bitmap: Bitmap) {
        frame?.takeIf { it !== bitmap }?.recycle()
        frame = bitmap
        postInvalidate()
    }

    fun setLevels(blackPoint: Float, whitePoint: Float) {
        black = blackPoint
        white = whitePoint
        applyFilter()
        postInvalidate()
    }

    private fun applyFilter() {
        val matrix = when (mode) {
            MODE_PASSTHROUGH -> ColorMatrix()

            MODE_RGB_INVERT -> ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )

            MODE_SHAPED -> {
                // Invert to grayscale, then stretch between the black and white points.
                val s = 1f / max(white - black, 0.02f)
                val offset = 255f * (1f - black * s)
                ColorMatrix(
                    floatArrayOf(
                        LR * s, LG * s, LB * s, 0f, offset,
                        LR * s, LG * s, LB * s, 0f, offset,
                        LR * s, LG * s, LB * s, 0f, offset,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            }

            else -> ColorMatrix(
                floatArrayOf(
                    LR, LG, LB, 0f, 255f,
                    LR, LG, LB, 0f, 255f,
                    LR, LG, LB, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
    }

    /**
     * Luminance of the captured frame at five points, for logcat.
     *
     * The overlay is opaque, so when something renders wrong the on-screen controls
     * are exactly what you cannot see. These numbers separate "the filter is wrong"
     * from "the capture is empty" without trusting your eyes on a washed-out panel.
     */
    fun probe(): String {
        val bmp = frame
        if (bmp == null || bmp.isRecycled) return "frame=none"
        val points = listOf(0.15f to 0.2f, 0.35f to 0.4f, 0.5f to 0.5f, 0.65f to 0.6f, 0.85f to 0.8f)
        val samples = points.joinToString(" ") { (fx, fy) ->
            val px = bmp.getPixel((bmp.width * fx).toInt(), (bmp.height * fy).toInt())
            val luma = (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000
            "(${(fx * 100).toInt()}%,${(fy * 100).toInt()}%)=$luma"
        }
        return "frame=${bmp.width}x${bmp.height} mode=$mode luma@ $samples"
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = frame
        if (bmp == null || bmp.isRecycled) {
            canvas.drawColor(Color.BLACK)
            return
        }
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bmp, null, dst, paint)
    }
}
