package dev.aurora.ring.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.SystemClock
import dev.aurora.ring.data.Prefs

class NotifListener : NotificationListenerService() {

    private val recent = HashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaGate.reset()
        runCatching {
            for (sbn in activeNotifications ?: emptyArray()) {
                if (isMedia(sbn.notification)) MediaGate.onMedia(sbn.packageName)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (sbn.packageName == packageName) return

        val media = isMedia(notification)
        if (media) MediaGate.onMedia(sbn.packageName)

        if (media || notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val now = SystemClock.elapsedRealtime()
        val last = recent[sbn.key] ?: 0L
        if (now - last < DEBOUNCE_MS) return
        recent[sbn.key] = now
        if (recent.size > 80) {
            val cutoff = now - 60_000L
            recent.entries.removeAll { it.value < cutoff }
        }

        val settings = Prefs.load(this)
        if (!settings.running) return

        val label = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        RingService.dispatchNotification(this, sbn.packageName, label)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (isMedia(notification)) MediaGate.onRemoved(sbn.packageName)
    }

    private fun isMedia(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        if (notification.extras.containsKey("android.mediaSession")) return true
        return false
    }

    private companion object {
        const val DEBOUNCE_MS = 1200L
    }
}
