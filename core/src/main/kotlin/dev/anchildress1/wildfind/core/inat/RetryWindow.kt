package dev.anchildress1.wildfind.core.inat

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
        until = maxOf(until, clock() + (seconds ?: DEFAULT_SECONDS) * MILLIS)
    }

    /** Window defaults. */
    companion object {
        /** The quiet period when a 429 carries no usable Retry-After. */
        const val DEFAULT_SECONDS = 60L

        private const val MILLIS = 1_000L
    }
}
