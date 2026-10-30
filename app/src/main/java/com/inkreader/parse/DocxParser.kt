package com.inkreader.parse

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * docx 解析器。
 *
 * 不使用 Apache POI —— POI 在 Android 上需要拖入 javax.xml.stream / AWT 等一大堆
 * 桌面端依赖，包体增加 10-15MB 且容易在 dex 阶段出问题。docx 本质是 ZIP + OOXML，
 * 这里直接用系统自带的 XmlPullParser 解析 word/document.xml，
 * 能拿到同样需要的东西：正文段落、标题层级、表格（降级为制表符文本）、嵌入图片（不支持的媒体保留占位）。
 */
class DocxParser {

    data class Para(val level: Int, val text: String, val hasImage: Boolean)

    data class Parsed(
        val title: String?,
        val author: String?,
        val parts: List<ChapterSplitter.Part>,
        val sourceParts: List<String> = emptyList(),
        val images: Map<String, DocumentImage> = emptyMap()
    ) {
        val sourceText: String? get() = sourceParts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    fun parse(input: InputStream): Parsed {
        val entries = DocxArchive.read(input)
        val documentXml = entries["word/document.xml"] ?: entries["word/document2.xml"]
        val coreXml = entries["docProps/core.xml"]
        val stylesXml = entries["word/styles.xml"]

        val doc = documentXml ?: return Parsed(null, null, emptyList())
        val core = coreXml?.let { parseCore(it) }
        // styles.xml 里的「样式代号 → 标题层级」映射：document.xml 往往只写了 pStyle 的 val
        // （如 `a3`），真正的层级信息在 styles.xml 里，不读它就全被当成正文。
        val styles = stylesXml?.let { parseStyles(it) } ?: emptyMap()

        val media = EmbeddedImages()
        val relationXml = entries[if (documentXml != null && entries.containsKey("word/document.xml")) "word/_rels/document.xml.rels" else "word/_rels/document2.xml.rels"]
        val relations = relationXml?.let { parseRelationships(it, entries, media) }.orEmpty()
        val paragraphs = parseDocument(doc, styles, relations)
        val parts = ChapterAssembler.assemble(
            paragraphs.map { ChapterAssembler.Para(it.level, it.text) }
        )
        return Parsed(core?.first, core?.second, parts, paragraphs.map { it.text }, media.images)
    }

    /**
     * 解析 word/styles.xml，建一张 `styleId -> 标题层级(0..5)` 的表。
     *
     * Word / WPS 生成的文档里，document.xml 的 `<w:pStyle w:val="a3"/>` 只是内部代号，
     * 「这是第几级标题」定义在这里：`<w:outlineLvl w:val="0"/>`，或样式名 `<w:name w:val="heading 1"/>`。
     */
    private fun parseStyles(bytes: ByteArray): Map<String, Int> = runCatching {
        val map = LinkedHashMap<String, DocxStyleLevels.Style>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), null)

        var styleId: String? = null
        var inPPr = false
        var outline = -1
        var name = ""
        var basedOn: String? = null
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            ParseCancellation.checkpoint()
            when (ev) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "style" -> {
                        styleId = attr(parser, "styleId")
                        inPPr = false
                        outline = -1
                        name = ""
                        basedOn = null
                    }

                    "pPr" -> inPPr = true
                    "outlineLvl" -> if (inPPr) {
                        outline = attr(parser, "val")?.trim()?.toIntOrNull() ?: -1
                    }

