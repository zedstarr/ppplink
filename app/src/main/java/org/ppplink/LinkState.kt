package org.ppplink

sealed interface LinkState {
    /** Link not running (user stopped it, or never started). */
    data object Idle : LinkState
    data object NoAdapter : LinkState
    data object NeedsPermission : LinkState
    /** Link running, but the adapter was unplugged: waiting for it to come back. */
    data object AdapterUnplugged : LinkState
    /** Port open, slirp running, no PPP seen yet. */
    data object Ready : LinkState
    data object Negotiating : LinkState
    data object Connected : LinkState
    /** The client is holding the line (CTS low): asleep, switched off or busy. */
    data object ClientNotReady : LinkState
    data class Error(val message: String) : LinkState
}

/** Human-readable label, shared by the UI and the notification. */
fun LinkState.label(): String = when (this) {
    LinkState.Idle -> "Idle"
    LinkState.NoAdapter -> "No USB adapter"
    LinkState.NeedsPermission -> "Waiting for USB permission"
    LinkState.AdapterUnplugged -> "Adapter unplugged - plug in to resume"
    LinkState.Ready -> "Ready for client"
    LinkState.Negotiating -> "Negotiating PPP..."
    LinkState.Connected -> "Connected - 10.0.2.15"
    LinkState.ClientNotReady -> "Client not ready (asleep or busy?)"
    is LinkState.Error -> "Error: $message"
}
