package com.christian.devialetremote

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * A static (non-draggable, matches the mockup) circular arc that reflects the
 * current volume level - a track ring plus a partial copper progress arc,
 * echoing the Expert Pro's physical rotary knob. Volume is still changed via
 * the -/+ buttons; this is a readout, not a touch control.
 *
 * Progress fills up to [MAX_SWEEP_DEGREES] (3/4 of the ring) as [progress]
 * goes from 0 to 1, matching the proportions of the original HTML mockup.
 */
class VolumeDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val START_ANGLE = -225f // top-left-ish start, matches mockup's rotate(-90deg) framing
        private const val MAX_SWEEP_DEGREES = 270f
        private const val STROKE_WIDTH_DP = 10f
    }

    private val strokeWidthPx = STROKE_WIDTH_DP * resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.color_surface_3)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
    }

    private val arcRect = RectF()
    private var progress: Float = 0f // 0f..1f

    fun setProgress(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped != progress) {
            progress = clamped
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeWidthPx / 2f
        arcRect.set(inset, inset, w - inset, h - inset)

        val copperDim = ContextCompat.getColor(context, R.color.color_copper_dim)
        val copperBright = ContextCompat.getColor(context, R.color.color_copper_bright)
        // A diagonal two-color gradient across the arc's bounds - reads
        // cleanly as a warm copper sweep without needing shader matrix math.
        progressPaint.shader = android.graphics.LinearGradient(
            arcRect.left, arcRect.top, arcRect.right, arcRect.bottom,
            copperDim, copperBright, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
        canvas.drawArc(arcRect, START_ANGLE, MAX_SWEEP_DEGREES * progress, false, progressPaint)
    }
}
