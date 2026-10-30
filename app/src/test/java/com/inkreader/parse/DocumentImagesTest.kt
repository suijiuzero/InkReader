package com.inkreader.parse

import com.inkreader.data.repo.TextEditMap
import org.junit.Assert.*
import org.junit.Test

class DocumentImagesTest {
    private val key = "a".repeat(64)
    private val image = DocumentImage(key, byteArrayOf(1), 800, 400)
    @Test fun referencesCannotOpenPathsOrFetchUrlsAndDimensionsAreBounded() {
        assertNull(DocumentImages.reference("../../secret:1:1"))
        assertNull(DocumentImages.reference("https://example.com:800:400"))
        assertNull(DocumentImages.reference("$key:0:1"))
        assertNull(DocumentImages.reference("$key:1:100001"))
        assertEquals(ImageReference(key, 800, 400), DocumentImages.reference("$key:800:400"))
    }
    @Test fun inlineImagesBecomeBlocksWhileOrdinaryObjectCharactersStayText() {
        val source = "\uFFFC\n\n前文${DocumentImages.token(image)}后文"
        val rendered = DocumentImages.render(source, mapOf(key to image))
        assertEquals("\uFFFC\n\n前文\n\n\uFFFC\n\n后文", rendered.text)
        val images = rendered.rich!!.blocks.filter { it.kind == RichKind.IMAGE }
        assertEquals(1, images.size)
        assertEquals("\uFFFC", rendered.text.substring(images.single().start, images.single().end))
        assertEquals(setOf(key), DocumentImages.references(rendered.rich))
        assertEquals(rendered.rich, RichChapter.decode(rendered.rich.encode()))
        assertEquals(DocumentImages.token(image), DocumentImages.render(DocumentImages.token(image), emptyMap()).text)
    }
    @Test fun slicingMergingAndEditingPreserveOnlyTheActualImageMarker() {
        val original = DocumentImages.render("前文\n\n${DocumentImages.token(image)}\n\n后文", mapOf(key to image))
        val editedText = original.text.replace("前文", "改过的前文").replace("\n\n", " ")
        val edited = RichTransforms.edit(original.rich, TextEditMap(original.text, editedText))!!
        val block = edited.blocks.single { it.kind == RichKind.IMAGE }
        assertEquals("\uFFFC", editedText.substring(block.start, block.end))
        assertEquals(setOf(key), DocumentImages.references(edited))
        val first = RichTransforms.slice(edited, editedText, 0, block.end)
        val second = RichTransforms.slice(edited, editedText, block.end, editedText.length)
        val merged = RichTransforms.merge(editedText, first, second, block.end)!!
        assertEquals(setOf(key), DocumentImages.references(merged))
        val removed = RichTransforms.edit(merged, TextEditMap(editedText, editedText.replace("\uFFFC", "")))
        assertTrue(DocumentImages.references(removed).isEmpty())
        assertTrue(removed!!.blocks.none { it.kind == RichKind.IMAGE })
    }
    @Test fun oversizedCompressedImageIsRejectedBeforeDecoding() {
        try { DocumentImages.fromBytes(ByteArray(16 * 1024 * 1024 + 1)); fail("Oversized image accepted") }
        catch (e: ParseException) { assertEquals(ParseErrorCode.TOO_LARGE, e.code) }
    }
    @Test fun imageHeightRespectsBothIntrinsicSizeAndPageBudget() {
        assertEquals(200f, ImageReference(key, 800, 400).heightAt(400f, 1000f), .001f)
        assertEquals(100f, ImageReference(key, 800, 400).heightAt(400f, 100f), .001f)
        assertEquals(32f, ImageReference(key, 16, 16).heightAt(400f, 1000f, 2f), .001f)
    }
}
