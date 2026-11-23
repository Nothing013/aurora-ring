package dev.aurora.ring.led

import android.content.Context
import android.os.SystemClock
import android.util.Log

sealed interface LedFrame {
    data class Solid(val color: Int) : LedFrame
    data class Dual(val a: Int, val b: Int) : LedFrame
    data class Spot(val spotA: Boolean, val color: Int) : LedFrame
    data object Off : LedFrame
}

/**
 * Single writer for the camera ring.
 *
 * Two backends:
 *  - ROOT  : direct AW21024 register writes through a persistent `su` shell.
 *            Full feature set: solid, hardware dual-split and hardware spin.
 *  - SHIZUKU: `miui.lights.ILightsManager.setColorCommon()`; one colour for the
 *            whole ring, no root required.
 *
 * The controller is the only place that talks to hardware, which keeps the
 * render loop free of backend details and makes write de-duplication trivial.
 */
class LedController(private val context: Context) {

    enum class Path { NONE, ROOT, SHIZUKU }

    data class Status(
        val path: Path,
        val rootAvailable: Boolean,
        val shizukuAvailable: Boolean,
        val shizukuGranted: Boolean,
        val dualSupported: Boolean,
        val spotSupported: Boolean,
        val lastError: String?
    )

    private val root = RootShell()
    private val lock = Any()

    @Volatile
    var path: Path = Path.NONE
        private set

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var rootAvailableCache: Boolean = false

    private var lastFrame: LedFrame? = null
    private var lastShizukuAt = 0L
    private var solidReady = false
    private var dualReady = false
    private var spotReady = false

    val supportsDual: Boolean get() = path == Path.ROOT
    val supportsSpot: Boolean get() = path == Path.ROOT

    fun status(): Status = Status(
        path = path,
        rootAvailable = rootAvailableCache,
        shizukuAvailable = LightsApi.shizukuAlive(),
        shizukuGranted = LightsApi.shizukuGranted(),
        dualSupported = supportsDual,
        spotSupported = supportsSpot,
        lastError = lastError
    )

    fun refreshPath(): Path {
        synchronized(lock) {
            val rootOk = root.probe()
            rootAvailableCache = rootOk
            path = when {
                rootOk -> Path.ROOT
                LightsApi.shizukuGranted() -> Path.SHIZUKU
                else -> Path.NONE
            }
            if (path == Path.ROOT) {
                // One clean state per service session; later frames are 1 write.
                root.ensure()
            } else {
                LightsApi.invalidate()
            }
            resetModeFlags()
            lastFrame = null
            Log.i(TAG, "backend = $path (root=$rootOk, shizuku=${LightsApi.shizukuGranted()})")
            return path
        }
    }

    fun apply(frame: LedFrame) {
        synchronized(lock) {
            if (frame == lastFrame) return
            val written = when (path) {
                Path.ROOT -> applyRoot(frame)
                Path.SHIZUKU -> applyShizuku(frame)
                Path.NONE -> false
            }
            if (written) lastFrame = frame
        }
    }

