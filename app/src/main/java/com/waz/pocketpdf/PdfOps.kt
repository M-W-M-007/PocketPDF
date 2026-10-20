package com.waz.pocketpdf

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * Every PDF-writing operation in the app.
 *
 * On editing existing text: PDF stores glyph positions, not paragraphs, and the
 * embedded font is usually subsetted — so typing a character the subset does not
 * contain produces a blank. Rewriting the content stream in place is therefore
 * unreliable. Instead this covers the old text with a rectangle painted in the
 * page's own background colour and draws the replacement on top with a standard
 * font. That is what mainstream editors do for names, dates and amounts, and it
 * cannot silently corrupt the file.
 */
object PdfOps {

    /** One run of text found on a page, with its position in top-left coordinates. */
    data class TextHit(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val fontSize: Float
    ) {
        fun contains(x: Float, y: Float, pad: Float = 4f) =
            x >= left - pad && x <= right + pad && y >= top - pad && y <= bottom + pad

        val width get() = right - left
        val height get() = bottom - top
    }

    // ------------------------------------------------------------------ read

    /**
     * Finds the text runs on one page. Runs are grouped by line so that tapping
     * a word offers the whole line, which is what people usually want to retype.
     */
    fun textHits(file: File, pageIndex: Int): List<TextHit> {
        val hits = mutableListOf<TextHit>()

        PDDocument.load(file).use { doc ->
            if (pageIndex !in 0 until doc.numberOfPages) return emptyList()

            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, positions: List<TextPosition>) {
                    if (text.isBlank() || positions.isEmpty()) return

                    var left = Float.MAX_VALUE
                    var top = Float.MAX_VALUE
                    var right = Float.MIN_VALUE
                    var bottom = Float.MIN_VALUE
                    var size = 0f

                    for (p in positions) {
                        left = minOf(left, p.xDirAdj)
                        top = minOf(top, p.yDirAdj - p.heightDir)
                        right = maxOf(right, p.xDirAdj + p.widthDirAdj)
                        bottom = maxOf(bottom, p.yDirAdj)
                        size = maxOf(size, p.fontSizeInPt)
                    }

                    hits += TextHit(
                        text = text.trim(),
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        fontSize = if (size > 0.5f) size else 11f
                    )
                }
            }

