package com.inkreader.data.repo

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 请求发出时编号，而非 IO 完成时编号；同书只提交最新请求。 */
class ProgressClock {
    private val books = ConcurrentHashMap<Long, AtomicLong>()
    fun next(book: Long): Long = books.getOrPut(book) { AtomicLong() }.incrementAndGet()
    fun isLatest(book: Long, token: Long): Boolean = books[book]?.get() == token
}
