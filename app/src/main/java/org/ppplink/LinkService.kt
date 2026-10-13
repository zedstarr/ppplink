package org.ppplink

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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LinkService : Service() {

    companion object {
        const val CHANNEL_ID = "ppplink_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "org.ppplink.action.START"
        const val ACTION_STOP = "org.ppplink.action.STOP"
        const val ACTION_USB_ATTACHED = "org.ppplink.action.USB_ATTACHED"

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        val isRunning: Boolean get() = _isRunningFlow.value
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationJob: Job? = null
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var engine: LinkEngine

    /** USB plug/unplug and permission results, handled here so they work with no activity. */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = intent?.usbDevice() ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    serviceScope.launch { engine.onUsbDetached(device) }
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    serviceScope.launch { engine.onUsbAttached(device) }
                LinkEngine.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    serviceScope.launch { engine.onPermissionResult(device, granted) }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        engine = LinkEngine(applicationContext)
        engine.onFatal = { _ ->
            serviceScope.launch { shutDown(keepState = true) }
        }
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PppLink::WakeLock").apply {
            setReferenceCounted(false)
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(LinkEngine.ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutDown(keepState = false)
                return START_NOT_STICKY
            }
            ACTION_USB_ATTACHED -> {
                val device = intent.usbDevice()
                if (isRunning && device != null) {
                    serviceScope.launch { engine.onUsbAttached(device) }
                } else {
                    startBridge()
                }
            }
            else -> startBridge()          // ACTION_START, or a sticky restart (null intent)
        }
        return START_STICKY
    }

    private fun startBridge() {
        if (isRunning) return
        _isRunningFlow.value = true

        renewWakeLock()

        val initialNotification = buildNotification("Starting...", 0L, 0L)

        // connectedDevice is the honest type for a USB serial link. Its Android 14+
        // prerequisite is met by CHANGE_NETWORK_STATE (declared in the manifest).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            LogBuffer.log("Could not start foreground service: ${e.message}")
            LinkStatus.setState(LinkState.Error("Could not start foreground service"))
            shutDown(keepState = true)
            return
        }

        startNotificationUpdater()

        serviceScope.launch {
            val result = try {
                engine.start(settingsRepo.settingsFlow.first())
            } catch (e: Throwable) {
                LogBuffer.log("Engine start error: ${e.message}")
                LinkStatus.setState(LinkState.Error("Start failed: ${e.message}"))
                LinkEngine.StartResult.Failed(e.message ?: "unknown")
            }
            if (result is LinkEngine.StartResult.Failed) {
                // Engine has already cleaned up and set the state; just stop running.
                shutDown(keepState = true)
            }
        }
    }

    /** Stops the link and the service. keepState=true leaves an error/no-adapter state visible. */
    private fun shutDown(keepState: Boolean) {
        if (isRunning) {
            _isRunningFlow.value = false
            notificationJob?.cancel()
            notificationJob = null
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                LogBuffer.log("stopForeground error: ${e.message}")
            }
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        // Runs in the engine's own scope, so it completes even as the service is destroyed.
        val e = engine
        CoroutineScope(Dispatchers.IO).launch {
            e.stop(if (keepState) null else LinkState.Idle)
        }
        stopSelf()
    }

    /** Holds the CPU awake for 2 h; re-called every 30 min while running so it never lapses. */
    private fun renewWakeLock() {
        try {
            wakeLock?.acquire(2 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            LogBuffer.log("WakeLock error: ${e.message}")
        }
    }

    private fun startNotificationUpdater() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var ticks = 0
            while (isActive && isRunning) {
                if (++ticks % 1800 == 0) renewWakeLock()
                val n = buildNotification(
                    LinkStatus.state.value.label(),
                    LinkStatus.bytesIn.value,
                    LinkStatus.bytesOut.value
                )
                nm.notify(NOTIFICATION_ID, n)
                delay(1000)
            }
        }
    }

    private fun buildNotification(stateText: String, bytesIn: Long, bytesOut: Long): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, LinkService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PPP Link: $stateText")
            .setContentText("From client: ${formatBytes(bytesIn)} | To client: ${formatBytes(bytesOut)}")
            .setSmallIcon(R.drawable.ic_stat_ppplink)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notification_channel_desc) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        if (isRunning) shutDown(keepState = false)
        serviceScope.cancel()
        super.onDestroy()
    }
}

@Suppress("DEPRECATION")
fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
    bytes >= 1024 -> String.format("%.1f KB", bytes.toDouble() / 1024)
    else -> "$bytes B"
}
