package com.waz.pocketpdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent overlay sitting on the page image. The user drags a rectangle over
 * the area to erase. Only shown in erase mode; invisible and non-interactive
 * otherwise, so ordinary taps reach the page underneath.
 */
class RegionSelectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onRegion: ((RectF) -> Unit)? = null

    private var startX = 0f
    private var startY = 0f
    private var currX = 0f
    private var currY = 0f
    private var dragging = false

    private val fillPaint = Paint().apply {
        color = Color.argb(70, 255, 80, 80)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#FF5050")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!dragging) return
        val r = rect()
        canvas.drawRect(r, fillPaint)
        canvas.drawRect(r, borderPaint)
    }

    private fun rect() = RectF(
        min(startX, currX),
        min(startY, currY),
        max(startX, currX),
        max(startY, currY)
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                currX = event.x; currY = event.y
                dragging = true
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                currX = event.x; currY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val r = rect()
                invalidate()
                if (abs(r.width()) > 8 && abs(r.height()) > 6) onRegion?.invoke(r)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
