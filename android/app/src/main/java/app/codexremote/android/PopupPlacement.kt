package app.codexremote.android

/** Offset in anchor coordinates, bounded by the visible window rather than the physical display. */
internal fun popupHorizontalOffset(
    anchorLeft: Int, anchorWidth: Int, popupWidth: Int,
    frameLeft: Int, frameRight: Int, gutter: Int, alignEnd: Boolean, preferredOffset: Int = 0,
): Int {
    val left = frameLeft + gutter
    val right = (frameRight - gutter - popupWidth).coerceAtLeast(left)
    val preferred = (if (alignEnd) anchorLeft + anchorWidth - popupWidth else anchorLeft) + preferredOffset
    return preferred.coerceIn(left, right) - anchorLeft
}

internal data class PopupVerticalPlacement(val height: Int, val offset: Int)
internal fun popupVerticalPlacement(
    anchorTop: Int, anchorHeight: Int, frameTop: Int, frameBottom: Int, desiredHeight: Int, gap: Int,
): PopupVerticalPlacement {
    val above = (anchorTop - frameTop - gap).coerceAtLeast(0)
    val below = (frameBottom - anchorTop - anchorHeight - gap).coerceAtLeast(0)
    val useAbove = above >= desiredHeight || above >= below
    val height = minOf(desiredHeight, if (useAbove) above else below).coerceAtLeast(1)
    return PopupVerticalPlacement(height, if (useAbove) -anchorHeight - height - gap else gap)
}
