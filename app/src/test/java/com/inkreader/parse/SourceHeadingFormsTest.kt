package com.inkreader.parse

import org.junit.Assert.*
import org.junit.Test

class SourceHeadingFormsTest {
    @Test fun sourceBlockSeamsAllowOnlyBoundaryWhitespaceDifferences() {
        val gold = SourceGold.Gold(emptyList(), emptySet(), listOf("alpha beta"))
        assertEquals(0, SourceGold.blockErrors(listOf("alpha", "beta"), gold))
        assertEquals(0, SourceGold.blockErrors(listOf("alpha be", "ta"), gold))
        assertTrue(SourceGold.blockErrors(listOf("alphabeta"), gold) > 0)
        assertTrue(SourceGold.blockErrors(listOf("alpha be", "Xa"), gold) > 0)
        assertTrue(SourceGold.blockErrors(listOf("alpha beta", "alpha beta"), gold) > 0)
    }
    @Test fun dotlessRomanSeriesIgnoresOrdinaryFirstPersonParagraphs() {
        val titles = listOf("I The Opening", "II Another Place", "III The End")
        val source = titles.joinToString("\n\n") { "$it\n\n" + "I was walking beside the old house and recalling a long journey. ".repeat(3) + "\n\n" + "A detailed narrative. ".repeat(12) }
        assertEquals(titles, ChapterSplitter.analyze(source).decisions.filter { it.accepted }.map { it.title })
        assertEquals(1, ChapterSplitter.chapterOrderFromTitle("第01齣 標目"))
    }
    @Test fun westernRomanAndFirstOrdinalLabelsKeepNumberBoundaries() {
        for ((title, expected) in listOf("CHAPITRE IV. Tempête" to 4, "Capítulo primero. Inicio" to 1,
            "Capítulo II. Salida" to 2, "Capitolo primo. Inizio" to 1, "Kapitel IX. Ende" to 9)) {
            assertEquals(title, expected, ChapterSplitter.chapterOrderFromTitle(title))
        }
    }
    @Test fun ordinaryWesternWordsAndInvalidRomansAreNotConvertedToNumbers() {
        for (title in listOf("Chapitre civil", "Capítulo civil", "Kapitel Mixologie", "Chapitre IIV", "Capitolo IVabc")) {
            assertEquals(title, title, HeadingNumbers.canonical(title))
        }
    }
    @Test fun sourceLineAnnotationsSurviveCollapsedBlankLinesWithoutChangingFrozenAnswers() {
        val source = "Book\n\nChapter I\n\nalpha\n\n\n\nChapter II\n\nbeta"
        val gold = SourceGold.read("T\tChapter I\nL\t3\nT\tChapter II\nL\t9\n", source)
        assertEquals(setOf(3, 7), gold.lines)
        assertEquals(ChapterSplitter.normalize(source), SourceGold.normalizeContract(source))
    }
    @Test fun longRomanTitlesNeedAConsecutiveBodyBackedSeries() {
        val titles = listOf("I.", "II.", "III.").map { "$it " + "A long original chapter subtitle with details ".repeat(3).trim() }
        val source = titles.joinToString("\n\n") { "$it\n\n" + "A complete narrative paragraph for this chapter. ".repeat(8) }
        assertEquals(titles, ChapterSplitter.analyze(source).decisions.filter { it.accepted }.map { it.title })
        val isolated = titles.first() + "\n\n" + "An ordinary paragraph. ".repeat(30)
        assertTrue(ChapterSplitter.analyze(isolated).decisions.none { it.accepted })
        val shortList = listOf("I. One", "II. Two", "III. Three").joinToString("\n\n") { "$it\n\nA short list entry." }
        assertTrue(ChapterSplitter.analyze(shortList).decisions.none { it.accepted })
    }
}
