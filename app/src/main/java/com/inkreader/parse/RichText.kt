package com.inkreader.parse

/**
 * 正文的「样式覆盖」。
 *
 * 墨阅的正文以纯文本落盘，阅读、朗读、搜索、选区、进度全部按「字符偏移」工作。
 * 格式化的 Markdown 正文如果改成存 AST 或 HTML，上面这一整套都要跟着改，
 * 而且任何一处漏改都会让标记泄漏到正文里。
 *
 * 所以这里采取「正文照旧 + 样式旁挂」：
 *  · 正文仍然是剥掉标记后的纯文本，偏移语义完全不变（老功能零改动）；
 *  · 另存一份 [RichChapter]，记录「哪些区间是标题、列表、代码块…」和行内样式区间。
 *
 * 偏移全部相对**本章正文**（长度 = [RichChapter.len]），
 * 读盘时会校验长度，对不上就整体弃用（正文照旧显示），不让一本书打不开。
 */
enum class RichKind {
    /** 普通段落 */
    BODY,

    /** 标题，[RichBlock.level] = 1..6 */
    HEADING,

    /** 列表项，[RichBlock.level] = 嵌套深度（0 起），[RichBlock.marker] = 项目符号 / 序号 */
    LIST,

    /** 引用块，[RichBlock.level] = 嵌套深度（0 起） */
    QUOTE,

    /** 围栏代码块（正文里保留原换行与缩进） */
    CODE,

    /** 表格表头行 */
    TABLE_HEAD,

    /** 表格数据行 */
    TABLE_ROW,

    /** 分隔线（正文是一个空格，渲染时画成一条线） */
    RULE,

    /** Embedded illustration. Body is one object character; marker is a validated local asset reference. */
    IMAGE
}

/** 行内样式区间 */
data class InlineStyle(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false
)

/** 链接区间（只在 http/https/mailto 时保留，其余退化成普通文字） */
data class LinkSpan(val start: Int, val end: Int, val url: String)

/** 一个块：kind / 层级 / 列表标记 + 正文中的区间 */
data class RichBlock(
    val kind: RichKind,
    val level: Int,
    val marker: String,
    val start: Int,
    val end: Int
)

/**
 * 一章的样式覆盖。
 *
 * @param len 该章正文的长度（读盘校验用）
 */
data class RichChapter(
    val len: Int,
    val blocks: List<RichBlock>,
    val spans: List<InlineStyle>,
    val links: List<LinkSpan>
) {

    /** 把覆盖数据写成一行一条的紧凑文本，随正文一起放在应用私有目录 */
    fun encode(): String {
        val sb = StringBuilder(64 + blocks.size * 16 + spans.size * 12)
        sb.append(HEADER).append(' ').append(len).append('\n')
        for (b in blocks) {
            sb.append("K ").append(b.kind.ordinal).append(' ').append(b.level).append(' ')
                .append(b.start).append(' ').append(b.end).append(' ')
                .append(b.marker.replace(' ', '_')).append('\n')
        }
        for (s in spans) {
            sb.append("S ").append(s.start).append(' ').append(s.end).append(' ')
                .append(s.flags()).append('\n')
        }
        for (l in links) {
            sb.append("L ").append(l.start).append(' ').append(l.end).append(' ')
                .append(l.url.replace(' ', '_')).append('\n')
        }
        return sb.toString()
    }

    companion object {

        /** 格式版本号：以后改结构就换个头，旧数据自动作废（不会误读成新结构） */
        private const val HEADER = "ikr1"

        private val KINDS = RichKind.entries.toTypedArray()

        fun decode(raw: String): RichChapter? {
            if (raw.isBlank()) return null
            val lines = raw.lineSequence()
            val head = lines.firstOrNull()?.split(' ') ?: return null
            if (head.size != 2 || head[0] != HEADER) return null
            val len = head[1].toIntOrNull() ?: return null
            if (len <= 0) return null

            val blocks = ArrayList<RichBlock>()
            val spans = ArrayList<InlineStyle>()
            val links = ArrayList<LinkSpan>()
            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val f = line.split(' ')
                when (f.getOrNull(0)) {
                    "K" -> {
                        if (f.size < 6) continue
                        val kind = KINDS.getOrNull(f[1].toIntOrNull() ?: -1) ?: continue
                        val level = f[2].toIntOrNull() ?: 0
                        val start = f[3].toIntOrNull() ?: continue
                        val end = f[4].toIntOrNull() ?: continue
                        if (end <= start) continue
                        blocks += RichBlock(kind, level, f[5].replace('_', ' '), start, end)
                    }

                    "S" -> {
                        if (f.size < 4) continue
                        val flags = f[3].toIntOrNull() ?: continue
                        val start = f[1].toIntOrNull() ?: continue
                        val end = f[2].toIntOrNull() ?: continue
                        if (end <= start) continue
                        spans += InlineStyle(
                            start = start,
                            end = end,
                            bold = flags and 1 != 0,
                            italic = flags and 2 != 0,
                            strike = flags and 4 != 0,
                            code = flags and 8 != 0
                        )
                    }

                    "L" -> {
                        if (f.size < 4) continue
                        val start = f[1].toIntOrNull() ?: continue
                        val end = f[2].toIntOrNull() ?: continue
                        if (end <= start) continue
                        links += LinkSpan(start, end, f[3].replace('_', ' '))
                    }
                }
            }
            if (blocks.isEmpty()) return null
            return RichChapter(len, blocks, spans, links)
        }

        private fun InlineStyle.flags(): Int =
            (if (bold) 1 else 0) or (if (italic) 2 else 0) or
                (if (strike) 4 else 0) or (if (code) 8 else 0)
    }
}