            stripper.sortByPosition = true
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.getText(doc)
        }

        return hits
    }

    fun pageCount(file: File): Int =
        PDDocument.load(file).use { it.numberOfPages }

    fun extractAllText(file: File): String =
        PDDocument.load(file).use { PDFTextStripper().getText(it) }

    // ------------------------------------------------------------------ edit

    /**
     * Covers [hit] with [backgroundColor] and writes [newText] in its place.
     * Passing the sampled page colour keeps the patch invisible on coloured paper.
     */
    fun replaceText(
        source: File,
        pageIndex: Int,
        hit: TextHit,
        newText: String,
        backgroundColor: Int = Color.WHITE,
        textColor: Int = Color.BLACK
    ): Result<File> = edit(source) { doc ->
        val page = doc.getPage(pageIndex)
        val pageHeight = page.mediaBox.height

        PDPageContentStream(
            doc, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            // A little padding, because glyph boxes sit tight against ascenders.
            val pad = 1.5f
            fillRect(
                cs,
                hit.left - pad,
                pageHeight - hit.bottom - pad,
                hit.width + pad * 2,
                hit.height + pad * 2,
                backgroundColor
            )

            if (newText.isNotBlank()) {
                drawText(
                    cs = cs,
                    text = newText,
                    xPt = hit.left,
                    baselinePt = pageHeight - hit.bottom + hit.height * 0.18f,
                    size = fitSize(newText, hit),
                    color = textColor
                )
            }
        }
    }

    /**
     * Places new text at an arbitrary point. [topY] is measured from the page top.
     *
     * With [coverBackground], a patch is painted under the text first, sized to
     * the text itself — otherwise writing over existing content leaves both
     * visible on top of each other. The patch is filled only, never stroked, so
     * it has no outline to give it away.
     */
    fun addText(
        source: File,
        pageIndex: Int,
        xPt: Float,
        topY: Float,
        text: String,
        size: Float = 12f,
        textColor: Int = Color.BLACK,
        coverBackground: Boolean = false,
        backgroundColor: Int = Color.WHITE
    ): Result<File> = edit(source) { doc ->
        val page = doc.getPage(pageIndex)
        val pageHeight = page.mediaBox.height

        PDPageContentStream(
            doc, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            val lines = text.split("\n")

            if (coverBackground) {
                val widest = lines.maxOfOrNull { line ->
                    PDType1Font.HELVETICA.getStringWidth(sanitize(line)) / 1000f * size
                } ?: 0f
                val blockHeight = size * 1.2f * lines.size
                val pad = size * 0.25f

                fillRect(
                    cs,
                    xPt - pad,
                    pageHeight - topY - blockHeight - pad * 0.5f,
                    widest + pad * 2,
                    blockHeight + pad,
                    backgroundColor
                )
            }

            drawText(cs, text, xPt, pageHeight - topY - size, size, textColor)
        }
    }

    /**
     * Paints over a rectangle with [color] — the eraser. Coordinates are in PDF
     * points with [topY] measured from the page top.
     */
    fun coverArea(
        source: File,
        pageIndex: Int,
        xPt: Float,
        topY: Float,
        widthPt: Float,
        heightPt: Float,
        color: Int
    ): Result<File> = edit(source) { doc ->
        val page = doc.getPage(pageIndex)
        val pageHeight = page.mediaBox.height

        PDPageContentStream(
            doc, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            fillRect(cs, xPt, pageHeight - topY - heightPt, widthPt, heightPt, color)
        }
    }

    /** Stamps a bitmap (a drawn signature, a stamp) onto a page. */
    fun addImage(
        source: File,
        pageIndex: Int,
        bitmap: Bitmap,
        xPt: Float,
        topY: Float,
        widthPt: Float
    ): Result<File> = edit(source) { doc ->
        val page = doc.getPage(pageIndex)
        val pageHeight = page.mediaBox.height

        val image = JPEGFactory.createFromImage(doc, flattenOnWhite(bitmap))
        val ratio = bitmap.height.toFloat() / bitmap.width.toFloat()
        val heightPt = widthPt * ratio

        PDPageContentStream(
            doc, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.drawImage(image, xPt, pageHeight - topY - heightPt, widthPt, heightPt)
        }
    }

    // ------------------------------------------------------------ page tools

    fun rotatePages(source: File, pages: Set<Int>, degrees: Int): Result<File> = edit(source) { doc ->
        pages.filter { it in 0 until doc.numberOfPages }.forEach { i ->
            val page = doc.getPage(i)
            page.rotation = ((page.rotation + degrees) % 360 + 360) % 360
        }
    }

    fun deletePages(source: File, pages: Set<Int>): Result<File> = edit(source) { doc ->
        if (pages.size >= doc.numberOfPages) error("Saare pages delete nahi kar sakte.")
        // Descending, so earlier indices stay valid as pages are removed.
        pages.sortedDescending()
            .filter { it in 0 until doc.numberOfPages }
            .forEach { doc.removePage(it) }
    }

    /** Builds a new document containing [order] only, in that exact sequence. */
    fun reorderOrExtract(source: File, order: List<Int>): Result<File> {
        return try {
            val out = File(source.parentFile, "op-${System.currentTimeMillis()}.pdf")

            PDDocument.load(source).use { src ->
                PDDocument().use { dest ->
                    order.filter { it in 0 until src.numberOfPages }
                        .forEach { dest.addPage(src.getPage(it)) }

                    if (dest.numberOfPages == 0) error("Koi page select nahi hua.")
                    dest.save(out)
                }
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun merge(parts: List<File>, workDir: File): Result<File> {
        return try {
            val out = File(workDir, "merged-${System.currentTimeMillis()}.pdf")
            val merger = PDFMergerUtility()
            merger.destinationFileName = out.absolutePath
            parts.forEach { merger.addSource(it) }
            merger.mergeDocuments(null)
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Splits into one file per chunk of [pagesPerFile] pages. */
    fun split(source: File, pagesPerFile: Int, workDir: File): Result<List<File>> {
        return try {
            val outputs = mutableListOf<File>()

            PDDocument.load(source).use { src ->
                var start = 0
                var part = 1
                while (start < src.numberOfPages) {
                    val end = minOf(start + pagesPerFile, src.numberOfPages)
                    val out = File(workDir, "part-$part.pdf")

                    PDDocument().use { dest ->
                        for (i in start until end) dest.addPage(src.getPage(i))
                        dest.save(out)
                    }

                    outputs += out
                    start = end
                    part++
                }
            }
            Result.success(outputs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun imagesToPdf(bitmaps: List<Bitmap>, out: File): Result<File> {
        return try {
            PDDocument().use { doc ->
                bitmaps.forEach { bmp ->
                    val page = PDPage(PDRectangle(bmp.width.toFloat(), bmp.height.toFloat()))
                    doc.addPage(page)

                    val image = JPEGFactory.createFromImage(doc, flattenOnWhite(bmp))
                    PDPageContentStream(doc, page).use { cs ->
                        cs.drawImage(image, 0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
                    }
                }
                if (doc.numberOfPages == 0) error("Koi image nahi mili.")
                doc.save(out)
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Loads, applies [block], saves to a fresh file. Nothing is written unless
     * the whole operation succeeded, so a failure leaves the working copy intact.
     */
    private inline fun edit(source: File, block: (PDDocument) -> Unit): Result<File> {
        return try {
            val out = File(source.parentFile, "edit-${System.currentTimeMillis()}.pdf")
            PDDocument.load(source).use { doc ->
                block(doc)
                doc.save(out)
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fill only — deliberately no stroke. A stroked rectangle would leave a
     * visible outline around every patch, which is the giveaway that something
     * was edited.
     */
    private fun fillRect(
        cs: PDPageContentStream,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: Int
    ) {
        cs.setNonStrokingColor(
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f
        )
        cs.addRect(x, y, width, height)
        cs.fill()
    }

    private fun drawText(
        cs: PDPageContentStream,
        text: String,
        xPt: Float,
        baselinePt: Float,
        size: Float,
        color: Int
    ) {
        val font = PDType1Font.HELVETICA
        cs.setNonStrokingColor(
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f
        )

        val lines = text.split("\n")
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset(xPt, baselinePt)
        lines.forEachIndexed { i, line ->
            if (i > 0) cs.newLineAtOffset(0f, -size * 1.2f)
            cs.showText(sanitize(line))
        }
        cs.endText()
    }

    /**
     * Shrinks the font just enough that the replacement still fits the space the
     * old text occupied — otherwise longer replacements run into neighbouring
     * text. Never grows it, and never goes below 5pt.
     */
    private fun fitSize(text: String, hit: TextHit): Float {
        val widthAtSize = { s: Float ->
            PDType1Font.HELVETICA.getStringWidth(sanitize(text)) / 1000f * s
        }
        var size = hit.fontSize
        while (size > 5f && widthAtSize(size) > hit.width * 1.02f) size -= 0.5f
        return size
    }

    /**
     * The standard-14 fonts are WinAnsi-encoded; anything outside that throws
     * when written. Rather than failing the whole edit, map the common cases and
     * drop the rest.
     */
    private fun sanitize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '‘', '’' -> sb.append('\'')
                '“', '”' -> sb.append('"')
                '–', '—' -> sb.append('-')
                '…' -> sb.append("...")
                ' ' -> sb.append(' ')
                '\t' -> sb.append("    ")
                else -> if (ch.code in 32..255) sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** JPEG has no alpha; compositing on white keeps transparent areas clean. */
    private fun flattenOnWhite(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }
}
