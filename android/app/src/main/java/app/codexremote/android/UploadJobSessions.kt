package app.codexremote.android

import java.util.concurrent.atomic.AtomicBoolean

/** Each OS job invocation owns a cancellation token that can never become active again. */
internal class UploadJobSessions {
    class Run internal constructor() {
        private val cancelled = AtomicBoolean(false)
        fun isStopped(): Boolean = cancelled.get()
        internal fun stop() { cancelled.set(true) }
    }
    private var current: Run? = null
    @Synchronized fun begin(): Run {
        current?.stop()
        return Run().also { current = it }
    }
    @Synchronized fun stop() { current?.stop() }
    @Synchronized fun finish(run: Run): Boolean {
        if (current !== run || run.isStopped()) return false
        current = null
        return true
    }
}
