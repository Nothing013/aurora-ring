package dev.aurora.ring.engine

import android.graphics.Color
import dev.aurora.ring.audio.BeatEngine
import dev.aurora.ring.data.DualStyles
import dev.aurora.ring.data.Modes
import dev.aurora.ring.data.Palettes
import dev.aurora.ring.data.Settings
import dev.aurora.ring.led.LedFrame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure, deterministic frame generator. Given a wall clock it returns the LED
 * frame for the current settings — no state, no allocation-heavy work, no
 * hardware access. That makes it reusable by the live preview in the UI.
 */
object Animator {

    private const val TAU = 2.0 * Math.PI

    fun frame(now: Long, s: Settings, audio: BeatEngine.Snapshot): LedFrame {
        val phase = ((now % s.cycleMs).toDouble() / s.cycleMs).toFloat()
        val beat = if (s.beatReactive && audio.active) audio.beat.coerceIn(0f, 1.5f) else 0f
        val glow = 1f + 0.85f * beat
        val primary = sanitize(s.color)
        val secondary = sanitize(s.colorB)

        return when (s.mode) {
            Modes.RAINBOW -> {
                val hue = phase * 360f
                val v = (1f + 0.18f * sin(TAU * phase * 2.0).toFloat()) * glow
                LedFrame.Solid(s.bright(hsv(hue, 0.95f, 1f), v))
            }

            Modes.AURORA -> {
                val a = Palettes.colorAt(s.palette, phase)
                val b = Palettes.colorAt(s.palette, phase + 0.33f)
                val mix = 0.5f + 0.5f * sin(TAU * phase).toFloat()
                val v = (0.72f + 0.28f * sin(TAU * phase * 1.0 + 1.2).toFloat()) * glow
                LedFrame.Solid(s.bright(Palettes.lerp(a, b, mix), v))
            }

            Modes.BREATHE -> {
                val wave = 0.5f - 0.5f * cos(TAU * phase).toFloat()
                val v = 0.06f + 0.94f * wave.pow(1.4f)
                LedFrame.Solid(s.bright(primary, v * glow))
            }

            Modes.SOLID -> LedFrame.Solid(s.bright(primary, 1f * glow))

            Modes.DUAL -> dualFrame(phase, s, glow, primary, secondary)

            Modes.SPIN -> {
                val hue = (phase * 360f + 40f * beat) % 360f
                val color = s.bright(hsv(hue, 0.95f, 1f), 1f + 0.4f * beat)
                LedFrame.Spot(spotA = phase < 0.5f, color = color)
            }

            Modes.FLOW -> {
                val wave = 0.5f + 0.5f * cos(TAU * phase).toFloat()
                val v = (0.18f + 0.82f * wave.pow(3.2f)) * glow
                val hue = (phase * 360f + 30f) % 360f
                LedFrame.Solid(s.bright(hsv(hue, 0.9f, 1f), v))
            }

            Modes.FIRE -> {
                val flicker = 0.68f + 0.32f * noise(now / 85.0)
                val hue = 8f + 30f * (0.5f + 0.5f * sin(TAU * phase * 3.0 + now / 900.0).toFloat())
                LedFrame.Solid(s.bright(hsv(hue, 1f, 1f), flicker * glow))
            }

            Modes.ICE -> {
                val hue = 188f + 24f * (0.5f + 0.5f * sin(TAU * phase * 0.5).toFloat())
                val v = 0.5f + 0.5f * sin(TAU * phase + 0.4).toFloat()
                LedFrame.Solid(s.bright(hsv(hue, 0.75f, 1f), 0.45f + 0.55f * v))
            }

            Modes.POLICE -> {
                val fast = ((now % 900L) / 450L).toInt()
                val color = if (fast == 0) hsv(0f, 1f, 1f) else hsv(222f, 1f, 1f)
                val pulse = 0.65f + 0.35f * abs(sin(TAU * (now % 450L) / 450.0).toFloat())
                LedFrame.Solid(s.bright(color, pulse))
            }

            Modes.CANDLE -> {
                val flicker = 0.55f + 0.45f * noise(now / 130.0)
                val v = flicker * (0.8f + 0.2f * sin(TAU * phase * 2.0).toFloat())
                LedFrame.Solid(s.bright(hsv(32f, 0.92f, 1f), v))
            }

            Modes.AUDIO -> {
                if (!audio.active) {
                    val v = 0.05f + 0.12f * (0.5f - 0.5f * cos(TAU * phase).toFloat())
                    LedFrame.Solid(s.bright(primary, v))
                } else {
                    val energy = (audio.level * 0.45f + audio.band * 0.55f).coerceIn(0f, 1f)
                    val hue = (Palettes.byId(s.palette).let { palette ->
                        if (palette.colors == null) (phase * 360f + audio.high * 90f) % 360f
                        else {
                            val hsv = FloatArray(3)
                            Color.colorToHSV(
                                Palettes.colorAt(s.palette, audio.low * 0.6f + phase * 0.4f),
                                hsv
                            )
                            (hsv[0] + audio.high * 40f) % 360f
                        }
                    })
                    val v = (0.10f + 0.90f * energy) * (1f + 0.7f * audio.beat)
                    LedFrame.Solid(s.bright(hsv(hue, 0.92f, 1f), v))
                }
            }

            else -> LedFrame.Solid(s.bright(primary))
        }
    }

