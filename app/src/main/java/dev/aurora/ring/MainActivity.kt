package dev.aurora.ring

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import dev.aurora.ring.service.RingService
import dev.aurora.ring.ui.AppState
import dev.aurora.ring.ui.AuroraTheme
import dev.aurora.ring.ui.HomeScreen
import dev.aurora.ring.ui.Permissions
import dev.aurora.ring.ui.SetupScreen
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var state: AppState
    private val resumeTick = mutableIntStateOf(0)

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            RingService.reload(applicationContext)
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            RingService.repair(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        state = AppState(applicationContext)

        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
        }

        setContent {
            AuroraTheme {
                val tick by resumeTick
                val settings = state.settings
                key(tick) {
                    if (settings.setupDone) {
                        HomeScreen(
                            state = state,
                            onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        )
                    } else {
                        SetupScreen(
                            state = state,
                            onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            onRequestNotifications = {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        )
                    }
                }
            }
        }

        if (!Permissions.hasNotifications(this) && !state.settings.setupDone) {
            runCatching { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) }
        super.onDestroy()
    }
}
