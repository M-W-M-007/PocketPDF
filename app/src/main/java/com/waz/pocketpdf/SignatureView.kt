package com.waz.pocketpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** A pad to draw a signature on with a finger. */
class SignatureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paths = mutableListOf<Path>()
    private var active: Path? = null

    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    val isEmpty: Boolean get() = paths.isEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        paths.forEach { canvas.drawPath(it, paint) }
        active?.let { canvas.drawPath(it, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                active = Path().apply { moveTo(event.x, event.y) }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                active?.lineTo(event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                active?.let { paths += it }
                active = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun clear() {
        paths.clear()
        active = null
        invalidate()
    }

    /** Renders what was drawn, cropped to the ink with a small margin. */
    fun toBitmap(): Bitmap? {
        if (isEmpty || width == 0 || height == 0) return null

        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(full).apply {
            drawColor(Color.WHITE)
            paths.forEach { drawPath(it, paint) }
        }

        // Trim the empty space so the stamp is the signature, not the whole pad.
        var left = width; var top = height; var right = 0; var bottom = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (full.getPixel(x, y) != Color.WHITE) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right <= left || bottom <= top) return full

        val m = 12
        val l = (left - m).coerceAtLeast(0)
        val t = (top - m).coerceAtLeast(0)
        val r = (right + m).coerceAtMost(width - 1)
        val b = (bottom + m).coerceAtMost(height - 1)

        return Bitmap.createBitmap(full, l, t, r - l, b - t)
    }
}
