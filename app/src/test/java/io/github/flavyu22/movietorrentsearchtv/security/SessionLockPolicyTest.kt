package io.github.flavyu22.movietorrentsearchtv.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLockPolicyTest {
    @Test
    fun locksOnlyAfterTheBackgroundTimeout() {
        val policy = SessionLockPolicy(timeoutMs = 120_000L)
        policy.onBackgrounded(1_000L)

        assertFalse(policy.shouldLockOnForeground(120_999L))
        assertTrue(policy.shouldLockOnForeground(121_000L))
    }

    @Test
    fun clearRemovesThePendingLock() {
        val policy = SessionLockPolicy(timeoutMs = 30_000L)
        policy.onBackgrounded(10L)
        policy.clear()

        assertFalse(policy.shouldLockOnForeground(Long.MAX_VALUE))
    }

    @Test
    fun monotonicClockRollbackDoesNotForceALock() {
        val policy = SessionLockPolicy(timeoutMs = 30_000L)
        policy.onBackgrounded(50_000L)

        assertFalse(policy.shouldLockOnForeground(40_000L))
    }
}
