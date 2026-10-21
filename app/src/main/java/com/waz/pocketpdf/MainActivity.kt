package com.waz.pocketpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.waz.pocketpdf.databinding.ActivityMainBinding

/**
 * Home screen: pick a PDF, then choose what to do with it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pickPdf = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { loadPdf(it) } }

    private val savePdf = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let { writeOut(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "PocketPDF  ·  v${BuildConfig.VERSION_NAME}"

        binding.openButton.setOnClickListener {
            pickPdf.launch(arrayOf("application/pdf"))
        }

        binding.editButton.setOnClickListener {
            if (requireDoc()) startActivity(Intent(this, EditorActivity::class.java))
        }

        binding.pagesButton.setOnClickListener {
            if (requireDoc()) startActivity(Intent(this, PageToolsActivity::class.java))
        }

        binding.convertButton.setOnClickListener {
            if (requireDoc()) startActivity(Intent(this, ConvertActivity::class.java))
        }

        binding.saveButton.setOnClickListener {
            if (requireDoc()) savePdf.launch(PdfSession.suggestedSaveName())
        }

        // Opened from another app via "Open with" or Share
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let { loadPdf(it) }
    }

    private fun loadPdf(uri: Uri) {
        val error = PdfSession.open(this, uri)
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val file = PdfSession.workingFile

        if (file == null) {
            binding.docName.text = "Koi PDF nahi khuli"
            binding.docInfo.visibility = View.GONE
            setToolsEnabled(false)
            return
        }

        val pages = PdfRender.pageCount(file)
        binding.docName.text = PdfSession.displayName
        binding.docInfo.visibility = View.VISIBLE
        binding.docInfo.text = buildString {
            append("$pages page")
            if (pages != 1) append("s")
            append(" · ${file.length() / 1024} KB")
            if (PdfSession.dirty) append(" · unsaved changes")
        }
        setToolsEnabled(true)
    }

    private fun setToolsEnabled(enabled: Boolean) {
        binding.editButton.isEnabled = enabled
        binding.pagesButton.isEnabled = enabled
        binding.convertButton.isEnabled = enabled
        binding.saveButton.isEnabled = enabled
    }

    private fun requireDoc(): Boolean {
        if (PdfSession.workingFile == null) {
            Toast.makeText(this, "Pehle koi PDF kholein.", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun writeOut(target: Uri) {
        val file = PdfSession.workingFile ?: return
        try {
            contentResolver.openOutputStream(target)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            PdfSession.dirty = false
            refresh()
            Toast.makeText(this, "Save ho gaya.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save fail: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}
