package com.socialmediaguide.instagram

/**
 * Clues used to recognise Instagram screens. Ids are written without the
 * "com.instagram.android:id/" prefix. When Instagram changes them, turn on Diagnostics,
 * open the screen, and read the ids from `adb logcat -s SMG`.
 */
object InstagramRules {

    /** The full-screen Reels player / its vertical pager. */
    val reelsIds = setOf(
        "clips_viewer_view_pager",
        "clips_viewer_container",
        "clips_viewer_fragment_container",
        "clips_video_container",
        "root_clips_layout",
    )

    /** Bottom tab bar buttons. The open tab is the one marked selected. */
    const val TAB_REELS = "clips_tab"
    const val TAB_EXPLORE = "search_tab"
    val tabIds = setOf("feed_tab", TAB_EXPLORE, TAB_REELS, "creation_tab", "profile_tab")

    /** Tab descriptions, used when the tab ids aren't reported. English only. */
    val reelsTabDescriptions = setOf("reels")
    val exploreTabDescriptions = setOf("search and explore", "search", "explore")

    /** The search box at the top of Explore. Typing in it means the user is searching. */
    val searchBoxIds = setOf(
        "action_bar_search_edit_text",
        "search_edit_text",
        "action_bar_search_hints_text_layout",
    )

    /** Comment sheets, share sheets and messages may always be scrolled. */
    val freeScrollHints = listOf("comment", "bottom_sheet", "direct", "share")

    /** The Reels player must be at least this tall (a Reels row in the feed doesn't count). */
    const val MIN_REELS_HEIGHT = 0.6f
}
