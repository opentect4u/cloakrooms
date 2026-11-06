package com.synergic.cloakroom.data.print

import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.print.PrintResult
import com.synergic.cloakroom.domain.print.Printer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No physical printer (PRINTER_TYPE = None, or none found by Auto): nothing is sent anywhere. The
 * screen that called [print] already shows the document, and the user can share it as a PDF.
 */
@Singleton
class PreviewPrinter @Inject constructor() : Printer {
    override suspend fun print(document: PrintDocument, copies: Int): PrintResult = PrintResult.PreviewOnly
}
