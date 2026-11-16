package com.socialmediaguide.youtube

/**
 * Clues used to recognise YouTube screens. YouTube renames views now and then, so each
 * screen has several alternatives; when detection breaks after an update, turn on
 * Diagnostics in the app, open the screen, and read the ids from `adb logcat -s SMG`.
 *
 * Ids are written without the "com.google.android.youtube:id/" prefix.
 */
object YouTubeRules {
    /**
     * The Shorts player itself (YouTube 21.43: `reel_recycler`, `reel_player_page_container`).
     * Not `reel_time_bar` / `reel_progress_bar`: those also appear on the home screen, where
     * Shorts previews play inside the feed.
     */
    val shortsIds = setOf(
        "reel_recycler",
        "reel_player_page_container",
        "reel_watch_player",
    )

    /** Preferred as the player's position: the pager holding the Shorts. */
    const val SHORTS_PAGER_ID = "reel_recycler"

    /**
     * Seen only on YouTube's home / browse screens (YouTube 21.43 log): if present, a
     * full-screen pager is the home tab holder, not Shorts.
     */
    val homeIds = setOf("youtube_logo", "tabs_bar", "results")

    /** Label of the Shorts button in the bottom bar; selected = the Shorts tab is open. */
    val shortsTabLabels = setOf("shorts")

    /** The video player on the watch page (also present in the mini player). */
    val playerIds = setOf(
        "watch_player",
        "player_view",
        "player_fragment_container",
        "watch_while_player_container",
    )

    /**
     * Start of a content description only found under an open video (the like button).
     * Used as a second clue when the player id isn't found. English only.
     */
    val watchPageDescriptionPrefixes = listOf("like this video")

    /**
     * Seen on the home and search screens of YouTube 19.17, so they must NOT be used as
     * watch-page clues: results, watch_while_layout_coordinator_layout,
     * slim_status_bar_player_container, pivot_bar.
     */

    /** Parts of ids of slide-up panels (comments, description) that may be scrolled freely. */
    val panelIdHints = listOf("engagement_panel", "comment", "description", "bottom_sheet")

    /** Fraction of the screen width the player must cover to count as the open watch page. */
    const val MIN_PLAYER_WIDTH = 0.8f

    /** The player's top edge must be in the top part of the screen (not the mini player). */
    const val MAX_PLAYER_TOP = 0.35f

    /** The Shorts pager must be at least this tall (a Shorts shelf in the feed doesn't count). */
    const val MIN_SHORTS_HEIGHT = 0.6f
}
