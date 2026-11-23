package dev.aurora.ring.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.aurora.ring.MainActivity
import dev.aurora.ring.R
import dev.aurora.ring.audio.BeatEngine
import dev.aurora.ring.data.AudioSources
import dev.aurora.ring.data.Modes
import dev.aurora.ring.data.Prefs
import dev.aurora.ring.data.Settings
import dev.aurora.ring.engine.RenderEngine
import dev.aurora.ring.led.LedController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class RingService : Service() {

    private lateinit var led: LedController
    private lateinit var audio: BeatEngine
    private lateinit var engine: RenderEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var engineStarted = false
    private var receiversRegistered = false
    private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    engine.setScreenOn(true)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    engine.setScreenOn(false)
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    engine.setScreenOn(true)
                }
            }
            syncAudioGate()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            engine.setBattery(pct)
            engine.setCharging(charging)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        led = LedController(this)
        audio = BeatEngine(this)
        engine = RenderEngine(this, led, audio)
        screenOn = (getSystemService(PowerManager::class.java))?.isInteractive ?: true
        createChannel()
        scope.launch { engine.status.collect { engineStatus.value = it } }
        scope.launch { audio.live.collect { audioLive.value = it } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_REPAIR -> {
                ensureRunning(Prefs.load(this))
                engine.refreshHardware()
                return START_STICKY
            }
            ACTION_RESET_HW -> {
                if (!Prefs.load(this).running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                engine.releaseHardware()
                return START_STICKY
            }
            ACTION_TEST -> {
                ensureRunning(Prefs.load(this))
                engine.triggerNotification("dev.aurora.ring.test", "Test alert")
                return START_STICKY
            }
            ACTION_RESET_TEMPO -> {
                audio.resetTempo()
                if (!Prefs.load(this).running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            ACTION_NOTIFY -> {
                val pkg = intent.getStringExtra(EXTRA_PKG) ?: return START_STICKY
                ensureRunning(Prefs.load(this))
                engine.triggerNotification(pkg, intent.getStringExtra(EXTRA_LABEL))
                return START_STICKY
            }
            ACTION_RELOAD, null -> ensureRunning(Prefs.load(this))
            ACTION_START -> {
                val loaded = Prefs.load(this)
                if (!loaded.running) Prefs.save(this, loaded.copy(running = true))
                ensureRunning(Prefs.load(this))
            }
            else -> ensureRunning(Prefs.load(this))
        }
        return START_STICKY
    }

    private fun ensureRunning(settings: Settings) {
        if (!settings.running) {
            stopEverything()
            return
        }
        goForeground(settings)
        registerReceivers()
        Thread {
            runCatching { led.refreshPath() }
        }.apply { isDaemon = true }.start()

        if (!engineStarted) {
            engine.start(settings)
            engineStarted = true
        } else {
            engine.update(settings)
        }

        if (settings.audio.enabled) {
            audio.start(audioConfig(settings))
        } else {
            audio.stop()
        }
        syncAudioGate()
        engine.setScreenOn(screenOn)
        engine.setCharging(isCharging())
        engine.setBattery(batteryPercent())

        if (settings.behavior.keepAlive) {
            Thread {
                runCatching { led.applyBackgroundSurvival(packageName) }
            }.apply { isDaemon = true }.start()
        }
        refreshBatterySticky()
        RingTileService.refresh(this)
    }

    private fun goForeground(settings: Settings) {
        val notification = buildNotification(settings)
        val micAllowed = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val type = if (settings.audio.enabled && micAllowed) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        try {
            startForeground(NOTIF_ID, notification, type)
        } catch (t: Throwable) {
            Log.w(TAG, "typed foreground failed, falling back", t)
            runCatching {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }.onFailure { startForeground(NOTIF_ID, notification) }
        }
    }

    private fun buildNotification(settings: Settings): Notification {
        val open = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            11,
            Intent(this, RingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val mode = modeName(settings.mode)
        val text = when {
            settings.audio.enabled && settings.mode == Modes.AUDIO -> "Audio reactive · $mode"
            settings.audio.enabled -> "$mode · beat sync on"
            else -> mode
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ring)
            .setContentTitle("Aurora Ring")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_ring),
                    "Stop",
                    stop
                ).build()
            )
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ring service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the camera ring animation running"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(batteryReceiver, batteryFilter, Context.RECEIVER_NOT_EXPORTED)
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        receiversRegistered = false
    }

    private fun syncAudioGate() {
        val s = Prefs.load(this)
        val open = s.audio.enabled &&
            (screenOn || s.audio.allowScreenOff) &&
            MediaGate.allows(s)
        audio.setGate(open)
    }

    private fun audioConfig(s: Settings) = BeatEngine.Config(
        source = if (s.audio.source == AudioSources.MIC) BeatEngine.Source.MIC else BeatEngine.Source.GLOBAL,
        sensitivity = s.audio.sensitivity,
        bandFocus = s.audio.bandFocus,
        latencyMs = s.audio.latencyMs,
        beatGain = s.audio.beatGain
    )

    private fun refreshBatterySticky() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryReceiver.onReceive(this, intent)
    }

    private fun isCharging(): Boolean {
        val bm = getSystemService(BatteryManager::class.java) ?: return false
        return bm.isCharging
    }

    private fun batteryPercent(): Int {
        val bm = getSystemService(BatteryManager::class.java) ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun stopEverything() {
        val s = Prefs.load(this)
        Prefs.save(this, s.copy(running = false))
        audio.stop()
        engine.stop()
        engineStarted = false
        led.release()
        led.close()
        unregisterReceivers()
        RingTileService.refresh(this)
        engineStatus.value = RenderEngine.Status()
        audioLive.value = BeatEngine.Snapshot()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        runCatching { audio.stop() }
        if (engineStarted) runCatching { engine.stop() }
        runCatching { led.release() }
        runCatching { led.close() }
        unregisterReceivers()
        engineStatus.value = RenderEngine.Status()
        audioLive.value = BeatEngine.Snapshot()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun modeName(mode: Int): String = when (mode) {
        Modes.RAINBOW -> "Rainbow"
        Modes.AURORA -> "Aurora"
        Modes.BREATHE -> "Breathe"
        Modes.SOLID -> "Solid"
        Modes.DUAL -> "Dual ring"
        Modes.SPIN -> "Spin"
        Modes.FLOW -> "Flow"
        Modes.FIRE -> "Ember"
        Modes.ICE -> "Ice"
        Modes.POLICE -> "Pulse"
        Modes.CANDLE -> "Candle"
        Modes.AUDIO -> "Audio"
        else -> "Ring"
    }

    companion object {
        private const val TAG = "AuroraService"
        const val CHANNEL_ID = "aurora_ring_service"
        private const val NOTIF_ID = 77

        const val ACTION_START = "dev.aurora.ring.action.START"
        const val ACTION_STOP = "dev.aurora.ring.action.STOP"
        const val ACTION_RELOAD = "dev.aurora.ring.action.RELOAD"
        const val ACTION_NOTIFY = "dev.aurora.ring.action.NOTIFY"
        const val ACTION_REPAIR = "dev.aurora.ring.action.REPAIR"
        const val ACTION_RESET_HW = "dev.aurora.ring.action.RESET_HW"
        const val ACTION_TEST = "dev.aurora.ring.action.TEST"
        const val ACTION_RESET_TEMPO = "dev.aurora.ring.action.RESET_TEMPO"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"

        val engineStatus = MutableStateFlow(RenderEngine.Status())
        val audioLive = MutableStateFlow(BeatEngine.Snapshot())

        @Volatile
        var instance: RingService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, RingService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RingService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        fun reload(context: Context) {
            val intent = Intent(context, RingService::class.java).setAction(ACTION_RELOAD)
            runCatching { context.startForegroundService(intent) }
        }

        fun repair(context: Context) {
            val intent = Intent(context, RingService::class.java).setAction(ACTION_REPAIR)
            runCatching { context.startForegroundService(intent) }
        }

        fun resetHardware(context: Context) {
            val intent = Intent(context, RingService::class.java).setAction(ACTION_RESET_HW)
            runCatching { context.startService(intent) }
        }

        fun test(context: Context) {
            val intent = Intent(context, RingService::class.java).setAction(ACTION_TEST)
            runCatching { context.startForegroundService(intent) }
        }

        fun resetTempo(context: Context) {
            val service = instance
            if (service != null) {
                service.audio.resetTempo()
                return
            }
            val intent = Intent(context, RingService::class.java).setAction(ACTION_RESET_TEMPO)
            runCatching { context.startService(intent) }
        }

        fun dispatchNotification(context: Context, pkg: String, label: String?) {
            val service = instance
            if (service != null) {
                service.engine.triggerNotification(pkg, label)
                return
            }
            if (!Prefs.load(context).running) return
            val intent = Intent(context, RingService::class.java)
                .setAction(ACTION_NOTIFY)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_LABEL, label)
            runCatching { context.startForegroundService(intent) }
        }
    }
}