                    "name" -> if (styleId != null) name = attr(parser, "val") ?: ""
                    "basedOn" -> if (styleId != null) basedOn = attr(parser, "val")
                }

                XmlPullParser.END_TAG -> when (local(parser.name)) {
                    "pPr" -> inPPr = false
                    "style" -> {
                        styleId?.let { id ->
                            map[id] = DocxStyleLevels.Style(name, outline.takeIf { it >= 0 }, basedOn)
                        }
                        styleId = null
                    }
                }
            }
            ev = parser.next()
        }
        DocxStyleLevels.resolve(map)
    }.getOrDefault(emptyMap())

    /** 返回 (title, author) */
    private fun parseCore(bytes: ByteArray): Pair<String?, String?>? {
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(ByteArrayInputStream(bytes), null)
            var title: String? = null
            var author: String? = null
            var inTitle = false
            var inCreator = false
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
            ParseCancellation.checkpoint()
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val n = local(parser.name)
                        if (n == "title") inTitle = true
                        if (n == "creator") inCreator = true
                    }

                    XmlPullParser.TEXT -> {
                        val t = parser.text ?: ""
                        if (inTitle && title.isNullOrBlank()) title = t.trim()
                        if (inCreator && author.isNullOrBlank()) author = t.trim()
                    }

                    XmlPullParser.END_TAG -> {
                        val n = local(parser.name)
                        if (n == "title") inTitle = false
                        if (n == "creator") inCreator = false
                    }
                }
                ev = parser.next()
            }
            title?.takeIf { it.isNotEmpty() } to author?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun parseRelationships(bytes: ByteArray, entries: Map<String, ByteArray>, media: EmbeddedImages): Map<String, String> {
        val result = HashMap<String, String>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(bytes), null)
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            ParseCancellation.checkpoint()
            if (event == XmlPullParser.START_TAG && local(parser.name) == "Relationship" && !attr(parser, "TargetMode").equals("External", true)) {
                val id = attr(parser, "Id"); val target = attr(parser, "Target")
                if (id != null && target != null && attr(parser, "Type")?.endsWith("/image") == true)
                    media.fromArchive(target, "word", entries)?.let { result[id] = it }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseDocument(bytes: ByteArray, styles: Map<String, Int>, relations: Map<String, String>): List<Para> {
        val out = ArrayList<Para>(256)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), null)

        var inP = false
        var inT = false
        var inCell = false
        var tableDepth = 0
        var pStyle = ""
        var pOutline = -1
        var hasDrawing = false
        var resolvedImage = false
        var numPrSeen = false
        val pText = StringBuilder()
        val cellBuf = StringBuilder()
        val rowBuf = StringBuilder()

        val alternatives = ArrayList<Pair<Int, Boolean>>()
        var skipDepth = -1
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            ParseCancellation.checkpoint()
            if (skipDepth >= 0) {
                if (ev == XmlPullParser.END_TAG && parser.depth == skipDepth) skipDepth = -1
                ev = parser.next(); continue
            }
            val tag = local(parser.name)
            if (ev == XmlPullParser.START_TAG && tag == "AlternateContent") alternatives += parser.depth to false
            if (ev == XmlPullParser.START_TAG && tag in setOf("Choice", "Fallback") &&
                alternatives.lastOrNull()?.first == parser.depth - 1) {
                if (alternatives.last().second) { skipDepth = parser.depth; ev = parser.next(); continue }
                alternatives[alternatives.lastIndex] = alternatives.last().first to true
            }
            if (ev == XmlPullParser.END_TAG && tag == "AlternateContent" && alternatives.isNotEmpty()) alternatives.removeAt(alternatives.lastIndex)
            when (ev) {
                XmlPullParser.START_TAG -> {
                    when (local(parser.name)) {
                        "p" -> {
                            inP = true
                            pStyle = ""
                            pOutline = -1
                            hasDrawing = false
                            resolvedImage = false
                            numPrSeen = false
                            pText.setLength(0)
                        }

                        "pStyle" -> if (inP) {
                            pStyle = attr(parser, "val") ?: ""
                        }

                        "outlineLvl" -> if (inP) {
                            pOutline = attr(parser, "val")?.trim()?.toIntOrNull() ?: -1
                        }

                        "numPr" -> if (inP) numPrSeen = true
                        "t" -> inT = true
                        "tab" -> if (inP) pText.append('\t')
                        "br", "cr" -> if (inP) pText.append('\n')
                        "drawing", "pict", "object" -> if (inP) hasDrawing = true
                        "blip", "imagedata" -> if (inP) {
                            hasDrawing = true
                            val id = attr(parser, "embed") ?: attr(parser, "id")
                            (if (id != null) relations[id] else null)?.let { token ->
                                if (pText.isNotEmpty()) pText.append("\n\n")
                                pText.append(token).append("\n\n")
                                resolvedImage = true
                            }
                        }
                        "tbl" -> tableDepth++
                        "tr" -> rowBuf.setLength(0)
                        "tc" -> {
                            inCell = true
                            cellBuf.setLength(0)
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    if (inT && inP) pText.append(parser.text ?: "")
                }

                XmlPullParser.END_TAG -> {
                    when (local(parser.name)) {
                        "t" -> inT = false
                        "tc" -> {
                            rowBuf.append(cellBuf.toString().trim()).append('\t')
                            inCell = false
                        }

                        "tr" -> {
                            val row = rowBuf.toString().trim().trimEnd('\t')
                            if (row.isNotEmpty()) out += Para(BODY, row, false)
                        }

                        "tbl" -> {
                            tableDepth = (tableDepth - 1).coerceAtLeast(0)
                        }

                        "p" -> {
                            var txt = pText.toString()
                            if (hasDrawing && !resolvedImage && txt.isBlank()) txt = IMAGE_PLACEHOLDER
                            else if (hasDrawing && !resolvedImage) txt = "$txt $IMAGE_PLACEHOLDER"
                            txt = txt.replace("\u00A0", " ").trimEnd()
                            val level = headingLevel(pStyle, pOutline, numPrSeen, styles)
                            if (tableDepth > 0) {
                                // 表格单元格内的段落：并进单元格缓冲，避免与行文本重复
                                if (txt.isNotBlank()) {
                                    if (cellBuf.isNotEmpty()) cellBuf.append('\n')
                                    cellBuf.append(txt)
                                }
                            } else if (txt.isNotBlank()) {
                                if (resolvedImage) txt.split("\n\n").filter { it.isNotBlank() }.forEach { part ->
                                    out += Para(if (DocumentImages.isToken(part)) BODY else level, part, DocumentImages.isToken(part))
                                } else out += Para(level, txt, hasDrawing)
                            }
                            inP = false
                        }
                    }
                }
            }
            ev = parser.next()
        }
        return out
    }

    /**
     * 从段落样式推断标题层级，返回 0..5；正文返回 BODY(-1)。
     *
     * 优先查 styles.xml 给出的映射（能认 `a3` 这类内部代号），认不出再退回按样式名猜；
     * 两者都没有时用段落自身的 `<w:outlineLvl>`。
     */
    private fun headingLevel(style: String, outline: Int, numPr: Boolean, styles: Map<String, Int>): Int =
        DocxStyleLevels.paragraph(style, outline, styles)

    private fun attr(parser: XmlPullParser, name: String): String? {
        // 命名空间关闭时属性名可能带前缀（w:val），两种都试一遍
        return parser.getAttributeValue(null, name)
            ?: parser.getAttributeValue(null, "w:$name")
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

    companion object {
        const val BODY = -1
        const val IMAGE_PLACEHOLDER = "〔图片〕"

    }
}
