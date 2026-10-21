package com.inkreader.data.repo

import com.inkreader.parse.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MutationSafetyTest {
    @Test fun scatteredEditsPreserveAllSurvivingUniqueCharacterAnchors() {
        val random = kotlin.random.Random(217)
        val before = (0 until 40).map { ('一'.code + it).toChar() }.joinToString("")
        repeat(200) {
            var after = before
            repeat(8) {
                val at = random.nextInt(after.length + 1)
                after = if (random.nextBoolean() || at == after.length)
                    after.substring(0, at) + "XX" + after.substring(at)
                else after.removeRange(at, minOf(after.length, at + random.nextInt(1, 4)))
            }
            val map = TextEditMap(before, after)
            before.forEachIndexed { index, char ->
                val location = after.indexOf(char)
                if (location >= 0) assertEquals("$index in $after", location to location + 1, map.range(index, index + 1))
            }
        }
    }
    @Test fun multipleEditsKeepUnchangedMiddleAnnotation() {
        val edit = TextEditMap("abcdefghi", "XXabcdeYYfghiZZ")
        assertEquals(4 to 6, edit.range(2,4))
        assertEquals(9 to 11, edit.range(5,7))
        assertEquals(13, edit.offset(9, true))
    }
    @Test fun prefixInsertionShiftsAnnotationsAndStylesTogether() {
        val edit = TextEditMap("abcdef", "XXabcdef")
        assertEquals(4 to 6, edit.range(2,4))
        val rich = RichChapter(6,listOf(RichBlock(RichKind.CODE,0,"",0,6)),listOf(InlineStyle(2,4,bold=true)),listOf(LinkSpan(2,4,"https://x.com")))
        val next = RichTransforms.edit(rich, edit)!!
        assertEquals(InlineStyle(4,6,bold=true), next.spans.single())
        assertEquals(4, next.links.single().start)
        assertEquals(RichKind.CODE, next.blocks.single().kind)
        assertNotNull(RichChapter.decode(next.encode()))
    }
    @Test fun insertionAtRangeEndDoesNotExtendPriorAnnotation() {
        val edit = TextEditMap("abcd", "abXXcd")
        assertEquals(0 to 2, edit.range(0,2))
        assertEquals(4 to 6, edit.range(2,4))
    }
    @Test fun deletionKeepsRangesValidAndPreservesFollowingAnchors() {
        val edit = TextEditMap("abcdef", "abef")
        assertEquals(2 to 2, edit.range(2,4))
        assertEquals(2 to 4, edit.range(4,6))
        assertEquals(4, edit.offset(6))
    }
    @Test fun splitThenMergePreservesInlineStylesAndLinks() {
        val text="abcd\n\nefgh"
        val rich=RichChapter(text.length,listOf(RichBlock(RichKind.BODY,0,"",0,4),RichBlock(RichKind.CODE,0,"",6,10)),
            listOf(InlineStyle(1,3,bold=true),InlineStyle(7,9,code=true)),listOf(LinkSpan(7,9,"https://x.com")))
        val first=RichTransforms.slice(rich,text,0,4)
        val second=RichTransforms.slice(rich,text,6,10)
        val combined=RichTransforms.merge(text,first,second,6)!!
        assertEquals(rich.spans,combined.spans)
        assertEquals(rich.links,combined.links)
        assertEquals(rich.blocks,combined.blocks)
    }
    @Test fun progressRequestsRejectAnOlderCompletion() {
        val clock=ProgressClock()
        val a=clock.next(1); val b=clock.next(1)
        assertFalse(clock.isLatest(1,a)); assertTrue(clock.isLatest(1,b))
        clock.next(2)
        assertTrue(clock.isLatest(1,b))
    }
    @Test fun bookWritesAreSerializedAndNestedOperationsDoNotDeadlock() = runBlocking {
        val gate=BookWriteGate()
        val held=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        var value=0
        val a=async(Dispatchers.Default) { gate.write(1) { held.complete(Unit); release.await(); gate.write(1) { value++ } } }
        held.await()
        val b=async(Dispatchers.Default) { gate.write(1) { value++ } }
        yield()
        assertEquals(0,value)
        release.complete(Unit)
        withTimeout(2000) { a.await(); b.await() }
        assertEquals(2,value)
    }
    @Test fun cancellingWriterReleasesTheBookGate() = runBlocking {
        val gate=BookWriteGate(); val held=CompletableDeferred<Unit>()
        val writer=launch { gate.write(1) { held.complete(Unit); awaitCancellation() } }
        held.await(); writer.cancelAndJoin()
        assertEquals(7,withTimeout(2000) { gate.write(1) { 7 } })
    }
}
