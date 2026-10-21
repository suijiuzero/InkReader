package com.inkreader.data.repo

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 同书的快照、正文文件、批注与数据库提交共用一把锁；嵌套仓库操作可重入。 */
class BookWriteGate {
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private class Held(val gate: BookWriteGate, val id: Long) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }
    suspend fun <T> write(id: Long, block: suspend () -> T): T {
        val held = currentCoroutineContext()[Held]
        if (held?.gate === this && held.id == id) return block()
        return locks.getOrPut(id) { Mutex() }.withLock {
            withContext(Held(this, id)) { block() }
        }
    }
}
