package dev.aurora.ring.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aurora.ring.led.LightsApi

@Composable
fun SetupScreen(
    state: AppState,
    onRequestMic: () -> Unit,
    onRequestNotifications: () -> Unit
) {
    val context = LocalContext.current
    val shizukuAlive = LightsApi.shizukuAlive()
    val shizukuGranted = LightsApi.shizukuGranted()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0D1122), Aurora.Bg, Color(0xFF04050A))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(28.dp))

            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(Aurora.Gradient)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(6.dp, Color(0xFF0A0714), CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Aurora Ring",
                style = MaterialTheme.typography.displaySmall,
                color = Aurora.Text,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Light around the camera, rebuilt for HyperOS",
                style = MaterialTheme.typography.bodyLarge,
                color = Aurora.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp)
            )

            GlassCard(title = "1 · Control path", subtitle = "Root is best, Shizuku also works") {
                StatusLine(
                    label = "Root (direct AW21024 registers)",
                    state = "auto-detected",
                    ok = false,
                    neutral = true
                )
                StatusLine(
                    label = "Shizuku service",
                    state = if (shizukuAlive) "running" else "not running",
                    ok = shizukuAlive
                )
                StatusLine(
                    label = "Shizuku permission",
                    state = if (shizukuGranted) "granted" else "not granted",
                    ok = shizukuGranted
                )
                if (!shizukuGranted) {
                    GhostButton("Grant Shizuku") {
                        LightsApi.requestShizukuPermission()
                    }
                }
                Text(
                    text = "With root, Aurora writes the LED driver directly: one sysfs write per frame, " +
                        "true dual-half colours and the hardware spin spots. Shizuku only needs the MIUI " +
                        "lights service and gives you flicker-free solid colours.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextDim
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            GlassCard(title = "2 · Alerts", subtitle = "Optional, but recommended") {
                val access = Permissions.hasNotificationAccess(context)
                StatusLine(
                    label = "Notification listener",
                    state = if (access) "granted" else "not granted",
                    ok = access
                )
                if (!access) {
                    GhostButton("Open notification access") {
                        Permissions.openNotificationAccess(context)
                    }
                }
                val notif = Permissions.hasNotifications(context)
                StatusLine(
                    label = "Post notifications",
                    state = if (notif) "granted" else "not granted",
                    ok = notif
                )
                if (!notif) {
                    GhostButton("Allow notifications") { onRequestNotifications() }
                }
                Text(
                    text = "Alerts are a transient layer: they flash over the animation and the animation " +
                        "continues underneath. Nothing is paused or restarted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextDim
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            GlassCard(title = "3 · Beat sync", subtitle = "Optional") {
                val mic = Permissions.hasMic(context)
                StatusLine(
                    label = "Microphone",
                    state = if (mic) "granted" else "not granted",
                    ok = mic
                )
                if (!mic) {
                    GhostButton("Allow microphone") { onRequestMic() }
                }
                Text(
                    text = "Used to analyse the media mix or the room. Audio is processed on-device and never stored.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextDim
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            GlassCard(title = "4 · Stay alive", subtitle = "Optional") {
                val battery = Permissions.ignoresBatteryOptimization(context)
                StatusLine(
                    label = "Battery optimisation",
                    state = if (battery) "disabled" else "enabled",
                    ok = battery
                )
                if (!battery) {
                    GhostButton("Disable for Aurora") {
                        Permissions.openBatteryOptimization(context)
                    }
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            PrimaryButton(
                text = "Start Aurora Ring",
                modifier = Modifier.fillMaxWidth()
            ) { state.completeSetup() }

            Text(
                text = "You can change every permission later in System.",
                style = MaterialTheme.typography.bodyMedium,
                color = Aurora.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { state.completeSetup() }
                    .padding(vertical = 18.dp)
            )
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    state: String,
    ok: Boolean,
    neutral: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        neutral -> Aurora.Primary
                        ok -> Aurora.Mint
                        else -> Aurora.Amber
                    }
                )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Aurora.Text,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = state,
            style = MaterialTheme.typography.labelMedium,
            color = Aurora.TextDim
        )
    }
}
