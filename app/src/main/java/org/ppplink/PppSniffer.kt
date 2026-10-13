package org.ppplink

/**
 * Watches the client-to-slirp byte stream (read-only) and reports PPP events.
 *
 * Handles header compression negotiated by LCP:
 *  - ACFC: the FF 03 address/control bytes may be absent.
 *  - PFC: the protocol field may be a single odd byte (e.g. 0x21 for IP).
 * LCP Echo/Discard (keepalives) are ignored so a connected link isn't
 * reported as "negotiating" every time the client pings.
 */
class PppSniffer(private val onEvent: (Event) -> Unit) {

    enum class Event { NEGOTIATING, IP_TRAFFIC, TERMINATE }

    private val frame = ByteArray(16)   // only the first few bytes are needed
    private var index = 0
    private var inFrame = false
    private var escapeNext = false

    fun processBytes(data: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            if (b == 0x7E) {
                if (inFrame && index > 0) parseFrame(index)
                inFrame = true
                index = 0
                escapeNext = false
                continue
            }
            if (!inFrame) continue
            if (b == 0x7D) { escapeNext = true; continue }
            val v = if (escapeNext) { escapeNext = false; b xor 0x20 } else b
            if (index < frame.size) frame[index++] = v.toByte()
        }
    }

    private fun byteAt(i: Int) = frame[i].toInt() and 0xFF

    private fun parseFrame(len: Int) {
        var off = 0
        if (len >= 2 && byteAt(0) == 0xFF && byteAt(1) == 0x03) off = 2
        if (len - off < 1) return

        // PFC: an odd first byte means a one-byte protocol field.
        val protocol: Int
        if (byteAt(off) and 0x01 == 1) {
            protocol = byteAt(off)
            off += 1
        } else {
            if (len - off < 2) return
            protocol = (byteAt(off) shl 8) or byteAt(off + 1)
            off += 2
        }

        when (protocol) {
            0xC021 -> {                       // LCP (never compressed)
                if (len <= off) return
                when (byteAt(off)) {
                    1, 2, 3, 4 -> onEvent(Event.NEGOTIATING)   // Configure-*
                    5 -> onEvent(Event.TERMINATE)              // Terminate-Request
                    else -> Unit                               // Echo, Discard, etc.
                }
            }
            0x8021 -> onEvent(Event.NEGOTIATING)               // IPCP
            0x0021, 0x21 -> onEvent(Event.IP_TRAFFIC)          // IPv4
        }
    }

    fun reset() {
        index = 0
        inFrame = false
        escapeNext = false
    }
}
