package com.socialmediaguide.instagram

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.socialmediaguide.guard.AppDetector
import com.socialmediaguide.guard.BlockMessage
import com.socialmediaguide.guard.GuardedApp
import com.socialmediaguide.guard.NodeTree
import com.socialmediaguide.guard.NodeTree.bounds
import com.socialmediaguide.guard.NodeTree.shortId
import com.socialmediaguide.guard.Rule
import com.socialmediaguide.guard.Screen
import com.socialmediaguide.guard.Snapshot
import com.socialmediaguide.guard.pagerMessages

/**
 * Instagram: the Reels player (block swiping to the next Reel) and the Explore grid
 * (block scrolling; searching stays free). Reads view ids, bounds, which tab is selected,
 * and whether the search box has a query — never posts, names or messages.
 */
object InstagramDetector : AppDetector {

    override val app = GuardedApp.INSTAGRAM
    override val playerIds get() = InstagramRules.reelsIds

    private val reelsMessages = pagerMessages("Reel")
    private val exploreMessage = BlockMessage(
        "SCROLL STOPPED",
        "Search for what you want.\nThe Explore grid is blocked.",
        "BACK TO TOP",
    )

    override fun classify(root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): Snapshot {
        val clues = HashSet<String>()
        var tallReels = false
        var selectedTab: String? = null
        var searchBox: Rect? = null
        var searching = false
        var shapePager: AccessibilityNodeInfo? = null
        var tallReelsBounds: Rect? = null
        val bounds = Rect()

        NodeTree.walk(root) { node ->
            if (shapePager == null && node.isScrollable && NodeTree.isFullScreenPager(node, screenHeight)) {
                shapePager = node
                clues += "pager-shape:${node.className}"
            }
            val id = node.shortId()
            if (id != null) {
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    clues += id
                    if (id in InstagramRules.reelsIds && bounds.height() >= screenHeight * InstagramRules.MIN_REELS_HEIGHT) {
                        tallReels = true
                        if (node.isScrollable || tallReelsBounds == null) tallReelsBounds = Rect(bounds)
                    }
                    if (id in InstagramRules.searchBoxIds) {
                        if (searchBox == null) searchBox = Rect(bounds)
                        if (node.isFocused || hasQuery(node)) searching = true
                    }
                }
            }
            if (node.isSelected) {
                val desc = node.contentDescription?.toString()?.lowercase()
                val tab = when {
                    id in InstagramRules.tabIds -> id
                    desc != null && desc in InstagramRules.reelsTabDescriptions -> InstagramRules.TAB_REELS
                    desc != null && desc in InstagramRules.exploreTabDescriptions -> InstagramRules.TAB_EXPLORE
                    else -> null
                }
                if (tab != null) {
                    selectedTab = tab
                    clues += "selected:$tab"
                }
            }
        }

        // The Reels player: a full-screen pager recognised by shape, or the Reels ids filling the screen.
        val screen = when {
            shapePager != null -> pagerScreen(shapePager!!.bounds())
            tallReels -> pagerScreen(tallReelsBounds)
            selectedTab == InstagramRules.TAB_EXPLORE && !searching -> explore(root, searchBox, screenHeight)
            else -> null
        } ?: Screen.Other(if (searching) "Instagram search" else "Instagram feed / other")

        return Snapshot(screen, clues)
    }

    override fun pagerScreen(pagerBounds: Rect?) = Screen.Pager(
        Rule.IG_REELS_SWIPE, Rule.IG_REELS_ALL, "Instagram Reels",
        reelsMessages.first, reelsMessages.second, pagerBounds,
    )

    /** The Explore grid: cover from under the search box to the bottom of the grid (tabs stay usable). */
    private fun explore(root: AccessibilityNodeInfo, searchBox: Rect?, screenHeight: Int): Screen? {
        val list = NodeTree.findListBelow(root, searchBox?.bottom ?: 0) ?: return null
        val r = list.bounds()
        return Screen.Feed(
            Rule.IG_EXPLORE, "Instagram Explore",
            coverTop = maxOf(r.top, searchBox?.bottom ?: 0),
            coverBottom = r.bottom.coerceAtMost(screenHeight),
            message = exploreMessage,
        )
    }

    private fun hasQuery(node: AccessibilityNodeInfo): Boolean =
        !node.text.isNullOrBlank() && !node.isShowingHintText

    override fun isFreeScroll(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(20) {
            val id = current?.shortId() ?: ""
            if (InstagramRules.freeScrollHints.any { id.contains(it) }) return true
            current = current?.parent ?: return false
        }
        return false
    }
}
