package dev.aurora.ring.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.aurora.ring.data.AudioSettings
import dev.aurora.ring.data.BehaviorSettings
import dev.aurora.ring.data.NotifSettings
import dev.aurora.ring.data.Prefs
import dev.aurora.ring.data.Settings
import dev.aurora.ring.service.RingService

/**
 * Small state holder: Compose-observable settings plus the service's live
 * status streams. Every mutation is persisted immediately and pushed to the
 * service with a short debounce (sliders otherwise restart the service dozens
 * of times per second).
 */
class AppState(private val context: Context) {

    var settings by mutableStateOf(Prefs.load(context))
        private set

    val engineStatus = RingService.engineStatus
    val audioLive = RingService.audioLive

    private val handler = Handler(Looper.getMainLooper())
    private var reloadScheduled = false

    fun update(transform: (Settings) -> Settings) {
        val previous = settings
        val next = transform(previous)
        settings = next
        Prefs.save(context, next)
        if (next.running != previous.running) {
            if (next.running) {
                runCatching { RingService.start(context) }
            } else {
                RingService.stop(context)
            }
        } else if (next.running) {
            scheduleReload()
        }
    }

    fun updateAudio(transform: (AudioSettings) -> AudioSettings) {
        update { it.copy(audio = transform(it.audio)) }
    }

    fun updateNotif(transform: (NotifSettings) -> NotifSettings) {
        update { it.copy(notifications = transform(it.notifications)) }
    }

    fun updateBehavior(transform: (BehaviorSettings) -> BehaviorSettings) {
        update { it.copy(behavior = transform(it.behavior)) }
    }

    fun setRunning(running: Boolean) {
        update { it.copy(running = running) }
    }

    fun completeSetup() {
        update { it.copy(setupDone = true, running = true) }
    }

    fun resetSetup() {
        update { it.copy(setupDone = false) }
    }

    private fun scheduleReload() {
        if (reloadScheduled) return
        reloadScheduled = true
        handler.postDelayed({
            reloadScheduled = false
            RingService.reload(context)
        }, 140L)
    }
}
