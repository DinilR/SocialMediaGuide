package com.socialmediaguide

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.socialmediaguide.service.GuardService
import com.socialmediaguide.settings.GuardSettings
import com.socialmediaguide.ui.GuideTheme
import com.socialmediaguide.ui.HomeScreen

class MainActivity : ComponentActivity() {

    private var serviceOn by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val settings = GuardSettings.get(this)
        setContent {
            GuideTheme {
                HomeScreen(
                    settings = settings,
                    serviceOn = serviceOn,
                    onOpenAccessibility = ::openAccessibilitySettings,
                    onOpenAppInfo = ::openAppInfo,
                    onPreview = { GuardService.instance?.preview() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceOn = isGuardEnabled()
        GuardSettings.get(this).refreshStats()
    }

    private fun isGuardEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, GuardService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Android 13+ needs "Allow restricted settings" here for sideloaded apps. */
    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
