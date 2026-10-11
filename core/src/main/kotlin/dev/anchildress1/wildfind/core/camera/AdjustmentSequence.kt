package dev.anchildress1.wildfind.core.camera

/**
 * One sequence for every camera adjustment, focus and zoom alike, so only the newest one's outcome reaches the kid.
 * Not thread-safe: begin and settle on one thread, which orders a completion against any newer registration.
 */
class AdjustmentSequence {
    private var newest = 0L

    /** Whether the newest adjustment failed. */
    var failed = false
        private set

    /** Starts an adjustment that supersedes every earlier one and returns its token. */
    fun begin(): Long {
        failed = false
        return ++newest
    }

    /** Records [token]'s outcome; false, and nothing changes, once a newer adjustment began. */
    fun settle(token: Long, failure: Boolean): Boolean {
        if (token != newest) return false
        failed = failure
        return true
    }
}
