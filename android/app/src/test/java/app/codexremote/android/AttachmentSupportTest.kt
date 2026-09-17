package app.codexremote.android
import org.junit.Assert.*
import org.junit.Test
class AttachmentSupportTest {
    @Test fun reconnectRetainsSupportWhileNegotiationIsUnknown() {
        assertTrue(retainedAttachmentSupport(0, false, true))
        assertFalse(retainedAttachmentSupport(0, false, false))
    }
    @Test fun actualDowngradeDisablesFiles() {
        assertFalse(retainedAttachmentSupport(1, false, true))
        assertFalse(retainedAttachmentSupport(2, false, true))
        assertTrue(retainedAttachmentSupport(2, true, false))
    }
}
