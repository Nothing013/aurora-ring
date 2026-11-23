package dev.aurora.ring.data

import android.graphics.Color

data class Palette(val id: Int, val name: String, val colors: IntArray?)

object Palettes {

    const val SPECTRUM = 0
    const val CYBERPUNK = 1
    const val SUNSET = 2
    const val OCEAN = 3
    const val PASTEL = 4
    const val FIRE = 5
    const val AURORA = 6
    const val NEON = 7

    val all = listOf(
        Palette(SPECTRUM, "Spectrum", null),
        Palette(
            CYBERPUNK, "Cyberpunk", intArrayOf(
                0xFFFF007F.toInt(), 0xFF7928CA.toInt(), 0xFF0070F3.toInt(), 0xFF00DFD8.toInt()
            )
        ),
        Palette(
            SUNSET, "Sunset", intArrayOf(
                0xFFFF1744.toInt(), 0xFFFF6D00.toInt(), 0xFFFFD600.toInt(), 0xFFE040FB.toInt()
            )
        ),
        Palette(
            OCEAN, "Ocean", intArrayOf(
                0xFF00E5FF.toInt(), 0xFF1DE9B6.toInt(), 0xFF00E676.toInt(), 0xFF2979FF.toInt()
            )
        ),
        Palette(
            PASTEL, "Pastel", intArrayOf(
                0xFFFF8FB1.toInt(), 0xFFB39DFF.toInt(), 0xFF8FE3FF.toInt(), 0xFF9CF5C4.toInt(),
                0xFFFFC48F.toInt()
            )
        ),
        Palette(
            FIRE, "Ember", intArrayOf(
                0xFFFF2D00.toInt(), 0xFFFF6A00.toInt(), 0xFFFFB300.toInt(), 0xFFFFE066.toInt()
            )
        ),
        Palette(
            AURORA, "Aurora", intArrayOf(
                0xFF00FFC6.toInt(), 0xFF00C2FF.toInt(), 0xFF7A5CFF.toInt(), 0xFFFF5CE1.toInt(),
                0xFF9DFF5C.toInt()
            )
        ),
        Palette(
            NEON, "Neon", intArrayOf(
                0xFFFF3D81.toInt(), 0xFF00F0FF.toInt(), 0xFFB4FF39.toInt(), 0xFFFFF338.toInt()
            )
        )
    )

    fun byId(id: Int): Palette = all.getOrElse(id) { all[0] }

    fun colorAt(id: Int, t: Float): Int {
        val palette = byId(id)
        val colors = palette.colors ?: run {
            val hue = ((t % 1f) + 1f) % 1f * 360f
            return Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        }
        val wrapped = ((t % 1f) + 1f) % 1f
        val scaled = wrapped * colors.size
        val i = scaled.toInt() % colors.size
        val j = (i + 1) % colors.size
        return lerp(colors[i], colors[j], scaled - scaled.toInt())
    }

    fun lerp(c1: Int, c2: Int, ratio: Float): Int {
        val t = ratio.coerceIn(0f, 1f)
        val a = FloatArray(3)
        val b = FloatArray(3)
        Color.colorToHSV(c1, a)
        Color.colorToHSV(c2, b)
        val achromatic = a[1] < 0.02f || b[1] < 0.02f
        val hue = if (achromatic) {
            if (a[1] >= b[1]) a[0] else b[0]
        } else {
            var diff = b[0] - a[0]
            if (diff > 180f) {
                diff -= 360f
            } else if (diff < -180f) {
                diff += 360f
            }
            var h = (a[0] + diff * t) % 360f
            if (h < 0f) h += 360f
            h
        }
        return Color.HSVToColor(
            floatArrayOf(
                hue,
                (a[1] + (b[1] - a[1]) * t).coerceIn(0f, 1f),
                (a[2] + (b[2] - a[2]) * t).coerceIn(0f, 1f)
            )
        )
    }
}
