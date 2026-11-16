package com.socialmediaguide.guard

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Helpers for walking another app's accessibility node tree. */
object NodeTree {

    private const val MAX_NODES = 1200

    /** Iterative depth-first walk, capped so a huge tree can't stall the service. */
    inline fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var count = 0
        while (stack.isNotEmpty() && count < maxNodes()) {
            val node = stack.removeLast()
            count++
            visit(node)
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
    }

    fun maxNodes() = MAX_NODES

    /** View id without the "package:id/" prefix, or null. */
    fun AccessibilityNodeInfo.shortId(): String? = viewIdResourceName?.substringAfter(":id/")

    fun AccessibilityNodeInfo.bounds(): Rect = Rect().also { getBoundsInScreen(it) }

    /** The tallest vertically scrollable node that starts at or below [top]: the feed list. */
    fun findListBelow(root: AccessibilityNodeInfo?, top: Int): AccessibilityNodeInfo? {
        root ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestHeight = 0
        walk(root) { node ->
            if (!node.isScrollable) return@walk
            val r = node.bounds()
            if (r.top >= top - 8 && r.height() > bestHeight && r.height() > r.width() / 3) {
                best = node
                bestHeight = r.height()
            }
        }
        return best
    }

    /**
     * A Shorts / Reels style player recognised by shape, whatever its id: a scrollable view
     * filling most of the screen whose visible page is as tall as the view itself. Feeds,
     * search results and video pages have rows much shorter than the list, so they don't match.
     */
    fun findFullScreenPager(root: AccessibilityNodeInfo?, screenHeight: Int): AccessibilityNodeInfo? {
        root ?: return null
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (found == null && node.isScrollable && isFullScreenPager(node, screenHeight)) found = node
        }
        return found
    }

    /**
     * True for a Shorts / Reels player: a scrollable view filling most of the screen, not
     * sideways, whose visible page fills it AND holds no scrolling list of its own.
     *
     * The last check matters: apps like Facebook and YouTube put each main tab (Home, Video…)
     * in a full-screen page holder with the feed scrolling *inside* the page. That holder is
     * page-shaped too, but its page contains a feed; a Short or Reel contains a video and
     * buttons.
     */
    fun isFullScreenPager(node: AccessibilityNodeInfo, screenHeight: Int): Boolean {
        val p = node.bounds()
        if (p.height() < screenHeight * 0.75f || p.width() <= 0) return false
        // Sideways pagers (tab pages, carousels) also have full-size pages; they're not Shorts.
        if (isHorizontal(node)) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue
            val c = child.bounds()
            if (c.height() >= p.height() * 0.95f && c.width() >= p.width() * 0.95f) {
                return !isTallerThan(child, p.height()) && !containsFeed(child, c.height())
            }
        }
        return false
    }

    /**
     * The pager already known at these bounds, seen mid-swipe: two partial pages instead of
     * one full one. Still a pager if every visible page is full width, holds no feed, and
     * together they fill it. (Only used for a view already recognised as the player: a feed's
     * rows would pass this too.)
     */
    fun isPagerMidSwipe(node: AccessibilityNodeInfo): Boolean {
        val p = node.bounds()
        if (p.height() <= 0 || isHorizontal(node)) return false
        var filled = 0
        var pages = 0
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue
            val c = child.bounds()
            if (c.isEmpty) continue
            if (c.width() < p.width() * 0.95f || isTallerThan(child, p.height()) || containsFeed(child, c.height())) return false
            filled += c.height()
            pages++
        }
        return pages in 1..3 && filled >= p.height() * 0.9f
    }

    /**
     * The page's real height, not just the part on screen. Screen bounds are clipped to the
     * list, so one long post filling a feed looks exactly as tall as the list, like a Reel.
     * Bounds-in-parent are the view's own size, unclipped. Null when the app doesn't report it.
     */
    @Suppress("DEPRECATION")
    fun fullHeight(page: AccessibilityNodeInfo): Int? {
        val r = Rect().also { page.getBoundsInParent(it) }
        return r.height().takeIf { it > 0 }
    }

    /**
     * A page clearly taller than its pager: a long feed post being scrolled through, not a
     * Short or Reel (a pager's pages are exactly its height).
     */
    fun isTallerThan(page: AccessibilityNodeInfo, pagerHeight: Int): Boolean {
        val full = fullHeight(page) ?: return false
        return full > pagerHeight * 1.08f
    }

    /**
     * Whether [page] is, or holds, a vertical scrolling list at least half its height (a feed).
     * "Is" matters: in Facebook's and YouTube's tab holders the page reported to
     * accessibility is the feed list itself (Facebook: `list`, YouTube: `results`).
     */
    private fun containsFeed(page: AccessibilityNodeInfo, pageHeight: Int): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(page)
        var count = 0
        while (stack.isNotEmpty() && count < 400) {
            val node = stack.removeLast()
            count++
            if (node.isScrollable && !isHorizontal(node) && node.bounds().height() >= pageHeight / 2) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return false
    }

    private val sidewaysActions = buildSet {
        add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id)
        add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id)
        }
    }

    /** Scrolls sideways: offers left/right actions, or reports one row of several columns. */
    fun isHorizontal(node: AccessibilityNodeInfo): Boolean {
        if (node.actionList.any { it.id in sidewaysActions }) return true
        val info = node.collectionInfo ?: return false
        return info.rowCount == 1 && info.columnCount > 1
    }

    /**
     * The node at exactly [bounds], scrollable or not (YouTube's Shorts pager isn't marked
     * scrollable). Prefers a scrollable one, then one with a view id.
     */
    fun findNodeAt(root: AccessibilityNodeInfo?, bounds: Rect, playerIds: Set<String> = emptySet()): AccessibilityNodeInfo? {
        root ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        walk(root) { node ->
            if (node.bounds() != bounds) return@walk
            val score = (if (node.shortId() in playerIds) 4 else 0) +
                (if (node.isScrollable) 2 else 0) + (if (node.viewIdResourceName != null) 1 else 0)
            if (score > bestScore) {
                best = node
                bestScore = score
            }
        }
        return best
    }

    /** A scrollable node exactly at [bounds]: the pager seen before, now mid-swipe. */
    fun findScrollableAt(root: AccessibilityNodeInfo?, bounds: Rect): AccessibilityNodeInfo? {
        root ?: return null
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (found == null && node.isScrollable && node.bounds() == bounds) found = node
        }
        return found
    }

    /**
     * A fingerprint of what a Shorts / Reels page shows: its short visible texts (title,
     * channel, caption), skipping counts and times that change while it plays. Used only to
     * notice that a different Short is on screen; kept in memory, never stored.
     */
    fun pageTexts(pager: AccessibilityNodeInfo): Set<String> {
        val p = pager.bounds()
        val texts = HashSet<String>()
        walk(pager) { node ->
            if (!node.isVisibleToUser) return@walk
            val t = (node.text ?: node.contentDescription)?.toString()?.trim() ?: return@walk
            if (t.length !in 3..120 || t.none { it.isLetter() } || countLike.matches(t)) return@walk
            val b = node.bounds()
            if (b.top >= p.top && b.bottom <= p.bottom) texts += t
        }
        return texts
    }

    /** "12K", "1.2M likes", "3 comments", "0:42"… numbers that change while a Short plays. */
    private val countLike = Regex("""^[\d.,:\s]+[KMBkmb]?(\s+\w+)?$""")
}
