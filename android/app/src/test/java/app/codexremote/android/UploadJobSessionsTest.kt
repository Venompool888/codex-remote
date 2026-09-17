package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class UploadJobSessionsTest {
    @Test fun networkRestartCannotReactivateOldUpload() {
        val sessions = UploadJobSessions()
        val old = sessions.begin()
        sessions.stop()
        val resumed = sessions.begin()
        assertTrue(old.isStopped())
        assertFalse(resumed.isStopped())
        assertFalse("Old completion must not finish the new OS job", sessions.finish(old))
        assertTrue(sessions.finish(resumed))
        assertFalse("Duplicate completion must be ignored", sessions.finish(resumed))
    }
    @Test fun replacementStopsPreviousRunEvenWithoutStopCallback() {
        val sessions = UploadJobSessions()
        val old = sessions.begin()
        val next = sessions.begin()
        assertTrue(old.isStopped())
        assertFalse(sessions.finish(old))
        sessions.stop()
        assertFalse("The OS owns rescheduling after onStopJob", sessions.finish(next))
    }
}
