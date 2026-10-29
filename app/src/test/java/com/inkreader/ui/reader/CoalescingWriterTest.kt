package com.inkreader.ui.reader

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CoalescingWriterTest {
    @Test fun aBurstWritesOnlyTheLatestPendingValue() = runBlocking {
        val values = arrayListOf<Int>()
        val writer = CoalescingWriter<Int>(this, 1, { throw AssertionError(it) }) { values += it }
        repeat(100) { writer.submit(it) }
        writer.awaitIdle()
        assertEquals(listOf(99), values)
    }

    @Test fun aBusyWriteRetainsTheLastRequestWithoutConcurrentWrites() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val values = arrayListOf<Int>()
        var active = 0; var maximum = 0
        val writer = CoalescingWriter<Int>(this, 1, { throw AssertionError(it) }) {
            active++; maximum = maxOf(active, maximum)
            if (it == 1) { started.complete(Unit); release.await() }
            values += it; active--
        }
        writer.submit(1); started.await()
        for (i in 2..100) writer.submit(i)
        release.complete(Unit); writer.awaitIdle()
        assertEquals(listOf(1, 100), values)
        assertEquals(1, maximum)
    }

    @Test fun aFailedWriteReportsTheErrorAndAllowsTheNextValue() = runBlocking {
        val errors = arrayListOf<Throwable>(); val values = arrayListOf<Int>()
        val writer = CoalescingWriter<Int>(this, 1, { errors += it }) {
            if (it == 1) throw java.io.IOException("injected")
            values += it
        }
        writer.submit(1); writer.awaitIdle()
        writer.submit(2); writer.awaitIdle()
        assertEquals(1, errors.size)
        assertEquals(listOf(2), values)
    }
}
