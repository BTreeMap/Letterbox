package org.joefang.letterbox

import kotlinx.coroutines.CancellationException

/**
 * Run [block], capturing a genuine failure but never a cancellation.
 *
 * `catch (e: Exception)` — and `runCatching`, which is worse — also swallow
 * `CancellationException`. That is not a mishandled error but a non-event
 * promoted to one: it rendered a healthy tunnel as "Failed: The coroutine scope
 * left the composition", and it leaves coroutines running after their caller has
 * given up on them.
 */
internal inline fun <T> attempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/** The failure's message, or [fallback] when it carries none. */
internal fun Throwable.describe(fallback: String): String = message ?: fallback
