package com.socialmediaguide.facebook

/**
 * Clues used to recognise Facebook screens. Facebook's app reports very few view ids, so
 * these are mostly accessibility descriptions (English only). When Facebook changes them,
 * turn on Diagnostics, open the screen, and read the clues from `adb logcat -s SMG`.
 */
object FacebookRules {

    /**
     * Tab buttons are described like "Video, tab 4 of 6". The part before the comma is the
     * tab name; the open tab is the one marked selected.
     */
    const val TAB_MARKER = ", tab "
    val videoTabNames = setOf("video", "videos", "watch")
    val reelsTabNames = setOf("reels")

    /** Text or description of the header shown at the top of the Reels player. */
    val reelsHeaders = setOf("reels", "reel")

    /**
     * The Reels header must sit in this top fraction of the screen. Logged as a clue only:
     * the Reels player itself is recognised by shape (see NodeTree.isFullScreenPager).
     */
    const val HEADER_ZONE = 0.2f

    /** Ids, when Facebook does report them, of the Reels player. */
    val reelsIds = setOf("reels_viewer", "fb_shorts_viewer", "reels_pager")

    /** Descriptions of sheets that may always be scrolled (comments, share). */
    val freeScrollHints = listOf("comment", "share")
}
