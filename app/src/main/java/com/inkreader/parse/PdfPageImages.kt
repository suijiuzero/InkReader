package com.inkreader.parse

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream

/** Scanned pages retain their whole layout; this displays scans without claiming OCR text. */
internal object PdfPageImages {
    fun render(bytes: ByteArray, media: EmbeddedImages): List<String> {
        val file = DocumentParsers.mediaTemporaryFile()
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount > 2000) throw ParseException(ParseErrorCode.TOO_LARGE)
                    return (0 until renderer.pageCount).map { index ->
                        ParseCancellation.checkpoint()
                        renderer.openPage(index).use { page ->
                            val scale = 2048f / maxOf(page.width, page.height).coerceAtLeast(1)
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                ParseCancellation.checkpoint()
                                val output = ByteArrayOutputStream()
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
                                media.add(output.toByteArray()) ?: error("Unable to encode page")
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
        } finally { file.delete() }
    }
}
