package com.socialmediaguide.facebook

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
 * Facebook: the Reels player (block swiping to the next Reel) and the Video tab (block
 * scrolling its endless feed). Reads tab descriptions, the Reels header, ids and bounds
 * only — never posts, names or messages.
 */
object FacebookDetector : AppDetector {

    override val app = GuardedApp.FACEBOOK
    override val playerIds get() = FacebookRules.reelsIds
    override val swipeByDistance get() = false

    private val reelsMessages = pagerMessages("Reel")
    private val videoMessage = BlockMessage(
        "SCROLL STOPPED",
        "Watch the videos you came for.\nThe endless Video feed is blocked.",
        "BACK TO TOP",
    )

    override fun classify(root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): Snapshot {
        val clues = HashSet<String>()
        var selectedTab: String? = null
        var selectedTabBottom = 0
        var reelsHeader = false
        var reelsId = false
        var shapePager: AccessibilityNodeInfo? = null
        val bounds = Rect()
        val headerZone = screenHeight * FacebookRules.HEADER_ZONE

        NodeTree.walk(root) { node ->
            if (shapePager == null && node.isScrollable && NodeTree.isFullScreenPager(node, screenHeight)) {
                shapePager = node
                clues += "pager-shape:${node.className}"
            }
            val id = node.shortId()
            if (id != null) {
                clues += id
                if (id in FacebookRules.reelsIds) reelsId = true
            }
            val desc = node.contentDescription?.toString()?.lowercase()
            if (desc != null && desc.contains(FacebookRules.TAB_MARKER)) {
                val name = desc.substringBefore(",").trim()
                clues += "tab:$name"
                if (node.isSelected) {
                    selectedTab = name
                    node.getBoundsInScreen(bounds)
                    selectedTabBottom = bounds.bottom
                    clues += "selected:$name"
                }
            }
            if (!reelsHeader) {
                val label = (node.text ?: node.contentDescription)?.toString()?.trim()?.lowercase()
                if (label != null && label in FacebookRules.reelsHeaders) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.top < headerZone) {
                        reelsHeader = true
                        clues += "header:$label"
                    }
                }
            }
        }

        // The Reels player is recognised only by its shape: a full-screen vertical pager whose
        // page holds no feed. The "Reels" header and tab are logged as clues but never decide
        // on their own: the home feed shows a "Reels" shelf title too, and the tab bar hides
        // while scrolling, which made the feed look like the Reels player.
        val screen = when {
            shapePager != null -> pagerScreen(shapePager!!.bounds())
            selectedTab in FacebookRules.videoTabNames -> videoFeed(root, selectedTabBottom, screenHeight)
            else -> null
        } ?: Screen.Other("Facebook feed / other")

        return Snapshot(screen, clues)
    }

    override fun pagerScreen(pagerBounds: Rect?) = Screen.Pager(
        Rule.FB_REELS_SWIPE, Rule.FB_REELS_ALL, "Facebook Reels",
        reelsMessages.first, reelsMessages.second, pagerBounds,
    )

    /** The Video tab: cover its list (the tab bar stays usable). */
    private fun videoFeed(root: AccessibilityNodeInfo, tabBottom: Int, screenHeight: Int): Screen? {
        val list = NodeTree.findListBelow(root, tabBottom) ?: return null
        val r = list.bounds()
        return Screen.Feed(
            Rule.FB_VIDEO_FEED, "Facebook Video",
            coverTop = r.top,
            coverBottom = r.bottom.coerceAtMost(screenHeight),
            message = videoMessage,
        )
    }

    override fun isFreeScroll(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(12) {
            val desc = current?.contentDescription?.toString()?.lowercase() ?: ""
            if (FacebookRules.freeScrollHints.any { desc.startsWith(it) }) return true
            current = current?.parent ?: return false
        }
        return false
    }
}
