package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

class VerifiedArtifactCopyTest {
    private val data = ByteArray(150_000) { (it % 251).toByte() }
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    @Test fun exactFileAndMonotonicProgress() {
        val file = Files.createTempFile("artifact-copy", ".part").toFile()
        try {
            val progress = mutableListOf<Int>()
            VerifiedArtifactCopy.copy(ByteArrayInputStream(data), file, data.size.toLong(), hash, progress::add)
            assertArrayEquals(data, file.readBytes())
            assertEquals(100, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> b > a })
        } finally { file.delete() }
    }
    @Test fun truncationOversizeAndCorruptionNeverLeavePayload() {
        for ((bytes, digest) in listOf(data.copyOf(100) to hash, (data + byteArrayOf(1)) to hash, data to "0".repeat(64))) {
            val file = Files.createTempFile("artifact-reject", ".part").toFile()
            try {
                try { VerifiedArtifactCopy.copy(ByteArrayInputStream(bytes), file, data.size.toLong(), digest); fail("Must reject invalid content") }
                catch (_: IllegalStateException) { }
                assertFalse(file.exists())
            } finally { file.delete() }
        }
    }
    @Test fun interruptedReadOnlyDeletesItsOwnTemporaryFile() {
        val directory = Files.createTempDirectory("artifact-concurrent").toFile()
        try {
            val other = java.io.File(directory, "other.part").apply { writeText("other download") }
            val file = java.io.File(directory, "current.part")
            val input = object : InputStream() { override fun read(): Int = throw IOException("Disconnected") }
            try { VerifiedArtifactCopy.copy(input, file, data.size.toLong(), hash); fail("Must propagate interruption") }
            catch (_: IOException) { }
            assertFalse(file.exists())
            assertEquals("other download", other.readText())
        } finally { directory.deleteRecursively() }
    }
}
