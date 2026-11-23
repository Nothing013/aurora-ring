package dev.aurora.ring.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aurora.ring.BuildConfig
import dev.aurora.ring.audio.BeatEngine
import dev.aurora.ring.data.AudioSources
import dev.aurora.ring.data.DualStyles
import dev.aurora.ring.data.Modes
import dev.aurora.ring.data.NotifColors
import dev.aurora.ring.data.NotifStyles
import dev.aurora.ring.data.Palettes
import dev.aurora.ring.data.ScreenModes
import dev.aurora.ring.led.LightsApi
import dev.aurora.ring.service.RingService

@Composable
private fun TabList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

private val PRESETS = listOf(
    0xFFFFFFFF, 0xFFFF3B30, 0xFFFF6B22, 0xFFFFB020, 0xFFFFE14D, 0xFFB6FF3B,
    0xFF3BFF8F, 0xFF00E5C0, 0xFF3BD6FF, 0xFF3B82FF, 0xFF7C5CFF, 0xFFB44DFF,
    0xFFFF4D8D, 0xFFFF2D55
).map { it.toInt() }

private val ALERT_COLORS = listOf(0, 0xFFFFFFFF.toInt(), 0xFFFF3B30.toInt(), 0xFF00E5C0.toInt(),
    0xFF3B82FF.toInt(), 0xFFFFB020.toInt(), 0xFFFF4D8D.toInt(), 0xFFB44DFF.toInt())

private fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

@Composable
fun LightsTab(state: AppState) {
    val settings = state.settings
    TabList {
        item { SectionLabel("Animation") }
        item {
            ChipRow(
                items = Modes.all.map { ChipItem(it, modeName(it)) },
                selected = settings.mode,
                onSelect = { mode -> state.update { it.copy(mode = mode) } }
            )
        }
        item {
            Text(
                text = modeDescription(settings.mode),
                style = MaterialTheme.typography.bodyMedium,
                color = Aurora.TextDim,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        item {
            GlassCard(title = "Palette", subtitle = Palettes.byId(settings.palette).name) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Palettes.all.forEach { palette ->
                        val selected = palette.id == settings.palette
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(paletteBrush(palette.id))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) Aurora.Text else Color.White.copy(alpha = 0.15f),
                                    shape = CircleShape
                                )
                                .clickable { state.update { it.copy(palette = palette.id) } }
                        )
                    }
                }
            }
        }

        item {
            GlassCard(title = "Colours") {
                ColorEditor(
                    label = "Primary",
                    color = settings.color,
                    onColor = { c -> state.update { it.copy(color = c) } }
                )
                if (settings.mode == Modes.DUAL) {
                    Divider()
                    ColorEditor(
                        label = "Secondary",
                        color = settings.colorB,
                        onColor = { c -> state.update { it.copy(colorB = c) } }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Randomise", icon = Icons.Rounded.Tune) {
                        state.update {
                            it.copy(
                                color = randomColor(),
                                colorB = randomColor()
                            )
                        }
                    }
                }
            }
        }

        item {
            GlassCard(title = "Motion") {
                AuroraSlider(
                    title = "Speed",
                    value = settings.speed,
                    valueRange = 0f..1f,
                    valueText = "${(settings.speed * 100).toInt()}%",
                    onValueChange = { v -> state.update { it.copy(speed = v) } }
                )
                AuroraSlider(
                    title = "Brightness",
                    value = settings.brightness,
                    valueRange = 0.05f..1f,
                    valueText = "${(settings.brightness * 100).toInt()}%",
                    onValueChange = { v -> state.update { it.copy(brightness = v) } }
                )
                Text(
                    text = "Frame rate",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Aurora.Text
                )
                Segmented(
                    options = listOf("Smooth", "Balanced", "Eco"),
                    selectedIndex = when {
                        settings.frameMs <= 22L -> 0
                        settings.frameMs <= 40L -> 1
                        else -> 2
                    },
                    onSelect = { index ->
                        val ms = when (index) {
                            0 -> 20L
                            1 -> 33L
                            else -> 66L
                        }
                        state.update { it.copy(frameMs = ms) }
                    }
                )
                Text(
                    text = "Smooth targets ~50 fps. Eco cuts hardware writes to save battery.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextDim
                )
            }
        }

        if (settings.mode == Modes.DUAL) {
            item {
                GlassCard(
                    title = "Dual style",
                    subtitle = if (state.settings.behavior.pathPreference == 2)
                        "Two halves need root; Shizuku blends them" else "Hardware split via the AW21024 driver"
                ) {
                    ChipRow(
                        items = listOf(
                            ChipItem(DualStyles.SPLIT, "Split"),
                            ChipItem(DualStyles.SPIN, "Duo spin"),
                            ChipItem(DualStyles.POLICE, "Alternate"),
                            ChipItem(DualStyles.ALTERNATE, "Crossfade"),
                            ChipItem(DualStyles.FIRE_ICE, "Fire & ice"),
                            ChipItem(DualStyles.RAINBOW_DUO, "Rainbow duo")
                        ),
                        selected = settings.dualStyle,
                        onSelect = { style -> state.update { it.copy(dualStyle = style) } }
                    )
                }
            }
        }

        item {
            GlassCard(title = "Beat sync", subtitle = "Every mode can pulse with the music") {
                ToggleRow(
                    title = "Pulse on beat",
                    subtitle = "Adds a brightness kick locked to the detected tempo",
                    checked = settings.beatReactive,
                    onCheckedChange = { on ->
                        state.update { it.copy(beatReactive = on, audio = it.audio.copy(enabled = if (on) true else it.audio.enabled)) }
                    }
                )
                if (settings.beatReactive && !settings.audio.enabled) {
                    Text(
                        text = "Enable the audio engine in the Audio tab to feed the beat.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Aurora.Amber
                    )
                }
            }
        }
    }
}

