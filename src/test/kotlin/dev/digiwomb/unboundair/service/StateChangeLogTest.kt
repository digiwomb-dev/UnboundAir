package dev.digiwomb.unboundair.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [StateChangeLog] (DL-02).
 *
 * DL-02 demands "exactly one log entry per state change". The interesting part
 * is not that a change is reported -- it is everything that must *not* be
 * reported, and the one case that looks like a repeat but is not.
 *
 * These are plain unit tests: no Spring context, no socket, no logging
 * framework. That is deliberate. The suppression rule is the part that can be
 * wrong, and it can be pinned without any of that machinery; the integration
 * test #98 then proves the rule is actually wired into the loop.
 */
class StateChangeLogTest {
    @Test
    fun `DL-02 the first observation is reported`() {
        val log = StateChangeLog<String>()

        assertThat(log.observe("offline"))
            .`as`("the first observation has no predecessor and must be reported: on startup the operator needs the state")
            .isTrue()
    }

    @Test
    fun `DL-02 a repeated state is reported once, not once per poll`() {
        val log = StateChangeLog<String>()
        log.observe("nopaper")

        val reports = (1..20).count { log.observe("nopaper") }

        assertThat(reports)
            .`as`("20 further polls of an unchanged state must produce no entry at all; this is the point of DL-02")
            .isZero()
    }

    @Test
    fun `DL-02 a genuine change is reported`() {
        val log = StateChangeLog<String>()
        log.observe("nopaper")

        assertThat(log.observe("scanready"))
            .`as`("a different state must be reported")
            .isTrue()
    }

    @Test
    fun `DL-02 returning to an earlier state is reported again`() {
        val log = StateChangeLog<String>()

        log.observe("online")
        log.observe("offline")
        val backOnline = log.observe("online")
        log.observe("offline")
        val offlineAgain = log.observe("offline")

        assertThat(backOnline)
            .`as`("coming back to a state seen before is a change, not a repeat: only the immediate predecessor counts")
            .isTrue()
        assertThat(offlineAgain)
            .`as`("but an immediate repeat of that state is still suppressed")
            .isFalse()
    }

    @Test
    fun `DL-02 a full offline and online cycle produces exactly one entry per transition`() {
        val log = StateChangeLog<String>()

        // A realistic sequence: the scanner answers, disappears for a while,
        // comes back, and settles. Five polls, but only three transitions.
        val observations = listOf("nopaper", "nopaper", "offline", "offline", "offline", "nopaper", "nopaper")

        val reported = observations.count { log.observe(it) }

        assertThat(reported)
            .`as`("7 polls covering 3 distinct phases must yield exactly 3 entries, not 7")
            .isEqualTo(3)
    }

    @Test
    fun `DL-02 the current state is exposed so a transition can be described`() {
        val log = StateChangeLog<String>()

        assertThat(log.current)
            .`as`("nothing observed yet")
            .isNull()

        log.observe("online")

        assertThat(log.current)
            .`as`("the caller needs the previous state to log 'offline -> online' rather than just 'online'")
            .isEqualTo("online")
    }

    @Test
    fun `DL-02 a reset makes the next observation count as the first again`() {
        val log = StateChangeLog<String>()
        log.observe("offline")

        assertThat(log.observe("offline"))
            .`as`("precondition: the repeat is suppressed")
            .isFalse()

        log.reset()

        assertThat(log.observe("offline"))
            .`as`("after a reset the same state must be reported again, so a restarted loop shows its state")
            .isTrue()
        assertThat(log.current)
            .`as`("the reset also clears the remembered state")
            .isEqualTo("offline")
    }

    @Test
    fun `DL-02 null is a state like any other`() {
        val log = StateChangeLog<String?>()

        assertThat(log.observe(null))
            .`as`("the first observation is reported even when the state itself is null")
            .isTrue()
        assertThat(log.observe(null))
            .`as`("a repeated null must be suppressed like any other repeat, not treated as 'nothing seen yet'")
            .isFalse()
        assertThat(log.observe("online"))
            .`as`("moving away from null is a change")
            .isTrue()
    }
}
