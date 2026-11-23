package dev.aurora.ring.engine

import android.content.Context
import android.graphics.Color
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.aurora.ring.audio.BeatEngine
import dev.aurora.ring.data.Modes
import dev.aurora.ring.data.NotifColors
import dev.aurora.ring.data.NotifStyles
import dev.aurora.ring.data.Palettes
import dev.aurora.ring.data.ScreenModes
import dev.aurora.ring.data.Settings
import dev.aurora.ring.led.LedController
import dev.aurora.ring.led.LedFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.sin

/**
 * The render arbiter.
 *
 * There is always exactly one *base layer* (the selected animation, or the
 * audio-reactive visual) and zero or more *transient layers* (notifications,
 * charging, low battery). Transient layers are composited over the base and
 * expire; the base is never paused or restarted. That is the structural fix for
 * the old "Notification only mode stops audio reactive" bug: enabling
 * notifications no longer tears down the animation/audio pipeline, it only
 * changes which layers are visible.
 */
class RenderEngine(
    private val context: Context,
    private val led: LedController,
    private val audio: BeatEngine
) {

    data class Status(
        val running: Boolean = false,
        val baseActive: Boolean = false,
        val overlayActive: Boolean = false,
        val overlayLabel: String? = null,
        val screenOn: Boolean = true,
        val charging: Boolean = false,
        val battery: Int = -1,
        val bpm: Float = 0f,
        val level: Float = 0f,
        val beat: Float = 0f,
        val audioActive: Boolean = false,
        val path: String = "none",
        val lastColor: Int = 0
    )

    private data class Overlay(
        val color: Int,
        val style: Int,
        val startMs: Long,
        val durationMs: Long,
        val label: String
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val lock = Object()
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var settings = Settings()

    @Volatile
    private var overlay: Overlay? = null

    @Volatile
    private var screenOn = true

    @Volatile
    private var charging = false

    @Volatile
    private var battery = -1

    private var previousSettings: Settings? = null
    private var transitionStart = 0L
    private var lastStatusPush = 0L
    private var lastColor = 0

    private val powerManager by lazy { context.getSystemService(PowerManager::class.java) }

    fun start(initial: Settings) {
        if (running) {
            update(initial)
            return
        }
        settings = initial
        screenOn = powerManager?.isInteractive ?: true
        running = true
        val t = Thread({ loop() }, "aurora-render")
        t.isDaemon = true
        t.start()
        thread = t
    }

    fun update(next: Settings) {
        val previous = settings
        if (previous.mode != next.mode) {
            previousSettings = previous
            transitionStart = SystemClock.elapsedRealtime()
        }
        settings = next
        wake()
    }

    fun stop() {
        running = false
        wake()
        runCatching { thread?.join(800) }
        thread = null
        led.off()
        _status.value = Status(running = false)
    }

    fun setScreenOn(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        wake()
    }

    fun setCharging(value: Boolean) {
        charging = value
        wake()
    }

    fun setBattery(level: Int) {
        battery = level
    }

    fun triggerNotification(pkg: String, label: String? = null) {
        val n = settings.notifications
        if (n.style == NotifStyles.OFF) return
        if (n.appFilter && pkg !in n.apps) return

        val duration = (n.appDurations[pkg] ?: n.durationSec).coerceIn(1, 60) * 1000L
        val color = when (n.colorMode) {
            NotifColors.PRIMARY -> settings.color
            NotifColors.PALETTE -> Palettes.colorAt(settings.palette, 0.15f)
            NotifColors.WHITE -> 0xFFFFFFFF.toInt()
            else -> n.appColors[pkg] ?: settings.color
        }
        overlay = Overlay(
            color = color,
            style = n.style,
            startMs = SystemClock.elapsedRealtime(),
            durationMs = duration,
            label = label ?: pkg
        )
        wake()
    }

    fun cancelOverlay() {
        overlay = null
        wake()
    }

    fun refreshHardware() {
        Thread {
            runCatching { led.refreshPath() }
            wake()
        }.apply { isDaemon = true }.start()
    }

    fun releaseHardware() {
        led.release()
    }

    private fun wake() {
        synchronized(lock) { lock.notifyAll() }
    }

    private fun loop() {
        var nextTick = SystemClock.elapsedRealtime()
        while (running) {
            val now = SystemClock.elapsedRealtime()
            try {
                tick(now)
            } catch (t: Throwable) {
                Log.e(TAG, "render tick failed", t)
            }
            nextTick += settings.frameMs
            val sleep = nextTick - SystemClock.elapsedRealtime()
            if (sleep > 0) {
                synchronized(lock) {
                    try {
                        lock.wait(sleep)
                    } catch (_: InterruptedException) {
                    }
                }
            } else {
                nextTick = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun tick(now: Long) {
        val s = settings
        val audioSnap = audio.snapshot(now)

        val baseAllowed = baseAllowed(s, audioSnap)
        var base: LedFrame = if (baseAllowed) {
            val current = Animator.frame(now, s, audioSnap)
            val previous = previousSettings
            if (previous != null && now - transitionStart < TRANSITION_MS) {
                val w = smooth((now - transitionStart).toFloat() / TRANSITION_MS)
                blend(Animator.frame(now, previous, audioSnap), current, w)
            } else {
                previousSettings = null
                current
            }
        } else {
            LedFrame.Off
        }

        if (!baseAllowed) {
            idleLayer(now)?.let { base = it }
        }

        val active = overlay
        var composed = base
        if (active != null) {
            val age = now - active.startMs
            if (age >= active.durationMs) {
                overlay = null
            } else {
                val weight = overlayWeight(age, active.durationMs)
                val layer = overlayFrame(active, now, weight)
                if (layer != null && weight > 0.001f) {
                    composed = blend(base, layer, weight)
                }
            }
        }

        led.apply(composed)

        if (composed is LedFrame.Solid) lastColor = composed.color
        if (now - lastStatusPush >= 120L) {
            lastStatusPush = now
            _status.value = Status(
                running = true,
                baseActive = baseAllowed,
                overlayActive = overlay != null,
                overlayLabel = overlay?.label,
                screenOn = screenOn,
                charging = charging,
                battery = battery,
                bpm = audioSnap.bpm,
                level = audioSnap.level,
                beat = audioSnap.beat,
                audioActive = audioSnap.active,
                path = led.path.name.lowercase(),
                lastColor = lastColor
            )
        }
    }

    private fun baseAllowed(s: Settings, a: BeatEngine.Snapshot): Boolean {
        val audioBase = s.audio.enabled && (s.mode == Modes.AUDIO || s.beatReactive)
        return when (s.behavior.screenMode) {
            ScreenModes.ALWAYS -> true
            ScreenModes.SCREEN_ON -> screenOn
            ScreenModes.SCREEN_OFF -> !screenOn
            ScreenModes.NOTIF_ONLY -> {
                if (!audioBase) false
                else if (!screenOn && !s.audio.allowScreenOff) false
                else a.active
            }
            else -> true
        }
    }

    private fun idleLayer(now: Long): LedFrame? {
        val s = settings
        if (charging && s.behavior.chargingAnimation) {
            val wave = 0.5f - 0.5f * cos(2.0 * Math.PI * (now % 3000L) / 3000.0).toFloat()
            return LedFrame.Solid(s.bright(Color.rgb(0, 220, 130), 0.18f + 0.42f * wave))
        }
        if (s.behavior.lowBatteryWarning && battery in 0..15 && !charging) {
            val slot = (now % 5000L) / 250L
            return if (slot < 2) LedFrame.Solid(s.bright(Color.rgb(255, 40, 60), 0.7f)) else LedFrame.Off
        }
        return null
    }

    private fun overlayWeight(age: Long, duration: Long): Float {
        val fadeIn = 90f
        val fadeOut = 420f
        val a = age.toFloat()
        val d = duration.toFloat()
        return when {
            a < fadeIn -> smooth(a / fadeIn)
            a > d - fadeOut -> smooth(((d - a) / fadeOut).coerceIn(0f, 1f))
            else -> 1f
        }
    }

    private fun overlayFrame(o: Overlay, now: Long, weight: Float): LedFrame? {
        val s = settings
        val color = if (o.color == -1) 0xFFFEFEFE.toInt() else o.color
        return when (o.style) {
            NotifStyles.FLASH -> {
                val slot = ((now - o.startMs) / 140L) % 2L
                if (slot == 0L) LedFrame.Solid(s.bright(color, 1f)) else null
            }
            NotifStyles.PULSE -> {
                val wave = 0.5f - 0.5f * cos(2.0 * Math.PI * (now % 700L) / 700.0).toFloat()
                LedFrame.Solid(s.bright(color, 0.25f + 0.75f * wave))
            }
            NotifStyles.WAVE -> {
                val hue = ((now % 1600L) / 1600f) * 360f
                LedFrame.Solid(s.bright(Color.HSVToColor(floatArrayOf(hue, 0.9f, 1f)), 1f))
            }
            NotifStyles.SOLID -> LedFrame.Solid(s.bright(color, 1f))
            else -> null
        }
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun blend(a: LedFrame, b: LedFrame, w: Float): LedFrame {
        val t = w.coerceIn(0f, 1f)
        if (t <= 0f) return a
        if (t >= 1f) return b
        if (a is LedFrame.Dual && b is LedFrame.Dual) {
            return LedFrame.Dual(Palettes.lerp(a.a, b.a, t), Palettes.lerp(a.b, b.b, t))
        }
        if (a is LedFrame.Spot && b is LedFrame.Spot) {
            return LedFrame.Spot(if (t < 0.5f) a.spotA else b.spotA, Palettes.lerp(a.color, b.color, t))
        }
        return LedFrame.Solid(Palettes.lerp(dominant(a), dominant(b), t))
    }

    private fun dominant(f: LedFrame): Int = when (f) {
        is LedFrame.Solid -> f.color
        is LedFrame.Dual -> Palettes.lerp(f.a, f.b, 0.5f)
        is LedFrame.Spot -> f.color
        LedFrame.Off -> Color.BLACK
    }

    private companion object {
        const val TAG = "AuroraRender"
        const val TRANSITION_MS = 380L
    }
}
