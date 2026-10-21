package com.waz.pocketpdf

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.waz.pocketpdf.databinding.ActivityEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Page view with three tap modes: retype existing text, drop new text, or stamp
 * a signature. Edits apply to the working copy; the original file is untouched
 * until the user saves from the home screen.
 */
class EditorActivity : AppCompatActivity() {

    private enum class Mode { EDIT, ADD_TEXT, ERASE, SIGN }

    private lateinit var binding: ActivityEditorBinding

    private var pageIndex = 0
    private var pageCount = 0
    private var mode = Mode.EDIT

    private var pageBitmap: Bitmap? = null
    private var pageSize: PdfRender.PageSize? = null
    private var hits: List<PdfOps.TextHit> = emptyList()
    private var lineHits: List<PdfOps.TextHit> = emptyList()

    /** Bitmap pixels per PDF point. Everything tapped converts through this. */
    private var scale = 1f

    private var pendingSignature: Bitmap? = null

    /** 1f = fit to screen width. Re-rendering at this factor keeps text sharp. */
    private var zoom = 1f
    private var baseWidthPx = 0
    private var isPinching = false
    private lateinit var scaleDetector: ScaleGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Back arrow and a visible Save. Without these the editor is a dead end:
        // the edit lands on the page and there is nothing on screen saying how to
        // keep it or how to get out.
        supportActionBar?.apply {
            title = "Edit PDF  ·  v${BuildConfig.VERSION_NAME}"
            subtitle = PdfSession.displayName
            setDisplayHomeAsUpEnabled(true)
        }

        pageCount = PdfSession.workingFile?.let { PdfRender.pageCount(it) } ?: 0
        if (pageCount == 0) {
            Toast.makeText(this, "PDF load nahi hui.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.prevButton.setOnClickListener { goTo(pageIndex - 1) }
        binding.nextButton.setOnClickListener { goTo(pageIndex + 1) }

        binding.modeEdit.setOnClickListener { setMode(Mode.EDIT) }
        binding.modeAdd.setOnClickListener { setMode(Mode.ADD_TEXT) }
        binding.modeErase.setOnClickListener { setMode(Mode.ERASE) }
        binding.modeSign.setOnClickListener { setMode(Mode.SIGN) }

        binding.pageImage.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP &&
                !isPinching && !scaleDetector.isInProgress && event.pointerCount == 1
            ) {
                onPageTapped(event.x, event.y)
            }
            true
        }

        binding.regionSelect.onRegion = { rect -> eraseRegion(rect) }

        binding.undoButton.setOnClickListener { undoLast() }
        binding.backButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener {
            saveLauncher.launch(PdfSession.suggestedSaveName())
        }

        setMode(Mode.EDIT)

        // The render target width is the view's width, so wait for layout.
        binding.zoomInButton.setOnClickListener { setZoom(zoom + 0.5f) }
        binding.zoomOutButton.setOnClickListener { setZoom(zoom - 0.5f) }

