package dev.aurora.ring.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Beat-locked audio analysis.
 *
 * Compared to a plain "envelope over threshold" detector this engine:
 *
 *  1. uses **spectral flux** on the raw magnitude spectrum, which is level
 *     independent and reacts to the attack of a drum instead of its tail;
 *  2. adapts the threshold with a **median + MAD** estimator over a ~2 s window,
 *     so quiet and loud tracks behave the same;
 *  3. estimates the **tempo** from a folded inter-onset histogram, and keeps a
 *     **phase-locked loop** on the beat grid;
 *  4. predicts beats from the grid and emits the visual pulse *early* by the
 *     configured latency, so the ring peaks with the audible beat instead of
 *     ~2 frames late.
 */
class BeatEngine(private val context: Context) {

    enum class Source { GLOBAL, MIC }

    data class Config(
        val source: Source = Source.GLOBAL,
        val sensitivity: Float = 1.0f,
        val minHz: Float = 45f,
        val maxHz: Float = 16000f,
        val bandFocus: Int = FOCUS_AUTO,
        val latencyMs: Int = 45,
        val beatGain: Float = 1.0f
    )

    data class Snapshot(
        val active: Boolean = false,
        val capturing: Boolean = false,
        val level: Float = 0f,
        val low: Float = 0f,
        val mid: Float = 0f,
        val high: Float = 0f,
        val beat: Float = 0f,
        val beatId: Long = 0L,
        val bpm: Float = 0f,
        val confidence: Float = 0f,
        val band: Float = 0f,
        val error: String? = null
    )

    private val _live = MutableStateFlow(Snapshot())
    val live: StateFlow<Snapshot> = _live.asStateFlow()

    @Volatile
    private var running = false

    @Volatile
    private var gateOpen = true

    @Volatile
    private var config = Config()

    @Volatile
    private var captureError: String? = null

    private var visualizer: Visualizer? = null
    private var record: AudioRecord? = null
    private var micThread: Thread? = null
    private var retryThread: Thread? = null
    private var micFft: Fft? = null
    private val startLock = Any()

    // ---- analysis state (touched from capture callbacks / mic thread) ----
    private var prevMags = FloatArray(0)
    private val fluxHistory = FloatArray(HISTORY)
    private var fluxIndex = 0
    private var fluxCount = 0
    private var lastNovelty = 0f
    private var lastOnsetMs = 0L
    private var lastSpectrumMs = 0L

    private var peakLow = 0f
    private var peakMid = 0f
    private var peakHigh = 0f
    private var peakLevel = 0f
    private var lowS = 0f
    private var midS = 0f
    private var highS = 0f
    private var levelS = 0f

    private val tempoHist = FloatArray(BPM_BUCKETS)
    private var periodMs = 0f
    private var nextBeatMs = 0L
    private var lastEmitMs = 0L
    private var beatId = 0L
    private var lastLivePush = 0L

    // ---------------------------------------------------------------------

    fun start(cfg: Config = config) {
        synchronized(startLock) {
            config = cfg
            if (running) {
                restartCaptureIfNeeded()
                return
            }
            running = true
            resetAnalysis()
            openCapture()
        }
    }

    fun isRunning(): Boolean = running

    fun update(cfg: Config) {
        val previous = config
        config = cfg
        if (!running) return
        if (previous.source != cfg.source) {
            synchronized(startLock) {
                closeCapture()
                resetAnalysis()
                openCapture()
            }
        }
    }

    fun setGate(open: Boolean) {
        if (gateOpen == open) return
        gateOpen = open
        synchronized(startLock) {
            if (!running) return
            if (open) {
                if (visualizer == null && record == null) openCapture()
            } else {
                closeCapture()
                resetAnalysis()
            }
        }
    }

    fun stop() {
        synchronized(startLock) {
            running = false
            closeCapture()
            resetAnalysis()
            _live.value = Snapshot()
        }
    }

    fun resetTempo() {
        periodMs = 0f
        nextBeatMs = 0L
        tempoHist.fill(0f)
    }

    // ---------------------------------------------------------------------
    // capture
    // ---------------------------------------------------------------------

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openCapture() {
        if (!gateOpen) return
        if (!hasPermission()) {
            captureError = "Microphone permission is required for audio reactive"
            return
        }
        val ok = when (config.source) {
            Source.GLOBAL -> openVisualizer()
            Source.MIC -> openMic()
        }
        if (!ok) {
            captureError = when (config.source) {
                Source.GLOBAL -> "Global audio capture unavailable — try Microphone"
                Source.MIC -> "Microphone busy or unavailable"
            }
            scheduleRetry()
        } else {
            captureError = null
        }
    }