    private fun dualFrame(
        phase: Float,
        s: Settings,
        glow: Float,
        primary: Int,
        secondary: Int
    ): LedFrame {
        return when (s.dualStyle) {
            DualStyles.SPLIT -> LedFrame.Dual(
                s.bright(primary, glow),
                s.bright(secondary, glow)
            )

            DualStyles.SPIN -> LedFrame.Dual(
                s.bright(Palettes.colorAt(s.palette, phase), 1f),
                s.bright(Palettes.colorAt(s.palette, phase + 0.5f), 1f)
            )

            DualStyles.POLICE -> {
                val flip = ((phase * 6f) % 1f) < 0.5f
                val a = if (flip) primary else secondary
                val b = if (flip) secondary else primary
                LedFrame.Dual(s.bright(a, 1f), s.bright(b, 1f))
            }

            DualStyles.ALTERNATE -> {
                val f = triangle(phase)
                LedFrame.Dual(
                    s.bright(Palettes.lerp(primary, secondary, f), 1f),
                    s.bright(Palettes.lerp(secondary, primary, f), 1f)
                )
            }

            DualStyles.FIRE_ICE -> LedFrame.Dual(
                s.bright(Palettes.colorAt(Palettes.FIRE, phase), glow),
                s.bright(Palettes.colorAt(Palettes.OCEAN, phase + 0.5f), glow)
            )

            DualStyles.RAINBOW_DUO -> LedFrame.Dual(
                s.bright(Palettes.colorAt(s.palette, phase), 1f),
                s.bright(Palettes.colorAt(s.palette, phase + 0.5f), 1f)
            )

            else -> LedFrame.Dual(s.bright(primary), s.bright(secondary))
        }
    }

    fun accent(settings: Settings, now: Long): Int {
        return when (settings.mode) {
            Modes.SOLID, Modes.BREATHE, Modes.CANDLE -> sanitize(settings.color)
            Modes.DUAL -> sanitize(settings.color)
            Modes.AUDIO -> Palettes.colorAt(settings.palette, 0.1f)
            else -> Palettes.colorAt(settings.palette, 0f)
        }
    }

    private fun triangle(phase: Float): Float {
        val p = ((phase % 1f) + 1f) % 1f
        return if (p < 0.5f) p * 2f else (1f - p) * 2f
    }

    private fun hsv(h: Float, s: Float, v: Float): Int =
        Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))

    private fun sanitize(color: Int): Int = if (color == -1) 0xFFFEFEFE.toInt() else color

    /** Deterministic value noise in 0..1. */
    private fun noise(x: Double): Float {
        val i = kotlin.math.floor(x).toLong()
        val f = (x - i).toFloat()
        val a = hash(i)
        val b = hash(i + 1)
        val t = f * f * (3f - 2f * f)
        return a + (b - a) * t
    }

    private fun hash(n: Long): Float {
        var x = n * 374761393L + 668265263L
        x = (x xor (x shr 13)) * 1274126177L
        x = x xor (x shr 16)
        return ((x and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat()).coerceIn(0f, 1f)
    }
}