        scaleDetector = ScaleGestureDetector(this, object :
            ScaleGestureDetector.SimpleOnScaleGestureListener() {

            private var pending = 1f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isPinching = true
                pending = zoom
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pending = (pending * detector.scaleFactor).coerceIn(1f, 4f)
                binding.zoomLabel.text = "${(pending * 100).toInt()}%"
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Re-render once the gesture settles rather than on every frame:
                // scaling the bitmap live would blur the text, and re-rendering
                // per frame would stutter.
                setZoom(pending)
                binding.pageImage.postDelayed({ isPinching = false }, 150)
            }
        })

        binding.pageScroll.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            // Never consume: the scroll views still need these events to pan.
            false
        }

        binding.pageImage.post {
            baseWidthPx = binding.pageScroll.width
            renderPage()
        }
    }

    // ------------------------------------------------------------------ view

    private fun setMode(m: Mode) {
        mode = m
        // Let the group own the checked state; setting each button individually
        // fights its single-selection logic.
        binding.modeGroup.check(
            when (m) {
                Mode.EDIT -> R.id.modeEdit
                Mode.ADD_TEXT -> R.id.modeAdd
                Mode.ERASE -> R.id.modeErase
                Mode.SIGN -> R.id.modeSign
            }
        )

        // Only erase mode needs the drag overlay; the rest want plain taps.
        binding.regionSelect.visibility = if (m == Mode.ERASE) View.VISIBLE else View.GONE

        binding.hint.text = when (m) {
            Mode.EDIT -> "Line par tap karein — poori line ek saath badlegi. Dialog mein \"Poori line\" hata kar sirf ek shabd bhi badal sakte hain."
            Mode.ADD_TEXT -> "Jahan naya text likhna hai wahan tap karein."
            Mode.ERASE -> "Jo hissa mitana hai uspar ungli se box kheenchein. Patch page ke rang ka hoga, border nahi aayegi."
            Mode.SIGN -> "Signature banane ke liye tap karein, phir jagah chunein."
        }
    }

    private fun goTo(index: Int) {
        if (index !in 0 until pageCount) return
        pageIndex = index
        renderPage()
    }

    private fun renderPage() {
        val file = PdfSession.workingFile ?: return
        if (baseWidthPx <= 0) baseWidthPx = binding.pageScroll.width
        val targetWidth = (baseWidthPx * zoom).toInt().takeIf { it > 0 } ?: return

        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val rendered = withContext(Dispatchers.IO) {
                PdfRender.renderPage(file, pageIndex, targetWidth)
            }
            val found = withContext(Dispatchers.IO) {
                runCatching { PdfOps.textHits(file, pageIndex) }.getOrDefault(emptyList())
            }

            binding.progress.visibility = View.GONE

            if (rendered == null) {
                Toast.makeText(
                    this@EditorActivity,
                    "Page render nahi hua. PDF encrypted ho sakti hai.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val (bitmap, size) = rendered
            pageBitmap = bitmap
            pageSize = size
            hits = found
            lineHits = PdfOps.mergeIntoLines(found)
            scale = bitmap.width / size.widthPt

            binding.pageImage.setImageBitmap(bitmap)
            binding.pageLabel.text = "Page ${pageIndex + 1} / $pageCount"
            binding.pageNavRow.visibility = if (pageCount > 1) View.VISIBLE else View.GONE
            binding.prevButton.isEnabled = pageIndex > 0
            binding.nextButton.isEnabled = pageIndex < pageCount - 1
            binding.undoButton.isEnabled = PdfSession.canUndo
            invalidateOptionsMenu()

            if (found.isEmpty() && mode == Mode.EDIT) {
                binding.hint.text =
                    "Is page par koi select karne layak text nahi mila — shayad yeh scan ki hui image hai. " +
                        "Aise page par \"Add text\" use karein."
            }
        }
    }

    // ------------------------------------------------------------------ taps

    private fun onPageTapped(viewX: Float, viewY: Float) {
        if (scale <= 0f) return

        val pdfX = viewX / scale
        val pdfY = viewY / scale

        when (mode) {
            Mode.EDIT -> {
                val line = findHit(lineHits, pdfX, pdfY)
                val word = findHit(hits, pdfX, pdfY)
                if (line == null && word == null) {
                    Toast.makeText(this, "Yahan koi text nahi mila.", Toast.LENGTH_SHORT).show()
                } else {
                    askReplacement(line ?: word!!, word ?: line!!)
                }
            }

            Mode.ADD_TEXT -> askNewText(pdfX, pdfY)

            Mode.ERASE -> Unit // handled by the drag overlay

            Mode.SIGN -> {
                val sig = pendingSignature
                if (sig == null) drawSignature()
                else placeSignature(sig, pdfX, pdfY)
            }
        }
    }

    /**
     * Exact hit first; otherwise the nearest line on the same row.
     *
     * Body text lines are only a few points tall, which is well under a
     * fingertip — requiring an exact hit made most taps miss and report
     * "no text here" on a page that was full of text.
     */
    private fun findHit(
        candidates: List<PdfOps.TextHit>,
        pdfX: Float,
        pdfY: Float
    ): PdfOps.TextHit? {
        candidates.firstOrNull { it.contains(pdfX, pdfY) }?.let { return it }

        // Within half a line height counts as aiming at that line.
        return candidates
            .filter { pdfY >= it.top - it.height && pdfY <= it.bottom + it.height }
            .minByOrNull { h ->
                val dx = when {
                    pdfX < h.left -> h.left - pdfX
                    pdfX > h.right -> pdfX - h.right
                    else -> 0f
                }
                val dy = kotlin.math.abs((h.top + h.bottom) / 2f - pdfY)
                dx + dy * 2f
            }
    }

    /**
     * [line] is the whole visual line, [word] the single run under the finger.
     * The line is the default because rewriting a line as one piece avoids
     * fighting its neighbours for space; the checkbox narrows to the run for
     * cases where only one value should move.
     */
    private fun askReplacement(line: PdfOps.TextHit, word: PdfOps.TextHit) {
        val input = EditText(this).apply {
            setText(line.text)
            setSelection(line.text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        val wholeLine = CheckBox(this).apply {
            text = "Poori line"
            isChecked = true
            isEnabled = line !== word
            setOnCheckedChangeListener { _, checked ->
                val target = if (checked) line else word
                input.setText(target.text)
                input.setSelection(target.text.length)
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(input)
            addView(wholeLine)
        }

        AlertDialog.Builder(this)
            .setTitle("Text badlein")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val target = if (wholeLine.isChecked) line else word
                applyReplacement(target, input.text.toString())
            }
            .setNeutralButton("Mitayein") { _, _ ->
                val target = if (wholeLine.isChecked) line else word
                applyReplacement(target, "")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyReplacement(hit: PdfOps.TextHit, newText: String) {
        val file = PdfSession.workingFile ?: return
        val bitmap = pageBitmap

        val l = (hit.left * scale).toInt()
        val t = (hit.top * scale).toInt()
        val r = (hit.right * scale).toInt()
        val b = (hit.bottom * scale).toInt()

        // Sample the paper colour around the old text so the patch disappears
        // into the page instead of leaving a white block.
        val background = if (bitmap != null) {
            PdfRender.sampleBackground(bitmap, l, t, r, b)
        } else {
            Color.WHITE
        }

        // And sample the ink, so a blue figure stays blue after the edit.
        val ink = if (bitmap != null) {
            PdfRender.sampleTextColor(bitmap, l, t, r, b, background)
        } else {
            Color.BLACK
        }

        // How far this text may grow before it would collide with whatever sits
        // next on the same line. Replacing "Rs." with "Rupees" next to an amount
        // overlapped the amount until this was taken into account.
        val neighbourLeft = hits
            .filter { it.left >= hit.right }                          // strictly after it
            .filter { it.top < hit.bottom && it.bottom > hit.top }    // same line
            .minOfOrNull { it.left }

        val room = if (neighbourLeft != null) {
            (neighbourLeft - hit.left - 2f)
        } else {
            pageSize?.let { it.widthPt - hit.left - 18f } ?: hit.width
        }

        runOp { PdfOps.replaceText(file, pageIndex, hit, newText, background, ink, room) }
    }

    private fun askNewText(pdfX: Float, pdfY: Float) {
        val file = PdfSession.workingFile ?: return

        val input = EditText(this).apply {
            hint = "Yahan likhein"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        // Writing on top of existing content without clearing it leaves both
        // visible, so offer the patch right here rather than making it a
        // separate two-step job.
        val cover = CheckBox(this).apply {
            text = "Neeche ka text pehle dhak dein"
            isChecked = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(input)
            addView(cover)
        }

        AlertDialog.Builder(this)
            .setTitle("Naya text")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) return@setPositiveButton

                val background = sampleAround(pdfX, pdfY)
                runOp {
                    PdfOps.addText(
                        source = file,
                        pageIndex = pageIndex,
                        xPt = pdfX,
                        topY = pdfY,
                        text = text,
                        coverBackground = cover.isChecked,
                        backgroundColor = background
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun eraseRegion(rect: RectF) {
        val file = PdfSession.workingFile ?: return
        if (scale <= 0f) return
        val bitmap = pageBitmap

        val background = if (bitmap != null) {
            PdfRender.sampleBackground(
                bitmap,
                rect.left.toInt(), rect.top.toInt(),
                rect.right.toInt(), rect.bottom.toInt()
            )
        } else {
            Color.WHITE
        }

        runOp {
            PdfOps.coverArea(
                source = file,
                pageIndex = pageIndex,
                xPt = rect.left / scale,
                topY = rect.top / scale,
                widthPt = rect.width() / scale,
                heightPt = rect.height() / scale,
                color = background
            )
        }
    }

    /** Page colour near a point, so a patch there blends into the paper. */
    private fun sampleAround(pdfX: Float, pdfY: Float): Int {
        val bitmap = pageBitmap ?: return Color.WHITE
        val px = (pdfX * scale).toInt()
        val py = (pdfY * scale).toInt()
        return PdfRender.sampleBackground(bitmap, px, py, px + 2, py + 2)
    }

    private fun drawSignature() {
        val view = layoutInflater.inflate(R.layout.dialog_signature, null)
        val pad = view.findViewById<SignatureView>(R.id.signaturePad)

        AlertDialog.Builder(this)
            .setTitle("Signature banayein")
            .setView(view)
            .setPositiveButton("Ready") { _, _ ->
                val bmp = pad.toBitmap()
                if (bmp == null) {
                    Toast.makeText(this, "Kuch banaya nahi gaya.", Toast.LENGTH_SHORT).show()
                } else {
                    pendingSignature = bmp
                    Toast.makeText(
                        this,
                        "Ab page par wahan tap karein jahan signature rakhna hai.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNeutralButton("Clear") { _, _ -> pad.clear() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun placeSignature(sig: Bitmap, pdfX: Float, pdfY: Float) {
        val file = PdfSession.workingFile ?: return
        // 150pt wide is roughly a real signature on A4.
        runOp { PdfOps.addImage(file, pageIndex, sig, pdfX, pdfY, 150f) }
        pendingSignature = null
    }

    // ------------------------------------------------------------------ apply

    // ------------------------------------------------------------------ menu

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let { writeOut(it) } }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.editor_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_undo)?.isEnabled = PdfSession.canUndo
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }

        R.id.action_undo -> {
            undoLast()
            true
        }

        R.id.action_save -> {
            saveLauncher.launch(PdfSession.suggestedSaveName())
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /** Writes the working copy out as a brand new file the user picks. */
    private fun writeOut(target: android.net.Uri) {
        val file = PdfSession.workingFile ?: return
        try {
            contentResolver.openOutputStream(target)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            PdfSession.dirty = false
            Snackbar.make(binding.root, "Save ho gaya", Snackbar.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save fail: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setZoom(value: Float) {
        val clamped = value.coerceIn(1f, 4f)
        if (kotlin.math.abs(clamped - zoom) < 0.01f) {
            binding.zoomLabel.text = "${(zoom * 100).toInt()}%"
            return
        }
        zoom = clamped
        binding.zoomLabel.text = "${(zoom * 100).toInt()}%"
        binding.zoomOutButton.isEnabled = zoom > 1f
        binding.zoomInButton.isEnabled = zoom < 4f
        renderPage()
    }

    private fun undoLast() {
        if (!PdfSession.undo()) {
            Toast.makeText(this, "Undo karne ko kuch nahi hai.", Toast.LENGTH_SHORT).show()
            return
        }
        renderPage()
    }

    /** Runs a PDF write off the main thread, then swaps in the result. */
    private fun runOp(block: () -> Result<File>) {
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            binding.progress.visibility = View.GONE

            result.fold(
                onSuccess = {
                    PdfSession.replaceWorking(it)
                    renderPage()
                    // Every edit is reversible, and saying so is the point —
                    // without it the page just changes and there is no obvious
                    // way back.
                    Snackbar.make(binding.root, "Edit ho gaya", Snackbar.LENGTH_LONG)
                        .setAction("Undo") { undoLast() }
                        .show()
                },
                onFailure = {
                    Toast.makeText(
                        this@EditorActivity,
                        "Edit fail: ${it.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }
}
