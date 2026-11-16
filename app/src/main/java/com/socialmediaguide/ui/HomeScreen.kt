package com.socialmediaguide.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.socialmediaguide.BuildConfig
import com.socialmediaguide.guard.GuardedApp
import com.socialmediaguide.guard.Rule
import com.socialmediaguide.settings.DailyStats
import com.socialmediaguide.settings.GuardConfig
import com.socialmediaguide.settings.GuardSettings
import com.socialmediaguide.settings.formatMinutes

@Composable
fun HomeScreen(
    settings: GuardSettings,
    serviceOn: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onPreview: () -> Unit,
) {
    val config by settings.config.collectAsStateWithLifecycle()
    val stats by settings.stats.collectAsStateWithLifecycle()
    val usage by settings.usage.collectAsStateWithLifecycle()
    val lastScreen by settings.lastScreen.collectAsStateWithLifecycle()
    val log by settings.log.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Left open past midnight, the counts still go back to zero.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            settings.refreshStats()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Night)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(serviceOn)

        if (!serviceOn) {
            TurnOnGuide(onOpenAccessibility, onOpenAppInfo, Modifier.padding(horizontal = 16.dp))
        }

        StatsCard(stats, Modifier.padding(horizontal = 16.dp))

        if (serviceOn) {
            Button(
                onClick = onPreview,
                colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White),
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            ) { Text("PREVIEW THE BLOCK SCREEN", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        }

        GuardedApp.entries.forEach { app ->
            Section(app.label.uppercase(), Modifier.padding(horizontal = 16.dp)) {
                Rule.forApp(app).forEachIndexed { index, rule ->
                    if (index > 0) HorizontalDivider(color = NightCardHigh)
                    ToggleRow(
                        title = rule.title,
                        subtitle = rule.subtitle,
                        checked = config.isOn(rule),
                        onChange = { on -> settings.setRule(rule, on) },
                    )
                }
                HorizontalDivider(color = NightCardHigh)
                ToggleRow(
                    title = "Block ${app.label} entirely",
                    subtitle = "Cover ${app.label} as soon as it opens, on every screen. Only \"Leave app\" is offered.",
                    checked = config.isAppBlocked(app),
                    onChange = { on -> settings.setAppBlocked(app, on) },
                )
                HorizontalDivider(color = NightCardHigh)
                val limit = config.limitMinutes(app)
                ToggleRow(
                    title = "Daily time limit",
                    subtitle = "Used today: ${formatMinutes(usage.minutes(app))}. Once the limit is reached, " +
                        "${app.label} is blocked until tomorrow.",
                    checked = config.hasTimeLimit(app),
                    onChange = { on -> settings.setTimeLimitOn(app, on) },
                )
                if (config.hasTimeLimit(app)) {
                    LimitStepper(
                        minutes = limit,
                        onChange = { settings.setLimitMinutes(app, it) },
                    )
                }
            }
        }

        Section("BLOCK SCREEN", Modifier.padding(horizontal = 16.dp)) {
            ToggleRow(
                title = "Allow \"Back to the Short\"",
                subtitle = "After swiping is blocked, offer a button to watch the Short or Reel that was blocked. " +
                    "Swiping again blocks again. Turn off so a blocked Short can't be watched.",
                checked = config.allowBackToItem,
                onChange = { on -> settings.setAllowBackToItem(on) },
            )
            HorizontalDivider(color = NightCardHigh)
            ToggleRow(
                title = "Show \"Leave app\"",
                subtitle = "Add a button to every block screen that closes the app and goes to your home screen.",
                checked = config.showLeaveApp,
                onChange = { on -> settings.setShowLeaveApp(on) },
            )
        }

        Section("ADVANCED", Modifier.padding(horizontal = 16.dp)) {
            ToggleRow(
                title = "Diagnostics",
                subtitle = "Writes detected screens and screen clues to logcat (tag SMG). " +
                    "Useful when an app update breaks detection.",
                checked = config.diagnostics,
                onChange = { on -> settings.setDiagnostics(on) },
            )
            if (config.diagnostics) {
                DiagnosticsLog(lastScreen, log, onCopy = { copyLog(context, log) }, onClear = settings::clearLog)
            }
        }

        Text(
            "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · " +
                "Works offline. Watches only YouTube, Instagram, Facebook and TikTok, and stores nothing it sees.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** Last screen seen, the newest log lines, and buttons to copy or clear the log. */
@Composable
private fun DiagnosticsLog(lastScreen: String?, log: List<String>, onCopy: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text(
            "Last screen seen: ${lastScreen ?: "none yet"}",
            style = MaterialTheme.typography.bodySmall,
            color = Signal,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "To report a problem: clear the log, use the app where it goes wrong (e.g. swipe a Short), " +
                "come back here, tap COPY LOG and paste it in your message.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Night)
                .padding(10.dp)
        ) {
            Text(
                if (log.isEmpty()) "Log is empty." else log.takeLast(6).joinToString("\n") { it.take(160) },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(
                onClick = onCopy,
                enabled = log.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Night),
            ) { Text("COPY LOG (${log.size} LINES)", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClear, colors = ButtonDefaults.textButtonColors(contentColor = Signal)) {
                Text("CLEAR", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun copyLog(context: Context, log: List<String>) {
    val text = buildString {
        appendLine("Social Media Guide ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}), Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}")
        appendYouTubeVersion(context)
        log.forEach { appendLine(it) }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Social Media Guide log", text))
    Toast.makeText(context, "Log copied. Paste it in your message.", Toast.LENGTH_SHORT).show()
}

/** Versions of the guarded apps help match their screen ids. */
private fun StringBuilder.appendYouTubeVersion(context: Context) {
    GuardedApp.entries.forEach { app ->
        // The first installed package (TikTok has several); its name shows which one it is.
        val installed = app.packageNames.firstNotNullOfOrNull { pkg ->
            runCatching {
                @Suppress("DEPRECATION")
                pkg to context.packageManager.getPackageInfo(pkg, 0).versionName
            }.getOrNull()
        }
        val version = installed?.let { (pkg, v) -> if (app.packageNames.size > 1) "$v ($pkg)" else "$v" } ?: "not installed"
        appendLine("${app.label} $version")
    }
}

@Composable
private fun Header(serviceOn: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            .background(Brush.linearGradient(listOf(Violet, VioletDeep)))
    ) {
        Column(Modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 28.dp)) {
            Text("SOCIAL MEDIA", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text("GUIDE", color = Signal, fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.height(4.dp))
            Text("Watch what you came for. Skip the endless feed.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.25f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (serviceOn) Calm else Danger))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (serviceOn) "GUARD IS ON" else "GUARD IS OFF",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun TurnOnGuide(onOpenAccessibility: () -> Unit, onOpenAppInfo: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Signal, contentColor = Night),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("TURN ON THE GUARD", fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Social Media Guide uses Android's Accessibility Service to see which screen is open " +
                    "in YouTube, Instagram, Facebook and TikTok and when you scroll, so it can cover endless feeds: " +
                    "related videos, Shorts, Reels, Explore, the Facebook Video tab and TikTok's videos. It only watches those " +
                    "three apps, stores nothing it sees, and never sends anything off your phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Step(1, "Tap OPEN ACCESSIBILITY SETTINGS below.")
            Step(2, "Scroll down to \"Downloaded apps\" (Samsung: \"Installed apps\") and tap Social Media Guide.")
            Step(3, "Turn on \"Use Social Media Guide\" and tap Allow.")
            Step(4, "Come back here. The header shows GUARD IS ON.")
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onOpenAccessibility,
                colors = ButtonDefaults.buttonColors(containerColor = Night, contentColor = Signal),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("OPEN ACCESSIBILITY SETTINGS", fontWeight = FontWeight.Bold) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Spacer(Modifier.height(12.dp))
                Text("SWITCH GREYED OUT OR \"RESTRICTED SETTING\"?", fontWeight = FontWeight.Black, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android blocks this for apps installed from an APK until you allow it once:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Step(1, "Try step 3 once, so Android shows the \"Restricted setting\" message. Tap OK.")
                Step(2, "Tap OPEN APP INFO below.")
                Step(3, "Tap ⋮ at the top right, then \"Allow restricted settings\", and confirm with your PIN.")
                Step(4, "Do steps 1 to 3 above again. The switch now works.")
                TextButton(onClick = onOpenAppInfo, colors = ButtonDefaults.textButtonColors(contentColor = Night)) {
                    Text("OPEN APP INFO", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Night), contentAlignment = Alignment.Center) {
            Text("$number", color = Signal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatsCard(stats: DailyStats, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NightCard),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SCROLLS STOPPED TODAY", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("${stats.total}", color = Signal, fontSize = 56.sp, fontWeight = FontWeight.Black)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GuardedApp.entries.forEach { StatLine(it.label, stats.count(it)) }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(10.dp))
        Text("$value", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Text(
            title,
            color = Violet,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = NightCard),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { content() }
    }
}

/** "Limit per day  [−]  1 h 30 min  [+]", in 15-minute steps. */
@Composable
private fun LimitStepper(minutes: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Limit per day", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        StepButton("−", enabled = minutes > GuardConfig.MIN_LIMIT_MINUTES) {
            onChange(minutes - GuardConfig.LIMIT_STEP_MINUTES)
        }
        Text(
            formatMinutes(minutes),
            color = Signal,
            fontWeight = FontWeight.Black,
            fontSize = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(110.dp),
        )
        StepButton("+", enabled = minutes < GuardConfig.MAX_LIMIT_MINUTES) {
            onChange(minutes + GuardConfig.LIMIT_STEP_MINUTES)
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NightCardHigh, contentColor = Color.White),
        modifier = Modifier.size(44.dp),
    ) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Night,
                checkedTrackColor = Signal,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = NightCardHigh,
            ),
        )
    }
}
