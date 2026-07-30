package org.joefang.letterbox.ui

/**
 * What the detail screen does about remote images in a message.
 *
 * The three states are exhaustive and mutually exclusive: there is nothing to
 * load, there is something to load and the user has not asked for it, or the
 * user has asked. Both the banner and the WebView's network gate are *derived*
 * from this one value rather than each testing its own predicate.
 *
 * That derivation is the point. Previously the banner asked "are images still
 * blocked?" while the gate asked "are images allowed *and* has a separate
 * consent flag been set?". The two predicates could disagree, and when they did
 * the failure was silent and total: tapping "Show images" dismissed the banner —
 * so the offer appeared to have been accepted — while the gate went on refusing
 * every request, with no surface anywhere that said why.
 *
 * The law this type enforces is that [showsBanner] and [allowsNetworkLoads] are
 * never both true, and that the banner is shown exactly when a tap would change
 * something. An offer the app will not honour is now unrepresentable.
 */
internal enum class RemoteImagePolicy {
    /** No remote references in the message: nothing to offer, nothing to block. */
    None,

    /** Remote references present and still blocked. Offer the banner. */
    Blocked,

    /** The user asked for images, for this message or by standing preference. */
    Allowed;

    /** Whether the WebView may issue requests for remote subresources. */
    val allowsNetworkLoads: Boolean get() = this == Allowed

    /** Whether to offer the "Show images" banner. */
    val showsBanner: Boolean get() = this == Blocked

    companion object {
        /**
         * The only way to build a policy.
         *
         * @param hasRemoteImages whether the parsed body references remote content.
         * @param sessionLoadImages whether the user has asked for images — either by
         * tapping the banner on this message or via the "always load" preference.
         */
        fun of(hasRemoteImages: Boolean, sessionLoadImages: Boolean): RemoteImagePolicy =
            when {
                sessionLoadImages -> Allowed
                hasRemoteImages -> Blocked
                else -> None
            }
    }
}
