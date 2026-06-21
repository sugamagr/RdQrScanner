package com.qrscanner.app.data.sync

import com.qrscanner.app.data.sync.SyncRepository.Companion.derivePillSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Invariant tests for the sync pill state machine.
 *
 * These do NOT test commits / regressions — they test the contractual
 * invariants the system MUST guarantee per docs/CLOUD_SYNC_SPEC.md
 * §15.5 and the oracle audits (bg_0ea195ce, bg_1eadd75b).
 *
 * If a test fails it means a real-world UX bug — not a code-style
 * mismatch.
 */
class SyncStateMachineTest {

    private fun summary(state: SyncPillState, pending: Int = 0): SyncSummary =
        SyncSummary(
            state = state,
            pendingCount = pending,
            lastSuccessfulPushAt = null,
            lastSuccessfulPullAt = null,
            lastErrorMessage = null
        )

    // ── Invariant I1: pill always reflects live DB count ──────────────

    @Test
    fun `live count overrides snapshot pendingCount`() {
        val stale = summary(SyncPillState.SYNCED, pending = 99)
        val out = derivePillSummary(stale, liveCount = 0)
        assertEquals(0, out.pendingCount)
        assertEquals(SyncPillState.SYNCED, out.state)
    }

    @Test
    fun `live count of 5 with stale SYNCED state derives PENDING`() {
        val stale = summary(SyncPillState.SYNCED, pending = 0)
        val out = derivePillSummary(stale, liveCount = 5)
        assertEquals(5, out.pendingCount)
        assertEquals(SyncPillState.PENDING, out.state)
    }

    @Test
    fun `live count of 0 with stale PENDING state derives SYNCED`() {
        // The exact race that produced the user-reported stuck PENDING bug:
        // push completed, DB clean, but a previous summary state of PENDING
        // would have stuck without this transition.
        val stale = summary(SyncPillState.PENDING, pending = 5)
        val out = derivePillSummary(stale, liveCount = 0)
        assertEquals(0, out.pendingCount)
        assertEquals(SyncPillState.SYNCED, out.state)
    }

    // ── Invariant I2: blocking states beat live count ─────────────────

    @Test
    fun `SCHEMA_MISSING is preserved regardless of live count`() {
        val s = summary(SyncPillState.SCHEMA_MISSING, pending = 0)
        val out = derivePillSummary(s, liveCount = 5)
        assertEquals(SyncPillState.SCHEMA_MISSING, out.state)
        assertEquals(5, out.pendingCount)
    }

    @Test
    fun `ERROR is preserved regardless of live count`() {
        val s = summary(SyncPillState.ERROR)
        val out = derivePillSummary(s, liveCount = 7)
        assertEquals(SyncPillState.ERROR, out.state)
        assertEquals(7, out.pendingCount)
    }

    @Test
    fun `SYNCING transient state is preserved during a cycle`() {
        val s = summary(SyncPillState.SYNCING)
        val out = derivePillSummary(s, liveCount = 3)
        assertEquals(SyncPillState.SYNCING, out.state)
    }

    @Test
    fun `INITIALIZING preserved before auth resolves`() {
        val s = summary(SyncPillState.INITIALIZING)
        val out = derivePillSummary(s, liveCount = 0)
        assertEquals(SyncPillState.INITIALIZING, out.state)
    }

    @Test
    fun `NOT_SIGNED_IN preserved`() {
        val s = summary(SyncPillState.NOT_SIGNED_IN)
        val out = derivePillSummary(s, liveCount = 99)
        assertEquals(SyncPillState.NOT_SIGNED_IN, out.state)
    }

    // ── Invariant I3: priority order strict ───────────────────────────

    @Test
    fun `SCHEMA_MISSING wins over live count even when 0`() {
        val s = summary(SyncPillState.SCHEMA_MISSING)
        val out = derivePillSummary(s, liveCount = 0)
        assertEquals(SyncPillState.SCHEMA_MISSING, out.state)
    }

    @Test
    fun `ERROR wins over live count even when 0`() {
        val s = summary(SyncPillState.ERROR)
        val out = derivePillSummary(s, liveCount = 0)
        assertEquals(SyncPillState.ERROR, out.state)
    }

    // ── Invariant I4: no contradictory UX ─────────────────────────────

    @Test
    fun `partial-success scenario does not produce ERROR pill with success notifications`() {
        // d1d13fc invariant: when a partial-success cycle has fired
        // per-session Channel A notifications, the next derivation must
        // not produce ERROR. Caller is responsible for setting state to
        // PENDING/SYNCED in this case; the derivation just preserves it.
        val partialPending = summary(SyncPillState.PENDING, pending = 1)
        val out = derivePillSummary(partialPending, liveCount = 1)
        assertEquals(SyncPillState.PENDING, out.state)
        // Crucially NOT ERROR — that's the contradiction we're guarding.
    }

    // ── Invariant I5: idempotent ──────────────────────────────────────

    @Test
    fun `derivation is idempotent`() {
        val s = summary(SyncPillState.SYNCED, pending = 0)
        val once = derivePillSummary(s, liveCount = 3)
        val twice = derivePillSummary(once, liveCount = 3)
        assertEquals(once, twice)
    }

    // ── Invariant I6: pendingCount always equals live count ───────────

    @Test
    fun `derived pendingCount always equals liveCount regardless of state`() {
        for (state in SyncPillState.values()) {
            for (live in listOf(0, 1, 5, 999)) {
                val out = derivePillSummary(summary(state, pending = 0), live)
                assertEquals(
                    "state=$state live=$live should preserve liveCount",
                    live,
                    out.pendingCount
                )
            }
        }
    }
}
