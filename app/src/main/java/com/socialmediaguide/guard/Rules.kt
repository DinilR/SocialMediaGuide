package com.socialmediaguide.guard

/** The apps the guard watches. Each package must also be listed in res/xml/guard_service.xml. */
enum class GuardedApp(val label: String, vararg packages: String) {
    YOUTUBE("YouTube", "com.google.android.youtube"),
    INSTAGRAM("Instagram", "com.instagram.android"),
    FACEBOOK("Facebook", "com.facebook.katana"),
    /** TikTok ships under different packages by region, plus TikTok Lite. */
    TIKTOK("TikTok", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go");

    val packageNames: List<String> = packages.toList()

    companion object {
        fun forPackage(packageName: CharSequence?): GuardedApp? {
            if (packageName == null) return null
            // Debug builds also guard the test harness (testapp/), which mimics YouTube.
            if (com.socialmediaguide.BuildConfig.TEST_PACKAGE.isNotEmpty() &&
                packageName == com.socialmediaguide.BuildConfig.TEST_PACKAGE
            ) return YOUTUBE
            return entries.firstOrNull { packageName.toString() in it.packageNames }
        }
    }
}

/** One on/off switch in the app. [prefKey] is where it's saved. */
enum class Rule(
    val app: GuardedApp,
    val prefKey: String,
    val defaultOn: Boolean,
    val title: String,
    val subtitle: String,
) {
    YT_RELATED(
        GuardedApp.YOUTUBE, "yt_block_related", true,
        "Block related videos",
        "Watch the video you opened. Scrolling down into \"Up next\" is covered.",
    ),
    YT_SHORTS_SWIPE(
        GuardedApp.YOUTUBE, "yt_block_shorts_swipe", true,
        "Block Shorts swiping",
        "A Short you open plays. Swiping to the next one is covered.",
    ),
    YT_SHORTS_ALL(
        GuardedApp.YOUTUBE, "yt_block_shorts_all", false,
        "Block Shorts entirely",
        "Cover the Shorts player as soon as it opens.",
    ),
    IG_REELS_SWIPE(
        GuardedApp.INSTAGRAM, "ig_block_reels_swipe", true,
        "Block Reels swiping",
        "A Reel you open from a message, link or profile plays. Swiping to the next one is covered.",
    ),
    IG_REELS_ALL(
        GuardedApp.INSTAGRAM, "ig_block_reels_all", false,
        "Block Reels entirely",
        "Cover the Reels player as soon as it opens.",
    ),
    IG_EXPLORE(
        GuardedApp.INSTAGRAM, "ig_block_explore", true,
        "Block the Explore grid",
        "Search still works. Scrolling the grid of suggested posts is covered.",
    ),
    FB_REELS_SWIPE(
        GuardedApp.FACEBOOK, "fb_block_reels_swipe", true,
        "Block Reels swiping",
        "A Reel you open from a post or link plays. Swiping to the next one is covered.",
    ),
    FB_REELS_ALL(
        GuardedApp.FACEBOOK, "fb_block_reels_all", false,
        "Block Reels entirely",
        "Cover the Reels player as soon as it opens.",
    ),
    FB_VIDEO_FEED(
        GuardedApp.FACEBOOK, "fb_block_video_feed", true,
        "Block the Video feed",
        "Videos you open from a post or link play. Scrolling the endless Video tab is covered.",
    ),
    TT_SWIPE(
        GuardedApp.TIKTOK, "tt_block_swipe", true,
        "Block swiping to the next video",
        "The video you open from a link, search or profile plays (on For You, the first one). " +
            "Swiping to the next one is covered.",
    ),
    TT_VIDEOS_ALL(
        GuardedApp.TIKTOK, "tt_block_videos_all", false,
        "Block the video player entirely",
        "Cover the player as soon as it opens. Messages, search and profiles stay usable.",
    );

    companion object {
        fun forApp(app: GuardedApp) = entries.filter { it.app == app }
    }
}

/**
 * What the block screen says. A null [backLabel] hides the back button; [showLeaveApp] adds
 * a "Leave app" button that goes to the phone's home screen.
 */
data class BlockMessage(
    val title: String,
    val body: String,
    val backLabel: String?,
    val showLeaveApp: Boolean = false,
    /** The whole app is blocked: only "Leave app" makes sense (Back would stay in the app). */
    val leaveAppOnly: Boolean = false,
)

/** Which screen of a guarded app is open, as far as blocking is concerned. */
sealed interface Screen {
    val label: String

    /** Anything the guard leaves alone: home feed, search, profiles, messages, … */
    data class Other(override val label: String = "Other") : Screen

    /**
     * A screen whose list may be looked at but not scrolled down: the panel covers
     * [coverTop]..[coverBottom] (screen pixels) once the user scrolls the list there.
     */
    data class Feed(
        val rule: Rule,
        override val label: String,
        val coverTop: Int,
        val coverBottom: Int,
        val message: BlockMessage,
    ) : Screen

    /**
     * A swipe-to-next player (Shorts, Reels). The first item plays; swiping on is blocked
     * by [swipeRule], or the whole player by [allRule]. The panel covers the full screen.
     */
    data class Pager(
        val swipeRule: Rule,
        val allRule: Rule,
        override val label: String,
        val swipeMessage: BlockMessage,
        val allMessage: BlockMessage,
        /** Screen bounds of the pager; only scrolls of this view count as swipes. */
        val pagerBounds: android.graphics.Rect?,
    ) : Screen
}

/** Result of one look at an app's window. [clues] are logged in Diagnostics mode. */
data class Snapshot(val screen: Screen, val clues: Set<String>)

/** Recognises the screens of one app. */
interface AppDetector {
    val app: GuardedApp
    fun classify(root: android.view.accessibility.AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): Snapshot

    /** View ids of this app's Shorts / Reels player, preferred when looking the player up. */
    val playerIds: Set<String> get() = emptySet()

    /**
     * Whether a scroll distance alone means a swipe to the next item. False where the pager
     * shape can also match a feed (Facebook: one long post filling the list): then only real
     * evidence counts — a new item index, the next page sliding in, or different page texts.
     */
    val swipeByDistance: Boolean get() = true

    /** This app's Shorts / Reels screen, for a pager found by shape ([NodeTree.findFullScreenPager]). */
    fun pagerScreen(pagerBounds: android.graphics.Rect?): Screen.Pager

    /** Scroll sources inside these (comment sheets etc.) may always be scrolled. */
    fun isFreeScroll(node: android.view.accessibility.AccessibilityNodeInfo): Boolean = false
}

/** Messages shared by the Shorts / Reels pagers. */
fun pagerMessages(itemName: String) = Pair(
    BlockMessage("NO MORE SWIPING", "One $itemName is fine.\nSwiping to the next one is blocked.", "BACK TO THE ${itemName.uppercase()}"),
    BlockMessage("${itemName.uppercase()}S ARE OFF", "You switched ${itemName}s off in Social Media Guide.", null),
)
