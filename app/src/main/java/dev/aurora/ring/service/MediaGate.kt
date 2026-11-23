package dev.aurora.ring.service

import android.os.SystemClock
import dev.aurora.ring.data.AudioSources
import dev.aurora.ring.data.Settings

/**
 * Tracks which app currently owns a media session. With global-mix capture we
 * cannot split audio per app, so "only react to these apps" is implemented as a
 * gate: the audio engine runs only while one of the selected apps is playing.
 */
object MediaGate {

    @Volatile
    private var currentPackage: String? = null

    @Volatile
    private var updatedAt = 0L

    fun onMedia(pkg: String) {
        currentPackage = pkg
        updatedAt = SystemClock.elapsedRealtime()
    }

    fun onRemoved(pkg: String) {
        if (currentPackage == pkg) {
            currentPackage = null
            updatedAt = 0L
        }
    }

    fun current(): String? = currentPackage

    fun allows(settings: Settings): Boolean {
        if (!settings.audio.appFilter) return true
        if (settings.audio.source == AudioSources.MIC) return true
        val pkg = currentPackage ?: return false
        if (SystemClock.elapsedRealtime() - updatedAt > STALE_MS) return false
        return pkg in settings.audio.apps
    }

    fun reset() {
        currentPackage = null
        updatedAt = 0L
    }

    private const val STALE_MS = 30 * 60 * 1000L
}
