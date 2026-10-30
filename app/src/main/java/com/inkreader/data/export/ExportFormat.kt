package com.inkreader.data.export

import com.inkreader.R

enum class ExportFormat(val extension: String, val mime: String, val hint: Int) {
    TXT("txt", "text/plain", R.string.export_format_txt_hint),
    EPUB("epub", "application/epub+zip", R.string.export_format_epub_hint),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", R.string.export_format_docx_hint),
    PDF("pdf", "application/pdf", R.string.export_format_pdf_hint)
}
