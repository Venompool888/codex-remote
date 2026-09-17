package app.codexremote.android

/** Measured from the narrow ChatGPT conversation; dimensions are dp and text sizes sp. */
internal object ConversationStyle {
    const val GUTTER = 16
    const val HEADER_HEIGHT = 52
    const val BODY_SIZE = 16f
    const val BODY_LINE_HEIGHT = 26f
    const val USER_LINE_HEIGHT = 24f
    const val USER_RADIUS = 22
    const val USER_WIDTH_FRACTION = 0.70f
    const val COMPOSER_RADIUS = 24
}

/** Unchanged rows retain selection, expanded children and in-flight animations during streaming. */
internal fun reusableTimelineRows(previous: List<TimelineItem>, next: List<TimelineItem>): List<Int?> {
    val previousByKey = previous.withIndex().associateBy { it.value.kind to it.value.id }
    return next.map { item -> previousByKey[item.kind to item.id]?.takeIf { it.value == item }?.index }
}
