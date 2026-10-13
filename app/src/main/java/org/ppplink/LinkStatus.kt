package org.ppplink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for live link status, written by LinkEngine and
 * read by the UI and the service notification.
 */
object LinkStatus {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _bytesIn = MutableStateFlow(0L)
    /** Bytes received from the client. */
    val bytesIn: StateFlow<Long> = _bytesIn.asStateFlow()

    private val _bytesOut = MutableStateFlow(0L)
    /** Bytes sent to the client. */
    val bytesOut: StateFlow<Long> = _bytesOut.asStateFlow()

    private val _adapterName = MutableStateFlow("None")
    val adapterName: StateFlow<String> = _adapterName.asStateFlow()

    private val _dnsInUse = MutableStateFlow("-")
    val dnsInUse: StateFlow<String> = _dnsInUse.asStateFlow()

    /** elapsedRealtime() when PPP reached Connected, or null if not connected. */
    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    fun setState(s: LinkState) { _state.value = s }
    fun setBytes(inCount: Long, outCount: Long) { _bytesIn.value = inCount; _bytesOut.value = outCount }
    fun setAdapterName(name: String) { _adapterName.value = name }
    fun setDns(dns: String) { _dnsInUse.value = dns }
    fun setConnectedSince(t: Long?) { _connectedSince.value = t }
}
