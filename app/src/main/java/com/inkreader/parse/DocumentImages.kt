package com.inkreader.parse

import android.graphics.BitmapFactory
import java.security.MessageDigest

/** Only embedded bytes enter the library. These references never fetch URLs or open arbitrary paths. */
data class DocumentImage(val key: String, val bytes: ByteArray, val width: Int, val height: Int)

data class ImageReference(val key: String, val width: Int, val height: Int) {
    val marker get() = "$key:$width:$height"
    fun heightAt(widthPx: Float, maxHeightPx: Float, density: Float = 1f): Float =
        (minOf(widthPx, width * density) * height.toFloat() / width).coerceIn(1f, maxHeightPx.coerceAtLeast(1f))
}

object DocumentImages {
    private val keyPattern = Regex("[a-f0-9]{64}")
    private val tokenPattern = Regex("\\[\\[ink-image:([a-f0-9]{64}):([0-9]{1,6}):([0-9]{1,6})]]")
    fun validKey(key: String) = keyPattern.matches(key)
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    fun fromBytes(bytes: ByteArray): DocumentImage? {
        if (bytes.size > 16 * 1024 * 1024) throw ParseException(ParseErrorCode.TOO_LARGE)
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val pixels = if (bounds.outMimeType == "image/jpeg") 128L * 1024 * 1024 else 64L * 1024 * 1024
        if (bounds.outWidth > 100000 || bounds.outHeight > 100000 || bounds.outWidth.toLong() * bounds.outHeight > pixels)
            throw ParseException(ParseErrorCode.TOO_LARGE)
        val swap = bounds.outMimeType == "image/jpeg" && bytes.inputStream().use { DocumentImageOrientation.swapsAxes(DocumentImageOrientation.read(it)) }
        return DocumentImage(hash(bytes), bytes, if (swap) bounds.outHeight else bounds.outWidth, if (swap) bounds.outWidth else bounds.outHeight)
    }
    fun token(image: DocumentImage) = "[[ink-image:${image.key}:${image.width}:${image.height}]]"
    fun reference(marker: String): ImageReference? {
        val fields = marker.split(':')
        if (fields.size != 3 || !validKey(fields[0])) return null
        val width = fields[1].toIntOrNull()?.takeIf { it in 1..100000 } ?: return null
        val height = fields[2].toIntOrNull()?.takeIf { it in 1..100000 } ?: return null
        return ImageReference(fields[0], width, height)
    }
    fun preview(text: String) = tokenPattern.replace(text, "〔图片〕")
    fun isToken(text: String) = tokenPattern.matches(text.trim())
    data class Rendered(val text: String, val rich: RichChapter?)
    fun render(content: String, images: Map<String, DocumentImage>): Rendered {
        if (images.isEmpty()) return Rendered(content, null)
        val found = HashMap<Int, ImageReference>()
        val text = buildString {
            var cursor = 0
            for (match in tokenPattern.findAll(content)) {
                append(content, cursor, match.range.first)
                val image = images[match.groupValues[1]]
                if (image == null || image.width.toString() != match.groupValues[2] || image.height.toString() != match.groupValues[3]) append(match.value)
                else {
                    if (isNotEmpty() && !endsWith("\n\n")) append(if (last() == '\n') "\n" else "\n\n")
                    found[length] = ImageReference(image.key, image.width, image.height)
                    append('\uFFFC')
                    val end = match.range.last + 1
                    if (end < content.length && !content.startsWith("\n\n", end)) append("\n\n")
                }
                cursor = match.range.last + 1
            }
            append(content, cursor, content.length)
        }
        if (found.isEmpty()) return Rendered(content, null)
        var start = 0
        val blocks = ArrayList<RichBlock>()
        while (start < text.length) {
            val end = text.indexOf("\n\n", start).let { if (it < 0) text.length else it }
            val image = found[start]
            if (text.substring(start, end) == "\uFFFC" && image != null) {
                blocks += RichBlock(RichKind.IMAGE, 0, image.marker, start, end)
            } else blocks += RichBlock(RichKind.BODY, 0, "", start, end)
            start = end + 2
        }
        return Rendered(text, RichChapter(text.length, blocks, emptyList(), emptyList()))
    }
    fun references(rich: RichChapter?): Set<String> = rich?.blocks.orEmpty()
        .filter { it.kind == RichKind.IMAGE }.mapNotNull { reference(it.marker)?.key }.toSet()
}
