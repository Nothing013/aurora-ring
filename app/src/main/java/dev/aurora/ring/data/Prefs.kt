package dev.aurora.ring.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "aurora_settings"

    private fun p(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): Settings {
        val s = p(context)
        val audio = AudioSettings(
            enabled = s.getBoolean("audio_enabled", false),
            source = s.getInt("audio_source", AudioSources.GLOBAL).coerceIn(0, 1),
            sensitivity = s.getFloat("audio_sensitivity", 1.0f).coerceIn(0.3f, 4f),
            bandFocus = s.getInt("audio_band", 0).coerceIn(0, 4),
            latencyMs = s.getInt("audio_latency", 45).coerceIn(0, 250),
            beatGain = s.getFloat("audio_gain", 1.0f).coerceIn(0f, 2f),
            appFilter = s.getBoolean("audio_app_filter", false),
            apps = s.getStringSet("audio_apps", emptySet())?.toSet() ?: emptySet(),
            allowScreenOff = s.getBoolean("audio_screen_off", true)
        )
        val colors = LinkedHashMap<String, Int>()
        val durations = LinkedHashMap<String, Int>()
        s.getString("notif_app_cfg", null)?.split(";")?.forEach { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size == 2) {
                val fields = parts[1].split(",")
                val c = fields.getOrNull(0)?.toLongOrNull()?.toInt()
                val d = fields.getOrNull(1)?.toIntOrNull()
                if (c != null && c != 0) colors[parts[0]] = c
                if (d != null && d in 1..60) durations[parts[0]] = d
            }
        }
        val notif = NotifSettings(
            style = s.getInt("notif_style", NotifStyles.FLASH).coerceIn(0, 4),
            colorMode = s.getInt("notif_color_mode", NotifColors.AUTO).coerceIn(0, 3),
            durationSec = s.getInt("notif_duration", 5).coerceIn(1, 60),
            appFilter = s.getBoolean("notif_app_filter", false),
            apps = s.getStringSet("notif_apps", emptySet())?.toSet() ?: emptySet(),
            appColors = colors,
            appDurations = durations
        )
        val behavior = BehaviorSettings(
            screenMode = s.getInt("screen_mode", ScreenModes.ALWAYS).coerceIn(0, 3),
            chargingAnimation = s.getBoolean("charging_anim", true),
            lowBatteryWarning = s.getBoolean("low_battery", true),
            keepAlive = s.getBoolean("keep_alive", false),
            pathPreference = s.getInt("path_pref", 0).coerceIn(0, 2),
            restoreOnBoot = s.getBoolean("restore_boot", true)
        )
        return Settings(
            running = s.getBoolean("running", false),
            mode = s.getInt("mode", Modes.RAINBOW).coerceIn(0, 11),
            palette = s.getInt("palette", 0).coerceIn(0, 7),
            color = s.getInt("color", 0xFFFF2D55.toInt()),
            colorB = s.getInt("color_b", 0xFF2D7BFF.toInt()),
            speed = s.getFloat("speed", 0.5f).coerceIn(0f, 1f),
            brightness = s.getFloat("brightness", 0.8f).coerceIn(0.03f, 1f),
            frameMs = s.getLong("frame_ms", 20L).coerceIn(15L, 100L),
            dualStyle = s.getInt("dual_style", DualStyles.SPLIT).coerceIn(0, 5),
            beatReactive = s.getBoolean("beat_reactive", false),
            audio = audio,
            notifications = notif,
            behavior = behavior,
            setupDone = s.getBoolean("setup_done", false)
        )
    }

    fun save(context: Context, settings: Settings) {
        val pkgs = settings.notifications.appColors.keys + settings.notifications.appDurations.keys
        val encoded = pkgs.joinToString(";") { pkg ->
            val color = settings.notifications.appColors[pkg] ?: 0
            val duration = settings.notifications.appDurations[pkg] ?: -1
            "$pkg=$color,$duration"
        }
        p(context).edit()
            .putBoolean("running", settings.running)
            .putInt("mode", settings.mode)
            .putInt("palette", settings.palette)
            .putInt("color", settings.color)
            .putInt("color_b", settings.colorB)
            .putFloat("speed", settings.speed)
            .putFloat("brightness", settings.brightness)
            .putLong("frame_ms", settings.frameMs)
            .putInt("dual_style", settings.dualStyle)
            .putBoolean("beat_reactive", settings.beatReactive)
            .putBoolean("audio_enabled", settings.audio.enabled)
            .putInt("audio_source", settings.audio.source)
            .putFloat("audio_sensitivity", settings.audio.sensitivity)
            .putInt("audio_band", settings.audio.bandFocus)
            .putInt("audio_latency", settings.audio.latencyMs)
            .putFloat("audio_gain", settings.audio.beatGain)
            .putBoolean("audio_app_filter", settings.audio.appFilter)
            .putStringSet("audio_apps", settings.audio.apps)
            .putBoolean("audio_screen_off", settings.audio.allowScreenOff)
            .putInt("notif_style", settings.notifications.style)
            .putInt("notif_color_mode", settings.notifications.colorMode)
            .putInt("notif_duration", settings.notifications.durationSec)
            .putBoolean("notif_app_filter", settings.notifications.appFilter)
            .putStringSet("notif_apps", settings.notifications.apps)
            .putString("notif_app_cfg", encoded)
            .putInt("screen_mode", settings.behavior.screenMode)
            .putBoolean("charging_anim", settings.behavior.chargingAnimation)
            .putBoolean("low_battery", settings.behavior.lowBatteryWarning)
            .putBoolean("keep_alive", settings.behavior.keepAlive)
            .putInt("path_pref", settings.behavior.pathPreference)
            .putBoolean("restore_boot", settings.behavior.restoreOnBoot)
            .putBoolean("setup_done", settings.setupDone)
            .apply()
    }
}
