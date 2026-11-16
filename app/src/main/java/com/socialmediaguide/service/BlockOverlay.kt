package com.socialmediaguide.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.socialmediaguide.guard.BlockMessage

/**
 * The "scroll stopped" panel, drawn over the guarded app as an accessibility overlay window.
 * Plain Views (not Compose) keep it fast and free of lifecycle plumbing inside a service.
 */
class BlockOverlay(
    private val context: Context,
    private val onBack: () -> Unit,
    private val onLeave: () -> Unit,
    private val onLeaveApp: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    /** The message on screen, or null when hidden. */
    var message: BlockMessage? = null
        private set

    val isShowing get() = view != null

    /** Shows [message] covering screen y [top] to [bottom]. Already showing it? Just moves. */
    fun show(message: BlockMessage, top: Int, bottom: Int) {
        val height = (bottom - top).coerceAtLeast(dp(260))
        val current = view
        if (current != null && this.message == message) {
            val params = current.layoutParams as WindowManager.LayoutParams
            if (params.y != top || params.height != height) {
                params.y = top
                params.height = height
                runCatching { windowManager.updateViewLayout(current, params) }
            }
            return
        }
        hide()
        val panel = buildPanel(message)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = top
        }
        runCatching { windowManager.addView(panel, params) }
            .onSuccess {
                view = panel
                this.message = message
            }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        message = null
    }

    private fun buildPanel(message: BlockMessage): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(24), dp(28), dp(24))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#FF2B1F66"), Color.parseColor("#FF14121F")),
        )
        // Swallow every touch so the feed underneath can't be scrolled or tapped.
        isClickable = true

        addView(TextView(context).apply {
            text = "✋" // raised hand
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = message.title
            setTextColor(Color.parseColor("#FFFFD23F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        })
        addView(TextView(context).apply {
            text = message.body
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        })
        // Buttons stacked, same width: [BACK TO THE SHORT] / [LEAVE] / [LEAVE APP].
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, 0)
            fun add(b: Button) = addView(b, LinearLayout.LayoutParams(dp(240), dp(52)).apply { topMargin = dp(10) })
            if (message.leaveAppOnly) {
                add(button("LEAVE APP", filled = true, onClick = onLeaveApp))
                return@apply
            }
            message.backLabel?.let { label -> add(button(label, filled = true, onClick = onBack)) }
            add(button("LEAVE", filled = message.backLabel == null, onClick = onLeave))
            if (message.showLeaveApp) add(button("LEAVE APP", filled = false, onClick = onLeaveApp))
        })
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(if (filled) Color.parseColor("#FF14121F") else Color.WHITE)
        stateListAnimator = null
        setPadding(dp(18), 0, dp(18), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            if (filled) setColor(Color.parseColor("#FFFFD23F"))
            else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.WHITE)
            }
        }
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
