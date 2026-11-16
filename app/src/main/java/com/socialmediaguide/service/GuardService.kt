package com.socialmediaguide.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.socialmediaguide.facebook.FacebookDetector
import com.socialmediaguide.guard.AppDetector
import com.socialmediaguide.guard.BlockMessage
import com.socialmediaguide.guard.GuardedApp
import com.socialmediaguide.guard.NodeTree
import com.socialmediaguide.guard.NodeTree.shortId
import com.socialmediaguide.guard.Screen
import com.socialmediaguide.guard.Snapshot
import com.socialmediaguide.instagram.InstagramDetector
import com.socialmediaguide.settings.GuardSettings
import com.socialmediaguide.settings.formatMinutes
import com.socialmediaguide.tiktok.TikTokDetector
import com.socialmediaguide.youtube.YouTubeDetector

/**
 * Watches the guarded apps (only their packages are delivered to this service, see
 * res/xml/guard_service.xml) and covers the feed when the user starts doomscrolling:
 * - [Screen.Feed]: scrolling down a feed (YouTube related videos, Instagram Explore,
 *   Facebook Video) is covered;
 * - [Screen.Pager]: swiping to the next Short / Reel is covered (or the whole player,
 *   if that app's "entirely" switch is on).
 */
class GuardService : AccessibilityService() {

    private val detectors: Map<GuardedApp, AppDetector> =
        listOf(YouTubeDetector, InstagramDetector, FacebookDetector, TikTokDetector).associateBy { it.app }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: GuardSettings
    private lateinit var overlay: BlockOverlay

    private var app: GuardedApp? = null
    private var screen: Screen = Screen.Other()
    private var lastLoggedClues: Set<String> = emptySet()

    /** The screen that caused the block now on show, and its app. */
    private var blockedScreen: Screen? = null
    private var blockedApp: GuardedApp? = null

    /** Scroll events are ignored until this time while we scroll the app back ourselves. */
    private var rewindUntil = 0L

    /** Index of the item that was open when a Shorts / Reels pager appeared. */
    private var pagerStartIndex: Int? = null

    /** When the current screen appeared (uptime ms). */
    private var screenSince = 0L

    /** A scroll is waiting [CONFIRM_MS] to check the screen is still open before blocking. */
    private var confirmPending = false

    /** The last Shorts / Reels pager seen, and when, to ride over brief misreads mid-swipe. */
    private var lastPager: Screen.Pager? = null
    private var lastPagerAt = 0L

    /** The player whose visit is in progress (settle time, start index, fingerprint). */
    private var lastVisit: Screen.Pager? = null

    private fun rememberPager(pager: Screen.Pager) {
        lastPager = pager
        lastPagerAt = SystemClock.uptimeMillis()
    }

    private var refreshPending = false
    private val refreshRunnable = Runnable {
        refreshPending = false
        refresh()
    }

    /**
     * Only guarded apps send events, so leaving one (Home, Recents, a notification) can't be
     * seen from events. While the panel is up, check every 0.6 s that its app is still in front.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!overlay.isShowing || previewing) return
            val front = GuardedApp.forPackage(rootInActiveWindow?.packageName)
            if (front != blockedApp) clearBlock() else handler.postDelayed(this, 600)
        }
    }

    /** True while the "Preview block screen" demo is showing; its buttons just close it. */
    private var previewing = false
    private val endPreview = Runnable { if (previewing) { previewing = false; overlay.hide() } }

    override fun onServiceConnected() {
        settings = GuardSettings.get(this)
        overlay = BlockOverlay(this, onBack = ::backToContent, onLeave = ::leave, onLeaveApp = ::leaveApp)
        instance = this
        log { "service connected" }
    }

