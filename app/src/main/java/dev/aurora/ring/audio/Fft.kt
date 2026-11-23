package dev.aurora.ring.audio

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-place iterative radix-2 FFT. All buffers are pre-allocated: the audio
 * thread calls [magnitude] 40+ times per second, so no per-frame garbage.
 */
internal class Fft(val size: Int) {

    init {
        require(size >= 8 && (size and (size - 1)) == 0) { "size must be a power of two" }
    }

    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val out = FloatArray(size / 2)
    private val window = FloatArray(size) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (size - 1))).toFloat()
    }

    fun magnitude(input: FloatArray): FloatArray {
        val n = size
        for (i in 0 until n) {
            re[i] = if (i < input.size) input[i] * window[i] else 0f
            im[i] = 0f
        }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var start = 0
            while (start < n) {
                var curRe = 1f
                var curIm = 0f
                val half = len shr 1
                for (k in 0 until half) {
                    val i1 = start + k
                    val i2 = i1 + half
                    val tRe = re[i2] * curRe - im[i2] * curIm
                    val tIm = re[i2] * curIm + im[i2] * curRe
                    re[i2] = re[i1] - tRe
                    im[i2] = im[i1] - tIm
                    re[i1] += tRe
                    im[i1] += tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                start += len
            }
            len = len shl 1
        }

        for (k in 0 until n / 2) {
            out[k] = sqrt(re[k] * re[k] + im[k] * im[k]) / (n / 4f)
        }
        return out
    }
}