@Composable
fun AudioTab(
    state: AppState,
    onRequestMic: () -> Unit,
    picker: (PickerTarget) -> Unit
) {
    val settings = state.settings
    val audio by state.audioLive.collectAsStateWithLifecycle()
    val context = LocalContext.current

    TabList {
        item {
            GlassCard(title = "Audio engine", subtitle = "Spectral-flux onsets + tempo phase lock") {
                ToggleRow(
                    title = "Audio reactive",
                    subtitle = "Analyses the media mix or the microphone",
                    checked = settings.audio.enabled,
                    onCheckedChange = { on ->
                        if (on && !Permissions.hasMic(context)) onRequestMic()
                        state.updateAudio { it.copy(enabled = on) }
                    }
                )
                if (settings.audio.enabled) {
                    LevelMeter(audio = audio)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BeatDot(beat = audio.beat, bpm = audio.bpm, active = audio.active)
                        Text(
                            text = if (audio.capturing) "capturing" else "waiting",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (audio.capturing) Aurora.Mint else Aurora.TextDim
                        )
                    }
                    if (audio.error != null) {
                        Text(
                            text = audio.error ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Aurora.Amber
                        )
                    }
                    KeyValueRow("Tempo confidence", "${(audio.confidence * 100).toInt()}%")
                }
            }
        }

        if (settings.audio.enabled) {
            item {
                GlassCard(title = "Source") {
                    Segmented(
                        options = listOf("Media mix", "Microphone"),
                        selectedIndex = if (settings.audio.source == AudioSources.MIC) 1 else 0,
                        onSelect = { index ->
                            if (index == 1 && !Permissions.hasMic(context)) onRequestMic()
                            state.updateAudio { it.copy(source = if (index == 1) AudioSources.MIC else AudioSources.GLOBAL) }
                        }
                    )
                    Text(
                        text = "Media mix captures everything playing on the phone. Microphone listens to the room — useful for speakers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Aurora.TextDim
                    )
                }
            }

            item {
                GlassCard(title = "Beat detection") {
                    AuroraSlider(
                        title = "Sensitivity",
                        value = settings.audio.sensitivity,
                        valueRange = 0.3f..3f,
                        valueText = String.format("%.2f×", settings.audio.sensitivity),
                        onValueChange = { v -> state.updateAudio { it.copy(sensitivity = v) } }
                    )
                    Text(
                        text = "Band focus",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Aurora.Text
                    )
                    ChipRow(
                        items = listOf(
                            ChipItem(0, "Auto"),
                            ChipItem(1, "Bass"),
                            ChipItem(2, "Mid"),
                            ChipItem(3, "Treble"),
                            ChipItem(4, "Full")
                        ),
                        selected = settings.audio.bandFocus,
                        onSelect = { band -> state.updateAudio { it.copy(bandFocus = band) } }
                    )
                    AuroraSlider(
                        title = "Pulse strength",
                        value = settings.audio.beatGain,
                        valueRange = 0f..1.5f,
                        valueText = String.format("%.2f×", settings.audio.beatGain),
                        onValueChange = { v -> state.updateAudio { it.copy(beatGain = v) } }
                    )
                }
            }

            item {
                GlassCard(
                    title = "Sync calibration",
                    subtitle = "Use this if the ring feels early or late against the beat"
                ) {
                    AuroraSlider(
                        title = "Latency compensation",
                        value = settings.audio.latencyMs.toFloat(),
                        valueRange = 0f..200f,
                        valueText = "${settings.audio.latencyMs} ms",
                        onValueChange = { v -> state.updateAudio { it.copy(latencyMs = v.toInt()) } }
                    )
                    Text(
                        text = "The engine predicts the next beat from the tempo grid and fires the pulse this many milliseconds early. " +
                            "Raise it if the flash lands after the kick; lower it if the flash runs ahead.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Aurora.TextDim
                    )
                    GhostButton("Reset tempo lock", icon = Icons.Rounded.Refresh) {
                        RingService.resetTempo(context)
                    }
                }
            }

            item {
                GlassCard(title = "Audio animation") {
                    ToggleRow(
                        title = "Use audio as the base animation",
                        subtitle = "Switches the ring to a band-driven visualiser",
                        checked = settings.mode == Modes.AUDIO,
                        onCheckedChange = { on ->
                            if (on && !Permissions.hasMic(context)) onRequestMic()
                            state.update {
                                it.copy(
                                    mode = if (on) Modes.AUDIO else Modes.RAINBOW,
                                    audio = it.audio.copy(enabled = true)
                                )
                            }
                        }
                    )
                }
            }

            item {
                GlassCard(title = "App filter") {
                    ToggleRow(
                        title = "Only react to selected apps",
                        subtitle = "Runs the engine while a chosen media app is playing",
                        checked = settings.audio.appFilter,
                        onCheckedChange = { on -> state.updateAudio { it.copy(appFilter = on) } }
                    )
                    if (settings.audio.appFilter) {
                        GhostButton(
                            text = "Choose apps (${settings.audio.apps.size})",
                            icon = Icons.Rounded.GraphicEq
                        ) { picker(PickerTarget.AUDIO) }
                    }
                    ToggleRow(
                        title = "Keep running with screen off",
                        subtitle = "Ambient beat sync in your pocket",
                        checked = settings.audio.allowScreenOff,
                        onCheckedChange = { on -> state.updateAudio { it.copy(allowScreenOff = on) } }
                    )
                }
            }
        } else {
            item {
                GlassCard(title = "How it works") {
                    Text(
                        text = "Aurora analyses the spectrum ~45 times a second. Instead of a slow envelope it measures " +
                            "spectral flux — how much new energy appears — then adapts its threshold to the track. " +
                            "A tempo histogram locks the BPM and a phase-locked loop predicts the next beat, so pulses " +
                            "land on time instead of trailing the music.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Aurora.TextDim
                    )
                }
            }
        }
    }
}

