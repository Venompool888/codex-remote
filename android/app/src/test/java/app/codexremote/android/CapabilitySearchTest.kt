package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class CapabilitySearchTest {
    @Test fun fieldsCannotCreateAnAccidentalMatch() {
        assertFalse(matchesCapabilityQuery("data-analytics:index", "Route Data Analytics plugin requests", "xr"))
        assertTrue(matchesCapabilityQuery("data-analytics:index", "Route Data Analytics plugin requests", "index route"))
    }
    @Test fun skillNamesAcceptSpacesHyphensAndCase() {
        for (query in listOf("xr", "Xray Reverse", "xray-reverse", " xray_reverse ", "reverse xray"))
            assertTrue(query, matchesCapabilityQuery("xray-reverse", "Remote configuration guidance", query))
        assertFalse(matchesCapabilityQuery("xray-reverse", "Remote configuration guidance", "xray missing"))
    }
    @Test fun emptyAndDescriptionAndNonLatinSearch() {
        assertTrue(matchesCapabilityQuery("xray-reverse", "反向代理配置", "反向代理"))
        assertTrue(matchesCapabilityQuery("xray-reverse", "Remote configuration guidance", "configuration"))
        assertTrue(matchesCapabilityQuery("anything", "", "   "))
    }
}
