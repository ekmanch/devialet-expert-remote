package com.christian.devialetremote

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.atan2

/**
 * A draggable circular arc that both shows and sets the current volume level -
 * a track ring plus a partial copper progress arc, echoing the Expert Pro's
 * physical rotary knob. Touching anywhere on the ring and dragging around it
 * moves the value; the -/+ buttons still work independently.
 *
 * Progress fills up to [MAX_SWEEP_DEGREES] (3/4 of the ring) as [progress]
 * goes from 0 to 1, matching the proportions of the original HTML mockup -
 * the remaining quarter-turn gap (bottom-right) is dead space, same as most
 * physical radial controls.
 */
class VolumeDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        // Canvas angle convention: 0deg = 3 o'clock, clockwise positive.
        // -225deg (= 135deg normalized) puts the start at roughly 7-8 o'clock,
        // sweeping 270deg clockwise up to 45deg (roughly 4-5 o'clock) - the
        // same "3/4 turn with a gap at the bottom" shape as the mockup.
        private const val START_ANGLE = -225f
        private const val MAX_SWEEP_DEGREES = 270f
        private const val STROKE_WIDTH_DP = 10f
    }

    /** Fired continuously while dragging, for live UI feedback (no network calls here). */
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((fraction: Float) -> Unit)? = null
    /** Fired once when the finger lifts - the right moment to actually send the volume command. */
    var onDragEnd: ((fraction: Float) -> Unit)? = null

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
    private var isDragging = false

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

    /**
     * Converts a touch point into a 0f..1f fraction along the arc's sweep.
     * Touches that land in the ~90deg gap at the bottom are snapped to
     * whichever end (0 or 1) they're closer to, rather than jumping to an
     * arbitrary value - standard behavior for radial sliders with a gap.
     */
    private fun angleToFraction(touchX: Float, touchY: Float): Float {
        val cx = width / 2f
        val cy = height / 2f
        val dx = touchX - cx
        val dy = touchY - cy

        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f // normalize to 0..360

        val startNormalized = ((START_ANGLE % 360f) + 360f) % 360f
        var relative = angle - startNormalized
        if (relative < 0f) relative += 360f

        return if (relative <= MAX_SWEEP_DEGREES) {
            (relative / MAX_SWEEP_DEGREES).coerceIn(0f, 1f)
        } else {
            // In the gap - snap to whichever end is closer.
            val distanceToEnd = relative - MAX_SWEEP_DEGREES
            val distanceToStart = 360f - relative
            if (distanceToEnd < distanceToStart) 1f else 0f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // This view normally lives inside a ScrollView - claim the
                // gesture immediately so a drag isn't stolen as a page scroll
                // partway through (dragging around a circle inherently moves
                // vertically too).
                parent?.requestDisallowInterceptTouchEvent(true)
                isDragging = true
                onDragStart?.invoke()
                val fraction = angleToFraction(event.x, event.y)
                setProgress(fraction)
                onDrag?.invoke(fraction)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                val fraction = angleToFraction(event.x, event.y)
                setProgress(fraction)
                onDrag?.invoke(fraction)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) return false
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onDragEnd?.invoke(progress)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onDragEnd?.invoke(progress)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        // Handles the accessibility-click side of things (announcing the
        // "clicked" event, invoking any OnClickListener). The actual drag
        // gesture logic above is unrelated to this and unaffected by it.
        super.performClick()
        return true
    }
}
