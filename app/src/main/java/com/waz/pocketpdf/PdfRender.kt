package com.waz.pocketpdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Page rendering, using Android's own PdfRenderer (hardware-backed and far
 * faster than rendering through PDFBox). PDFBox stays on the writing side.
 */
object PdfRender {

    /** Page size in PDF points — the coordinate space every edit is expressed in. */
    data class PageSize(val widthPt: Float, val heightPt: Float)

    fun open(file: File): PdfRenderer =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))

    /**
     * Renders one page at roughly [targetWidthPx] wide.
     * The bitmap is pre-filled white: PdfRenderer leaves untouched areas
     * transparent, which otherwise shows up as black on screen.
     */
    fun renderPage(file: File, pageIndex: Int, targetWidthPx: Int): Pair<Bitmap, PageSize>? {
        return try {
            open(file).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return null

                renderer.openPage(pageIndex).use { page ->
                    val widthPt = page.width.toFloat()
                    val heightPt = page.height.toFloat()

                    val scale = targetWidthPx / widthPt
                    val w = targetWidthPx.coerceAtLeast(1)
                    val h = (heightPt * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    bitmap to PageSize(widthPt, heightPt)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Small bitmaps for the page grid. */
    fun renderThumbnails(file: File, widthPx: Int = 220): List<Bitmap> {
        val out = mutableListOf<Bitmap>()
        try {
            open(file).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val scale = widthPx / page.width.toFloat()
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out += bmp
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun pageCount(file: File): Int = try {
        open(file).use { it.pageCount }
    } catch (e: Exception) {
        0
    }

    /**
     * Samples the page colour just outside a text box, so a patch drawn over
     * that text blends in instead of leaving a white scar on coloured paper.
     */
    fun sampleBackground(bitmap: Bitmap, leftPx: Int, topPx: Int, rightPx: Int, bottomPx: Int): Int {
        val candidates = listOf(
            (leftPx + rightPx) / 2 to (bottomPx + 6),
            (leftPx + rightPx) / 2 to (topPx - 6),
            leftPx - 6 to (topPx + bottomPx) / 2,
            rightPx + 6 to (topPx + bottomPx) / 2
        )

        val samples = candidates.mapNotNull { (x, y) ->
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) bitmap.getPixel(x, y)
            else null
        }

        if (samples.isEmpty()) return Color.WHITE

        // Median-ish: the most common sample, so one stray dark pixel can't win.
        return samples.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: Color.WHITE
    }

    /**
     * Works out what colour the existing text is drawn in, so a replacement can
     * be written in the same colour. Without this, coloured figures on a receipt
     * come back black and the edit stands out immediately.
     *
     * Everything close to [background] is paper, so it is skipped; of what
     * remains, the most common colour is the ink. Colours are quantised before
     * counting because anti-aliasing spreads a single ink colour across dozens
     * of near-identical shades.
     */
    fun sampleTextColor(
        bitmap: Bitmap,
        leftPx: Int,
        topPx: Int,
        rightPx: Int,
        bottomPx: Int,
        background: Int,
        fallback: Int = Color.BLACK
    ): Int {
        val l = leftPx.coerceIn(0, bitmap.width - 1)
        val t = topPx.coerceIn(0, bitmap.height - 1)
        val r = rightPx.coerceIn(l + 1, bitmap.width)
        val b = bottomPx.coerceIn(t + 1, bitmap.height)

        val counts = HashMap<Int, Int>()

        for (y in t until b) {
            for (x in l until r) {
                val p = bitmap.getPixel(x, y)
                if (distance(p, background) < 90) continue      // paper
                val q = Color.rgb(
                    Color.red(p) / 24 * 24,
                    Color.green(p) / 24 * 24,
                    Color.blue(p) / 24 * 24
                )
                counts[q] = (counts[q] ?: 0) + 1
            }
        }

        // Too few ink pixels means the sample is unreliable — don't guess.
        val best = counts.maxByOrNull { it.value } ?: return fallback
        if (best.value < 6) return fallback
        return best.key
    }

    private fun distance(a: Int, b: Int): Int =
        kotlin.math.abs(Color.red(a) - Color.red(b)) +
            kotlin.math.abs(Color.green(a) - Color.green(b)) +
            kotlin.math.abs(Color.blue(a) - Color.blue(b))
}