    /** Shows the block screen for a few seconds over whatever is open, so the user can see it. */
    fun preview() {
        val (_, height) = screenSize()
        previewing = true
        overlay.show(
            BlockMessage(
                "SCROLL STOPPED", "You came for this video.\nThe feed of related videos is blocked.", "BACK TO VIDEO",
                showLeaveApp = settings.config.value.showLeaveApp,
            ),
            top = (height * 0.32f).toInt(),
            bottom = height,
        )
        handler.removeCallbacks(endPreview)
        handler.postDelayed(endPreview, 6000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventApp = GuardedApp.forPackage(event.packageName) ?: return
        startUsageTracking(eventApp)
        // A new window (another screen of the app opened): the next Short / Reel player seen
        // is a new visit, never a continuation of the last one.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !overlay.isShowing) lastPagerAt = 0L
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Playing videos change content non-stop, so throttle (at most one check
                // every 300 ms) rather than waiting for quiet, which might never come.
                if (!refreshPending) {
                    refreshPending = true
                    handler.postDelayed(refreshRunnable, 300)
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handler.removeCallbacks(refreshRunnable)
                refreshPending = false
                refresh()
                onScrolled(event)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }

    // ---- screen tracking ------------------------------------------------------------

    private fun refresh() {
        val root = rootInActiveWindow
        val frontApp = GuardedApp.forPackage(root?.packageName)
        val detector = frontApp?.let { detectors[it] }
        var snapshot = if (root != null && detector != null) {
            val (width, height) = screenSize()
            detector.classify(root, width, height)
        } else {
            Snapshot(Screen.Other("Not a guarded app"), emptySet())
        }
        val fresh = snapshot.screen as? Screen.Pager
        val previous = screen
        if (frontApp == app && root != null) {
            if (snapshot.clues.isEmpty()) {
                // An empty read (the app is mid-redraw) says nothing about the screen: keep it.
                snapshot = snapshot.copy(screen = previous)
            } else if (snapshot.screen is Screen.Other && previous is Screen.Pager && isSamePagerMidSwipe(root, previous)) {
                // Mid-swipe the player shows two half pages and no longer looks like one by shape.
                snapshot = snapshot.copy(screen = previous)
                rememberPager(previous)
            }
        }
        onScreen(frontApp, snapshot)
        // Note the sighting only after onScreen has decided whether this is a new visit
        // (noting it first made every newly opened Short look like "the same visit").
        if (fresh != null) rememberPager(fresh)
        checkPageChanged(root)
    }

    /** The known player is still there (same place), its pages just mid-swipe. */
    private fun isSamePagerMidSwipe(root: AccessibilityNodeInfo, pager: Screen.Pager): Boolean {
        val bounds = pager.pagerBounds ?: return false
        val node = NodeTree.findScrollableAt(root, bounds) ?: return false
        return NodeTree.isPagerMidSwipe(node) || NodeTree.isFullScreenPager(node, screenSize().second)
    }

    // ---- "a different Short / Reel is on screen" ------------------------------------

    /** Texts of the Short / Reel open when the visit started (in memory only). */
    private var pageBaseline: Set<String>? = null
    private var lastPageTexts: Set<String>? = null

    /**
     * While a Shorts / Reels player is open, look again every [PAGER_TICK_MS]: a player can
     * move to the next page without Android announcing anything, so events alone can miss it.
     */
    private val pagerTicker = object : Runnable {
        override fun run() {
            if (screen !is Screen.Pager) return
            if (!overlay.isShowing) refresh()
            handler.postDelayed(this, PAGER_TICK_MS)
        }
    }

    private fun startPagerTicker() {
        handler.removeCallbacks(pagerTicker)
        handler.postDelayed(pagerTicker, PAGER_TICK_MS)
    }

    /** Last reason the page check couldn't run, logged once per change. */
    private var pageProblem: String? = null

    /**
     * Blocks once the next Short / Reel is on screen, even if the app sent no scroll events
     * (YouTube 21.43 sends none for Shorts): the texts on the page are compared with those of
     * the Short the visit started on. A different Short means its main text (the title) is
     * gone AND new texts appeared; a comment sheet or a caption line doesn't do both.
     * Only a fingerprint is kept, only while the player is open; the log shows codes, not text.
     */
    private fun checkPageChanged(root: AccessibilityNodeInfo?) {
        val pager = screen as? Screen.Pager ?: return
        val config = settings.config.value
        if (root == null || overlay.isShowing || !config.isOn(pager.swipeRule)) return
        if (SystemClock.uptimeMillis() < rewindUntil) return
        if (SystemClock.uptimeMillis() - screenSince < SETTLE_MS) return
        val bounds = pager.pagerBounds ?: return pageNote("the player's position is unknown")
        val playerIds = app?.let { detectors[it] }?.playerIds ?: emptySet()
        // YouTube doesn't mark its Shorts pager scrollable, so fall back to any view there.
        val node = NodeTree.findScrollableAt(root, bounds) ?: NodeTree.findNodeAt(root, bounds, playerIds)
            ?: return pageNote("no view at the player's position $bounds")
        val nodeName = node.shortId() ?: node.className?.toString()?.substringAfterLast('.')
        if (pageBaseline == null && pageProblem == null) {
            log { "player: ${describePlayer(node)}" }
        }
        val texts = NodeTree.pageTexts(node)
        if (texts.size < 2) return pageNote("only ${texts.size} texts on the page ($nodeName)")
        pageProblem = null
        if (texts != lastPageTexts) {
            lastPageTexts = texts
            log { "page texts ($nodeName, ${texts.size}): ${describeTexts(texts)}" }
        }
        val baseline = pageBaseline
        if (baseline == null) {
            pageBaseline = texts
            log { "page fingerprint taken (${texts.size} texts)" }
            return
        }
        val removed = baseline - texts
        val added = texts - baseline
        if (removed.isEmpty()) {
            // Same Short, more of it loaded (or a sheet opened on top): grow the fingerprint.
            if (added.isNotEmpty()) pageBaseline = baseline + added
            return
        }
        val mainTextGone = removed.any { it.length >= MAIN_TEXT_LENGTH }
        if (removed.size >= 2 && added.size >= 2 && mainTextGone) {
            log { "page changed: ${removed.size} of ${baseline.size} first-page texts gone, ${added.size} new" }
            confirmThenBlock(pager)
        }
    }

    private fun pageNote(reason: String) {
        if (reason != pageProblem) {
            pageProblem = reason
            log { "page check: $reason" }
        }
    }

    /** The player's id, class, scrollability and its visible children's positions (diagnostics). */
    private fun describePlayer(node: AccessibilityNodeInfo): String {
        val r = Rect().also { node.getBoundsInScreen(it) }
        val c = Rect()
        val children = (0 until minOf(node.childCount, 6)).mapNotNull { i ->
            node.getChild(i)?.let { child ->
                child.getBoundsInScreen(c)
                "${child.shortId() ?: child.className?.toString()?.substringAfterLast('.')}" +
                    "${if (child.isVisibleToUser) "" else "(hidden)"}@${c.top}-${c.bottom}" +
                    "(h=${NodeTree.fullHeight(child)})"
            }
        }
        return "${node.shortId()} ${node.className?.toString()?.substringAfterLast('.')} at $r " +
            "scrollable=${node.isScrollable} children=${node.childCount} $children"
    }

    /** "a1f3/42 09bc/17 …": a short code and the length of each text, longest first. */
    private fun describeTexts(texts: Set<String>) = texts.sortedByDescending { it.length }.take(14)
        .joinToString(" ") { "%04x/%d".format(it.hashCode() and 0xffff, it.length) }

    private fun resetPageFingerprint() {
        pageBaseline = null
        lastPageTexts = null
        pageProblem = null
    }

    private fun onScreen(frontApp: GuardedApp?, snapshot: Snapshot) {
        val previous = screen
        app = frontApp
        screen = snapshot.screen
        if (previous.label != screen.label) {
            settings.reportScreen(screen.label)
            val now = SystemClock.uptimeMillis()
            // A new visit to a Shorts / Reels player starts its settle time, start index and
            // page fingerprint. Back on the same player within moments is the same visit (a
            // misread mid-swipe), so a swipe in progress still counts.
            val current = screen
            val newVisit = current is Screen.Pager &&
                !(current.label == lastVisit?.label && now - lastPagerAt < PAGER_MEMORY_MS)
            log { "screen ${current.label}${if (current is Screen.Pager && !newVisit) " (same visit)" else ""}" }
            if (newVisit) {
                lastVisit = current as Screen.Pager
                pagerStartIndex = null
                screenSince = now
                resetPageFingerprint()
            }
            if (current is Screen.Pager) startPagerTicker()
        }
        if (settings.config.value.diagnostics && snapshot.clues != lastLoggedClues) {
            lastLoggedClues = snapshot.clues
            log { "clues ${frontApp?.label} (${snapshot.clues.size}): ${snapshot.clues.sorted().joinToString(" ")}" }
        }

        // "Block entirely" or the daily time limit: the whole app is covered, whatever screen.
        val appBlock = frontApp?.let { appBlockMessage(it) }
        if (frontApp != null && appBlock != null && !previewing) {
            showAppBlock(frontApp, appBlock)
            return
        }

        if (overlay.isShowing && !previewing) {
            val blocked = blockedScreen
            val current = screen
            when {
                frontApp != blockedApp -> clearBlock()
                blocked is Screen.Feed && current is Screen.Feed && current.rule == blocked.rule ->
                    overlay.show(overlay.message ?: blocked.message, current.coverTop, current.coverBottom)
                blocked is Screen.Pager && current is Screen.Pager && current.swipeRule == blocked.swipeRule -> Unit
                else -> clearBlock()
            }
        }

        val current = screen
        if (current is Screen.Pager && settings.config.value.isOn(current.allRule) && !overlay.isShowing) {
            block(current, wholePlayer = true)
        }
    }

    // ---- scrolling --------------------------------------------------------------------

    private fun onScrolled(event: AccessibilityEvent) {
        val config = settings.config.value
        val deltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else 0
        val deltaX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaX else 0
        val source = event.source

        if (config.diagnostics) {
            val r = Rect().also { source?.getBoundsInScreen(it) }
            log {
                "scroll ${app?.label} on=${screen.label} src=${source?.shortId()} class=${event.className} " +
                    "from=${event.fromIndex} to=${event.toIndex} items=${event.itemCount} dx=$deltaX dy=$deltaY bounds=$r"
            }
        }
        if (SystemClock.uptimeMillis() < rewindUntil || overlay.isShowing) return
        // Sideways scrolls (chip rows, carousels) are never doomscrolling the feed.
        if (deltaX != 0 && deltaY == 0) return

        // The screen check can miss a pager (huge view trees are cut short); the scrolled view
        // itself shows it: a full-screen vertical pager is a Shorts / Reels player.
        if (screen is Screen.Other && source != null) {
            val sourceBounds = Rect().also { source.getBoundsInScreen(it) }
            // The player seen before, at the same place, mid-swipe.
            val recent = lastPager?.takeIf { it.pagerBounds == sourceBounds && NodeTree.isPagerMidSwipe(source) }
            val detector = app?.let { detectors[it] }
            val byShape = if (recent == null && detector != null && NodeTree.isFullScreenPager(source, screenSize().second)) {
                detector.pagerScreen(sourceBounds)
            } else null
            val found = recent ?: byShape
            if (found != null) {
                log { "player found from the scroll" }
                // Same bookkeeping as any screen change (new visit or not), then note the sighting.
                onScreen(app, Snapshot(found, lastLoggedClues))
                rememberPager(found)
            }
        }

        when (val current = screen) {
            is Screen.Feed -> if (config.isOn(current.rule) && isFeedScroll(source, current, deltaY, event)) {
                confirmThenBlock(current)
            }
            is Screen.Pager -> if (config.isOn(current.swipeRule) || config.isOn(current.allRule)) {
                onPagerScrolled(current, source, deltaY, event.fromIndex)
            }
            is Screen.Other -> Unit
        }
    }

    /**
     * A scroll of a Shorts / Reels pager. Three independent signs of a swipe, because apps
     * differ in what they report: the scroll distance, the item index, or the item no longer
     * lining up with the pager (the next one sliding in).
     */
    private fun onPagerScrolled(pager: Screen.Pager, source: AccessibilityNodeInfo?, deltaY: Int, index: Int) {
        // Only the pager itself counts. Exit animations and other views also send scroll events.
        if (source == null || !isPagerSource(source, pager)) {
            log { "pager check: not the pager (expected ${pager.pagerBounds})" }
            return
        }
        // The pager lays itself out when it opens; that's not a swipe. Note where it started.
        if (SystemClock.uptimeMillis() - screenSince < SETTLE_MS) {
            if (index >= 0) pagerStartIndex = index
            return
        }
        val start = pagerStartIndex
        if (start == null && index >= 0) pagerStartIndex = index
        val indexChanged = start != null && start >= 0 && index >= 0 && index != start
        val midSwipe = isMidSwipe(source)
        val byDistance = deltaY != 0 && app?.let { detectors[it] }?.swipeByDistance != false
        log { "pager check: dy=$deltaY index=$index start=$start midSwipe=$midSwipe byDistance=$byDistance" }
        if (byDistance || indexChanged || midSwipe) confirmThenBlock(pager)
    }

    /**
     * True when [source] is the pager found by the detector: the same place on screen, to a
     * few pixels. (A loose match let Facebook's feed list, which sits just inside the tab
     * holder, pass as the player.)
     */
    private fun isPagerSource(source: AccessibilityNodeInfo, pager: Screen.Pager): Boolean {
        val r = Rect().also { source.getBoundsInScreen(it) }
        val height = screenSize().second
        val expected = pager.pagerBounds ?: return NodeTree.isFullScreenPager(source, height)
        val tolerance = maxOf(16f, height * 0.01f)
        val samePlace = Math.abs(r.top - expected.top) < tolerance && Math.abs(r.bottom - expected.bottom) < tolerance
        // Same place isn't enough: YouTube's home feed can fill exactly the player's area once
        // its toolbar hides. The source must also look like a pager (full-width pages, no feed).
        return samePlace && (NodeTree.isPagerMidSwipe(source) || NodeTree.isFullScreenPager(source, height))
    }

    /**
     * Another page is peeking into the pager: a swipe is in progress. (Bounds are clipped to
     * the screen, so the page moving out still looks aligned; the page coming in is the tell:
     * a visible child that starts well below the pager's top edge.)
     */
    private fun isMidSwipe(pager: AccessibilityNodeInfo): Boolean {
        val p = Rect().also { pager.getBoundsInScreen(it) }
        if (p.height() <= 0) return false
        val margin = p.height() * 0.04f
        val c = Rect()
        for (i in 0 until pager.childCount) {
            val child = pager.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue
            child.getBoundsInScreen(c)
            if (c.isEmpty) continue
            if (c.top > p.top + margin && c.top < p.bottom - margin) return true
        }
        return false
    }

    /** A downward scroll of the feed list itself (not a comment sheet or a panel). */
    private fun isFeedScroll(source: AccessibilityNodeInfo?, feed: Screen.Feed, deltaY: Int, event: AccessibilityEvent): Boolean {
        if (deltaY < 0) return false // scrolling back up is always fine
        source ?: return deltaY > 0
        val r = Rect()
        source.getBoundsInScreen(r)
        if (r.top < feed.coverTop - 8 || r.top > feed.coverBottom) return false
        val detector = app?.let { detectors[it] }
        if (detector != null && detector.isFreeScroll(source)) return false
        // Some lists report no scroll distance, so also check whether the list has left its top.
        return deltaY > 0 || event.fromIndex > 0 || canScrollBack(source)
    }

    /** A list offers "scroll backward" only once it has left its top, i.e. it was scrolled down. */
    private fun canScrollBack(list: AccessibilityNodeInfo): Boolean =
        list.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }

    /**
     * Blocks only if the same screen is still open a moment later. Leaving a screen (Back from
     * Shorts, minimising a video) animates it away with scroll events; this ignores those.
     */
    private fun confirmThenBlock(screen: Screen) {
        if (confirmPending) return
        confirmPending = true
        handler.postDelayed({
            confirmPending = false
            refresh()
            val now = this.screen
            val same = when {
                overlay.isShowing -> false
                screen is Screen.Feed && now is Screen.Feed -> now.rule == screen.rule
                screen is Screen.Pager && now is Screen.Pager -> now.swipeRule == screen.swipeRule
                else -> false
            }
            if (same) block(now, wholePlayer = false) else log { "ignored scroll: ${screen.label} -> ${now.label}" }
        }, CONFIRM_MS)
    }

    // ---- blocking -------------------------------------------------------------------

    private fun block(screen: Screen, wholePlayer: Boolean) {
        val (_, height) = screenSize()
        val config = settings.config.value
        when (screen) {
            is Screen.Feed -> overlay.show(screen.message.copy(showLeaveApp = config.showLeaveApp), screen.coverTop, screen.coverBottom)
            is Screen.Pager -> {
                val message = when {
                    wholePlayer -> screen.allMessage
                    // "Back to the Short" switched off: no way back to it.
                    !config.allowBackToItem -> screen.swipeMessage.copy(backLabel = null)
                    else -> screen.swipeMessage
                }
                overlay.show(message.copy(showLeaveApp = config.showLeaveApp), 0, height)
            }
            is Screen.Other -> return
        }
        if (!overlay.isShowing) return
        blockedScreen = screen
        blockedApp = app
        app?.let { settings.countBlock(it) }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 600)
        log { "blocked ${screen.label}${if (wholePlayer) " (whole player)" else ""}" }
    }

    // ---- whole-app blocks: "block entirely" and the daily time limit -----------------

    /** The block screen for [app] if the whole app is blocked right now, else null. */
    private fun appBlockMessage(app: GuardedApp): BlockMessage? {
        val config = settings.config.value
        return when {
            config.isAppBlocked(app) -> BlockMessage(
                "${app.label.uppercase()} IS BLOCKED",
                "You chose to block ${app.label} entirely.\nTurn it off in Social Media Guide to use it again.",
                backLabel = null, leaveAppOnly = true,
            )
            settings.isOverLimit(app) -> BlockMessage(
                "TIME'S UP",
                "You've used ${app.label} for ${formatMinutes(config.limitMinutes(app))} today, your daily limit.\n" +
                    "It opens again tomorrow.",
                backLabel = null, leaveAppOnly = true,
            )
            else -> null
        }
    }

    private fun showAppBlock(app: GuardedApp, message: BlockMessage) {
        if (overlay.isShowing && blockedApp == app && blockedScreen == APP_BLOCK && overlay.message == message) return
        overlay.show(message, 0, screenSize().second)
        if (!overlay.isShowing) return
        blockedScreen = APP_BLOCK
        blockedApp = app
        settings.countBlock(app)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 600)
        log { "blocked ${app.label}: ${message.title.lowercase()}" }
    }

