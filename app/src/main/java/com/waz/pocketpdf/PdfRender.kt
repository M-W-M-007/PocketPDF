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
}
