package com.waz.pocketpdf

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.waz.pocketpdf.databinding.ActivityPageToolsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thumbnail grid with multi-select, plus the operations that act on whole pages.
 */
class PageToolsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPageToolsBinding
    private val selected = linkedSetOf<Int>()
    private var thumbs: List<Bitmap> = emptyList()

    private val pickToMerge = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) mergeWith(uris) }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPageToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Pages"
            subtitle = PdfSession.displayName
            setDisplayHomeAsUpEnabled(true)
        }

        binding.grid.layoutManager = GridLayoutManager(this, 3)

        binding.rotateButton.setOnClickListener { withSelection { rotate(it) } }
        binding.deleteButton.setOnClickListener { withSelection { confirmDelete(it) } }
        binding.extractButton.setOnClickListener { withSelection { extract(it) } }
        binding.moveUpButton.setOnClickListener { withSelection { move(it, -1) } }
        binding.moveDownButton.setOnClickListener { withSelection { move(it, +1) } }
        binding.mergeButton.setOnClickListener {
            pickToMerge.launch(arrayOf("application/pdf"))
        }
        binding.splitButton.setOnClickListener { askSplit() }

        loadThumbs()
    }

    // ---------------------------------------------------------------- loading

    private fun loadThumbs() {
        val file = PdfSession.workingFile ?: run { finish(); return }
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val rendered = withContext(Dispatchers.IO) { PdfRender.renderThumbnails(file) }
            thumbs = rendered
            selected.retainAll { it < rendered.size }

            binding.progress.visibility = View.GONE
            binding.grid.adapter = ThumbAdapter()
            updateCount()
        }
    }

    private fun updateCount() {
        binding.selectionLabel.text = when (selected.size) {
            0 -> "${thumbs.size} pages · koi select nahi"
            1 -> "Page ${selected.first() + 1} selected"
            else -> "${selected.size} pages selected"
        }
    }

    private inline fun withSelection(block: (Set<Int>) -> Unit) {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Pehle page select karein.", Toast.LENGTH_SHORT).show()
            return
        }
        block(selected.toSet())
    }

    // ------------------------------------------------------------- operations

    private fun rotate(pages: Set<Int>) {
        val file = PdfSession.workingFile ?: return
        runOp { PdfOps.rotatePages(file, pages, 90) }
    }

    private fun confirmDelete(pages: Set<Int>) {
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("${pages.size} page delete karein? Original file safe rahegi — yeh sirf working copy par hoga.")
            .setPositiveButton("Delete") { _, _ ->
                val file = PdfSession.workingFile ?: return@setPositiveButton
                selected.clear()
                runOp { PdfOps.deletePages(file, pages) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Keeps only the selected pages, in their existing order. */
    private fun extract(pages: Set<Int>) {
        val file = PdfSession.workingFile ?: return
        runOp { PdfOps.reorderOrExtract(file, pages.sorted()) }
    }

    /** Moves the selection one slot earlier or later in the page order. */
    private fun move(pages: Set<Int>, delta: Int) {
        val file = PdfSession.workingFile ?: return
        val order = (0 until thumbs.size).toMutableList()

        val targets = if (delta < 0) pages.sorted() else pages.sortedDescending()
        for (p in targets) {
            val from = order.indexOf(p)
            val to = from + delta
            if (to < 0 || to >= order.size) return
            order.removeAt(from)
            order.add(to, p)
        }

        val newSelection = pages.map { order.indexOf(it) }
        runOp(afterSelection = newSelection) { PdfOps.reorderOrExtract(file, order) }
    }

    private fun mergeWith(uris: List<Uri>) {
        val current = PdfSession.workingFile ?: return
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val parts = mutableListOf(current)
                uris.forEachIndexed { i, uri ->
                    val tmp = File(cacheDir, "merge-$i.pdf")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    if (tmp.length() > 0) parts += tmp
                }
                PdfOps.merge(parts, cacheDir)
            }

            binding.progress.visibility = View.GONE
            result.fold(
                onSuccess = {
                    PdfSession.replaceWorking(it)
                    selected.clear()
                    loadThumbs()
                    Toast.makeText(this@PageToolsActivity, "Merge ho gaya.", Toast.LENGTH_SHORT).show()
                },
                onFailure = {
                    Toast.makeText(
                        this@PageToolsActivity,
                        "Merge fail: ${it.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun askSplit() {
        val file = PdfSession.workingFile ?: return

        val options = arrayOf("Har page alag file", "Har 2 pages", "Har 5 pages", "Har 10 pages")
        val sizes = intArrayOf(1, 2, 5, 10)

        AlertDialog.Builder(this)
            .setTitle("Split")
            .setItems(options) { _, which ->
                binding.progress.visibility = View.VISIBLE

                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        PdfOps.split(file, sizes[which], File(cacheDir, "split").apply { mkdirs() })
                    }
                    binding.progress.visibility = View.GONE

                    result.fold(
                        onSuccess = { files -> shareFiles(files) },
                        onFailure = {
                            Toast.makeText(
                                this@PageToolsActivity,
                                "Split fail: ${it.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
            .show()
    }

    private fun shareFiles(files: List<File>) {
        AlertDialog.Builder(this)
            .setTitle("${files.size} files ban gayi")
            .setMessage(
                "Ye app ke andar bani hain. Inhe phone mein save karne ke liye ek-ek karke " +
                    "kholna padega — abhi ke liye: har file ko session mein open karke home se Save karein.\n\n" +
                    files.joinToString("\n") { it.name }
            )
            .setPositiveButton("Pehli file kholein") { _, _ ->
                files.firstOrNull()?.let {
                    PdfSession.replaceWorking(it)
                    selected.clear()
                    loadThumbs()
                }
            }
            .setNegativeButton("Rehne dein", null)
            .show()
    }

    private fun runOp(afterSelection: List<Int>? = null, block: () -> Result<File>) {
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            binding.progress.visibility = View.GONE

            result.fold(
                onSuccess = {
                    PdfSession.replaceWorking(it)
                    if (afterSelection != null) {
                        selected.clear()
                        selected.addAll(afterSelection)
                    }
                    loadThumbs()
                },
                onFailure = { e ->
                    Toast.makeText(
                        this@PageToolsActivity,
                        "Fail: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    // ---------------------------------------------------------------- adapter

    private inner class ThumbAdapter : RecyclerView.Adapter<ThumbAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.thumbImage)
            val label: TextView = view.findViewById(R.id.thumbLabel)
            val frame: View = view.findViewById(R.id.thumbFrame)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_thumb, parent, false))

        override fun getItemCount() = thumbs.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.image.setImageBitmap(thumbs[position])
            holder.label.text = "${position + 1}"

            val isSelected = position in selected
            holder.frame.setBackgroundColor(
                if (isSelected) Color.parseColor("#3348B0FF") else Color.TRANSPARENT
            )
            holder.image.alpha = if (isSelected) 0.75f else 1f

            holder.itemView.setOnClickListener {
                if (!selected.add(position)) selected.remove(position)
                notifyItemChanged(position)
                updateCount()
            }
        }
    }
}
