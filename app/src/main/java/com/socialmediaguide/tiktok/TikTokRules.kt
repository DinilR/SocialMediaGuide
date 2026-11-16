package com.socialmediaguide.tiktok

/**
 * Clues used to recognise TikTok screens. TikTok hides most of its view ids, so the video
 * feed is found mainly by shape (see [com.socialmediaguide.guard.NodeTree.isFullScreenPager]).
 * When TikTok changes, turn on Diagnostics, use the screen, and read the `clues` lines.
 */
object TikTokRules {

    /** View ids the video pager has used (ids without the "package:id/" prefix). */
    val feedIds = setOf("viewpager", "view_pager", "feed_view_pager", "vertical_view_pager")

    /**
     * TikTok's vertical video pager isn't always marked scrollable for accessibility; views of
     * these classes are still checked by shape. (Any scrollable view is checked too.)
     */
    val pagerClassHints = listOf("ViewPager", "RecyclerView")
}
