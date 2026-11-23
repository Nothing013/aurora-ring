package dev.aurora.ring.data

import android.graphics.Color

object Modes {
    const val RAINBOW = 0
    const val AURORA = 1
    const val BREATHE = 2
    const val SOLID = 3
    const val DUAL = 4
    const val SPIN = 5
    const val FLOW = 6
    const val FIRE = 7
    const val ICE = 8
    const val POLICE = 9
    const val CANDLE = 10
    const val AUDIO = 11

    val all = listOf(RAINBOW, AURORA, BREATHE, SOLID, DUAL, SPIN, FLOW, FIRE, ICE, POLICE, CANDLE, AUDIO)
}

object DualStyles {
    const val SPLIT = 0
    const val SPIN = 1
    const val POLICE = 2
    const val ALTERNATE = 3
    const val FIRE_ICE = 4
    const val RAINBOW_DUO = 5
    val all = listOf(SPLIT, SPIN, POLICE, ALTERNATE, FIRE_ICE, RAINBOW_DUO)
}

object ScreenModes {
    /** Always render, screen on or off. */
    const val ALWAYS = 0
    /** Render only while the display is on. */
    const val SCREEN_ON = 1
    /** Render only while the display is off (ambient). */
    const val SCREEN_OFF = 2
    /**
     * Idle base layer: no animation until something happens. Notifications and
     * audio-reactive visuals keep working — this is the mode that used to kill
     * the whole engine.
     */
    const val NOTIF_ONLY = 3
    val all = listOf(ALWAYS, SCREEN_ON, SCREEN_OFF, NOTIF_ONLY)
}

object NotifStyles {
    const val OFF = 0
    const val FLASH = 1
    const val PULSE = 2
    const val WAVE = 3
    const val SOLID = 4
    val all = listOf(OFF, FLASH, PULSE, WAVE, SOLID)
}

object NotifColors {
    /** App colour when known, otherwise the primary colour. */
    const val AUTO = 0
    /** Always the primary colour. */
    const val PRIMARY = 1
    /** Colour taken from the active palette. */
    const val PALETTE = 2
    /** Always white. */
    const val WHITE = 3
    val all = listOf(AUTO, PRIMARY, PALETTE, WHITE)
}

object AudioSources {
    const val GLOBAL = 0
    const val MIC = 1
}

data class AudioSettings(
    val enabled: Boolean = false,
    val source: Int = AudioSources.GLOBAL,
    val sensitivity: Float = 1.0f,
    val bandFocus: Int = 0,
    val latencyMs: Int = 45,
    val beatGain: Float = 1.0f,
    val appFilter: Boolean = false,
    val apps: Set<String> = emptySet(),
    val allowScreenOff: Boolean = true
)

data class NotifSettings(
    val style: Int = NotifStyles.FLASH,
    val colorMode: Int = NotifColors.AUTO,
    val durationSec: Int = 5,
    val appFilter: Boolean = false,
    val apps: Set<String> = emptySet(),
    val appColors: Map<String, Int> = emptyMap(),
    val appDurations: Map<String, Int> = emptyMap()
)

data class BehaviorSettings(
    val screenMode: Int = ScreenModes.ALWAYS,
    val chargingAnimation: Boolean = true,
    val lowBatteryWarning: Boolean = true,
    val keepAlive: Boolean = false,
    val pathPreference: Int = 0, // 0 auto, 1 root only, 2 shizuku only
    val restoreOnBoot: Boolean = true
)

data class Settings(
    val running: Boolean = false,
    val mode: Int = Modes.RAINBOW,
    val palette: Int = 0,
    val color: Int = 0xFFFF2D55.toInt(),
    val colorB: Int = 0xFF2D7BFF.toInt(),
    val speed: Float = 0.5f,
    val brightness: Float = 0.8f,
    val frameMs: Long = 20L,
    val dualStyle: Int = DualStyles.SPLIT,
    val beatReactive: Boolean = false,
    val audio: AudioSettings = AudioSettings(),
    val notifications: NotifSettings = NotifSettings(),
    val behavior: BehaviorSettings = BehaviorSettings(),
    val setupDone: Boolean = false
) {
    /** Cycle length in ms for one full animation pass. */
    val cycleMs: Long get() = (9000.0 - speed.coerceIn(0f, 1f) * 8300.0).toLong().coerceAtLeast(600L)

    val scaledBrightness: Float get() = brightness.coerceIn(0.03f, 1f)

    fun bright(color: Int, scale: Float = 1f): Int {
        val f = (scaledBrightness * scale).coerceIn(0f, 1f)
        val r = (((color shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
