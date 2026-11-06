package com.synergic.cloakroom.data.print

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.synergic.cloakroom.domain.print.PrintDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Writes a [PrintDocument] as a one-page PDF the width of the paper roll, and shares it. */
@Singleton
class PdfExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun export(document: PrintDocument): File = withContext(Dispatchers.IO) {
        val pageWidth = (document.paperWidthMm * POINTS_PER_MM).toInt()
        val painter = DocumentPainter(document, pageWidth, MARGIN, antiAlias = true)
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, painter.height, 1).create())
        painter.draw(page.canvas)
        pdf.finishPage(page)

        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, document.title.replace(Regex("[^A-Za-z0-9-]+"), "_") + ".pdf")
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        file
    }

    /** Opens Android's share sheet (WhatsApp, email, Drive, a printing app…) for [file]. */
    fun shareIntent(file: File, title: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    private companion object {
        const val POINTS_PER_MM = 72f / 25.4f
        const val MARGIN = 8
        const val SHARE_DIR = "shared"
    }
}
