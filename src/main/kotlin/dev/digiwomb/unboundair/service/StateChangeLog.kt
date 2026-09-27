package dev.digiwomb.unboundair.service

/**
 * Reports a value only when it differs from the previous one (DL-02).
 *
 * The service loop asks the scanner for its status every few seconds. Logging
 * every answer would produce a line every three seconds forever -- twenty
 * thousand lines a day saying nothing happened -- and bury the handful of lines
 * that matter. DL-02 therefore demands exactly one entry per state *change*.
 *
 * The rule is small but easy to get subtly wrong, which is why it lives in its
 * own class instead of as an `if` inside the loop:
 *
 * - The **first** observation is a change. There is no previous state, and
 *   "scanner is offline" on startup is information the operator needs.
 * - A repeat of the current state is **not** a change, no matter how often it
 *   is observed.
 * - Returning to an earlier state **is** a change. Going offline, coming back,
 *   and going offline again is three events, not two: a suppressor that
 *   remembered every state it had ever seen would swallow the third.
 *
 * The class holds the previous state and nothing else. It does not log by
 * itself and knows no logger: it answers whether something should be reported,
 * and the caller decides what that means. That keeps it a pure unit test with
 * no logging framework attached, and lets the same rule serve a log line today
 * and a web UI notification later.
 *
 * Not thread-safe. The service loop observes its state from a single thread.
 *
 * @param T the type of the observed state; compared with `==`.
 */
class StateChangeLog<T> {
    private var previous: T? = null
    private var seenAny = false

    /**
     * The most recently observed state, or `null` if nothing has been observed
     * yet. Exposed for callers that want to describe a transition ("offline ->
     * online") rather than just the new state.
     */
    val current: T?
        get() = previous

    /**
     * Records [state] and reports whether it differs from the previous one.
     *
     * @return `true` if this observation should be reported -- either the first
     *   observation or a genuine change -- and `false` if it repeats the
     *   current state.
     */
    fun observe(state: T): Boolean {
        val changed = !seenAny || previous != state
        previous = state
        seenAny = true
        return changed
    }

    /**
     * Forgets the previous state, so the next [observe] counts as a first
     * observation and reports a change.
     *
     * Used when the loop restarts and the operator should see the current state
     * again rather than have it suppressed as unchanged.
     */
    fun reset() {
        previous = null
        seenAny = false
    }
}
