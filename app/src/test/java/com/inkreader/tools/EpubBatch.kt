package com.inkreader.tools

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import com.inkreader.parse.ChapterSplitter
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * EPUB 批量体检：解包 container.xml → OPF → spine，抽取正文后跑生产分章器。
 * App 的 EpubParser 依赖 android.util.Xml，无法在 JVM 里直接跑；这里用等价的
 * XML 解析实现了同样的正文抽取，用于发现分章算法在 EPUB 上的问题。
 *
 * 用法：EpubBatch <目录或单个epub> [抽样数量]
 */
object EpubBatch {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0])
        val limit = args.getOrNull(1)?.toIntOrNull() ?: 0
        val files = (if (root.isFile) sequenceOf(root) else root.walkTopDown())
            .filter { it.isFile && it.extension.equals("epub", true) }
            .sortedBy { it.path }
            .let { if (limit > 0) it.take(limit) else it }
            .toList()
        println("epub\t" + files.size)
        val sb = StringBuilder()
        sb.append("file\tchars\tchapters\tmaxChapChars\temptyBody\tfirstTitles\n")
        var failed = 0
        for (f in files) {
            val text = runCatching { extract(f) }.getOrNull()
            if (text == null || text.length < 500) { failed++; println("skip\t" + f.name); continue }
            val parts = runCatching {
                ChapterSplitter.split(text, f.nameWithoutExtension, SplitOptions(mode = SplitMode.SMART))
            }.getOrNull()
            if (parts == null) { failed++; println("fail\t" + f.name); continue }
            val empty = parts.count { it.content.isBlank() }
            val maxc = parts.maxOfOrNull { it.content.length } ?: 0
            sb.append(f.name).append('\t').append(text.length).append('\t').append(parts.size)
                .append('\t').append(maxc).append('\t').append(empty)
                .append('\t').append(parts.take(3).joinToString(" | ") { it.title.take(18) })
                .append('\n')
            println("done\t" + f.name + "\t" + parts.size)
        }
        val report = File(if (root.isDirectory) root else root.parentFile, "epub-report.tsv")
        report.writeText(sb.toString(), Charsets.UTF_8)
        println("report\t" + report.path + "\tfailed=" + failed)
    }

    private fun extract(f: File): String {
        ZipFile(f).use { zip ->
            val container = zip.getEntry("META-INF/container.xml")
                ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("container.xml", true) }
            val opfPath = container?.let { parseContainer(zip.getInputStream(it).readBytes()) }
            val opf = opfPath?.let { p -> zip.getEntry(p)?.let { parseOpf(zip.getInputStream(it).readBytes()) } }
                ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".opf", true) }
                    ?.let { e -> parseOpf(zip.getInputStream(e).readBytes()) }
                ?: return ""
            val base = opfPath?.substringBeforeLast('/', "") ?: ""
            val sb = StringBuilder()
            for (href in opf.spineHrefs) {
                val full = if (base.isEmpty()) href else "$base/$href"
                val entry = zip.getEntry(full) ?: zip.getEntry(full.substringAfter('/'))
                if (entry == null) continue
                val raw = zip.getInputStream(entry).readBytes()
                val html = String(raw, charsetOf(raw))
                val body = toText(html)
                if (body.isNotBlank()) {
                    sb.append(body).append("\n\n")
                    if (sb.length > 4_000_000) break
                }
            }
            return sb.toString()
        }
    }

    private fun charsetOf(raw: ByteArray): java.nio.charset.Charset {
        val head = String(raw, 0, minOf(raw.size, 300), Charsets.ISO_8859_1)
        val m = Regex("(?i)encoding=[\"']([A-Za-z0-9\\-]+)[\"']").find(head)
        return runCatching { java.nio.charset.Charset.forName(m?.groupValues?.get(1) ?: "UTF-8") }
            .getOrDefault(Charsets.UTF_8)
    }

    private fun toText(html: String): String {
        var s = html
        s = Regex("(?is)<(script|style)[^>]*>.*?</\\1>").replace(s, " ")
        s = Regex("(?i)</?(p|div|br|h[1-6]|li|tr)[^>]*>").replace(s, "\n")
        s = Regex("(?s)<[^>]+>").replace(s, "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        s = Regex("&#(\\d+);").replace(s) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        return s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun parseContainer(xml: ByteArray): String? = runCatching {
        doc(xml).getElementsByTagName("rootfile").item(0)?.attributes?.getNamedItem("full-path")?.nodeValue
    }.getOrNull()

    private data class Opf(val spineHrefs: List<String>)

    private fun parseOpf(xml: ByteArray): Opf? = runCatching {
        val d = doc(xml)
        val manifest = HashMap<String, String>()
        val items = d.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val a = items.item(i).attributes ?: continue
            val id = a.getNamedItem("id")?.nodeValue ?: continue
            val href = a.getNamedItem("href")?.nodeValue ?: continue
            val type = a.getNamedItem("media-type")?.nodeValue ?: ""
            if (type.contains("html", true) || href.endsWith(".xhtml", true) || href.endsWith(".html", true))
                manifest[id] = href
        }
        val hrefs = ArrayList<String>()
        val spine = d.getElementsByTagName("itemref")
        for (i in 0 until spine.length) {
            val ref = spine.item(i).attributes?.getNamedItem("idref")?.nodeValue ?: continue
            manifest[ref]?.let { hrefs.add(it) }
        }
        Opf(if (hrefs.isNotEmpty()) hrefs else manifest.values.toList())
    }.getOrNull()

    private fun doc(xml: ByteArray): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        return f.newDocumentBuilder().parse(java.io.ByteArrayInputStream(xml))
    }
}