    /** The guarded app being timed, and since when (elapsed ms). */
    private var usageApp: GuardedApp? = null
    private var usageSince = 0L

    /**
     * Every [USAGE_TICK_MS] while a guarded app is in front with the screen on, adds the time
     * since the last tick to today's usage, and blocks the app once its limit is reached.
     * Stops when no guarded app is in front; the app's next event starts it again.
     */
    private val usageTicker = object : Runnable {
        override fun run() {
            tickUsage()
            if (usageApp != null) handler.postDelayed(this, USAGE_TICK_MS)
        }
    }

    private fun startUsageTracking(app: GuardedApp) {
        if (usageApp != null || !isScreenOn()) return
        usageApp = app
        usageSince = SystemClock.elapsedRealtime()
        handler.removeCallbacks(usageTicker)
        handler.postDelayed(usageTicker, USAGE_TICK_MS)
    }

    private fun tickUsage() {
        val now = SystemClock.elapsedRealtime()
        usageApp?.let { counted ->
            // Capped, so a stalled tick (phone asleep) never adds a big jump.
            val elapsed = (now - usageSince).coerceIn(0L, USAGE_TICK_MS * 3)
            if (elapsed > 0) settings.addUsage(counted, elapsed)
        }
        usageSince = now
        val front = GuardedApp.forPackage(rootInActiveWindow?.packageName)
        usageApp = front.takeIf { isScreenOn() }
        val timed = usageApp ?: return
        if (blockedScreen != APP_BLOCK && settings.isOverLimit(timed)) {
            log { "${timed.label}: daily time limit reached" }
            refresh()
        }
    }

