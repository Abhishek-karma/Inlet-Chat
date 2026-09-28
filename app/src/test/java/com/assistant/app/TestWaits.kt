package com.assistant.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val STORE_WAIT_MS = 10_000L
private const val STORE_POLL_MS = 50L

private class Emitted<T>(val value: T)

/**
 * Collects [this] until [predicate] matches, bounded to [STORE_WAIT_MS] of
 * wall time. DataStore flows read a real file on DataStore's own IO scope, so
 * an unbounded `first { }` inside `runTest` can outlive the 60s test wall and
 * fail the whole suite with UncompletedCoroutinesError; this fails fast with
 * the last observed value instead.
 */
suspend fun <T> Flow<T>.firstBounded(
    what: String = "matching value",
    predicate: (T) -> Boolean = { true },
): T = withContext(Dispatchers.IO) {
    // Runs off the test dispatcher so withTimeoutOrNull uses the real clock:
    // inside runTest the virtual clock advances past the timeout instantly.
    val deadline = System.currentTimeMillis() + STORE_WAIT_MS
    var last: T? = null
    while (true) {
        // Wrapped so a null emission is distinct from a read timeout.
        val outcome = withTimeoutOrNull(STORE_WAIT_MS) { Emitted(first()) }
        if (outcome != null) {
            last = outcome.value
            if (predicate(outcome.value)) return@withContext outcome.value
        }
        if (System.currentTimeMillis() >= deadline) {
            throw AssertionError(
                "Timed out after ${STORE_WAIT_MS}ms waiting for $what; last value: $last",
            )
        }
        Thread.sleep(STORE_POLL_MS)
    }
    error("unreachable")
}
