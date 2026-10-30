package com.inkreader.parse

/** Per-document, bounded embedded-media collection. External URLs are left as placeholders. */
internal class EmbeddedImages {
    val images = LinkedHashMap<String, DocumentImage>()
    private var retained = 0L
    fun add(bytes: ByteArray): String? {
        ParseCancellation.checkpoint()
        val image = DocumentImages.fromBytes(bytes) ?: return null
        if (image.key !in images) {
            if (images.size >= 2048 || retained + bytes.size > 96L * 1024 * 1024) throw ParseException(ParseErrorCode.TOO_LARGE)
            images[image.key] = image; retained += bytes.size
        }
        return DocumentImages.token(image)
    }
    fun dataUri(reference: String): String? {
        if (!reference.startsWith("data:image/", true)) return null
        val comma = reference.indexOf(',')
        if (comma !in 1..200 || !reference.substring(0, comma).endsWith(";base64", true) || reference.length > 24 * 1024 * 1024) return null
        val data = runCatching { android.util.Base64.decode(reference.substring(comma + 1), android.util.Base64.DEFAULT) }.getOrNull() ?: return null
        return add(data)
    }
    fun fromArchive(reference: String, baseDir: String, entries: Map<String, ByteArray>): String? {
        dataUri(reference)?.let { return it }
        if (reference.contains(':') || reference.startsWith("//")) return null
        val path = EpubArchive.resolve(baseDir, reference)
        return entries[path]?.let(::add)
    }
}
