package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class AttachmentImageReferenceTest {
    private val id = "12345678-1234-4234-8234-123456789abc"
    @Test fun redactedMarkdownImageResolvesOnlyTheRestrictedId() {
        val source = "remote-attachment://$id-daily-shapes.png"
        val parsed = AttachmentImageReference.parse(source)!!
        assertEquals(id, parsed.id)
        assertEquals("daily-shapes.png", parsed.name)
        assertEquals("image", parsed.kind)
        assertEquals(source, (MarkdownParser.parse("![Shapes]($source)").single() as MarkdownBlock.Image).source)
    }
    @Test fun pathsAndMalformedReferencesCannotBecomeAttachmentRequests() {
        listOf("/private/files/$id-photo.png", "https://example.com/$id-photo.png", "remote-attachment://not-an-id-photo.png",
            "remote-attachment://$id-../secret.png", "remote-attachment://$id-foo\\bar.png", "remote-attachment://$id-",
            "remote-attachment://$id-photo\n.png").forEach { assertNull(it, AttachmentImageReference.parse(it)) }
    }
}
