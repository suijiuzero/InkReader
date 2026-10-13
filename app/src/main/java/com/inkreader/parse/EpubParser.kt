package com.inkreader.parse

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * EPUB 解析。
 *
 * EPUB 本质也是 ZIP + XML，和 docx 一样不引第三方库，直接用系统自带 XmlPullParser：
 *  `META-INF/container.xml` → 找到 .opf → 读 metadata（书名/作者）、manifest（文件清单）、
 *  spine（**阅读顺序**，这才是章节顺序的权威来源，不能按压缩包里的文件名排）。
 *
 * 一个 spine 项就是一个 XHTML 文件 = 一本书里自然的一章；章名优先取文件里第一个标题，
 * 其次 `<title>`，都没有就用序号。正文沿用 [HtmlTextExtractor]，与 HTML 导入完全一致。
 */
class EpubParser : DocumentParser {

    override val format: DocumentFormat = DocumentFormat.EPUB

    private class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    private class Opf(
        val title: String?,
        val author: String?,
        val items: List<ManifestItem>,
        val spine: List<String>
    )

    override fun parse(bytes: ByteArray, displayName: String): ParsedDoc {
        val entries = readEntries(bytes)
        if (entries.isEmpty()) throw ParseException(ParseErrorCode.CORRUPTED, format, "压缩包为空")

        val container = lookup(entries, "META-INF/container.xml")
            ?: throw ParseException(ParseErrorCode.CORRUPTED, format, "缺少 container.xml")
        val opfPath = parseContainer(container)
            ?: throw ParseException(ParseErrorCode.CORRUPTED, format, "container.xml 里没有 rootfile")
        val opfBytes = lookup(entries, opfPath)
            ?: throw ParseException(ParseErrorCode.CORRUPTED, format, "缺少 $opfPath")
        val opf = parseOpf(opfBytes)
            ?: throw ParseException(ParseErrorCode.CORRUPTED, format, "无法解析 $opfPath")

        val baseDir = opfPath.substringBeforeLast('/', "")
        val byId = opf.items.associateBy { it.id }
        val navIds = opf.items
            .filter { it.properties.contains("nav", ignoreCase = true) }
            .map { it.id }
            .toSet()

        val parts = ArrayList<ChapterSplitter.Part>()

        fun addFrom(item: ManifestItem) {
            val href = resolve(baseDir, item.href)
            val raw = lookup(entries, href) ?: return
            val html = HtmlTextExtractor.decodeHtml(raw)
            val ex = HtmlTextExtractor.extract(html)
            if (ex.paragraphs.isEmpty()) return
            val content = ex.paragraphs.joinToString("\n\n") { it.text }.trim()
            if (content.isEmpty()) return
            val heading = ex.paragraphs.firstOrNull { it.level >= 0 }?.text?.trim()
            val title = heading?.takeIf { it.isNotEmpty() && it.length <= 40 }
                ?: ex.title?.takeIf { it.isNotBlank() && it.length <= 40 }
                ?: "第 ${parts.size + 1} 章"
            parts += ChapterSplitter.Part(title, content)
        }

        for (idref in opf.spine) {
            if (idref in navIds) continue
            val item = byId[idref] ?: continue
            addFrom(item)
        }

        // spine 不可用（少见）时退回 manifest 里的正文文件顺序
        if (parts.isEmpty()) {
            opf.items
                .filter { it.id !in navIds && isContentType(it.mediaType, it.href) }
                .forEach { addFrom(it) }
        }

        if (parts.isEmpty()) throw ParseException(ParseErrorCode.NO_CONTENT, format)

        val title = opf.title?.takeIf { it.isNotBlank() }
            ?: ChapterSplitter.titleFromFileName(displayName)
        return ParsedDoc(title, opf.author.orEmpty(), parts, format)
    }

    private fun isContentType(mediaType: String, href: String): Boolean {
        if (mediaType.isNotEmpty()) {
            return mediaType.contains("xhtml", true) || mediaType.contains("html", true)
        }
        val ext = href.substringAfterLast('.', "").lowercase()
        return ext == "xhtml" || ext == "html" || ext == "htm"
    }

    // ---------------------------------------------------------------- ZIP

    private fun lookup(entries: Map<String, ByteArray>, path: String): ByteArray? =
        entries[normalize(path).lowercase()]

