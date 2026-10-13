package org.ppplink

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ppplink_settings")

data class PppLinkSettings(
    val baudRate: Int = 115200,
    val hardwareFlowControl: Boolean = true,
    val connectionMode: String = "Direct", // "Direct" or "Modem emulation"
    val mtuMru: Int = 1500, // 1500, 1006, 576
    val dnsMode: String = "Automatic", // "Automatic" or custom IPv4
    val customDns: String = "1.1.1.1",
    val startOnPlug: Boolean = true,
    val debugHexLogging: Boolean = false,
    val debugTcpBridge: Boolean = false,
    val debugBridgePort: Int = 8000
)

/** Strict dotted-quad IPv4 check (no hostnames, no leading/trailing junk). */
fun isValidIpv4(s: String): Boolean {
    val parts = s.trim().split(".")
    if (parts.size != 4) return false
    return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255 }
}

class SettingsRepository(private val context: Context) {
    companion object {
        val KEY_BAUD_RATE = intPreferencesKey("baud_rate")
        val KEY_HARDWARE_FLOW = booleanPreferencesKey("hardware_flow")
        val KEY_CONNECTION_MODE = stringPreferencesKey("connection_mode")
        val KEY_MTU_MRU = intPreferencesKey("mtu_mru")
        val KEY_DNS_MODE = stringPreferencesKey("dns_mode")
        val KEY_CUSTOM_DNS = stringPreferencesKey("custom_dns")
        val KEY_START_ON_PLUG = booleanPreferencesKey("start_on_plug")
        val KEY_DEBUG_HEX = booleanPreferencesKey("debug_hex")
        val KEY_DEBUG_TCP_BRIDGE = booleanPreferencesKey("debug_tcp_bridge")
        val KEY_DEBUG_BRIDGE_PORT = intPreferencesKey("debug_bridge_port")
    }

    val settingsFlow: Flow<PppLinkSettings> = context.dataStore.data.map { prefs ->
        PppLinkSettings(
            baudRate = prefs[KEY_BAUD_RATE] ?: 115200,
            hardwareFlowControl = prefs[KEY_HARDWARE_FLOW] ?: true,
            connectionMode = prefs[KEY_CONNECTION_MODE] ?: "Direct",
            mtuMru = prefs[KEY_MTU_MRU] ?: 1500,
            dnsMode = prefs[KEY_DNS_MODE] ?: "Automatic",
            customDns = prefs[KEY_CUSTOM_DNS] ?: "1.1.1.1",
            startOnPlug = prefs[KEY_START_ON_PLUG] ?: true,
            debugHexLogging = prefs[KEY_DEBUG_HEX] ?: false,
            debugTcpBridge = prefs[KEY_DEBUG_TCP_BRIDGE] ?: false,
            debugBridgePort = prefs[KEY_DEBUG_BRIDGE_PORT] ?: 8000
        )
    }

    suspend fun updateBaudRate(value: Int) {
        context.dataStore.edit { it[KEY_BAUD_RATE] = value }
    }

    suspend fun updateHardwareFlow(value: Boolean) {
        context.dataStore.edit { it[KEY_HARDWARE_FLOW] = value }
    }

    suspend fun updateConnectionMode(value: String) {
        context.dataStore.edit { it[KEY_CONNECTION_MODE] = value }
    }

    suspend fun updateMtuMru(value: Int) {
        context.dataStore.edit { it[KEY_MTU_MRU] = value }
    }

    suspend fun updateDnsMode(value: String) {
        context.dataStore.edit { it[KEY_DNS_MODE] = value }
    }

    suspend fun updateCustomDns(value: String) {
        context.dataStore.edit { it[KEY_CUSTOM_DNS] = value }
    }

    suspend fun updateStartOnPlug(value: Boolean) {
        context.dataStore.edit { it[KEY_START_ON_PLUG] = value }
    }

    suspend fun updateDebugHex(value: Boolean) {
        context.dataStore.edit { it[KEY_DEBUG_HEX] = value }
    }

    suspend fun updateDebugTcpBridge(value: Boolean) {
        context.dataStore.edit { it[KEY_DEBUG_TCP_BRIDGE] = value }
    }

    suspend fun updateDebugBridgePort(value: Int) {
        context.dataStore.edit { it[KEY_DEBUG_BRIDGE_PORT] = value }
    }
}