    private fun applyRoot(frame: LedFrame): Boolean {
        when (frame) {
            is LedFrame.Solid -> {
                ensureSolid()
                val c = sanitize(frame.color)
                writeFast(
                    "SYS=\"$SYS_REG\"; " +
                        "echo '0xa8 ${hex((c shr 16) and 0xFF)}' > \"\$SYS\"; " +
                        "echo '0xa9 ${hex((c shr 8) and 0xFF)}' > \"\$SYS\"; " +
                        "echo '0xaa ${hex(c and 0xFF)}' > \"\$SYS\"; " +
                        "echo '0xab 0xff' > \"\$SYS\""
                )
            }

            is LedFrame.Dual -> {
                ensureDual()
                val a = sanitize(frame.a)
                val b = sanitize(frame.b)
                writeFast(
                    "SYS=\"$SYS_REG\"; " +
                        "echo '0x5c ${hex((a shr 16) and 0xFF)}' > \"\$SYS\"; " +
                        "echo '0x5d ${hex((a shr 8) and 0xFF)}' > \"\$SYS\"; " +
                        "echo '0x5e ${hex(a and 0xFF)}' > \"\$SYS\"; " +
                        "echo '0x53 ${hex((b shr 16) and 0xFF)}' > \"\$SYS\"; " +
                        "echo '0x54 ${hex((b shr 8) and 0xFF)}' > \"\$SYS\"; " +
                        "echo '0x55 ${hex(b and 0xFF)}' > \"\$SYS\"; " +
                        "echo '0x49 0x00' > \"\$SYS\""
                )
            }

            is LedFrame.Spot -> {
                ensureSpot()
                val c = sanitize(frame.color)
                val mask = if (frame.spotA) "0x40" else "0x08"
                writeFast(
                    "SYS=\"$SYS_REG\"; " +
                        "echo '0xa8 ${hex((c shr 16) and 0xFF)}' > \"\$SYS\"; " +
                        "echo '0xa9 ${hex((c shr 8) and 0xFF)}' > \"\$SYS\"; " +
                        "echo '0xaa ${hex(c and 0xFF)}' > \"\$SYS\"; " +
                        "echo '0xab $mask' > \"\$SYS\""
                )
            }

            LedFrame.Off -> {
                solidReady = false
                dualReady = false
                spotReady = false
                root.submit(
                    "SYS=\"$SYS_REG\"; " +
                        "echo 0 > \"$SYS_EFFECT\"; " +
                        "echo '0xa1 0x00' > \"\$SYS\"; " +
                        "echo '0xab 0x00' > \"\$SYS\""
                )
            }
        }
        return true
    }

