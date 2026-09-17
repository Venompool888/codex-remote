package app.codexremote.android
import org.junit.Assert.assertEquals
import org.junit.Test

class PopupPlacementTest {
    @Test fun endAlignedWideMenuKeepsLeftGutter() {
        assertEquals(12, 140 + popupHorizontalOffset(140, 130, 300, 0, 412, 12, true))
    }
    @Test fun rightAnchorKeepsRightGutter() {
        assertEquals(164, 365 + popupHorizontalOffset(365, 40, 236, 0, 412, 12, false))
    }
    @Test fun visibleWindowOriginAndNaturalAnchorArePreserved() {
        assertEquals(512, 550 + popupHorizontalOffset(550, 40, 280, 500, 820, 12, true))
        assertEquals(100, 100 + popupHorizontalOffset(100, 40, 220, 0, 412, 12, false))
    }
    @Test fun landscapeSubmenuFitsAboveAnchor() {
        val p = popupVerticalPlacement(320, 48, 24, 412, 440, 8)
        assertEquals(288, p.height)
        assertEquals(24, 320 + 48 + p.offset)
    }
    @Test fun topAnchorOpensBelowAndShortMenusRetainTheirHeight() {
        val p = popupVerticalPlacement(40, 48, 24, 412, 440, 8)
        assertEquals(316, p.height)
        assertEquals(8, p.offset)
        assertEquals(160, popupVerticalPlacement(700, 48, 24, 800, 160, 8).height)
    }
}
