package com.socialmediaguide.settings

import android.content.Context
import android.content.SharedPreferences
import com.socialmediaguide.guard.GuardedApp
import com.socialmediaguide.guard.Rule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Which rules are on, plus general switches. [allowBackToItem]: the Shorts / Reels block
 * screen offers "Back to the Short"; off = no way back, so it can't be watched again.
 * [showLeaveApp]: every block screen offers "Leave app" (to the phone's home screen).
 */
data class GuardConfig(
    val rules: Map<Rule, Boolean>,
    val diagnostics: Boolean,
    val allowBackToItem: Boolean = true,
    val showLeaveApp: Boolean = true,
    /** Apps blocked entirely: the block screen covers them as soon as they open. Off by default. */
    val appBlocked: Map<GuardedApp, Boolean> = emptyMap(),
    /** Apps with a daily time limit, and the limit in minutes. Off by default. */
    val timeLimitOn: Map<GuardedApp, Boolean> = emptyMap(),
    val limitMinutes: Map<GuardedApp, Int> = emptyMap(),
) {
    fun isOn(rule: Rule) = rules[rule] ?: rule.defaultOn
    fun isAppBlocked(app: GuardedApp) = appBlocked[app] ?: false
    fun hasTimeLimit(app: GuardedApp) = timeLimitOn[app] ?: false
    fun limitMinutes(app: GuardedApp) = limitMinutes[app] ?: DEFAULT_LIMIT_MINUTES

    companion object {
        const val DEFAULT_LIMIT_MINUTES = 60
        const val MIN_LIMIT_MINUTES = 15
        const val MAX_LIMIT_MINUTES = 12 * 60
        const val LIMIT_STEP_MINUTES = 15
    }
}

/** Time spent in each app today (while it was in front and the screen was on), in ms. */
data class DailyUsage(val day: String, val millis: Map<GuardedApp, Long>) {
    fun millis(app: GuardedApp) = millis[app] ?: 0L
    fun minutes(app: GuardedApp) = (millis(app) / 60_000L).toInt()
}

/** Blocks counted today, per app. */
data class DailyStats(val day: String, val counts: Map<GuardedApp, Int>) {
    val total get() = counts.values.sum()
    fun count(app: GuardedApp) = counts[app] ?: 0
}

/**
 * Settings and daily stats in SharedPreferences, shared by the app screen and the
 * accessibility service (same process), exposed as StateFlows.
 */
class GuardSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("guard", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<GuardConfig> = _config.asStateFlow()

    private val _stats = MutableStateFlow(readStats())
    val stats: StateFlow<DailyStats> = _stats.asStateFlow()

    private val _usage = MutableStateFlow(readUsage())
    val usage: StateFlow<DailyUsage> = _usage.asStateFlow()

    /** Last screen the detector saw, for the diagnostics line on the home screen. */
    private val _lastScreen = MutableStateFlow<String?>(null)
    val lastScreen: StateFlow<String?> = _lastScreen.asStateFlow()

    fun setRule(rule: Rule, on: Boolean) {
        prefs.edit().putBoolean(rule.prefKey, on).apply()
        _config.value = _config.value.copy(rules = _config.value.rules + (rule to on))
    }

    fun setAllowBackToItem(on: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_BACK, on).apply()
        _config.value = _config.value.copy(allowBackToItem = on)
    }

    fun setShowLeaveApp(on: Boolean) {
        prefs.edit().putBoolean(KEY_LEAVE_APP, on).apply()
        _config.value = _config.value.copy(showLeaveApp = on)
    }

    fun setAppBlocked(app: GuardedApp, on: Boolean) {
        prefs.edit().putBoolean(appBlockKey(app), on).apply()
        _config.value = _config.value.copy(appBlocked = _config.value.appBlocked + (app to on))
    }

    fun setTimeLimitOn(app: GuardedApp, on: Boolean) {
        prefs.edit().putBoolean(limitOnKey(app), on).apply()
        _config.value = _config.value.copy(timeLimitOn = _config.value.timeLimitOn + (app to on))
    }

    fun setLimitMinutes(app: GuardedApp, minutes: Int) {
        val m = minutes.coerceIn(GuardConfig.MIN_LIMIT_MINUTES, GuardConfig.MAX_LIMIT_MINUTES)
        prefs.edit().putInt(limitMinutesKey(app), m).apply()
        _config.value = _config.value.copy(limitMinutes = _config.value.limitMinutes + (app to m))
    }

    /** Adds time spent in [app]; a new day starts from zero. */
    fun addUsage(app: GuardedApp, millis: Long) {
        val current = readUsage()
        val next = current.copy(millis = current.millis + (app to current.millis(app) + millis))
        // Write every app, not just this one: on a new day the others must be saved as 0 too,
        // or yesterday's values would be read back as today's once the day is updated.
        val edit = prefs.edit().putString(KEY_USAGE_DAY, next.day)
        GuardedApp.entries.forEach { edit.putLong(usageKey(it), next.millis(it)) }
        edit.apply()
        _usage.value = next
    }

    /** Daily time limit reached for [app] (and the limit is on). */
    fun isOverLimit(app: GuardedApp): Boolean {
        val config = _config.value
        if (!config.hasTimeLimit(app)) return false
        return readUsage().millis(app) >= config.limitMinutes(app) * 60_000L
    }

    fun setDiagnostics(on: Boolean) {
        prefs.edit().putBoolean(KEY_DIAGNOSTICS, on).apply()
        _config.value = _config.value.copy(diagnostics = on)
    }

    fun countBlock(app: GuardedApp) {
        val current = readStats()
        val next = current.copy(counts = current.counts + (app to current.count(app) + 1))
        // Write every app, not just this one: on a new day the others must be saved as 0 too,
        // or yesterday's counts would be read back as today's once the day is updated.
        val edit = prefs.edit().putString(KEY_DAY, next.day)
        GuardedApp.entries.forEach { edit.putInt(statsKey(it), next.count(it)) }
        edit.apply()
        _stats.value = next
    }

    /**
     * Re-reads stats and usage, so a new day shows zero: when the app is reopened, and every
     * minute while it's on screen (it may be left open past midnight).
     */
    fun refreshStats() {
        val stats = readStats()
        val usage = readUsage()
        if (stats != _stats.value) _stats.value = stats
        if (usage != _usage.value) _usage.value = usage
    }

    fun reportScreen(name: String) {
        _lastScreen.value = name
    }

    /**
     * Diagnostics lines (screen changes, screen clues, scroll events, blocks), newest last,
     * kept in memory only so they can be copied from the app and sent for troubleshooting.
     */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun appendLog(line: String) {
        val stamped = "${java.time.LocalTime.now().withNano(0)} $line"
        _log.value = (_log.value + stamped).takeLast(MAX_LOG_LINES)
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private fun readConfig() = GuardConfig(
        rules = Rule.entries.associateWith { prefs.getBoolean(it.prefKey, it.defaultOn) },
        diagnostics = prefs.getBoolean(KEY_DIAGNOSTICS, false),
        allowBackToItem = prefs.getBoolean(KEY_ALLOW_BACK, true),
        showLeaveApp = prefs.getBoolean(KEY_LEAVE_APP, true),
        appBlocked = GuardedApp.entries.associateWith { prefs.getBoolean(appBlockKey(it), false) },
        timeLimitOn = GuardedApp.entries.associateWith { prefs.getBoolean(limitOnKey(it), false) },
        limitMinutes = GuardedApp.entries.associateWith {
            prefs.getInt(limitMinutesKey(it), GuardConfig.DEFAULT_LIMIT_MINUTES)
        },
    )

    private fun readUsage(): DailyUsage {
        val today = LocalDate.now().toString()
        val sameDay = prefs.getString(KEY_USAGE_DAY, null) == today
        return DailyUsage(today, GuardedApp.entries.associateWith { if (sameDay) prefs.getLong(usageKey(it), 0L) else 0L })
    }

    private fun readStats(): DailyStats {
        val today = LocalDate.now().toString()
        val sameDay = prefs.getString(KEY_DAY, null) == today
        return DailyStats(today, GuardedApp.entries.associateWith { if (sameDay) prefs.getInt(statsKey(it), 0) else 0 })
    }

    private fun statsKey(app: GuardedApp) = "stats_${app.name.lowercase()}"
    private fun appBlockKey(app: GuardedApp) = "block_app_${app.name.lowercase()}"
    private fun limitOnKey(app: GuardedApp) = "time_limit_on_${app.name.lowercase()}"
    private fun limitMinutesKey(app: GuardedApp) = "time_limit_minutes_${app.name.lowercase()}"
    private fun usageKey(app: GuardedApp) = "usage_${app.name.lowercase()}"

    companion object {
        private const val KEY_DIAGNOSTICS = "diagnostics"
        private const val KEY_ALLOW_BACK = "allow_back_to_item"
        private const val KEY_LEAVE_APP = "show_leave_app"
        private const val KEY_DAY = "stats_day"
        private const val KEY_USAGE_DAY = "usage_day"
        private const val MAX_LOG_LINES = 300

        @Volatile private var instance: GuardSettings? = null

        fun get(context: Context): GuardSettings =
            instance ?: synchronized(this) { instance ?: GuardSettings(context).also { instance = it } }
    }
}

/** "45 min", "1 h", "1 h 30 min". */
fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}