    private fun openVisualizer(): Boolean {
        return try {
            val v = Visualizer(0)
            val range = Visualizer.getCaptureSizeRange()
            val size = range[1].coerceAtMost(1024).coerceAtLeast(256)
            v.captureSize = size
            v.setDataCaptureListener(listener, Visualizer.getMaxCaptureRate(), true, true)
            v.enabled = true
            visualizer = v
            Log.i(TAG, "visualizer ready, size=$size")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "visualizer failed", t)
            runCatching { visualizer?.release() }
            visualizer = null
            false
        }
    }

    private fun openMic(): Boolean {
        if (!hasPermission()) return false
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (src in sources) {
            val minBuf = AudioRecord.getMinBufferSize(
                MIC_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) continue
            val rec = try {
                AudioRecord(
                    src,
                    MIC_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } catch (_: Throwable) {
                null
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { rec?.release() }
                continue
            }
            try {
                rec.startRecording()
            } catch (_: Throwable) {
                runCatching { rec.release() }
                continue
            }
            record = rec
            micFft = Fft(MIC_FFT)
            val thread = Thread { micLoop(rec) }
            thread.isDaemon = true
            thread.start()
            micThread = thread
            Log.i(TAG, "mic ready, source=$src")
            return true
        }
        return false
    }

    private fun micLoop(rec: AudioRecord) {
        val pcm = ShortArray(MIC_FFT)
        val samples = FloatArray(MIC_FFT)
        val fft = micFft ?: return
        while (running && record === rec) {
            val n = try {
                rec.read(pcm, 0, pcm.size)
            } catch (_: Throwable) {
                -1
            }
            if (n <= 0) {
                if (n < 0) break
                continue
            }
            for (i in 0 until n) samples[i] = pcm[i] / 32768f
            if (n < pcm.size) java.util.Arrays.fill(samples, n, pcm.size, 0f)
            val mags = fft.magnitude(samples)
            analyse(mags, MIC_RATE.toFloat() / MIC_FFT, SystemClock.elapsedRealtime())
        }
        synchronized(startLock) {
            if (record === rec) {
                closeCapture()
                if (running && gateOpen) scheduleRetry()
            }
        }
    }

    private fun closeCapture() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        val rec = record
        record = null
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        micThread = null
    }

    private fun restartCaptureIfNeeded() {
        if (gateOpen && visualizer == null && record == null && captureError != null) {
            openCapture()
        }
    }

    private fun scheduleRetry() {
        if (retryThread?.isAlive == true) return
        val t = Thread {
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
            }
            synchronized(startLock) {
                if (running && gateOpen) openCapture()
            }
        }
        t.isDaemon = true
        t.start()
        retryThread = t
    }

    // ---------------------------------------------------------------------
    // analysis
    // ---------------------------------------------------------------------

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(viz: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

        override fun onFftDataCapture(viz: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            if (fft == null || fft.size < 32) return
            val bins = fft.size / 2
            if (prevMags.size != bins) prevMags = FloatArray(bins)
            val mags = FloatArray(bins)
            for (i in 0 until bins) {
                val re = fft[2 * i].toFloat()
                val im = fft[2 * i + 1].toFloat()
                mags[i] = sqrt(re * re + im * im) / 128f
            }
            val hz = if (samplingRate > 0) samplingRate / 1000f else 44100f
            analyse(mags, hz / fft.size, SystemClock.elapsedRealtime())
        }
    }

    private fun analyse(mags: FloatArray, binHz: Float, now: Long) {
        if (prevMags.size != mags.size) prevMags = FloatArray(mags.size)

        var flux = 0f
        var total = 0f
        var counted = 0
        var lowSum = 0f
        var lowN = 0
        var midSum = 0f
        var midN = 0
        var highSum = 0f
        var highN = 0

        val lo = config.minHz.coerceAtLeast(25f)
        val hi = config.maxHz.coerceAtLeast(lo + 40f)

        for (k in 1 until mags.size) {
            val f = k * binHz
            if (f < 25f || f > 16000f) continue
            val m = mags[k]
            if (m > prevMags[k]) flux += m - prevMags[k]
            prevMags[k] = m
            total += m
            counted++
            if (f in 40f..160f) { lowSum += m; lowN++ }
            if (f in 300f..2500f) { midSum += m; midN++ }
            if (f in 4000f..14000f) { highSum += m; highN++ }
        }
        if (counted == 0) return

        lastSpectrumMs = now
        val mean = total / counted

        val lowRaw = if (lowN > 0) lowSum / lowN else 0f
        val midRaw = if (midN > 0) midSum / midN else 0f
        val highRaw = if (highN > 0) highSum / highN else 0f

        peakLow = max(lowRaw, peakLow * PEAK_DECAY)
        peakMid = max(midRaw, peakMid * PEAK_DECAY)
        peakHigh = max(highRaw, peakHigh * PEAK_DECAY)
        peakLevel = max(mean, peakLevel * PEAK_DECAY)

        val low = (lowRaw / max(peakLow, 1e-5f)).coerceIn(0f, 1f)
        val mid = (midRaw / max(peakMid, 1e-5f)).coerceIn(0f, 1f)
        val high = (highRaw / max(peakHigh, 1e-5f)).coerceIn(0f, 1f)
        val level = (mean / max(peakLevel, 1e-5f)).coerceIn(0f, 1f)

        lowS += (low - lowS) * if (low > lowS) 0.55f else 0.10f
        midS += (mid - midS) * if (mid > midS) 0.55f else 0.10f
        highS += (high - highS) * if (high > highS) 0.55f else 0.10f
        levelS += (level - levelS) * if (level > levelS) 0.6f else 0.08f

        // ---- onset detection -------------------------------------------------
        val novelty = flux / (total + 1e-4f)
        fluxHistory[fluxIndex] = novelty
        fluxIndex = (fluxIndex + 1) % HISTORY
        if (fluxCount < HISTORY) fluxCount++

        val threshold = adaptiveThreshold() / config.sensitivity.coerceIn(0.3f, 4f)
        val rising = novelty > threshold && novelty > lastNovelty * 1.05f
        lastNovelty = novelty

        val loudEnough = mean > NOISE_FLOOR
        if (rising && loudEnough && now - lastOnsetMs >= MIN_ONSET_GAP_MS) {
            onOnset(now)
        }

        // ---- live snapshot (throttled) --------------------------------------
        if (now - lastLivePush >= 50L) {
            lastLivePush = now
            _live.value = buildSnapshot(now)
        }
    }

    private fun adaptiveThreshold(): Float {
        val n = fluxCount
        if (n < 8) return 0.05f
        val copy = FloatArray(n)
        for (i in 0 until n) copy[i] = fluxHistory[i]
        java.util.Arrays.sort(copy)
        val median = copy[n / 2]
        val deviations = FloatArray(n)
        for (i in 0 until n) deviations[i] = abs(copy[i] - median)
        java.util.Arrays.sort(deviations)
        val mad = deviations[n / 2]
        return median + MAD_K * mad + FLUX_FLOOR
    }

    private fun onOnset(now: Long) {
        val previous = lastOnsetMs
        lastOnsetMs = now

        if (previous > 0L) {
            val gap = now - previous
            if (gap in 200L..2000L) {
                var bpm = 60000f / gap
                while (bpm < 60f) bpm *= 2f
                while (bpm > 180f) bpm /= 2f
                val idx = (bpm - 60f).toInt().coerceIn(0, BPM_BUCKETS - 1)
                for (d in -1..1) {
                    val i = (idx + d).coerceIn(0, BPM_BUCKETS - 1)
                    tempoHist[i] += if (d == 0) 1f else 0.45f
                }
                for (i in tempoHist.indices) tempoHist[i] *= 0.985f
                val best = tempoHist.indices.maxByOrNull { tempoHist[it] } ?: -1
                val bestScore = if (best >= 0) tempoHist[best] else 0f
                val sum = tempoHist.sum()
                if (best >= 0 && bestScore > 2.5f) {
                    val period = 60000f / (60f + best)
                    periodMs = if (periodMs <= 0f) period else periodMs + (period - periodMs) * 0.22f
                    if (nextBeatMs <= 0L) {
                        nextBeatMs = now + periodMs.toLong()
                    } else {
                        lockPhase(now)
                    }
                    lastConfidence = (bestScore / max(sum, 1f)).coerceIn(0f, 1f)
                }
            }
        }

        if (periodMs <= 0f) {
            // No tempo yet: show the raw onset so the ring is never dead.
            lastEmitMs = now - config.latencyMs
            beatId++
        }
    }

    private fun lockPhase(now: Long) {
        val period = periodMs
        if (period < 120f) return
        val k = Math.round((now - nextBeatMs) / period.toDouble()).toInt()
        val grid = nextBeatMs + k * period.toLong()
        val err = (now - grid).toFloat()
        if (abs(err) > period * 0.45f) return
        nextBeatMs = (grid + (err * 0.35f).toLong())
        while (nextBeatMs < now + 30L) nextBeatMs += period.toLong()
    }

    private fun buildSnapshot(now: Long): Snapshot {
        val hasSignal = now - lastSpectrumMs < 900L && levelS > 0.008f

        var beat = 0f
        if (lastEmitMs > 0L) {
            val age = now - lastEmitMs
            if (age in 0L..1200L) {
                beat = if (age < ATTACK_MS) age.toFloat() / ATTACK_MS
                else exp(-(age - ATTACK_MS) / DECAY_MS)
            }
        }

        val band = when (config.bandFocus) {
            FOCUS_LOW -> lowS
            FOCUS_MID -> midS
            FOCUS_HIGH -> highS
            FOCUS_BROAD -> levelS
            else -> {
                val strongest = max(lowS, max(midS, highS))
                when (strongest) {
                    lowS -> lowS
                    midS -> midS
                    else -> highS
                }
            }
        }

        return Snapshot(
            active = hasSignal,
            capturing = visualizer != null || record != null,
            level = levelS,
            low = lowS,
            mid = midS,
            high = highS,
            beat = beat * config.beatGain.coerceIn(0f, 2f),
            beatId = beatId,
            bpm = if (periodMs > 0f) 60000f / periodMs else 0f,
            confidence = lastConfidence,
            band = band,
            error = captureError
        )
    }

    @Volatile
    private var lastConfidence = 0f

    /**
     * Called by the render loop. Advances the beat grid and returns the current
     * visual state. Never blocks, never allocates on the hot path beyond the
     * immutable snapshot.
     */
    fun snapshot(now: Long = SystemClock.elapsedRealtime()): Snapshot {
        if (running && gateOpen) {
            val period = periodMs
            if (period > 120f && nextBeatMs > 0L) {
                if (now >= nextBeatMs - config.latencyMs) {
                    lastEmitMs = nextBeatMs - config.latencyMs
                    beatId++
                    nextBeatMs += period.toLong()
                    if (nextBeatMs < now - period * 4) {
                        // fell far behind (device sleep): resync instead of bursting
                        nextBeatMs = now + period.toLong()
                    }
                }
            } else if (now - lastOnsetMs > 6000L) {
                lastEmitMs = 0L
            }
            // A long silence should also drop the tempo lock.
            if (now - lastOnsetMs > 8000L && lastOnsetMs > 0L) {
                periodMs = 0f
                nextBeatMs = 0L
                lastConfidence = 0f
            }
        }
        return buildSnapshot(now)
    }

    private fun resetAnalysis() {
        prevMags = FloatArray(0)
        fluxHistory.fill(0f)
        fluxIndex = 0
        fluxCount = 0
        lastNovelty = 0f
        lastOnsetMs = 0L
        lastSpectrumMs = 0L
        peakLow = 0f; peakMid = 0f; peakHigh = 0f; peakLevel = 0f
        lowS = 0f; midS = 0f; highS = 0f; levelS = 0f
        tempoHist.fill(0f)
        periodMs = 0f
        nextBeatMs = 0L
        lastEmitMs = 0L
        lastConfidence = 0f
        captureError = null
        _live.value = Snapshot()
    }

    companion object {
        private const val TAG = "AuroraBeat"

        const val FOCUS_AUTO = 0
        const val FOCUS_LOW = 1
        const val FOCUS_MID = 2
        const val FOCUS_HIGH = 3
        const val FOCUS_BROAD = 4

        private const val HISTORY = 96
        private const val BPM_BUCKETS = 121
        private const val MAD_K = 2.6f
        private const val FLUX_FLOOR = 0.012f
        private const val NOISE_FLOOR = 0.0018f
        private const val MIN_ONSET_GAP_MS = 105L
        private const val ATTACK_MS = 8f
        private const val DECAY_MS = 125f
        private const val PEAK_DECAY = 0.9965f

        private const val MIC_RATE = 22050
        private const val MIC_FFT = 1024
    }
}
