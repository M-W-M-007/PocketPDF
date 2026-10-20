package com.waz.pocketpdf

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * PDFBox-Android ships its font metrics and CMaps as assets and needs an
 * explicit init before any document is touched. Doing it here means no screen
 * has to remember.
 */
class PocketPdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