@Composable
fun AlertsTab(state: AppState, refreshKey: Int = 0, picker: (PickerTarget) -> Unit) {
    val settings = state.settings
    val context = LocalContext.current
    val engine by state.engineStatus.collectAsStateWithLifecycle()

    TabList {
        item {
            GlassCard(title = "Notification flashes", subtitle = "Transient layer over the base animation") {
                ChipRow(
                    items = listOf(
                        ChipItem(NotifStyles.OFF, "Off"),
                        ChipItem(NotifStyles.FLASH, "Flash"),
                        ChipItem(NotifStyles.PULSE, "Pulse"),
                        ChipItem(NotifStyles.WAVE, "Wave"),
                        ChipItem(NotifStyles.SOLID, "Solid")
                    ),
                    selected = settings.notifications.style,
                    onSelect = { style -> state.updateNotif { it.copy(style = style) } }
                )
                if (settings.notifications.style != NotifStyles.OFF) {
                    Text(
                        text = "Colour",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Aurora.Text
                    )
                    ChipRow(
                        items = listOf(
                            ChipItem(NotifColors.AUTO, "App / primary"),
                            ChipItem(NotifColors.PRIMARY, "Primary"),
                            ChipItem(NotifColors.PALETTE, "Palette"),
                            ChipItem(NotifColors.WHITE, "White")
                        ),
                        selected = settings.notifications.colorMode,
                        onSelect = { mode -> state.updateNotif { it.copy(colorMode = mode) } }
                    )
                    AuroraSlider(
                        title = "Duration",
                        value = settings.notifications.durationSec.toFloat(),
                        valueRange = 1f..30f,
                        valueText = "${settings.notifications.durationSec}s",
                        onValueChange = { v -> state.updateNotif { it.copy(durationSec = v.toInt()) } }
                    )
                    GhostButton("Test alert", icon = Icons.Rounded.Bolt) {
                        RingService.test(context)
                    }
                    if (!settings.running) {
                        Text(
                            text = "Start Aurora to test the alert layer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Aurora.Amber
                        )
                    }
                }
            }
        }

        item {
            GlassCard(title = "Apps") {
                ToggleRow(
                    title = "Only selected apps",
                    subtitle = "Other notifications are ignored",
                    checked = settings.notifications.appFilter,
                    onCheckedChange = { on -> state.updateNotif { it.copy(appFilter = on) } }
                )
                GhostButton(
                    text = "Choose apps (${settings.notifications.apps.size})",
                    icon = Icons.Rounded.Notifications
                ) { picker(PickerTarget.NOTIF) }
            }
        }

        if (settings.notifications.apps.isNotEmpty()) {
            item {
                GlassCard(
                    title = "Per-app colours",
                    subtitle = "Tap the dot to cycle · tap the duration to change it"
                ) {
                    settings.notifications.apps.sorted().forEach { pkg ->
                        val configured = settings.notifications.appColors[pkg] ?: 0
                        val shown = if (configured == 0) settings.color else configured
                        val duration = settings.notifications.appDurations[pkg] ?: settings.notifications.durationSec
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color(shown))
                                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                                    .clickable {
                                        val index = ALERT_COLORS.indexOf(configured).coerceAtLeast(0)
                                        val next = ALERT_COLORS[(index + 1) % ALERT_COLORS.size]
                                        val colors = settings.notifications.appColors.toMutableMap()
                                        if (next == 0) colors.remove(pkg) else colors[pkg] = next
                                        state.updateNotif { it.copy(appColors = colors) }
                                    }
                            )
                            Text(
                                text = appLabel(context, pkg),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Aurora.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${duration}s",
                                style = MaterialTheme.typography.labelLarge,
                                color = Aurora.Mint,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .clickable {
                                        val next = when {
                                            duration < 4 -> 4
                                            duration < 7 -> 7
                                            duration < 10 -> 10
                                            duration < 15 -> 15
                                            else -> 3
                                        }
                                        val durations = settings.notifications.appDurations.toMutableMap()
                                        durations[pkg] = next
                                        state.updateNotif { it.copy(appDurations = durations) }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            GlassCard(title = "Notification access") {
                val granted = Permissions.hasNotificationAccess(context)
                KeyValueRow(
                    key = "Listener",
                    value = if (granted) "Granted" else "Not granted",
                    valueColor = if (granted) Aurora.Mint else Aurora.Amber
                )
                GhostButton(
                    text = if (granted) "Open settings" else "Grant access",
                    icon = Icons.Rounded.OpenInNew
                ) { Permissions.openNotificationAccess(context) }
                Text(
                    text = "Aurora uses a notification listener to know when an alert arrives. " +
                        "Alerts are layered over the running animation and the base layer resumes afterwards — " +
                        "even in Notification-only mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextDim
                )
                KeyValueRow("Last alert", engine.overlayLabel ?: "—")
            }
        }
    }
}

@Composable
fun SystemTab(state: AppState, refreshKey: Int = 0) {
    val settings = state.settings
    val context = LocalContext.current
    val engine by state.engineStatus.collectAsStateWithLifecycle()

    TabList {
        item {
            GlassCard(title = "When to shine") {
                ChipRow(
                    items = listOf(
                        ChipItem(ScreenModes.ALWAYS, "Always"),
                        ChipItem(ScreenModes.SCREEN_ON, "Screen on"),
                        ChipItem(ScreenModes.SCREEN_OFF, "Screen off"),
                        ChipItem(ScreenModes.NOTIF_ONLY, "Notification only")
                    ),
                    selected = settings.behavior.screenMode,
                    onSelect = { mode -> state.updateBehavior { it.copy(screenMode = mode) } }
                )
                Text(
                    text = screenModeDescription(settings.behavior.screenMode),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextDim
                )
            }
        }

        item {
            GlassCard(title = "Behaviour") {
                ToggleRow(
                    title = "Charging animation",
                    subtitle = "Slow green breathe while charging and idle",
                    checked = settings.behavior.chargingAnimation,
                    onCheckedChange = { on -> state.updateBehavior { it.copy(chargingAnimation = on) } }
                )
                ToggleRow(
                    title = "Low battery warning",
                    subtitle = "Red double blink below 15%",
                    checked = settings.behavior.lowBatteryWarning,
                    onCheckedChange = { on -> state.updateBehavior { it.copy(lowBatteryWarning = on) } }
                )
                ToggleRow(
                    title = "Keep alive (root)",
                    subtitle = "Doze whitelist, active standby bucket, auto-start",
                    checked = settings.behavior.keepAlive,
                    onCheckedChange = { on -> state.updateBehavior { it.copy(keepAlive = on) } }
                )
                ToggleRow(
                    title = "Restore after reboot",
                    subtitle = "Start the ring when the phone boots",
                    checked = settings.behavior.restoreOnBoot,
                    onCheckedChange = { on -> state.updateBehavior { it.copy(restoreOnBoot = on) } }
                )
            }
        }

        item {
            GlassCard(title = "Hardware", subtitle = "AW21024 · /sys/class/leds/aw21024_led") {
                KeyValueRow(
                    key = "Active backend",
                    value = when (engine.path) {
                        "root" -> "Root · full ring"
                        "shizuku" -> "Shizuku · solid ring"
                        else -> "Not connected"
                    },
                    valueColor = when (engine.path) {
                        "root" -> Aurora.Mint
                        "shizuku" -> Aurora.Primary
                        else -> Aurora.Amber
                    }
                )
                val shizukuAlive = LightsApi.shizukuAlive()
                val shizukuGranted = LightsApi.shizukuGranted()
                KeyValueRow(
                    key = "Shizuku",
                    value = when {
                        !shizukuAlive -> "Not running"
                        shizukuGranted -> "Granted"
                        else -> "Permission needed"
                    },
                    valueColor = if (shizukuGranted) Aurora.Mint else Aurora.TextDim
                )
                KeyValueRow("Dual split", if (engine.path == "root") "Supported" else "Root only")
                KeyValueRow("Spin spots", if (engine.path == "root") "Supported" else "Root only")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Repair LED", icon = Icons.Rounded.Refresh) {
                        RingService.repair(context)
                    }
                    GhostButton("Reset to stock") {
                        RingService.resetHardware(context)
                    }
                }
                if (!shizukuGranted) {
                    GhostButton("Grant Shizuku") {
                        LightsApi.requestShizukuPermission()
                    }
                }
                Text(
                    text = "Root writes the AW21024 registers directly through a persistent su shell — one write per frame. " +
                        "Without root the app falls back to miui.lights.ILightsManager.setColorCommon(), which is flicker-free " +
                        "but cannot address the two halves separately.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextDim
                )
            }
        }

        item {
            GlassCard(title = "Permissions") {
                PermissionRow(
                    label = "Microphone",
                    granted = Permissions.hasMic(context),
                    action = "Grant"
                ) {
                    Permissions.openAppSettings(context)
                }
                PermissionRow(
                    label = "Notification listener",
                    granted = Permissions.hasNotificationAccess(context),
                    action = "Grant"
                ) {
                    Permissions.openNotificationAccess(context)
                }
                PermissionRow(
                    label = "Battery optimisation",
                    granted = Permissions.ignoresBatteryOptimization(context),
                    action = "Disable"
                ) {
                    Permissions.openBatteryOptimization(context)
                }
                PermissionRow(
                    label = "Notifications",
                    granted = Permissions.hasNotifications(context),
                    action = "Grant"
                ) {
                    Permissions.openAppSettings(context)
                }
            }
        }

        item {
            GlassCard(title = "About") {
                KeyValueRow("Version", BuildConfig.VERSION_NAME)
                KeyValueRow("Device target", "Redmi Turbo 4 Pro (onyx)")
                Text(
                    text = "Aurora Ring is an independent rebuild inspired by DashDazz and HaloLight. " +
                        "Hardware protocol notes live in the repository README.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextDim
                )
                GhostButton("Open project on GitHub", icon = Icons.Rounded.OpenInNew) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Nothing013/aurora-ring"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }

        item {
            GhostButton(
                text = "Re-run setup",
                icon = Icons.Rounded.Tune,
                modifier = Modifier.fillMaxWidth()
            ) { state.resetSetup() }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    action: String,
    onAction: () -> Unit
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
                .background(if (granted) Aurora.Mint else Aurora.Amber)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Aurora.Text,
            modifier = Modifier.weight(1f)
        )
        if (!granted) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = Aurora.Primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAction() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        } else {
            Text(
                text = "OK",
                style = MaterialTheme.typography.labelMedium,
                color = Aurora.Mint
            )
        }
    }
}

@Composable
private fun ColorEditor(label: String, color: Int, onColor: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val hsv = remember(color) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge, color = Aurora.Text)
                Text(text = hex(color), style = MaterialTheme.typography.labelMedium, color = Aurora.TextDim)
            }
            Text(
                text = if (open) "Done" else "Edit",
                style = MaterialTheme.typography.labelLarge,
                color = Aurora.Primary
            )
        }
        if (open) {
            HueSlider(hue = hsv[0]) { hue ->
                onColor(
                    android.graphics.Color.HSVToColor(
                        floatArrayOf(hue, hsv[1].coerceAtLeast(0.25f), 1f)
                    )
                )
            }
            AuroraSlider(
                title = "Saturation",
                value = hsv[1],
                valueRange = 0.2f..1f,
                valueText = "${(hsv[1] * 100).toInt()}%",
                onValueChange = { sat ->
                    onColor(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], sat, 1f)))
                }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PRESETS.forEach { preset ->
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(preset))
                            .border(
                                1.dp,
                                if (preset == color) Aurora.Text else Color.White.copy(alpha = 0.18f),
                                CircleShape
                            )
                            .clickable { onColor(preset) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HueSlider(hue: Float, onHue: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                            Color.Blue, Color.Magenta, Color.Red
                        )
                    )
                )
        )
        Slider(
            value = hue.coerceIn(0f, 360f),
            onValueChange = onHue,
            valueRange = 0f..360f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent
            )
        )
    }
}

