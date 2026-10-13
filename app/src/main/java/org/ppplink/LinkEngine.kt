package org.ppplink

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns one link session: the serial line (USB or debug TCP), the current slirp
 * process and the loops that copy bytes between them.
 *
 * Threading: every state change runs on [control], a single-threaded
 * dispatcher, so start/stop/USB/network events never interleave. The copy loops
 * and the process watcher run on Dispatchers.IO and only touch shared state
 * through @Volatile fields, checking identity (line === l, slirp === s) so a
 * loop from an old session can never act on a newer one.
 */
class LinkEngine(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "org.ppplink.USB_PERMISSION"
        private const val SIGTERM = 15
        private const val SIGKILL = 9
        private const val RESTART_DELAY_MS = 500L
        private const val FALLBACK_DNS = "1.1.1.1"

        var nativeLoaded: Boolean = false
            private set
        var nativeLoadError: String? = null
            private set

        init {
            try {
                System.loadLibrary("ppplinknative")
                nativeLoaded = true
            } catch (e: Throwable) {
                nativeLoadError = e.message ?: "Failed to load libppplinknative.so"
                LogBuffer.log("FATAL: $nativeLoadError")
            }
        }
    }

    sealed interface StartResult {
        data object Started : StartResult
        data object WaitingForPermission : StartResult
        data class Failed(val message: String) : StartResult
    }

    // Native JNI methods implemented in ppplinknative.c
    private external fun nativeStart(exe: String, args: Array<String>, env: Array<String>): IntArray
    private external fun nativeWait(pid: Int): Int
    private external fun nativeKill(pid: Int, signal: Int)

    /** Called when the engine gives up (e.g. permission denied); the service should stop. */
    var onFatal: ((String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val control = Dispatchers.IO.limitedParallelism(1)

    /** One slirp process and its pty. */
    private class Slirp(
        val pid: Int,
        private val master: ParcelFileDescriptor,
        private val stderr: ParcelFileDescriptor
    ) {
        val input = FileInputStream(master.fileDescriptor)
        val output = FileOutputStream(master.fileDescriptor)
        val stderrInput = FileInputStream(stderr.fileDescriptor)
        @Volatile var exited = false
        @Volatile var closed = false

        fun close() {
            if (closed) return
            closed = true
            try { master.close() } catch (_: Exception) {}
            try { stderr.close() } catch (_: Exception) {}
        }
    }

    @Volatile private var wanted = false               // user wants the link running
    @Volatile private var settings = PppLinkSettings()
    @Volatile private var line: SerialLine? = null
    @Volatile private var slirp: Slirp? = null
    /** True while modem emulation is answering AT commands (slirp output is held back). */
    @Volatile private var atPhase = false
    /** A USB permission dialog is already showing; don't stack another. */
    @Volatile private var permissionPending = false
    /** The client has negotiated PPP (LCP/IPCP) with the current slirp process. */
    @Volatile private var negotiated = false
    @Volatile private var staleLinkWarned = false
    /** State to return to when the client starts accepting data again. */
    @Volatile private var stateBeforeStall: LinkState? = null
    /** DNS change already reported for the current slirp (avoids log spam). */
    @Volatile private var notedDns: String? = null

    private var lineReader: Job? = null
    private var publisher: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Local DNS relay used in Automatic DNS mode (null = slirp talks to a DNS server directly). */
    private var dnsRelay: DnsRelay? = null
    private val bytesIn = AtomicLong()
    private val bytesOut = AtomicLong()

    private val sniffer = PppSniffer { onPppEvent(it) }

    // ---------------------------------------------------------------- public API

    suspend fun start(newSettings: PppLinkSettings): StartResult = withContext(control) {
        if (!nativeLoaded) {
            val msg = nativeLoadError ?: "Native library not loaded"
            LinkStatus.setState(LinkState.Error(msg))
            return@withContext StartResult.Failed(msg)
        }
        if (wanted) return@withContext StartResult.Started

        settings = newSettings
        wanted = true
        permissionPending = false
        bytesIn.set(0)
        bytesOut.set(0)
        LinkStatus.setBytes(0, 0)
        LinkStatus.setConnectedSince(null)
        startPublisher()
        startDnsRelay()
        registerNetworkCallback()

        when (val r = openLine()) {
            is OpenResult.Ok -> {
                if (beginSession(r.line)) StartResult.Started
                else failStart("Could not start slirp")
            }
            OpenResult.NoAdapter -> failStart("No USB serial adapter connected", LinkState.NoAdapter)
            OpenResult.NeedsPermission -> {
                LinkStatus.setState(LinkState.NeedsPermission)
                StartResult.WaitingForPermission
            }
            is OpenResult.Failed -> failStart(r.message)
        }
    }

    suspend fun stop(finalState: LinkState? = LinkState.Idle) = withContext(control) {
        wanted = false
        endSession()
        unregisterNetworkCallback()
        stopDnsRelay()
        publisher?.cancel()
        publisher = null
        LinkStatus.setBytes(bytesIn.get(), bytesOut.get())
        if (finalState != null) LinkStatus.setState(finalState)
        LogBuffer.log("Link stopped.")
    }

    suspend fun onUsbAttached(device: UsbDevice) = withContext(control) {
        if (!wanted || line != null || settings.debugTcpBridge || !UsbAdapters.isSupported(device)) {
            return@withContext
        }
        LogBuffer.log("Adapter attached: ${UsbAdapters.describe(device)}")
        reconnect()
    }

    suspend fun onUsbDetached(device: UsbDevice) = withContext(control) {
        val l = line ?: return@withContext
        if (l.usbDeviceName != device.deviceName) return@withContext   // not our adapter
        LogBuffer.log("Adapter unplugged.")
        endSession()
        LinkStatus.setAdapterName("None")
        if (wanted) LinkStatus.setState(LinkState.AdapterUnplugged)
    }

    suspend fun onPermissionResult(device: UsbDevice, granted: Boolean) = withContext(control) {
        permissionPending = false
        if (!wanted || line != null) return@withContext
        if (granted) {
            LogBuffer.log("USB permission granted for ${UsbAdapters.describe(device)}")
            reconnect()
        } else {
            LogBuffer.log("USB permission denied.")
            wanted = false
            endSession()
            unregisterNetworkCallback()
            stopDnsRelay()
            LinkStatus.setState(LinkState.Error("USB permission denied"))
            onFatal?.invoke("USB permission denied")
        }
    }

    // ---------------------------------------------------------------- session control (on [control])

    private fun failStart(message: String, state: LinkState = LinkState.Error(message)): StartResult {
        LogBuffer.log("Start failed: $message")
        wanted = false
        endSession()
        unregisterNetworkCallback()
        stopDnsRelay()
        publisher?.cancel()
        publisher = null
        LinkStatus.setState(state)
        return StartResult.Failed(message)
    }

    /** Re-open the adapter after an unplug or a permission grant, while the link is wanted. */
    private fun reconnect() {
        when (val r = openLine()) {
            is OpenResult.Ok -> {
                if (beginSession(r.line)) LogBuffer.log("Link resumed.")
            }
            OpenResult.NoAdapter -> LinkStatus.setState(LinkState.AdapterUnplugged)
            OpenResult.NeedsPermission -> LinkStatus.setState(LinkState.NeedsPermission)
            is OpenResult.Failed -> LinkStatus.setState(LinkState.Error("${r.message} - replug adapter"))
        }
    }

    private fun beginSession(l: SerialLine): Boolean {
        line = l
        LinkStatus.setAdapterName(l.description)
        lineReader = launchLineReader(l)
        if (!startSlirp(l)) {
            endSession()
            return false
        }
        return true
    }

    /** Close the line and kill slirp. Leaves [wanted] alone. */
    private fun endSession() {
        val l = line
        line = null                       // makes the reader loop exit
        lineReader?.cancel()
        lineReader = null
        l?.close()
        killSlirp()
        atPhase = false
        stateBeforeStall = null
        LinkStatus.setConnectedSince(null)
    }

    private fun killSlirp() {
        val s = slirp ?: return
        slirp = null
        LogBuffer.log("Stopping slirp pid=${s.pid}")
        nativeKill(s.pid, SIGTERM)
        s.close()
        scope.launch {
            delay(2000)
            // Only while unreaped: the PID can't have been reused yet.
            if (!s.exited) nativeKill(s.pid, SIGKILL)
        }
    }

    private sealed interface OpenResult {
        data class Ok(val line: SerialLine) : OpenResult
        data object NoAdapter : OpenResult
        data object NeedsPermission : OpenResult
        data class Failed(val message: String) : OpenResult
    }

    private fun openLine(): OpenResult {
        val s = settings
        if (s.debugTcpBridge) {
            return try {
                val l = TcpLine.open(s.debugBridgePort)
                LogBuffer.log("Debug TCP bridge listening on 127.0.0.1:${s.debugBridgePort} (USB not used)")
                OpenResult.Ok(l)
            } catch (e: Exception) {
                OpenResult.Failed("Debug bridge: can't listen on port ${s.debugBridgePort}: ${e.message}")
            }
        }

        val driver = UsbAdapters.findDriver(context) ?: return OpenResult.NoAdapter
        val device = driver.device
        LinkStatus.setAdapterName(UsbAdapters.describe(device))

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) {
            if (!permissionPending) {
                LogBuffer.log("Requesting USB permission for ${UsbAdapters.describe(device)}")
                permissionPending = true
                requestPermission(usbManager, device)
            }
            return OpenResult.NeedsPermission
        }

        val connection = usbManager.openDevice(device)
            ?: return OpenResult.Failed("Could not open USB device")
        val p = driver.ports[0]
        try {
            p.open(connection)
            p.setParameters(s.baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            if (s.hardwareFlowControl && p.supportedFlowControl.contains(UsbSerialPort.FlowControl.RTS_CTS)) {
                p.setFlowControl(UsbSerialPort.FlowControl.RTS_CTS)
                LogBuffer.log("Hardware flow control (RTS/CTS) on")
            } else {
                p.setFlowControl(UsbSerialPort.FlowControl.NONE)
                LogBuffer.log(if (s.hardwareFlowControl) "RTS/CTS not supported by this adapter - flow control off"
                              else "Flow control off")
            }
            p.dtr = true
            p.rts = true
            LogBuffer.log("USB port open: ${s.baudRate} 8N1, DTR=1, RTS=1")
            return OpenResult.Ok(UsbLine(p))
        } catch (e: Exception) {
            try { p.close() } catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
            return OpenResult.Failed("USB port setup failed: ${e.message}")
        }
    }

    private fun requestPermission(usbManager: UsbManager, device: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    private fun startSlirp(l: SerialLine): Boolean {
        val exe = File(context.applicationInfo.nativeLibraryDir, "libslirp.so")
        if (!exe.exists() || !exe.canExecute()) {
            LinkStatus.setState(LinkState.Error("libslirp.so missing or not executable"))
            LogBuffer.log("Error: ${exe.absolutePath} missing or not executable")
            return false
        }

        val s = settings
        val relay = dnsRelay
        val dns = if (relay != null) "127.0.0.1" else determineDnsServer(s)
        LinkStatus.setDns(if (relay != null) "Phone's DNS (follows network)" else dns)
        writeSlirpConfig(s, dns, relay?.port)

        val res = try {
            // argv[0] is added by the native side; only pass the real arguments.
            nativeStart(exe.absolutePath, arrayOf("ppp"), arrayOf("HOME=${context.filesDir.absolutePath}"))
        } catch (e: Throwable) {
            LogBuffer.log("nativeStart threw: ${e.message}")
            null
        }
        if (res == null || res.size < 3 || res[0] <= 0) {
            LinkStatus.setState(LinkState.Error("Failed to start slirp"))
            return false
        }

        val proc = Slirp(res[0], ParcelFileDescriptor.adoptFd(res[1]), ParcelFileDescriptor.adoptFd(res[2]))
        atPhase = s.connectionMode != "Direct"
        negotiated = false
        staleLinkWarned = false
        stateBeforeStall = null
        notedDns = null
        slirp = proc
        launchStderrReader(proc)
        launchSlirpToLine(proc, l)
        launchWatcher(proc, l)
        LinkStatus.setConnectedSince(null)
        LinkStatus.setState(LinkState.Ready)
        LogBuffer.log("slirp started pid=${proc.pid}" +
            if (atPhase) ", modem emulation: waiting for AT commands" else ", waiting for client")
        return true
    }

    // ---------------------------------------------------------------- I/O loops (on Dispatchers.IO)

    /** The only reader of the line for the whole session; survives slirp restarts. */
    private fun launchLineReader(l: SerialLine): Job = scope.launch {
        val buf = ByteArray(4096)
        val at = StringBuilder()
        var current: Slirp? = null

        while (isActive && line === l) {
            val n = try {
                l.read(buf, 200)
            } catch (e: IOException) {
                if (line === l) withContext(control) { onLineFailure(l, "Read failed: ${e.message}") }
                break
            }
            if (n < 0) {
                // Debug bridge client went away: treat as the client hanging up.
                slirp?.let { nativeKill(it.pid, SIGTERM) }
                continue
            }
            if (n == 0) continue
            bytesIn.addAndGet(n.toLong())

            val latest = slirp
            if (latest !== current) {            // new slirp session: fresh parser state
                current = latest
                sniffer.reset()
                at.setLength(0)
            }
            val s = latest ?: continue            // between slirp sessions: nothing to deliver to
            if (s.closed) continue

            if (settings.debugHexLogging) LogBuffer.logHex("client->slirp", buf, n)

            var start = 0
            if (atPhase) {
                start = handleAtBytes(l, buf, n, at)
                if (atPhase) continue             // still all AT traffic
            }
            val len = n - start
            if (len <= 0) continue
            sniffer.processBytes(buf, start, len)
            try {
                s.output.write(buf, start, len)
            } catch (_: IOException) {
                // slirp is exiting; the watcher restarts it.
            }
        }
    }

    /**
     * Modem emulation. Answers AT commands; returns the index in [buf] where
     * PPP data starts (and clears [atPhase]) once the client dials or sends a frame.
     */
    private fun handleAtBytes(l: SerialLine, buf: ByteArray, n: Int, at: StringBuilder): Int {
        var i = 0
        while (i < n && atPhase) {
            val b = buf[i].toInt() and 0xFF
            when (b) {
                0x7E -> {
                    LogBuffer.log("Modem emulation: PPP frame seen, switching to PPP")
                    atPhase = false
                    return i                      // include the 0x7E
                }
                0x0D -> {
                    val cmd = at.toString().replace(" ", "").uppercase()
                    at.setLength(0)
                    try {
                        if (cmd.startsWith("ATD")) {
                            writeLine(l, "\r\nCONNECT ${settings.baudRate}\r\n".toByteArray())
                            LogBuffer.log("Modem emulation: $cmd -> CONNECT")
                            atPhase = false
                            return i + 1                  // PPP starts after the CR
                        } else if (cmd.startsWith("AT")) {
                            writeLine(l, "\r\nOK\r\n".toByteArray())
                            LogBuffer.log("Modem emulation: $cmd -> OK")
                        }
                    } catch (e: IOException) {
                        LogBuffer.log("Modem emulation: write failed: ${e.message}")
                    }
                }
                0x0A -> Unit
                else -> if (at.length < 256) at.append(b.toChar())
            }
            i++
        }
        return n
    }

    private fun launchSlirpToLine(s: Slirp, l: SerialLine) = scope.launch {
        val buf = ByteArray(4096)
        while (isActive) {
            val n = try { s.input.read(buf) } catch (_: IOException) { break }   // EIO once slirp exits
            if (n < 0) break
            if (n == 0) continue
            if (atPhase) continue        // hold slirp's LCP back until the client has "dialled"
            if (settings.debugHexLogging) LogBuffer.logHex("slirp->client", buf, n)
            try {
                writeLine(l, buf, n)
            } catch (_: IOException) {
                break                    // line gone: the reader reports it
            }
        }
    }

    /**
     * Sends to the client. If the client is holding the line (asleep, busy), the
     * data is dropped rather than blocking: PPP and TCP resend what matters, and
     * slirp's LCP keepalives end the link if the client never comes back.
     */
    private fun writeLine(l: SerialLine, data: ByteArray, len: Int = data.size) {
        val sent = l.write(data, len)
        bytesOut.addAndGet(sent.toLong())
        if (line === l) updateStall(l.stalled)
    }

    private fun updateStall(stalled: Boolean) {
        val current = LinkStatus.state.value
        if (stalled) {
            if (current != LinkState.ClientNotReady && stateBeforeStall == null) {
                stateBeforeStall = current
                LinkStatus.setState(LinkState.ClientNotReady)
                LogBuffer.log("Client not accepting data (CTS low) - holding the link")
            }
        } else {
            val previous = stateBeforeStall ?: return
            stateBeforeStall = null
            if (LinkStatus.state.value == LinkState.ClientNotReady) LinkStatus.setState(previous)
            LogBuffer.log("Client accepting data again")
        }
    }

    private fun launchStderrReader(s: Slirp) = scope.launch {
        try {
            BufferedReader(InputStreamReader(s.stderrInput)).use { reader ->
                while (true) {
                    val text = reader.readLine() ?: break
                    if (text.isNotBlank()) LogBuffer.log("[slirp] $text")
                }
            }
        } catch (_: Exception) {}
    }

    private fun launchWatcher(s: Slirp, l: SerialLine) = scope.launch {
        val code = nativeWait(s.pid)          // blocks this IO thread only
        s.exited = true
        LogBuffer.log("slirp (pid=${s.pid}) exited with code $code")

        val restart = withContext(control) {
            s.close()
            if (slirp !== s) return@withContext false     // stale: stopped or replaced already
            slirp = null
            stateBeforeStall = null
            LinkStatus.setConnectedSince(null)
            if (!wanted || line !== l) return@withContext false
            LinkStatus.setState(LinkState.Ready)
            LogBuffer.log("PPP link ended - restarting slirp")
            true
        }
        if (!restart) return@launch

        delay(RESTART_DELAY_MS)
        withContext(control) {
            if (wanted && line === l && slirp == null) startSlirp(l)
        }
    }

    /** Called on [control] when the line's reader fails. */
    private fun onLineFailure(l: SerialLine, message: String) {
        if (line !== l) return
        LogBuffer.log(message)
        val name = l.usbDeviceName
        val stillAttached = if (name == null) false else {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            usbManager.deviceList.containsKey(name)
        }
        endSession()
        if (!wanted) return
        if (name != null && !stillAttached) {
            LinkStatus.setAdapterName("None")
            LinkStatus.setState(LinkState.AdapterUnplugged)
        } else {
            LinkStatus.setState(LinkState.Error("$message - " +
                if (name == null) "restart the link" else "replug adapter"))
        }
    }

    // ---------------------------------------------------------------- status

    /** Runs on the line reader thread. */
    private fun onPppEvent(e: PppSniffer.Event) {
        if (!wanted) return
        when (e) {
            PppSniffer.Event.NEGOTIATING -> {
                negotiated = true
                if (stateBeforeStall != null) { stateBeforeStall = LinkState.Negotiating; return }
                if (LinkStatus.state.value != LinkState.Negotiating) {
                    LinkStatus.setConnectedSince(null)
                    LinkStatus.setState(LinkState.Negotiating)
                }
            }
            PppSniffer.Event.IP_TRAFFIC -> {
                if (!negotiated) {
                    // IP from a PPP session this slirp never negotiated: the client
                    // still thinks the previous link is up. slirp drops these packets.
                    if (!staleLinkWarned) {
                        staleLinkWarned = true
                        LogBuffer.log("Client is still using the previous PPP link - disconnect and reconnect on the client")
                        LinkStatus.setState(LinkState.Error("Client on old link - reconnect on client"))
                    }
                    return
                }
                if (stateBeforeStall != null) {
                    // Traffic from the client while stalled: it's awake; remember Connected.
                    if (stateBeforeStall != LinkState.Connected) {
                        stateBeforeStall = LinkState.Connected
                        if (LinkStatus.connectedSince.value == null) {
                            LinkStatus.setConnectedSince(SystemClock.elapsedRealtime())
                        }
                    }
                    return
                }
                if (LinkStatus.state.value != LinkState.Connected) {
                    if (LinkStatus.connectedSince.value == null) {
                        LinkStatus.setConnectedSince(SystemClock.elapsedRealtime())
                        LogBuffer.log("PPP up: client is sending IP traffic")
                    }
                    LinkStatus.setState(LinkState.Connected)
                }
            }
            PppSniffer.Event.TERMINATE -> LogBuffer.log("PPP: client sent LCP Terminate-Request")
        }
    }

    private fun startPublisher() {
        publisher?.cancel()
        publisher = scope.launch {
            while (isActive) {
                LinkStatus.setBytes(bytesIn.get(), bytesOut.get())
                delay(500)
            }
        }
    }

    // ---------------------------------------------------------------- DNS and network changes

    private fun determineDnsServer(s: PppLinkSettings): String {
        if (s.dnsMode != "Automatic") {
            val custom = s.customDns.trim()
            if (isValidIpv4(custom)) return custom
            LogBuffer.log("Custom DNS '$custom' is not a valid IPv4 address - using automatic")
        }
        return currentNetworkDns() ?: FALLBACK_DNS
    }

    private fun currentNetworkDns(): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return null
            val v4 = cm.getLinkProperties(net)?.dnsServers?.firstOrNull { it is Inet4Address }
            val addr = v4?.hostAddress
            if (addr != null && isValidIpv4(addr)) return addr
        } catch (e: Exception) {
            LogBuffer.log("DNS lookup fallback: ${e.message}")
        }
        return null
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                scope.launch { withContext(control) { onNetworkChanged() } }
            }
            override fun onLost(network: Network) {
                scope.launch { withContext(control) { onNetworkChanged() } }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            LogBuffer.log("Could not watch network changes: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    /** On [control]. Applies a new automatic DNS server when the phone changes network. */
    private fun onNetworkChanged() {
        if (!wanted || settings.dnsMode != "Automatic") return
        if (dnsRelay != null) return          // the relay follows the network by itself
        val newDns = currentNetworkDns() ?: FALLBACK_DNS
        val inUse = LinkStatus.dnsInUse.value
        if (newDns == inUse || newDns == notedDns) return
        notedDns = newDns
        val s = slirp
        if (s != null && !negotiated) {
            // client not connected yet: restart slirp now so it gets the new DNS.
            LogBuffer.log("Network changed: DNS $inUse -> $newDns, restarting slirp to apply")
            nativeKill(s.pid, SIGTERM)
        } else if (s != null) {
            LogBuffer.log("Network changed: DNS is now $newDns. It applies from the client's next " +
                "connection; until then the client's secondary DNS (1.1.1.1) still works.")
        }
    }

    private fun startDnsRelay() {
        if (dnsRelay != null || settings.dnsMode != "Automatic") return
        dnsRelay = try {
            DnsRelay.start(context)
        } catch (e: Exception) {
            LogBuffer.log("DNS relay unavailable (${e.message}) - using the network's DNS server directly")
            null
        }
    }

    private fun stopDnsRelay() {
        val r = dnsRelay ?: return
        dnsRelay = null
        LogBuffer.log("DNS relay stopped: ${r.queries.get()} queries, ${r.failures.get()} failed")
        r.stop()
    }

    // ---------------------------------------------------------------- slirp config

    private fun writeSlirpConfig(s: PppLinkSettings, dns: String, dnsPort: Int?) {
        val lines = mutableListOf(
            "ppp_exit",
            // No "type five zeroes to exit" / "five ones to detach" escape:
            // a dial-up shell-account feature with no place in an app.
            "nozeros",
            "dns $dns",
            "baudrate ${s.baudRate}",
            // Keepalives: if the client stops answering (cable pulled, switched off),
            // slirp drops PPP after ~40-50 s, exits (ppp_exit) and is restarted.
            "lcp-echo-interval 10",
            "lcp-echo-failure 4"
        )
        if (dnsPort != null) lines.add("dnsport $dnsPort")   // patched slirp: send DNS to the relay
        if (s.mtuMru != 1500) {
            lines.add("mtu ${s.mtuMru}")
            lines.add("mru ${s.mtuMru}")
        }
        File(context.filesDir, ".slirprc").writeText(lines.joinToString("\n") + "\n")
        LogBuffer.log("slirp config: dns=$dns${if (dnsPort != null) ":$dnsPort (relay)" else ""}, " +
            "baud=${s.baudRate}, mtu=${s.mtuMru}")
    }
}