    private fun applyShizuku(frame: LedFrame): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShizukuAt < SHIZUKU_MIN_GAP_MS) return false
        val color = when (frame) {
            is LedFrame.Solid -> frame.color
            is LedFrame.Dual -> blend(frame.a, frame.b)
            is LedFrame.Spot -> if (frame.spotA) frame.color else dim(frame.color, 0.35f)
            LedFrame.Off -> 0
        }
        return if (LightsApi.setRingColor(sanitize(color))) {
            lastShizukuAt = now
            true
        } else {
            lastError = "Shizuku write failed"
            false
        }
    }

    private fun ensureSolid() {
        if (solidReady) return
        dualReady = false
        spotReady = false
        root.exec(
            "SYS=\"$SYS_REG\"; " +
                "echo 1 > \"$SYS_HWEN\"; " +
                "echo 2 > \"$SYS_EFFECT\"; " +
                "echo '0x00 0x11' > \"\$SYS\"; " +
                "echo '0x6e 0xff' > \"\$SYS\"; " +
                "echo '0x7a 0x00' > \"\$SYS\"; " +
                "echo '0xa0 0x00' > \"\$SYS\"; " +
                "echo '0xa1 0x00' > \"\$SYS\"; " +
                "echo '0xac 0x00' > \"\$SYS\"; " +
                "echo '0xab 0xff' > \"\$SYS\""
        )
        solidReady = true
    }

    private fun ensureDual() {
        if (dualReady) return
        solidReady = false
        spotReady = false
        root.exec(
            "SYS=\"$SYS_REG\"; " +
                "echo 1 > \"$SYS_HWEN\"; " +
                "echo 2 > \"$SYS_EFFECT\"; " +
                "echo '0x00 0x11' > \"\$SYS\"; " +
                "echo '0x6e 0xff' > \"\$SYS\"; " +
                "echo '0x7a 0x01' > \"\$SYS\"; " +
                "echo '0xa0 0x00' > \"\$SYS\"; " +
                "echo '0xa1 0x00' > \"\$SYS\"; " +
                "echo '0xab 0x00' > \"\$SYS\"; " +
                "echo '0xac 0x10' > \"\$SYS\"; " +
                "for b in 0x01 0x02 0x03 0x04 0x05 0x06 0x07 0x08 0x09 0x0a 0x0b 0x0c " +
                "0x0d 0x0e 0x0f 0x10 0x11 0x12 0x13 0x14 0x15 0x16 0x17 0x18; do " +
                "echo \"\$b 0xff\" > \"\$SYS\"; done"
        )
        dualReady = true
    }

    private fun ensureSpot() {
        if (spotReady) return
        solidReady = false
        dualReady = false
        root.exec(
            "SYS=\"$SYS_REG\"; " +
                "echo 1 > \"$SYS_HWEN\"; " +
                "echo 2 > \"$SYS_EFFECT\"; " +
                "echo '0x00 0x11' > \"\$SYS\"; " +
                "echo '0x6e 0xff' > \"\$SYS\"; " +
                "echo '0x7a 0x00' > \"\$SYS\"; " +
                "echo '0xa0 0x00' > \"\$SYS\"; " +
                "echo '0xa1 0x00' > \"\$SYS\"; " +
                "echo '0xa7 0xcc' > \"\$SYS\"; " +
                "echo '0xab 0xff' > \"\$SYS\"; " +
                "echo '0xac 0x00' > \"\$SYS\""
        )
        spotReady = true
    }

    private fun writeFast(cmd: String) {
        if (!root.submit(cmd)) {
            if (root.ensure()) {
                root.submit(cmd)
            } else {
                lastError = "root shell lost"
            }
        }
    }

    fun off() {
        synchronized(lock) {
            lastFrame = LedFrame.Off
            when (path) {
                Path.ROOT -> {
                    solidReady = false
                    dualReady = false
                    spotReady = false
                    root.submit(
                        "SYS=\"$SYS_REG\"; " +
                            "echo 0 > \"$SYS_EFFECT\"; " +
                            "echo '0xa1 0x00' > \"\$SYS\"; " +
                            "echo '0xab 0x00' > \"\$SYS\""
                    )
                }
                Path.SHIZUKU -> LightsApi.setRingColor(0)
                Path.NONE -> Unit
            }
        }
    }

    /** Restore the ring to the stock scene (camera effects work again). */
    fun release() {
        synchronized(lock) {
            lastFrame = null
            resetModeFlags()
            if (path == Path.ROOT) {
                root.exec(
                    "SYS=\"$SYS_REG\"; " +
                        "echo 1 > \"$SYS_HWEN\"; " +
                        "echo 2 > \"$SYS_EFFECT\"; " +
                        "echo '0x00 0x11' > \"\$SYS\"; " +
                        "echo '0x6e 0xff' > \"\$SYS\"; " +
                        "echo '0x7a 0x00' > \"\$SYS\"; " +
                        "echo '0xa0 0x00' > \"\$SYS\"; " +
                        "echo '0xa1 0x00' > \"\$SYS\"; " +
                        "echo '0xa7 0xcc' > \"\$SYS\"; " +
                        "echo '0xab 0xff' > \"\$SYS\""
                )
            }
            LightsApi.resetScene()
        }
    }

    fun close() {
        root.close()
    }

    /**
     * Root-only battery/background survival: Doze whitelist, active standby
     * bucket and auto-start app-op. Best effort, never throws.
     */
    fun applyBackgroundSurvival(packageName: String): Boolean {
        if (path != Path.ROOT) return false
        return root.exec(
            "cmd deviceidle whitelist +$packageName; " +
                "am set-standby-bucket $packageName active; " +
                "cmd appops set $packageName AUTO_START allow",
            timeoutSec = 12L
        )
    }

    private fun resetModeFlags() {
        solidReady = false
        dualReady = false
        spotReady = false
    }

    private fun sanitize(argb: Int): Int {
        val c = if (argb == -1) 0xFFFEFEFE.toInt() else argb
        return c or (0xFF shl 24)
    }

    private fun blend(a: Int, b: Int): Int {
        val r = (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) / 2
        val g = (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) / 2
        val bl = ((a and 0xFF) + (b and 0xFF)) / 2
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun dim(color: Int, scale: Float): Int {
        val r = (((color shr 16) and 0xFF) * scale).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * scale).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * scale).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun hex(v: Int): String = String.format(java.util.Locale.ROOT, "0x%02x", v.coerceIn(0, 255))

    private companion object {
        const val TAG = "AuroraLed"
        const val SHIZUKU_MIN_GAP_MS = 30L
        const val SYS_REG = "/sys/class/leds/aw21024_led/reg"
        const val SYS_HWEN = "/sys/class/leds/aw21024_led/hwen"
        const val SYS_EFFECT = "/sys/class/leds/aw21024_led/effect"
    }
}
