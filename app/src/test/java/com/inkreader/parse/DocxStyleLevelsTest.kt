package com.inkreader.parse

import org.junit.Assert.*
import org.junit.Test

class DocxStyleLevelsTest {
    @Test fun numericNormalWebStyleIsBodyRatherThanAHeading() {
        val styles = DocxStyleLevels.resolve(mapOf("1" to DocxStyleLevels.Style("Normal"),
            "2" to DocxStyleLevels.Style("Normal (Web)", basedOn = "1")))
        assertEquals(-1, DocxStyleLevels.paragraph("2", -1, styles))
        assertEquals(-1, DocxStyleLevels.paragraph("2", -1, emptyMap()))
    }
    @Test fun explicitParagraphBodyOverridesAHeadingStyle() {
        assertEquals(-1, DocxStyleLevels.paragraph("Heading1", 9, mapOf("Heading1" to 0)))
        assertEquals(2, DocxStyleLevels.paragraph("Heading1", 2, mapOf("Heading1" to 0)))
    }
    @Test fun namedAndInheritedHeadingsRemainSupportedIncludingNumericIds() {
        val styles = DocxStyleLevels.resolve(mapOf("2" to DocxStyleLevels.Style("Heading 2"),
            "custom" to DocxStyleLevels.Style("Custom", basedOn = "2"),
            "body" to DocxStyleLevels.Style("Body", 9, "2")))
        assertEquals(1, styles["2"])
        assertEquals(1, styles["custom"])
        assertEquals(-1, styles["body"])
        assertEquals(0, DocxStyleLevels.paragraph("Heading1", -1, emptyMap()))
    }
    @Test fun cyclesAndMissingParentsStayBody() {
        val styles = DocxStyleLevels.resolve(mapOf("a" to DocxStyleLevels.Style(basedOn = "b"),
            "b" to DocxStyleLevels.Style(basedOn = "a"), "c" to DocxStyleLevels.Style(basedOn = "missing")))
        assertTrue(styles.values.all { it == -1 })
    }
}
