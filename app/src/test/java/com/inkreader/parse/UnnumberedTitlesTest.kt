package com.inkreader.parse

import com.inkreader.data.model.SplitOptions
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UnnumberedTitlesTest {
    private val options = SplitOptions(cleanWatermark = false, detectUnnumberedTitles = true)
    @Test fun frozenChineseCollectionTitlesAreRecognizedWithoutRewritingAnswers() {
        val relative = "tests/fixtures/real-source-v3"
        val root = File(relative).takeIf { it.isDirectory } ?: File("../$relative")
        val failures = arrayListOf<String>()
        for (row in root.resolve("manifest.tsv").readLines().drop(1).filter { it.isNotBlank() }) {
            val values = row.split('\t'); val id = values[0]
            val source = root.resolve("$id.txt").readText()
            val gold = SourceGold.read(root.resolve("$id.gold").readText(), source)
            val score = SourceGold.score(ChapterSplitter.analyze(ChapterSplitter.normalize(source), options = options), gold, true)
            println("UNNUMBERED case=$id contract=${values[5]} TP=${score.tp} FP=${score.fp} FN=${score.fn} bodyErrors=${score.bodyErrors}")
            if (!score.exact) failures += "$id $score"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
    @Test fun inferenceIsOptInAndDoesNotInventTitlesFromPlainProseListsOrFences() {
        val source = listOf("遠行", "歸途", "回聲").joinToString("\n\n") { "$it\n\n" + "這是一段完整的敘事文字，保留每個字。".repeat(30) }
        assertTrue(ChapterSplitter.analyze(source).decisions.none { it.accepted })
        assertEquals(3, ChapterSplitter.analyze(source, options = options).decisions.count { it.accepted })
        for (text in listOf("一\n\n二\n\n三", "春風\n\n明月\n\n山河\n\n湖海", "```\n$source\n```",
            "他說\n\n" + "這是正文。".repeat(90) + "\n\n所以\n\n" + "這也是正文。".repeat(90))) {
            assertTrue(ChapterSplitter.analyze(text, options = options).decisions.none { it.accepted })
        }
    }
    @Test fun optionChangesReplanCachedDocumentAndCorrectionsStillOverrideInference() {
        val source = listOf("遠行", "歸途", "回聲").joinToString("\n\n") { "$it\n\n" + "這是完整而獨立的敘事內容。".repeat(40) }
        val directory = java.nio.file.Files.createTempDirectory("ink-natural").toFile()
        try {
            val store = IndexedTxtStore(directory)
            fun count(value: SplitOptions): Int {
                var count = -1
                store.withDocument("collection.txt", value, { source.byteInputStream() }) { doc ->
                    count = doc.plan.decisions.count { it.accepted }
                }
                return count
            }
            assertEquals(0, count(options.copy(detectUnnumberedTitles = false)))
            assertEquals(3, count(options))
            assertEquals(0, count(options.copy(detectUnnumberedTitles = false)))
            val line = ChapterSplitter.analyze(source, options = options).decisions[1].line
            assertEquals(2, count(options.copy(ignoredHeadingLines = setOf(line))))
        } finally { directory.deleteRecursively() }
    }
    @Test fun shortTailNeedsLongPrecedingBodyAndNumberedModeKeepsItsContract() {
        val long = "這是一段完整的敘事文字。".repeat(40)
        val short = "短篇正文還在這裏。".repeat(10)
        val source = "遠行\n\n$long\n\n歸途\n\n$short"
        assertEquals(2, ChapterSplitter.analyze(source, options = options).decisions.count { it.accepted })
        assertTrue(ChapterSplitter.analyze("遠行\n\n$short\n\n歸途\n\n$short", options = options).decisions.none { it.accepted })
        assertTrue(ChapterSplitter.analyze(source, options = options.copy(mode = com.inkreader.data.model.SplitMode.STRICT_CHAP)).decisions.none { it.accepted })
        assertTrue(ChapterSplitter.analyze("遠行\n\n$long\n\n歸途", options = options).decisions.none { it.accepted })
    }
    @Test(timeout = 60000) fun largeCollectionRetainsAllBoundaries() {
        val source = (1..10000).joinToString("\n\n") { "篇名" + ('甲'.code + it % 1000).toChar() + "\n\n" + "這是一段完整的敘事文字。".repeat(30) }
        assertEquals(10000, ChapterSplitter.plan(source.lines(), source.length, options).headings.size)
    }
    @Test fun reconstructedFirstHeadingKeepsRetainedCollectionNameInBody() {
        val body = "完整的故事正文，應當全部保留。".repeat(40)
        val source = "遠行\n\n散文集\n\n$body\n\n歸途\n\n$body"
        val result = ChapterSplitter.analyze(source, options = options.copy(reconstructedFirstTitle = "遠行"))
        assertEquals(listOf("遠行", "歸途"), result.parts.map { it.title })
        assertTrue(result.parts.first().content.startsWith("散文集"))
    }
}