    private fun isScreenOn() = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive

    private fun clearBlock() {
        overlay.hide()
        blockedScreen = null
        blockedApp = null
        handler.removeCallbacks(watchdog)
    }

    /**
     * "Back to video / top": scroll the feed back to its top, then uncover it.
     * "Back to the Short / Reel": uncover the Short that was blocked (the one on screen now),
     * not the one before it. It becomes the new starting point, so the next swipe is blocked
     * again.
     */
    private fun backToContent() {
        if (previewing) { previewing = false; overlay.hide(); return }
        val blocked = blockedScreen
        clearBlock()
        rewindUntil = SystemClock.uptimeMillis() + 1500
        when (blocked) {
            is Screen.Feed -> rewindList(blocked.coverTop, stepsLeft = 25)
            is Screen.Pager -> {
                pagerStartIndex = null
                resetPageFingerprint()
                screenSince = SystemClock.uptimeMillis() // settle before the new fingerprint is taken
                log { "back to the Short: showing the blocked Short; the next swipe is blocked again" }
            }
            else -> Unit
        }
    }

    /** "Leave app": straight to the phone's home screen. */
    private fun leaveApp() {
        if (previewing) { previewing = false; overlay.hide(); return }
        clearBlock()
        rewindUntil = SystemClock.uptimeMillis() + 800
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** Scrolls the feed list up one page at a time until it reaches the top. */
    private fun rewindList(top: Int, stepsLeft: Int) {
        if (stepsLeft == 0) return
        val list = NodeTree.findListBelow(rootInActiveWindow, top) ?: return
        rewindUntil = SystemClock.uptimeMillis() + 1500
        if (list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            handler.postDelayed({ rewindList(top, stepsLeft - 1) }, 150)
        }
    }

    private fun leave() {
        if (previewing) { previewing = false; overlay.hide(); return }
        clearBlock()
        rewindUntil = SystemClock.uptimeMillis() + 800
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ---- helpers --------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun log(message: () -> String) {
        if (::settings.isInitialized && settings.config.value.diagnostics) {
            val line = message()
            Log.d(TAG, line)
            settings.appendLog(line)
        }
    }

    companion object {
        /** Marks a block of the whole app (as opposed to a feed or player screen). */
        private val APP_BLOCK = Screen.Other("Whole app blocked")
        private const val USAGE_TICK_MS = 5_000L

        private const val TAG = "SMG"

        /** How long a Shorts / Reels pager may lay itself out after opening before swipes count. */
        private const val SETTLE_MS = 700L

        /** Wait before blocking, to ignore the scrolls of a screen that's closing. */
        private const val CONFIRM_MS = 350L

        /** Seeing the same pager again within this time continues the same visit. */
        private const val PAGER_MEMORY_MS = 2000L

        /** How often an open Shorts / Reels player is re-checked without any event. */
        private const val PAGER_TICK_MS = 800L

        /** A text this long is a main text (title / caption), not a button label. */
        private const val MAIN_TEXT_LENGTH = 10

        /** The running service (same process as the app), for the preview button. */
        @Volatile var instance: GuardService? = null
            private set
    }
}
