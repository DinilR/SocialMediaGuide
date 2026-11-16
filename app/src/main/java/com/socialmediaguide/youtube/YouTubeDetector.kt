package com.socialmediaguide.youtube

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
 * YouTube: an open video (block scrolling into related videos) and Shorts (block swiping).
 * Reads view ids, bounds and the like button's description only.
 */
object YouTubeDetector : AppDetector {

    override val app = GuardedApp.YOUTUBE
    override val playerIds get() = YouTubeRules.shortsIds

    private val relatedMessage = BlockMessage(
        "SCROLL STOPPED",
        "You came for this video.\nThe feed of related videos is blocked.",
        "BACK TO VIDEO",
    )
    private val shortsMessages = pagerMessages("Short")

    override fun classify(root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): Snapshot {
        val ids = HashSet<String>()
        var player: Rect? = null
        var tallShorts = false
        var likeButtonTop: Int? = null
        var shortsTabSelected = false
        var shapePager: AccessibilityNodeInfo? = null
        var tallShortsBounds: Rect? = null
        val bounds = Rect()

        NodeTree.walk(root) { node ->
            if (shapePager == null && node.isScrollable && NodeTree.isFullScreenPager(node, screenHeight)) {
                shapePager = node
                ids += "pager-shape:${node.className}"
            }
            val id = node.shortId()
            if (id != null) {
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    ids += id
                    if (id in YouTubeRules.playerIds && (player == null || bounds.width() > player!!.width())) {
                        player = Rect(bounds)
                    }
                    if (id in YouTubeRules.shortsIds && bounds.height() >= screenHeight * YouTubeRules.MIN_SHORTS_HEIGHT) {
                        tallShorts = true
                        // YouTube doesn't mark its Shorts pager scrollable; prefer it by id.
                        if (id == YouTubeRules.SHORTS_PAGER_ID || tallShortsBounds == null) tallShortsBounds = Rect(bounds)
                    }
                }
            }
            if (node.isSelected && !shortsTabSelected) {
                val label = (node.contentDescription ?: node.text)?.toString()?.trim()?.lowercase()
                if (label != null && label in YouTubeRules.shortsTabLabels) {
                    shortsTabSelected = true
                    ids += "selected:$label"
                }
            }
            if (likeButtonTop == null) {
                val desc = node.contentDescription?.toString()?.lowercase()
                if (desc != null && YouTubeRules.watchPageDescriptionPrefixes.any { desc.startsWith(it) }) {
                    node.getBoundsInScreen(bounds)
                    likeButtonTop = bounds.top
                }
            }
        }

        val p = player
        // The Shorts player: YouTube's own Shorts ids filling the screen (reported by YouTube
        // 21.x: reel_recycler, reel_player_page_container), else a full-screen pager
        // recognised by shape, unless this is clearly the home / browse screen.
        val homeScreen = ids.any { it in YouTubeRules.homeIds }
        val screen = when {
            // On the home screen YouTube keeps parts of the Shorts player around; never Shorts there.
            tallShorts && !homeScreen -> pagerScreen(tallShortsBounds)
            shapePager != null && !homeScreen -> pagerScreen(shapePager!!.bounds())
            p != null && isOpenWatchPlayer(p, screenWidth, screenHeight) -> watch(p.bottom, screenHeight)
            // No player id found, but the like button of an open video is on screen:
            // treat everything from a little above it as the area under the player.
            p == null && likeButtonTop != null && likeButtonTop!! < screenHeight * 0.7f ->
                watch((likeButtonTop!! - screenHeight / 8).coerceAtLeast(0), screenHeight)
            else -> Screen.Other("YouTube feed / other")
        }
        return Snapshot(screen, ids)
    }

    override fun pagerScreen(pagerBounds: Rect?) = Screen.Pager(
        Rule.YT_SHORTS_SWIPE, Rule.YT_SHORTS_ALL, "YouTube Shorts",
        shortsMessages.first, shortsMessages.second, pagerBounds,
    )

    private fun watch(playerBottom: Int, screenHeight: Int) =
        Screen.Feed(Rule.YT_RELATED, "YouTube video", playerBottom, screenHeight, relatedMessage)

    private fun isOpenWatchPlayer(player: Rect, width: Int, height: Int): Boolean {
        val wide = player.width() >= width * YouTubeRules.MIN_PLAYER_WIDTH
        val atTop = player.top <= height * YouTubeRules.MAX_PLAYER_TOP
        // Landscape / full-screen player has nothing below it to block.
        val fullScreen = player.height() >= height * 0.9f
        return wide && atTop && !fullScreen
    }

    override fun isFreeScroll(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(20) {
            val id = current?.shortId() ?: ""
            if (YouTubeRules.panelIdHints.any { hint -> id.contains(hint) }) return true
            current = current?.parent ?: return false
        }
        return false
    }
}
