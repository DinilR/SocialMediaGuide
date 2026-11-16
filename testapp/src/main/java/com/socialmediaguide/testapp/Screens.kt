package com.socialmediaguide.testapp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

private val colors = intArrayOf(0xFF8E24AA.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt())

private fun label(parent: ViewGroup, size: Float) = TextView(parent.context).apply {
    gravity = Gravity.CENTER
    textSize = size
    setTextColor(Color.WHITE)
}

/** Feed rows: short coloured rows, item 0 can be a "Reels" shelf title (like Facebook's feed). */
private class FeedRows(private val count: Int, private val title: (Int) -> String) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = count
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val tv = label(parent, 18f).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300)
        }
        return object : RecyclerView.ViewHolder(tv) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder.itemView as TextView).apply {
            text = title(position)
            setBackgroundColor(colors[position % colors.size])
        }
    }
}

/** Full-screen Short pages with a title, channel and like count, like a real Short's overlay. */
private class ShortPages(private val kind: String, private val pageId: Int = View.NO_ID) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = 30
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val page = LinearLayout(parent.context).apply {
            id = pageId
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(label(parent, 36f))
            addView(label(parent, 18f))
            addView(label(parent, 16f))
        }
        return object : RecyclerView.ViewHolder(page) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val page = holder.itemView as LinearLayout
        page.setBackgroundColor(colors[position % colors.size])
        (page.getChildAt(0) as TextView).text = "Short #$position\n($kind)"
        (page.getChildAt(1) as TextView).text = "Funny cats compilation part $position by Channel $position"
        (page.getChildAt(2) as TextView).text = "${position + 3}K"
    }
}

/**
 * Like YouTube's / Facebook's home: buttons on top, then full-screen *sideways* tab pages,
 * each holding a vertical feed whose first row is a "Reels" shelf title. Scrolling this
 * must never count as Shorts / Reels.
 */
class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { onClick() } }
        fun shorts(vararg extras: Pair<String, Boolean>) =
            Intent(this, ShortsActivity::class.java).apply { extras.forEach { putExtra(it.first, it.second) } }
        root.addView(button("Short (RecyclerView pager)") { startActivity(shorts()) })
        root.addView(button("Short (ViewPager2)") { startActivity(shorts("viewpager2" to true)) })
        root.addView(button("Short, auto-advance, no swipe") { startActivity(shorts("viewpager2" to true, "auto" to true)) })
        root.addView(button("Video page") { startActivity(Intent(this, WatchActivity::class.java)) })
        // YouTube's home shows its logo; the guard must never take this screen for Shorts.
        root.addView(TextView(this).apply {
            id = R.id.youtube_logo
            text = "YouTube-style home"
            setTextColor(Color.WHITE)
        })
        // As in the phone logs (Facebook 587, YouTube 21.43): a tab holder reported as a
        // ViewPager, scrollable, with no sideways actions, whose page IS the feed list.
        root.addView(TabHolder(this).apply {
            id = R.id.home_feed
            addView(RecyclerView(context).apply {
                id = R.id.list
                layoutManager = LinearLayoutManager(context)
                adapter = FeedRows(40) { if (it == 0) "Reels" else "Home · post $it" }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}

/**
 * Like YouTube 21.43's Shorts pager (phone log): accessibility sees it as NOT scrollable and
 * it sends NO scroll events, so the guard can only notice a new Short from the page content.
 */
class QuietShortsPager(context: android.content.Context) : RecyclerView(context) {
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = false
        info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun sendAccessibilityEventUnchecked(event: android.view.accessibility.AccessibilityEvent) {
        if (event.eventType != android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            super.sendAccessibilityEventUnchecked(event)
        }
    }
}

/** Looks to accessibility like Facebook's / YouTube's main tab holder (a classic ViewPager). */
class TabHolder(context: android.content.Context) : FrameLayout(context) {
    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun getAccessibilityClassName(): CharSequence = "androidx.viewpager.widget.ViewPager"
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = true
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
    }
}

/**
 * Like YouTube Shorts: a full-screen vertical pager, one Short per page. With "auto", it jumps
 * to the next Short by itself after 6 s without a swipe gesture, the way an app that sends no
 * usable scroll events looks to the guard.
 */
class ShortsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pager: View = if (intent.getBooleanExtra("youtube", false)) {
            QuietShortsPager(this).apply {
                id = R.id.reel_recycler
                layoutManager = LinearLayoutManager(context)
                adapter = ShortPages("YouTube-like", pageId = R.id.reel_player_page_container)
                PagerSnapHelper().attachToRecyclerView(this)
                if (intent.getBooleanExtra("auto", false)) {
                    this@ShortsActivity.handler.postDelayed({ scrollToPosition(1) }, 6000)
                }
            }
        } else if (intent.getBooleanExtra("viewpager2", false)) {
            ViewPager2(this).apply {
                orientation = ViewPager2.ORIENTATION_VERTICAL
                adapter = ShortPages("ViewPager2")
                if (intent.getBooleanExtra("auto", false)) {
                    this@ShortsActivity.handler.postDelayed({ setCurrentItem(currentItem + 1, false) }, 6000)
                }
            }
        } else {
            RecyclerView(this).apply {
                id = R.id.reel_recycler
                layoutManager = LinearLayoutManager(context)
                adapter = ShortPages("RecyclerView")
                PagerSnapHelper().attachToRecyclerView(this)
            }
        }
        setContentView(FrameLayout(this).apply { addView(pager) })
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

/** Like a YouTube video page: a wide player at the top and a list (title, then related videos) below. */
class WatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val width = resources.displayMetrics.widthPixels
        root.addView(TextView(this).apply {
            id = R.id.watch_player
            text = "▶ PLAYER"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, width * 9 / 16))
        root.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = FeedRows(40) { if (it == 0) "Title · Like · Share" else "Related video $it" }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
