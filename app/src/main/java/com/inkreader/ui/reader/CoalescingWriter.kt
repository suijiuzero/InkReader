package com.inkreader.ui.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread confined. Idle writers finish; queued values outlive a disposed page. */
internal class CoalescingWriter<T : Any>(
    private val scope: CoroutineScope,
    private val intervalMillis: Long = 120,
    private val onError: (Throwable) -> Unit,
    private val write: suspend (T) -> Unit
) {
    private var pending: T? = null
    private var worker: Job? = null

    fun submit(value: T) {
        pending = value
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = pending ?: break
                pending = null
                try { write(next) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { onError(error) }
                // Throttle, rather than debounce: continuous scrolling still saves
                // periodically, and the final request is never cancelled on page exit.
                delay(intervalMillis)
            }
        }
    }

    suspend fun awaitIdle() { worker?.join() }
}
