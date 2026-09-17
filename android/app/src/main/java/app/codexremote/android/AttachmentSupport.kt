package app.codexremote.android

/** An interrupted negotiation is unknown, not evidence that the host lost upload support. */
internal fun retainedAttachmentSupport(version: Int, supported: Boolean, previous: Boolean): Boolean =
    if (version == 0) previous else version >= 2 && supported
