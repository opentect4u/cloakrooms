package com.synergic.cloakroom.ui.print

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.synergic.cloakroom.data.print.QrBitmap
import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.print.PrintLine

/** Shows a [PrintDocument] as a strip of thermal paper (58 mm narrower than 80 mm). */
@Composable
fun PrintDocumentView(document: PrintDocument, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val paperWidth = min(maxWidth, if (document.paperWidthMm >= 80) 400.dp else 300.dp)
        Surface(
            color = Color.White,
            contentColor = Color.Black,
            shadowElevation = 4.dp,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.width(paperWidth),
        ) {
            val inner = paperWidth - 24.dp
            // Monospace glyphs are about 0.6 × the font size wide: fit charsPerLine into the width.
            val fontSize = with(LocalDensity.current) { (inner / (document.charsPerLine * 0.6f)).toSp() }
            val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = fontSize * 1.25f, color = Color.Black)
            Column(Modifier.padding(12.dp)) {
                document.lines.forEach { line ->
                    when (line) {
                        is PrintLine.Text -> Text(
                            line.text.ifEmpty { " " },
                            style = style,
                            fontWeight = if (line.bold) FontWeight.Bold else FontWeight.Normal,
                            softWrap = false,
                        )
                        PrintLine.Divider -> Text("-".repeat(document.charsPerLine), style = style, softWrap = false)
                        is PrintLine.Qr -> QrImage(line.content, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        is PrintLine.Image -> LogoImage(line.png, Modifier.fillMaxWidth().padding(bottom = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun QrImage(content: String, modifier: Modifier = Modifier, sizeDp: Int = 180) {
    val bitmap = remember(content) { QrBitmap.encode(content, 512).asImageBitmap() }
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap,
            contentDescription = "QR code",
            modifier = Modifier.size(sizeDp.dp).background(Color.White),
        )
    }
}

/** A black-and-white logo, at most the paper width. */
@Composable
fun LogoImage(png: ByteArray, modifier: Modifier = Modifier) {
    val bitmap = remember(png) { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() } ?: return
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(bitmap = bitmap, contentDescription = "Logo", modifier = Modifier.fillMaxWidth(0.8f))
    }
}
