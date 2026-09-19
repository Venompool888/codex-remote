package app.codexremote.android

import app.codexremote.android.ui.conversation.SubagentAvatarIdentity
import app.codexremote.android.ui.conversation.SubagentAvatarSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentAvatarIdentityTest {

    @Test
    fun testSameIdIsAlwaysDeterministic() {
        val testIds = listOf(
            "subagent-alpha-99",
            "thread_48291038472910",
            "worker_compiler_task",
            "search-evaluator-v2",
            "unique-guid-8f4b-4c2d-91ea",
        )

        for (id in testIds) {
            val spec1 = SubagentAvatarIdentity.forId(id)
            val spec2 = SubagentAvatarIdentity.forId(id)
            val spec3 = SubagentAvatarIdentity.forId(id)
            assertEquals("Repeated calls for '$id' must return identical spec", spec1, spec2)
            assertEquals("Repeated calls for '$id' must return identical spec", spec2, spec3)
        }
    }

    @Test
    fun testFixedKnownIdsMatchFixedFixtures() {
        // Fixed literal expected fixtures derived once from SHA-256 mapping
        // to detect any future mapping changes without recomputing SHA at test time.
        val fixtures = mapOf(
            "abc" to SubagentAvatarSpec(shape = 2, palette = 0),
            "hello world" to SubagentAvatarSpec(shape = 1, palette = 5),
            "The quick brown fox jumps over the lazy dog" to SubagentAvatarSpec(shape = 7, palette = 0),
            "The quick brown fox jumps over the lazy cog" to SubagentAvatarSpec(shape = 4, palette = 4),
            "Hello, World!" to SubagentAvatarSpec(shape = 7, palette = 5),
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" to SubagentAvatarSpec(shape = 4, palette = 5),
        )

        for ((id, expectedSpec) in fixtures) {
            val actual = SubagentAvatarIdentity.forId(id)
            assertEquals("Spec for '$id' must match fixed literal expected fixture", expectedSpec, actual)
        }
    }

    @Test
    fun testBlankAndWhitespaceFallback() {
        val fallback = SubagentAvatarSpec(shape = 0, palette = 0)
        assertEquals("Empty ID must return deterministic fallback", fallback, SubagentAvatarIdentity.forId(""))
        assertEquals("Whitespace-only ID must return deterministic fallback", fallback, SubagentAvatarIdentity.forId("   "))
        assertEquals("Tab-newline ID must return deterministic fallback", fallback, SubagentAvatarIdentity.forId("\t\n"))
    }

    @Test
    fun testRangeBoundsForAllSpecs() {
        val sampleIds = (0..200).map { "test-sample-id-$it" } +
            listOf("a", "123", "!@#$%", "thread_0", "unicode_🔥_subagent", "very-long-id-".repeat(20))

        for (id in sampleIds) {
            val spec = SubagentAvatarIdentity.forId(id)
            assertTrue("Shape must be in range 0..7 for '$id' but was ${spec.shape}", spec.shape in 0..7)
            assertTrue("Palette must be in range 0..7 for '$id' but was ${spec.palette}", spec.palette in 0..7)
        }
    }

    @Test
    fun testDistinctAaBbJavaHashCollision() {
        val id1 = "Aa"
        val id2 = "BB"
        // In Java/Kotlin String.hashCode(), "Aa" and "BB" have an identical 31-multiplier hash collision (2112)
        assertEquals("Precondition: 'Aa' and 'BB' must have identical Java String.hashCode()", id1.hashCode(), id2.hashCode())

        val spec1 = SubagentAvatarIdentity.forId(id1)
        val spec2 = SubagentAvatarIdentity.forId(id2)

        // SHA-256 digest on full opaque ID must not be tricked by Java hash collision
        assertNotEquals(
            "Distinct IDs with same java hashCode must yield distinct specs or different sha-256 bytes",
            spec1,
            spec2
        )
    }

    @Test
    fun testDiversityAcross128SequentialIds() {
        val shapesSeen = mutableSetOf<Int>()
        val palettesSeen = mutableSetOf<Int>()
        val distinctSpecsSeen = mutableSetOf<SubagentAvatarSpec>()

        for (i in 0 until 128) {
            val id = "subagent-sequence-item-$i"
            val spec = SubagentAvatarIdentity.forId(id)
            shapesSeen.add(spec.shape)
            palettesSeen.add(spec.palette)
            distinctSpecsSeen.add(spec)
        }

        // Across 128 sequential hashes, all 8 shapes and all 8 palettes should be well-represented
        assertEquals("All 8 shapes (0..7) should be seen across 128 IDs", 8, shapesSeen.size)
        assertEquals("All 8 palettes (0..7) should be seen across 128 IDs", 8, palettesSeen.size)

        // Should have high diversity across distinct combinations
        assertTrue(
            "Expected at least 30 unique (shape, palette) pairs across 128 IDs, got ${distinctSpecsSeen.size}",
            distinctSpecsSeen.size >= 30
        )
    }
}