    private fun normalize(path: String): String {
        var p = path.trim()
        while (p.startsWith("./")) p = p.substring(2)
        return p.trimStart('/')
    }

    /**
     * 读出所有「可能是正文」的条目。
     *
     * 图片/字体/音频体积大且用不到，按扩展名跳过（顺带控制内存）；
     * 单个条目超过 8MB 的也跳过 —— 那不会是 XHTML 正文。
     */
    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        runCatching {
            ZipInputStream(bytes.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory) {
                        val name = normalize(e.name)
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in TEXT_EXT && total < MAX_TOTAL) {
                            // 流式 ZIP 的 e.size 常为 -1，不能当闸门；按上限分块读，超限整条跳过
                            val data = readEntryCapped(zip, MAX_ENTRY)
                            if (data != null) {
                                total += data.size
                                out[name.lowercase()] = data
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return out
    }

    /**
     * 分块读一个 ZIP 条目，最多读 [limit] 字节；超出返回 null，调用方据此跳过该条目。
     * 边读边数，不依赖 entry.size（流式 ZIP 里它常是 -1）。
     */
    private fun readEntryCapped(zip: ZipInputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            if (out.size() + n.toLong() > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- XML

    private fun parseContainer(xml: ByteArray): String? = runCatching {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(xml), null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && local(p.name) == "rootfile") {
                val path = attr(p, "full-path")
                if (!path.isNullOrBlank()) return@runCatching normalize(path)
            }
            ev = p.next()
        }
        null
    }.getOrNull()

    private fun parseOpf(xml: ByteArray): Opf? = runCatching {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(xml), null)

        var title: String? = null
        var author: String? = null
        var textTarget: String? = null
        val items = ArrayList<ManifestItem>()
        val spine = ArrayList<String>()
        var inMetadata = false

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "metadata" -> inMetadata = true
                    "title" -> if (inMetadata) textTarget = "title"
                    "creator" -> if (inMetadata) textTarget = "creator"
                    "item" -> {
                        val id = attr(p, "id").orEmpty()
                        val href = attr(p, "href").orEmpty()
                        if (id.isNotEmpty() && href.isNotEmpty()) {
                            items += ManifestItem(
                                id = id,
                                href = href,
                                mediaType = attr(p, "media-type").orEmpty(),
                                properties = attr(p, "properties").orEmpty()
                            )
                        }
                    }

                    "itemref" -> {
                        val idref = attr(p, "idref")
                        val linear = attr(p, "linear")
                        if (!idref.isNullOrBlank() && !linear.equals("no", true)) spine += idref
                    }
                }

                XmlPullParser.TEXT -> {
                    val t = p.text?.trim().orEmpty()
                    if (t.isNotEmpty() && inMetadata) {
                        when (textTarget) {
                            "title" -> if (title.isNullOrBlank()) title = t
                            "creator" -> if (author.isNullOrBlank()) author = t
                        }
                    }
                }

                XmlPullParser.END_TAG -> when (local(p.name)) {
                    "metadata" -> inMetadata = false
                    "title", "creator" -> textTarget = null
                }
            }
            ev = p.next()
        }
        if (items.isEmpty()) null else Opf(title, author, items, spine)
    }.getOrNull()

    /** 把 manifest 里的相对路径解析成压缩包内的绝对路径（处理 ./ 与 ../） */
    private fun resolve(baseDir: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        val decoded = runCatching { java.net.URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val raw = if (decoded.startsWith("/")) decoded.trimStart('/') else "$baseDir/$decoded"
        val out = ArrayList<String>()
        for (seg in raw.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += seg
            }
        }
        return out.joinToString("/")
    }

    private fun attr(parser: XmlPullParser, name: String): String? {
        return parser.getAttributeValue(null, name)
            ?: run {
                for (i in 0 until parser.attributeCount) {
                    if (local(parser.getAttributeName(i)) == name) return@run parser.getAttributeValue(i)
                }
                null
            }
    }

    private fun local(name: String?): String {
        if (name == null) return ""
        val i = name.indexOf(':')
        return if (i >= 0) name.substring(i + 1) else name
    }

    private companion object {
        val TEXT_EXT = setOf("xhtml", "html", "htm", "xml", "opf", "ncx", "txt")
        const val MAX_ENTRY = 8L * 1024 * 1024
        const val MAX_TOTAL = 96L * 1024 * 1024
    }
}