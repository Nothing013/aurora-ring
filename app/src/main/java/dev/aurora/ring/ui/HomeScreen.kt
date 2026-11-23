package dev.aurora.ring.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aurora.ring.data.Modes
import dev.aurora.ring.data.Palettes
import dev.aurora.ring.data.ScreenModes

@Composable
fun HomeScreen(
    state: AppState,
    onRequestMic: () -> Unit
) {
    val settings = state.settings
    val engine by state.engineStatus.collectAsStateWithLifecycle()
    val audio by state.audioLive.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0C1020), Aurora.Bg, Color(0xFF04050A))
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Aurora.Primary.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(size.width * 0.15f, size.height * 0.05f),
                    radius = size.width * 0.75f
                ),
                radius = size.width * 0.75f,
                center = Offset(size.width * 0.15f, size.height * 0.05f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Aurora.Mint.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * 0.95f, size.height * 0.35f),
                    radius = size.width * 0.7f
                ),
                radius = size.width * 0.7f,
                center = Offset(size.width * 0.95f, size.height * 0.35f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Header(
                running = settings.running,
                path = engine.path,
                onToggle = { state.setRunning(!settings.running) }
            )

            PreviewCard(
                state = state,
                onOpenAudio = { tab = 1 }
            )

            TabPills(
                labels = listOf("Lights", "Audio", "Alerts", "System"),
                selected = tab,
                onSelect = { tab = it }
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(targetState = tab, animationSpec = tween(220), label = "tab") { current ->
                    when (current) {
                        0 -> LightsTab(state = state)
                        1 -> AudioTab(state = state, onRequestMic = onRequestMic, picker = { picker = it })
                        2 -> AlertsTab(state = state, picker = { picker = it })
                        else -> SystemTab(state = state)
                    }
                }
            }
        }

        val target = picker
        if (target != null) {
            AppPickerDialog(
                title = if (target == PickerTarget.NOTIF) "Alert apps" else "Audio apps",
                selected = if (target == PickerTarget.NOTIF) settings.notifications.apps else settings.audio.apps,
                onDismiss = { picker = null },
                onConfirm = { picked ->
                    if (target == PickerTarget.NOTIF) {
                        state.updateNotif { it.copy(apps = picked) }
                    } else {
                        state.updateAudio { it.copy(apps = picked) }
                    }
                    picker = null
                }
            )
        }
    }
}

enum class PickerTarget { NOTIF, AUDIO }

@Composable
private fun Header(running: Boolean, path: String, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(Aurora.Gradient)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0A0714))
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Aurora Ring",
                style = MaterialTheme.typography.titleLarge,
                color = Aurora.Text
            )
            Text(
                text = if (running) "Live · ${path.ifBlank { "starting" }}" else "Paused",
                style = MaterialTheme.typography.labelMedium,
                color = if (running) Aurora.Mint else Aurora.TextDim
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (running) Brush.linearGradient(Aurora.Gradient)
                    else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.05f)))
                )
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = "Toggle ring",
                tint = if (running) Color(0xFF0A0714) else Aurora.TextDim,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PreviewCard(state: AppState, onOpenAudio: () -> Unit) {
    val settings = state.settings
    val audio by state.audioLive.collectAsStateWithLifecycle()
    val engine by state.engineStatus.collectAsStateWithLifecycle()

    GlassCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            RingPreview(
                settings = settings,
                audio = audio,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(236.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallBadge(modeName(settings.mode), Aurora.Primary)
                    SmallBadge(Palettes.byId(settings.palette).name, Aurora.Mint)
                }
                if (engine.overlayActive) {
                    SmallBadge("alert", Aurora.Pink)
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BeatDot(
                    beat = audio.beat,
                    bpm = audio.bpm,
                    active = audio.active && settings.audio.enabled
                )
            }
        }
    }
}

@Composable
private fun TabPills(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val bg by animateColorAsState(
                targetValue = if (active) Color.White.copy(alpha = 0.10f) else Color.Transparent,
                animationSpec = tween(200),
                label = "tabBg"
            )
            val fg by animateColorAsState(
                targetValue = if (active) Aurora.Text else Aurora.TextDim,
                animationSpec = tween(200),
                label = "tabFg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(bg)
                    .then(
                        if (active) Modifier.border(
                            1.dp,
                            Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(14.dp)
                        ) else Modifier
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

fun modeName(mode: Int): String = when (mode) {
    Modes.RAINBOW -> "Rainbow"
    Modes.AURORA -> "Aurora"
    Modes.BREATHE -> "Breathe"
    Modes.SOLID -> "Solid"
    Modes.DUAL -> "Dual ring"
    Modes.SPIN -> "Spin"
    Modes.FLOW -> "Flow"
    Modes.FIRE -> "Ember"
    Modes.ICE -> "Ice"
    Modes.POLICE -> "Strobe"
    Modes.CANDLE -> "Candle"
    Modes.AUDIO -> "Audio"
    else -> "Ring"
}

fun screenModeName(mode: Int): String = when (mode) {
    ScreenModes.ALWAYS -> "Always"
    ScreenModes.SCREEN_ON -> "Screen on"
    ScreenModes.SCREEN_OFF -> "Screen off"
    ScreenModes.NOTIF_ONLY -> "Notification only"
    else -> "Always"
}
