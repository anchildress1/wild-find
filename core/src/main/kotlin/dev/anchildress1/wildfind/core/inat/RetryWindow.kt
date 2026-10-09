package dev.anchildress1.wildfind.core.inat

import dev.anchildress1.wildfind.core.hunt.Sighting

/**
 * iNat's Retry-After window: after a 429, no request goes out until it passes, and the cache answers instead, so the
 * widened query never fires inside it and no thread sleeps waiting.
 *
 * @param clock monotonic milliseconds
 */
class RetryWindow(private val clock: () -> Long) {
    private var until = Long.MIN_VALUE

    /** True while iNat asked for quiet. */
    val open: Boolean
        @Synchronized get() = clock() < until

    /** Records a 429 that asked for [seconds], or [DEFAULT_SECONDS] when it gave none; a longer earlier ask stands. */
    @Synchronized
    fun rateLimited(seconds: Long?) {
        // A huge Retry-After would overflow to a past time and reopen the window at once.
        val wait = (seconds ?: DEFAULT_SECONDS).coerceAtMost(MAX_SECONDS)
        until = maxOf(until, clock() + wait * MILLIS)
    }

    /**
     * [fetch]'s sightings, or null so the cache answers: inside the window nothing goes out, so the widened query
     * never fires there; a 429 opens the window; a failure is just null.
     */
    fun pull(fetch: () -> Pull): List<Sighting>? {
        if (open) return null
        return when (val pull = fetch()) {
            is Pull.Pulled -> pull.sightings

            is Pull.RateLimited -> {
                rateLimited(pull.retryAfterSeconds)
                null
            }

            Pull.Failed -> null
        }
    }

    /** Window defaults. */
    companion object {
        /** The quiet period when a 429 carries no usable Retry-After. */
        const val DEFAULT_SECONDS = 60L

        /** Longest quiet honored: a day, far past any real Retry-After. */
        const val MAX_SECONDS = 86_400L

        private const val MILLIS = 1_000L
    }
}
