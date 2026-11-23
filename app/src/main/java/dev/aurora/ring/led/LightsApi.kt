package dev.aurora.ring.led

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Minimal, dependency-free client for `miui.lights.ILightsManager`.
 *
 * The AIDL interface is deliberately *not* generated: a local class named
 * `miui.lights.ILightsManager` would shadow the platform class inside the app's
 * boot class loader and break `SystemServiceHelper`. Transactions are written by
 * hand instead. Interface order (verified against the HyperOS service):
 *
 *   1 setColorfulLight(String pkg, int styleType, int userId)
 *   2 setColorCommon(int color, String pkg, int styleType, int userId)
 *   3 setColorLed(...)            // empty implementation on HyperOS
 *   4 setCustomLight(int color, int flashMode, int onMs, int offMs,
 *                    int brightNessMode, String pkg, int styleType, int userId)
 *
 * `setColorCommon` with [STYLE_RING] is the only call that is both timer-free and
 * flicker-free, so it is the animation path for the Shizuku backend.
 */
object LightsApi {

    private const val TAG = "AuroraLights"
    const val DESCRIPTOR = "miui.lights.ILightsManager"
    const val CAMERA_PKG = "com.android.camera"

    const val STYLE_RING = 3
    const val STYLE_RESET = -1
    const val STYLE_DUAL_SCENE = 2

    private const val TX_SET_COLORFUL = 1
    private const val TX_SET_COLOR_COMMON = 2
    private const val TX_SET_CUSTOM = 4

    @Volatile
    private var cachedBinder: IBinder? = null

    fun shizukuAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun shizukuGranted(): Boolean = try {
        shizukuAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestShizukuPermission(code: Int = 4001) {
        try {
            if (shizukuAlive() && !shizukuGranted()) Shizuku.requestPermission(code)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed", t)
        }
    }

    fun binder(): IBinder? {
        val current = cachedBinder
        if (current != null && current.isBinderAlive) return current
        if (!shizukuGranted()) return null
        synchronized(this) {
            cachedBinder?.let { if (it.isBinderAlive) return it }
            return try {
                val raw = SystemServiceHelper.getSystemService(DESCRIPTOR)
                if (raw == null) {
                    Log.w(TAG, "ILightsManager not published")
                    null
                } else {
                    ShizukuBinderWrapper(raw).also { cachedBinder = it }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "cannot acquire ILightsManager", t)
                null
            }
        }
    }

    fun invalidate() {
        cachedBinder = null
    }

    private inline fun transact(block: (Parcel) -> Unit): Boolean {
        val b = binder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            block(data)
            b.transact(TX_SET_COLOR_COMMON, data, reply, 0)
            reply.readException()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "transaction failed", t)
            invalidate()
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Flicker-free solid ring colour. */
    fun setRingColor(color: Int): Boolean = transact { p ->
        p.writeInt(color)
        p.writeString(CAMERA_PKG)
        p.writeInt(STYLE_RING)
        p.writeInt(0)
    }

    /** Two-colour split scene (only some firmwares honour it). */
    fun setDualScene(): Boolean {
        val b = binder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(CAMERA_PKG)
            data.writeInt(STYLE_DUAL_SCENE)
            data.writeInt(0)
            b.transact(TX_SET_COLORFUL, data, reply, 0)
            reply.readException()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "dual scene failed", t)
            invalidate()
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Hands the ring back to the system scene engine.
     *
     * `styleType = -1` resets the scene. The zero-colour `setCustomLight` with
     * style 12 is the camera-scene reset used by the stock camera app.
     */
    fun resetScene(): Boolean {
        val b = binder() ?: return false
        var ok = false
        runCatching { setRingColor(0) }.onSuccess { ok = it || ok }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(0)
            data.writeInt(0)
            data.writeInt(0)
            data.writeInt(0)
            data.writeInt(0)
            data.writeString(CAMERA_PKG)
            data.writeInt(12)
            data.writeInt(0)
            b.transact(TX_SET_CUSTOM, data, reply, 0)
            reply.readException()
            ok = true
        } catch (_: Throwable) {
        } finally {
            reply.recycle()
            data.recycle()
        }
        val data2 = Parcel.obtain()
        val reply2 = Parcel.obtain()
        try {
            data2.writeInterfaceToken(DESCRIPTOR)
            data2.writeInt(0)
            data2.writeString(CAMERA_PKG)
            data2.writeInt(STYLE_RESET)
            data2.writeInt(0)
            b.transact(TX_SET_COLOR_COMMON, data2, reply2, 0)
            reply2.readException()
            ok = true
        } catch (_: Throwable) {
        } finally {
            reply2.recycle()
            data2.recycle()
        }
        return ok
    }

}

/**
 * Persistent `su` shell. One process, one writer: keeps the per-frame cost of a
 * sysfs write at a single `write()` + `flush()` instead of forking a shell for
 * every animation frame.
 */
internal class RootShell {

    private val lock = Any()
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    @Volatile
    var alive: Boolean = false
        private set

    fun probe(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val done = p.waitFor(3, TimeUnit.SECONDS)
        val text = if (done) p.inputStream.bufferedReader().readText() else ""
        if (!done) p.destroyForcibly()
        done && text.contains("uid=0")
    } catch (_: Throwable) {
        false
    }

    fun ensure(): Boolean {
        synchronized(lock) {
            val p = process
            if (alive && p != null && p.isAlive && writer != null) return true
            closeLocked()
            return try {
                val np = ProcessBuilder("su").redirectErrorStream(true).start()
                val out = BufferedWriter(OutputStreamWriter(np.outputStream))
                val drain = Thread {
                    runCatching {
                        np.inputStream.bufferedReader().use { r ->
                            while (r.readLine() != null) {
                                // drain: never block on the root shell's stdout
                            }
                        }
                    }
                }
                drain.isDaemon = true
                drain.start()
                process = np
                writer = out
                alive = true
                true
            } catch (t: Throwable) {
                Log.w("AuroraRoot", "cannot start su shell", t)
                alive = false
                false
            }
        }
    }

    /** Fire-and-forget: never blocks the render loop. */
    fun submit(command: String): Boolean {
        synchronized(lock) {
            if (!ensure()) return false
            return try {
                writer?.write(command)
                writer?.write("\n")
                writer?.flush()
                true
            } catch (t: Throwable) {
                Log.w("AuroraRoot", "write failed, restarting shell", t)
                closeLocked()
                false
            }
        }
    }

    /** Blocking, with output. Used for init/reset sequences only. */
    fun exec(command: String, timeoutSec: Long = 6L): Boolean = try {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        done && p.exitValue() == 0
    } catch (_: Throwable) {
        false
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        alive = false
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        writer = null
        process = null
    }
}
