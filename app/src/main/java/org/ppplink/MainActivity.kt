package org.ppplink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.ppplink.ui.PppLinkApp
import org.ppplink.ui.theme.PppLinkTheme

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            LogBuffer.log(if (isGranted) "Notification permission granted" else "Notification permission denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(this)

        checkPermissionsAndBattery()

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbAttached(intent.usbDevice())
        }

        setContent {
            PppLinkTheme {
                PppLinkApp(
                    settingsRepo = settingsRepo,
                    onStartService = { startLinkService() },
                    onStopService = { stopLinkService() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbAttached(intent.usbDevice())
        }
    }

    /**
     * Launched (or re-launched) by plugging in the adapter. Android has granted
     * USB permission for this device as part of that launch.
     */
    private fun handleUsbAttached(device: UsbDevice?) {
        LogBuffer.log("Adapter plugged in: ${device?.let { UsbAdapters.describe(it) } ?: "unknown"}")
        if (LinkService.isRunning) {
            // Link already wanted (e.g. adapter was unplugged mid-session): tell the service.
            val i = Intent(this, LinkService::class.java).apply {
                action = LinkService.ACTION_USB_ATTACHED
                putExtra(UsbManager.EXTRA_DEVICE, device)
            }
            startService(i)
            return
        }
        lifecycleScope.launch {
            if (settingsRepo.settingsFlow.first().startOnPlug) {
                LogBuffer.log("Auto-start: 'Start when adapter is plugged in' is on")
                startLinkService()
            }
        }
    }

    private fun startLinkService() {
        lifecycleScope.launch {
            val bridge = settingsRepo.settingsFlow.first().debugTcpBridge
            if (!bridge && UsbAdapters.findDriver(this@MainActivity) == null) {
                LinkStatus.setState(LinkState.NoAdapter)
                LogBuffer.log("Start: no USB serial adapter connected")
                Toast.makeText(this@MainActivity, "Plug in the USB serial adapter first", Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                val intent = Intent(this@MainActivity, LinkService::class.java).apply {
                    action = LinkService.ACTION_START
                }
                ContextCompat.startForegroundService(this@MainActivity, intent)
            } catch (e: Throwable) {
                LogBuffer.log("Error starting service: ${e.message}")
                LinkStatus.setState(LinkState.Error("Could not start service: ${e.message}"))
            }
        }
    }

    private fun stopLinkService() {
        try {
            val intent = Intent(this, LinkService::class.java).apply { action = LinkService.ACTION_STOP }
            startService(intent)
        } catch (e: Throwable) {
            LogBuffer.log("Error stopping service: ${e.message}")
        }
    }

    private fun checkPermissionsAndBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Ask for the battery exemption once; after that the Help section covers it.
        val prefs = getSharedPreferences("ppplink_ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_prompted", false)) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean("battery_prompted", true).apply()
            // Play restricts the direct "allow" prompt (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
            // so open the system list instead; the user picks PPP Link > Don't optimise.
            Toast.makeText(this, "Set PPP Link to 'Not optimised' / Unrestricted so the link survives screen-off",
                Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                LogBuffer.log("Could not request battery exemption: ${e.message}")
            }
        }
    }
}
