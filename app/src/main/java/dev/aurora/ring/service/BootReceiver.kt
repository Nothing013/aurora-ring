package dev.aurora.ring.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.aurora.ring.data.Prefs

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val settings = Prefs.load(ctx)
                if (settings.running && settings.behavior.restoreOnBoot) {
                    runCatching { RingService.start(ctx) }
                }
            }
        }
    }
}
