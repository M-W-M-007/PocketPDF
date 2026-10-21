package com.waz.pocketpdf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.waz.pocketpdf.databinding.ActivityConvertBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Conversions in both directions. PDF to Word is deliberately absent: doing it
 * properly means rebuilding layout, tables and styles, and a half-working
 * version produces files that look broken. Text extraction is offered instead,
 * which is honest about what it gives you.
 */
class ConvertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConvertBinding

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) imagesToPdf(uris) }

    private val saveText = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let { writeExtractedText(it) } }

    private var extracted: String = ""

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConvertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Convert"
            subtitle = PdfSession.displayName
            setDisplayHomeAsUpEnabled(true)
        }

        binding.toImagesButton.setOnClickListener { pdfToImages() }
        binding.fromImagesButton.setOnClickListener {
            pickImages.launch(arrayOf("image/*"))
        }
        binding.toTextButton.setOnClickListener { pdfToText() }
    }

    // ------------------------------------------------------------ pdf → images

    private fun pdfToImages() {
        val file = PdfSession.workingFile ?: return
        busy(true, "Pages render ho rahe hain…")

        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val dir = File(
                    getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "PocketPDF"
                ).apply { mkdirs() }

                val base = PdfSession.displayName.removeSuffix(".pdf")
                val out = mutableListOf<File>()

                // 1600px wide keeps text readable without blowing up memory.
                val count = PdfRender.pageCount(file)
                for (i in 0 until count) {
                    val rendered = PdfRender.renderPage(file, i, 1600) ?: continue
                    val dest = File(dir, "$base-page${i + 1}.jpg")
                    dest.outputStream().use { stream ->
                        rendered.first.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                    }
                    rendered.first.recycle()
                    out += dest
                }
                out
            }

            busy(false)

            if (saved.isEmpty()) {
                toast("Koi page render nahi hua.")
                return@launch
            }

            binding.result.visibility = View.VISIBLE
            binding.result.text =
                "${saved.size} images ban gayi:\n${saved.first().parentFile?.absolutePath}"

            shareImages(saved)
        }
    }

    private fun shareImages(files: List<File>) {
        try {
            val uris = ArrayList<Uri>(files.map {
                FileProvider.getUriForFile(this, "$packageName.files", it)
            })

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Images bhejein / save karein"))
        } catch (e: Exception) {
            toast("Images ban gayi, lekin share nahi ho paayi: ${e.localizedMessage}")
        }
    }

    // ------------------------------------------------------------ images → pdf

    private fun imagesToPdf(uris: List<Uri>) {
        busy(true, "PDF ban rahi hai…")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val bitmaps = uris.mapNotNull { uri ->
                    runCatching {
                        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }

                if (bitmaps.isEmpty()) {
                    Result.failure(IllegalStateException("Images padhi nahi ja saki."))
                } else {
                    PdfOps.imagesToPdf(bitmaps, File(cacheDir, "from-images.pdf"))
                }
            }

            busy(false)

            result.fold(
                onSuccess = {
                    PdfSession.replaceWorking(it)
                    binding.result.visibility = View.VISIBLE
                    binding.result.text =
                        "PDF ban gayi aur khul gayi. Home par jaakar Save dabayein."
                },
                onFailure = { toast("Fail: ${it.localizedMessage}") }
            )
        }
    }

    // -------------------------------------------------------------- pdf → text

    private fun pdfToText() {
        val file = PdfSession.workingFile ?: return
        busy(true, "Text nikala ja raha hai…")

        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { PdfOps.extractAllText(file) }.getOrDefault("")
            }

            busy(false)
            extracted = text

            if (text.isBlank()) {
                binding.result.visibility = View.VISIBLE
                binding.result.text =
                    "Koi text nahi mila. Yeh PDF shayad scan ki hui images hai — " +
                        "usme text hota hi nahi, sirf tasveer hoti hai."
                return@launch
            }

            binding.result.visibility = View.VISIBLE
            binding.result.text = text.take(1200) +
                if (text.length > 1200) "\n\n… (aur bhi hai)" else ""

            saveText.launch(PdfSession.displayName.removeSuffix(".pdf") + ".txt")
        }
    }

    private fun writeExtractedText(target: Uri) {
        try {
            contentResolver.openOutputStream(target)?.use {
                it.write(extracted.toByteArray(Charsets.UTF_8))
            }
            toast("Text file save ho gayi.")
        } catch (e: Exception) {
            toast("Save fail: ${e.localizedMessage}")
        }
    }

    // ------------------------------------------------------------------ util

    private fun busy(on: Boolean, label: String = "") {
        binding.progress.visibility = if (on) View.VISIBLE else View.GONE
        binding.status.visibility = if (on) View.VISIBLE else View.GONE
        binding.status.text = label
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
