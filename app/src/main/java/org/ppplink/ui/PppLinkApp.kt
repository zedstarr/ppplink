package org.ppplink.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ppplink.*
import com.hoho.android.usbserial.driver.UsbSerialPort

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PppLinkApp(
    settingsRepo: SettingsRepository,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val settings by settingsRepo.settingsFlow.collectAsState(initial = PppLinkSettings())
    val isRunning by LinkService.isRunningFlow.collectAsState()
    val linkState by LinkStatus.state.collectAsState()
    val bytesIn by LinkStatus.bytesIn.collectAsState()
    val bytesOut by LinkStatus.bytesOut.collectAsState()
    val adapterName by LinkStatus.adapterName.collectAsState()
    val dnsInUse by LinkStatus.dnsInUse.collectAsState()
    val connectedSince by LinkStatus.connectedSince.collectAsState()
    var initialRunningSettings by remember { mutableStateOf<PppLinkSettings?>(null) }

    LaunchedEffect(isRunning) {
        initialRunningSettings = if (isRunning) settings else null
    }

    val settingsChangedWhileRunning = isRunning && initialRunningSettings != null && initialRunningSettings != settings

    var showAboutDialog by remember { mutableStateOf(false) }
    var showLicences by remember { mutableStateOf(false) }
    var isLogExpanded by remember { mutableStateOf(true) }
    var isHelpExpanded by remember { mutableStateOf(false) }
    var isSettingsExpanded by remember { mutableStateOf(false) }

    // Time since PPP came up (ticks once a second while connected)
    var connectedSeconds by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(connectedSince) {
        val since = connectedSince
        if (since == null) {
            connectedSeconds = null
        } else {
            while (true) {
                connectedSeconds = (android.os.SystemClock.elapsedRealtime() - since) / 1000
                delay(1000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PPP Link", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "About")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. Status Card
            StatusCardView(
                isRunning = isRunning,
                linkState = linkState,
                connectedSeconds = connectedSeconds,
                adapterName = adapterName,
                dnsInUse = dnsInUse,
                bytesIn = bytesIn,
                bytesOut = bytesOut
            )

            // 2. Start / Stop Button (Full width)
            Button(
                onClick = {
                    if (isRunning) {
                        onStopService()
                    } else {
                        onStartService()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRunning) "STOP LINK" else "START LINK",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (settingsChangedWhileRunning) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Settings changed while running: Restart to apply",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 3. Settings Card (Saved with DataStore)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Connection Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { isSettingsExpanded = !isSettingsExpanded }) {
                            Icon(
                                imageVector = if (isSettingsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle Settings"
                            )
                        }
                    }

                    AnimatedVisibility(visible = isSettingsExpanded) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Baud rate
                            SettingDropdown(
                                label = "Baud rate",
                                current = settings.baudRate.toString(),
                                options = listOf("9600", "19200", "38400", "57600", "115200"),
                                onSelect = { coroutineScope.launch { settingsRepo.updateBaudRate(it.toInt()) } }
                            )

                            // Hardware flow control
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // null = no adapter plugged in (can't tell yet)
                                val rtsSupported: Boolean? = remember(adapterName, isRunning, isSettingsExpanded) {
                                    UsbAdapters.findDriver(context)?.ports?.firstOrNull()
                                        ?.supportedFlowControl?.contains(UsbSerialPort.FlowControl.RTS_CTS)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Hardware Flow Control (RTS/CTS)", fontWeight = FontWeight.Medium)
                                    Text(
                                        if (rtsSupported == false) "Not supported by this adapter"
                                        else "Prevents buffer overruns at 115200 baud",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                                Switch(
                                    checked = settings.hardwareFlowControl && rtsSupported != false,
                                    enabled = rtsSupported != false,
                                    onCheckedChange = { coroutineScope.launch { settingsRepo.updateHardwareFlow(it) } }
                                )
                            }

                            // Connection mode
                            SettingDropdown(
                                label = "Connection Mode",
                                current = settings.connectionMode,
                                options = listOf("Direct", "Modem emulation"),
                                onSelect = { coroutineScope.launch { settingsRepo.updateConnectionMode(it) } }
                            )

                            // MTU / MRU
                            SettingDropdown(
                                label = "MTU / MRU",
                                current = settings.mtuMru.toString(),
                                options = listOf("1500", "1006", "576"),
                                onSelect = { coroutineScope.launch { settingsRepo.updateMtuMru(it.toInt()) } }
                            )

                            // DNS server
                            SettingDropdown(
                                label = "DNS server",
                                current = settings.dnsMode,
                                options = listOf("Automatic", "Custom IPv4"),
                                onSelect = { coroutineScope.launch { settingsRepo.updateDnsMode(it) } }
                            )

                            if (settings.dnsMode == "Custom IPv4") {
                                // Edit locally; only valid addresses are saved.
                                var dnsText by remember { mutableStateOf(settings.customDns) }
                                val dnsValid = isValidIpv4(dnsText)
                                OutlinedTextField(
                                    value = dnsText,
                                    onValueChange = {
                                        dnsText = it
                                        if (isValidIpv4(it)) coroutineScope.launch { settingsRepo.updateCustomDns(it.trim()) }
                                    },
                                    label = { Text("Custom DNS IP") },
                                    isError = !dnsValid,
                                    supportingText = { if (!dnsValid) Text("Enter an IPv4 address, e.g. 1.1.1.1") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Start on plug
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Start when adapter is plugged in", fontWeight = FontWeight.Medium)
                                Switch(
                                    checked = settings.startOnPlug,
                                    onCheckedChange = { coroutineScope.launch { settingsRepo.updateStartOnPlug(it) } }
                                )
                            }

                            // Debug hex logging
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Debug Hex Logging", fontWeight = FontWeight.Medium)
                                    Text("Logs first 64 bytes of each packet", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Switch(
                                    checked = settings.debugHexLogging,
                                    onCheckedChange = { coroutineScope.launch { settingsRepo.updateDebugHex(it) } }
                                )
                            }

                            // Debug TCP bridge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Debug TCP Bridge", fontWeight = FontWeight.Medium)
                                    Text("Use a TCP client on 127.0.0.1 instead of USB (testing)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Switch(
                                    checked = settings.debugTcpBridge,
                                    onCheckedChange = { coroutineScope.launch { settingsRepo.updateDebugTcpBridge(it) } }
                                )
                            }
                            if (settings.debugTcpBridge) {
                                var portText by remember { mutableStateOf(settings.debugBridgePort.toString()) }
                                val portNum = portText.toIntOrNull()
                                val portValid = portNum != null && portNum in 1024..65535
                                OutlinedTextField(
                                    value = portText,
                                    onValueChange = {
                                        portText = it.filter { c -> c.isDigit() }.take(5)
                                        val n = portText.toIntOrNull()
                                        if (n != null && n in 1024..65535) coroutineScope.launch { settingsRepo.updateDebugBridgePort(n) }
                                    },
                                    label = { Text("Bridge port") },
                                    isError = !portValid,
                                    supportingText = { if (!portValid) Text("1024-65535") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            // 4. Log Section (Collapsible, 500 lines monospace, Copy & Clear)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Log Buffer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            val logs by LogBuffer.logsFlow.collectAsState()
                            Badge { Text("${logs.size}/500") }
                        }

                        Row {
                            IconButton(onClick = {
                                val text = LogBuffer.getAllAsText()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("PPP Link Log", text))
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Log")
                            }
                            IconButton(onClick = { LogBuffer.clear() }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Log")
                            }
                            IconButton(onClick = { isLogExpanded = !isLogExpanded }) {
                                Icon(
                                    imageVector = if (isLogExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Toggle Log"
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = isLogExpanded) {
                        val logs by LogBuffer.logsFlow.collectAsState()
                        val listState = rememberLazyListState()

                        LaunchedEffect(logs.size) {
                            if (logs.isNotEmpty()) {
                                listState.animateScrollToItem(logs.size - 1)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            if (logs.isEmpty()) {
                                Text(
                                    "No log entries yet.",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            } else {
                                LazyColumn(state = listState) {
                                    items(logs) { entry ->
                                        Text(
                                            text = entry,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = when {
                                                entry.contains("Error") -> Color(0xFFEF5350)
                                                entry.contains("Connected") -> Color(0xFF81C784)
                                                entry.contains("Negotiating") -> Color(0xFFFFB74D)
                                                entry.contains("USB ->") || entry.contains("PTY ->") -> Color(0xFF90CAF9)
                                                else -> Color(0xFFCFD8DC)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Help Section (Collapsible: client-side setup verbatim & battery note)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Client Setup & Battery Note",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { isHelpExpanded = !isHelpExpanded }) {
                            Icon(
                                imageVector = if (isHelpExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle Help"
                            )
                        }
                    }

                    AnimatedVisibility(visible = isHelpExpanded) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Client-Side Setup:",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Text(
                                    text = "Set the client (the retro computer) up for PPP over its serial port:\n\n• Serial: the same speed as PPP Link, 8 data bits, no parity, 1 stop bit. Flow control Hardware (RTS/CTS) if PPP Link's RTS/CTS is on, otherwise None. Use a null-modem cable.\n• Connection: PPP. If it can start PPP straight away (direct/cable connection, no dialling), use Connection mode Direct. If it insists on dialling a modem, use Modem emulation: any phone number works and any AT init string gets OK.\n• Login: no login script or terminal window; PAP/CHAP not needed (if it insists on a username/password, any will do).\n• IP address: 10.0.2.15 (static), or obtain automatically.\n• Gateway / default route: the PPP link itself; if an address is required, 10.0.2.2.\n• DNS: 10.0.2.3 primary, 1.1.1.1 secondary.\n• MTU: leave at default; if large transfers stall, set 576 here and on the client.\n• Power saving: turn off auto switch-off while connected.\n\nThe phone itself is reachable from the client at 10.0.2.2 (e.g. an SSH server in Termux on port 8022).",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 18.sp
                                )
                            }

                            Text(
                                text = "Battery Optimization Warning:",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = "Samsung One UI kills background apps aggressively. To prevent disconnection when screen is off, set PPP Link battery usage to 'Unrestricted' in App info > Battery.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About PPP Link v1.0.0") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("PPP Link: a PPP server on your phone for retro computers. Connect any machine with a serial port and a PPP stack through a USB serial adapter, and it shares the phone's internet connection.")
                    HorizontalDivider()
                    Text("Credits", fontWeight = FontWeight.Bold)
                    Text(
                        "This product includes software developed by Danny Gasparovski (Slirp), " +
                            "maintained by Kelly Price and Cameron Kaiser (Slirp-CK), with Android " +
                            "changes by Mark Williams (slirp-termux).\n\n" +
                            "This product includes software developed by Juha Pirkola.\n\n" +
                            "This product includes software developed by the University of " +
                            "California, Berkeley and its contributors.\n\n" +
                            "PPP software developed by Carnegie Mellon University, Gregory M. " +
                            "Christy and The Australian National University.\n\n" +
                            "Derived from the RSA Data Security, Inc. MD5 Message-Digest Algorithm.\n\n" +
                            "usb-serial-for-android by Mike Wakerly and contributors (MIT). " +
                            "AndroidX, Jetpack Compose and Kotlin (Apache 2.0).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { showAboutDialog = false; showLicences = true }) {
                        Text("Open-source licences")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showLicences) {
        val licenceText = remember {
            try {
                context.assets.open("open_source_notices.txt").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "Could not load the licence file: ${e.message}"
            }
        }
        AlertDialog(
            onDismissRequest = { showLicences = false },
            title = { Text("Open-source licences") },
            text = {
                Text(
                    licenceText,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicences = false }) { Text("Close") }
            }
        )
    }
}

@Composable
fun StatusCardView(
    isRunning: Boolean,
    linkState: LinkState,
    connectedSeconds: Long?,
    adapterName: String,
    dnsInUse: String,
    bytesIn: Long,
    bytesOut: Long
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // When stopped, still show why (error / no adapter); otherwise "Idle".
            val shown: LinkState = if (isRunning) linkState else when (linkState) {
                is LinkState.Error, LinkState.NoAdapter -> linkState
                else -> LinkState.Idle
            }
            val stateColor = when (shown) {
                LinkState.Connected -> Color(0xFF2E7D32)
                LinkState.Negotiating -> Color(0xFFF9A825)
                LinkState.Ready -> Color(0xFF546E7A)
                LinkState.NeedsPermission, LinkState.AdapterUnplugged, LinkState.NoAdapter,
                LinkState.ClientNotReady -> Color(0xFFE65100)
                is LinkState.Error -> Color(0xFFC62828)
                LinkState.Idle -> Color(0xFF757575)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = stateColor,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = shown.label(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                if (connectedSeconds != null) {
                    val h = connectedSeconds / 3600
                    val m = (connectedSeconds % 3600) / 60
                    val sec = connectedSeconds % 60
                    Text(
                        text = if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
                               else String.format("%02d:%02d", m, sec),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Adapter", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(adapterName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("DNS in use", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(dnsInUse, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("From client", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(formatBytes(bytesIn), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("To client", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(formatBytes(bytesOut), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun SettingDropdown(
    label: String,
    current: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.Medium)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(current)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = {
                            onSelect(opt)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