private fun paletteBrush(id: Int): Brush {
    val palette = Palettes.byId(id)
    val colors = palette.colors
    return if (colors == null) {
        Brush.sweepGradient(
            listOf(
                Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                Color.Blue, Color.Magenta, Color.Red
            )
        )
    } else {
        Brush.linearGradient(colors.map { Color(it) })
    }
}

private fun randomColor(): Int {
    val hue = (Math.random() * 360).toFloat()
    return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
}

private fun appLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

private fun modeDescription(mode: Int): String = when (mode) {
    Modes.RAINBOW -> "Full-spectrum hue rotation at your chosen speed."
    Modes.AURORA -> "Two palette colours drifting through each other."
    Modes.BREATHE -> "A slow inhale and exhale in your primary colour."
    Modes.SOLID -> "One steady colour, no movement."
    Modes.DUAL -> "Two halves of the ring, each with its own colour or effect."
    Modes.SPIN -> "A single spot orbiting the ring using the hardware masks."
    Modes.FLOW -> "A comet that brightens as it sweeps past."
    Modes.FIRE -> "Warm flicker driven by value noise."
    Modes.ICE -> "Cool blue drift with a slow pulse."
    Modes.POLICE -> "Fast red/blue alternation."
    Modes.CANDLE -> "Soft candle-like flicker."
    Modes.AUDIO -> "The ring becomes a live band visualiser."
    else -> ""
}

private fun notifStyleName(style: Int): String = when (style) {
    NotifStyles.OFF -> "Off"
    NotifStyles.FLASH -> "Flash"
    NotifStyles.PULSE -> "Pulse"
    NotifStyles.WAVE -> "Wave"
    NotifStyles.SOLID -> "Solid"
    else -> "Off"
}

private fun screenModeDescription(mode: Int): String = when (mode) {
    ScreenModes.ALWAYS -> "The ring renders whether the screen is on or off."
    ScreenModes.SCREEN_ON -> "Renders only while you are using the phone."
    ScreenModes.SCREEN_OFF -> "Ambient mode: only while the display is off."
    ScreenModes.NOTIF_ONLY -> "The base animation stays idle, but alerts still flash and beat sync keeps " +
        "running when audio is enabled. Nothing is torn down, so audio reactive no longer stops working."
    else -> ""
}
