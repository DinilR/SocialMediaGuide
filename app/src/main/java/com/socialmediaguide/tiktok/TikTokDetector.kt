package com.socialmediaguide.tiktok

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.socialmediaguide.guard.AppDetector
import com.socialmediaguide.guard.GuardedApp
import com.socialmediaguide.guard.NodeTree
import com.socialmediaguide.guard.NodeTree.bounds
import com.socialmediaguide.guard.NodeTree.shortId
import com.socialmediaguide.guard.Rule
import com.socialmediaguide.guard.Screen
import com.socialmediaguide.guard.Snapshot
import com.socialmediaguide.guard.pagerMessages

/**
 * TikTok: the video feed is one big swipe-to-next player, wherever it's opened (For You,
 * Following, a link, a search result, a profile). The first video plays; swiping to the next
 * is blocked, like Shorts and Reels. Profiles, search, messages and the inbox are lists or
 * grids, so they're left alone. Reads ids, classes and bounds only — never videos or names.
 */
object TikTokDetector : AppDetector {

    override val app = GuardedApp.TIKTOK
    override val playerIds get() = TikTokRules.feedIds

    private val messages = pagerMessages("Video")

    override fun classify(root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): Snapshot {
        val clues = HashSet<String>()
        var pager: AccessibilityNodeInfo? = null

        NodeTree.walk(root) { node ->
            val id = node.shortId()
            if (id != null) clues += id
            if (pager == null && isCandidate(node, id) && NodeTree.isFullScreenPager(node, screenHeight)) {
                pager = node
                clues += "pager-shape:${node.className}${if (node.isScrollable) "" else "(not scrollable)"}"
            }
        }

        val screen = pager?.let { pagerScreen(it.bounds()) } ?: Screen.Other("TikTok other")
        return Snapshot(screen, clues)
    }

    /**
     * Worth a shape check: scrollable, a known id, or a pager / list class. (Not every view:
     * the window's root layout also has one full-size child and would look like a pager.)
     */
    private fun isCandidate(node: AccessibilityNodeInfo, id: String?): Boolean {
        if (node.isScrollable || id in TikTokRules.feedIds) return true
        val cls = node.className?.toString() ?: return false
        return TikTokRules.pagerClassHints.any { cls.contains(it) }
    }

    override fun pagerScreen(pagerBounds: Rect?) = Screen.Pager(
        Rule.TT_SWIPE, Rule.TT_VIDEOS_ALL, "TikTok videos",
        messages.first, messages.second, pagerBounds,
    )
}
