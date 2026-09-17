package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class CapabilityPresentationCacheTest {
    private val scope = CapabilityCacheScope("https://host", "device", "/workspace")
    @Test fun scopeIncludesServerDeviceAndDirectory() {
        val cache = CapabilityPresentationCache()
        assertTrue(cache.put(scope, "snapshot", cache.generation))
        assertNull(cache.get(scope.copy(server = "https://other")))
        assertNull(cache.get(scope.copy(device = "other")))
        assertNull(cache.get(scope.copy(cwd = "/other")))
        assertEquals("snapshot", cache.get(scope))
    }
    @Test fun accessDoesNotExtendFreshnessAndClockRollbackExpires() {
        var clock = 0L
        val cache = CapabilityPresentationCache(now = { clock })
        cache.put(scope, "snapshot", cache.generation)
        clock = 29_999; assertEquals("snapshot", cache.get(scope))
        clock = 30_000; assertNull(cache.get(scope))
        cache.put(scope, "snapshot", cache.generation)
        clock = 29_000; assertNull(cache.get(scope))
    }
    @Test fun changeOrRefreshPreventsLateRequestsFromRepopulatingCache() {
        val cache = CapabilityPresentationCache()
        val generation = cache.generation
        cache.put(scope, "old", generation)
        cache.invalidateServer(scope.server)
        assertNull(cache.get(scope))
        assertFalse(cache.put(scope, "late", generation))
        cache.put(scope, "new", cache.generation)
        cache.invalidate(scope)
        assertNull(cache.get(scope))
    }
    @Test fun memoryIsBoundedAndUnauthenticatedScopeIsNotCached() {
        val cache = CapabilityPresentationCache()
        assertFalse(cache.put(scope.copy(device = ""), "value", cache.generation))
        assertFalse(cache.put(scope, "x".repeat(262_145), cache.generation))
        repeat(8) { cache.put(scope.copy(cwd = "/$it"), "$it", cache.generation) }
        cache.get(scope.copy(cwd = "/0"))
        cache.put(scope.copy(cwd = "/8"), "8", cache.generation)
        assertEquals("0", cache.get(scope.copy(cwd = "/0")))
        assertNull(cache.get(scope.copy(cwd = "/1")))
    }
}
