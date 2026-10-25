package com.inkreader.parse

/** Called by bounded loops; preview parsing runs in runInterruptible so superseded work stops. */
internal object ParseCancellation {
    fun checkpoint() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Parsing cancelled")
    }
}
