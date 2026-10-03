package io.github.flavyu22.movietorrentsearchtv.security

/** Pure monotonic-clock policy used by AppViewModel and covered by JVM tests. */
internal class SessionLockPolicy(private val timeoutMs: Long) {
    init {
        require(timeoutMs > 0L)
    }

    private var backgroundedAtMs: Long? = null

    fun onBackgrounded(nowMs: Long) {
        backgroundedAtMs = nowMs.coerceAtLeast(0L)
    }

    fun shouldLockOnForeground(nowMs: Long): Boolean {
        val startedAt = backgroundedAtMs ?: return false
        return nowMs.coerceAtLeast(startedAt) - startedAt >= timeoutMs
    }

    fun clear() {
        backgroundedAtMs = null
    }
}
