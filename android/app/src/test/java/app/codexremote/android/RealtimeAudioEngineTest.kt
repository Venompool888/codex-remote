package app.codexremote.android

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeAudioEngineTest {
    @Test fun partialWritesContinueFromReturnedOffset() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val result = writePcmFully(byteArrayOf(1, 2, 3, 4, 5, 6)) { _, offset, length ->
            calls += offset to length
            minOf(2, length)
        }
        assertEquals(6, result)
        assertEquals(listOf(0 to 6, 2 to 4, 4 to 2), calls)
    }

    @Test fun zeroOrErrorWriteStopsWithoutLooping() {
        var zeroCalls = 0
        assertEquals(0, writePcmFully(byteArrayOf(1, 2)) { _, _, _ -> zeroCalls++; 0 })
        assertEquals(1, zeroCalls)
        assertEquals(-7, writePcmFully(byteArrayOf(1, 2)) { _, _, _ -> -7 })
    }
}
