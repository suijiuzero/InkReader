package com.inkreader.parse

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * docx 解析器。
 *
 * 不使用 Apache POI —— POI 在 Android 上需要拖入 javax.xml.stream / AWT 等一大堆
 * 桌面端依赖，包体增加 10-15MB 且容易在 dex 阶段出问题。docx 本质是 ZIP + OOXML，
 * 这里直接用系统自带的 XmlPullParser 解析 word/document.xml，
 * 能拿到同样需要的东西：正文段落、标题层级、表格（降级为制表符文本）、图片（占位符）。
 */
class DocxParser {

    data class Para(val level: Int, val text: String, val hasImage: Boolean)

    data class Parsed(
        val title: String?,
        val author: String?,
        val parts: List<ChapterSplitter.Part>
    )

    fun parse(input: InputStream): Parsed {
        var documentXml: ByteArray? = null
        var coreXml: ByteArray? = null

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                when {
                    name == "word/document.xml" -> documentXml = readEntry(zip)
                    name == "docProps/core.xml" -> coreXml = readEntry(zip)
                    name == "word/document2.xml" -> if (documentXml == null) documentXml = readEntry(zip)
                }
                zip.closeEntry()
                if (documentXml != null && coreXml != null) break
            }
        }

        val doc = documentXml ?: return Parsed(null, null, emptyList())
        val core = coreXml?.let { parseCore(it) }

        val paragraphs = parseDocument(doc)
        val parts = ChapterAssembler.assemble(
            paragraphs.map { ChapterAssembler.Para(it.level, it.text) }
        )
        return Parsed(core?.first, core?.second, parts)
    }

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

    private fun parseDocument(bytes: ByteArray): List<Para> {
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
        var numPrSeen = false
        val pText = StringBuilder()
        val cellBuf = StringBuilder()
        val rowBuf = StringBuilder()

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    when (local(parser.name)) {
                        "p" -> {
                            inP = true
                            pStyle = ""
                            pOutline = -1
                            hasDrawing = false
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
                        "drawing", "pict", "object", "imagedata" -> if (inP) hasDrawing = true
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
                            if (hasDrawing && txt.isBlank()) txt = IMAGE_PLACEHOLDER
                            else if (hasDrawing) txt = "$txt $IMAGE_PLACEHOLDER"
                            txt = txt.replace("\u00A0", " ").trimEnd()
                            val level = headingLevel(pStyle, pOutline, numPrSeen)
                            if (tableDepth > 0) {
                                // 表格单元格内的段落：并进单元格缓冲，避免与行文本重复
                                if (txt.isNotBlank()) {
                                    if (cellBuf.isNotEmpty()) cellBuf.append('\n')
                                    cellBuf.append(txt)
                                }
                            } else if (txt.isNotBlank()) {
                                out += Para(level, txt, hasDrawing)
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
     * 兼容 Word 中文/英文模板与 WPS 的多种样式命名。
     */
    private fun headingLevel(style: String, outline: Int, numPr: Boolean): Int {
        if (style.isNotEmpty()) {
            val s = style.trim()
            Regex("(?i)^heading\\s*([1-6])$").find(s)?.let {
                return it.groupValues[1].toInt() - 1
            }
            Regex("^([1-6])$").find(s)?.let { return it.groupValues[1].toInt() - 1 }
            Regex("^标题\\s*([1-6])$").find(s)?.let { return it.groupValues[1].toInt() - 1 }
            Regex("(?i)^title$").find(s)?.let { return 0 }
            Regex("(?i)^subtitle$").find(s)?.let { return 1 }
        }
        if (outline in 0..5) return outline
        return BODY
    }

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

    /**
     * 分块读一个 ZIP 条目，累计超过 [MAX_ENTRY_BYTES] 立即抛异常。
     * 流式 ZIP 的 entry.size 常为 -1，不能拿它当闸门，只能边读边数。
     */
    private fun readEntry(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            if (out.size() + n.toLong() > MAX_ENTRY_BYTES) {
                throw IllegalStateException("zip 条目过大，已拒绝解析")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val BODY = -1
        const val IMAGE_PLACEHOLDER = "〔图片〕"

        /**
         * 单个 XML 条目的解压体积上限。正常 docx 的 document.xml 远小于此，
         * 超过基本可判定为畸形/压缩炸弹，直接拒绝（外层会包成 CORRUPTED）。
         */
        private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    }
}
