package com.waz.pocketpdf

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Holds the PDF currently being worked on.
 *
 * Everything operates on a private working copy in cache, never on the file the
 * user picked. That keeps the original safe no matter what an edit does, and
 * sidesteps the fact that a content:// Uri is often read-only anyway.
 * Saving is an explicit, separate step that writes to a new file.
 */
object PdfSession {

    /** The working copy. All edits land here. */
    var workingFile: File? = null
        private set

    /** Name shown in the UI and used as the basis for the saved file name. */
    var displayName: String = "document.pdf"
        private set

    /** Set once the user has made at least one change. */
    var dirty: Boolean = false

    /**
     * Copies the picked document into cache and makes it the active session.
     * @return null on success, or a message explaining what went wrong.
     */
    fun open(context: Context, uri: Uri): String? {
        return try {
            val name = queryName(context, uri) ?: "document.pdf"
            val dest = File(context.cacheDir, "working.pdf")

            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return "Yeh file padhi nahi ja saki."
                dest.outputStream().use { input.copyTo(it) }
            }

            if (dest.length() == 0L) return "File khali hai."

            workingFile = dest
            displayName = name
            dirty = false
            null
        } catch (e: Exception) {
            "File kholne mein dikkat: ${e.localizedMessage}"
        }
    }

    /** Replaces the working copy with [newFile], used after an edit succeeds. */
    fun replaceWorking(newFile: File) {
        val target = workingFile ?: return
        if (newFile.absolutePath == target.absolutePath) {
            dirty = true
            return
        }
        newFile.copyTo(target, overwrite = true)
        newFile.delete()
        dirty = true
    }

    fun suggestedSaveName(): String {
        val base = displayName.removeSuffix(".pdf").removeSuffix(".PDF")
        return "$base-edited.pdf"
    }

    fun clear() {
        workingFile = null
        displayName = "document.pdf"
        dirty = false
    }

    private fun queryName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
        }
        return null
    }
}
