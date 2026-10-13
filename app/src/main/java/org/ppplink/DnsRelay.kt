package org.ppplink

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Local DNS relay for slirp. slirp sends the client's queries for 10.0.2.3 to
 * 127.0.0.1:[port] (UDP, and TCP for large answers); each query is answered
 * through whatever network the phone is on *now*, so DNS keeps working across
 * Wi-Fi <-> mobile switches mid-session. On Android 10+ it uses DnsResolver,
 * which also honours the phone's Private DNS setting.
 */
class DnsRelay private constructor(
    private val context: Context,
    private val udp: DatagramSocket,
    private val tcp: ServerSocket
) {
    companion object {
        private const val QUERY_TIMEOUT_MS = 5000L
        private const val UDP_LIMIT_PLAIN = 512     // classic DNS over UDP
        private const val UDP_LIMIT_EDNS = 1232     // when the query carries EDNS

        /** Binds UDP and TCP on the same free loopback port. */
        fun start(context: Context): DnsRelay {
            val lo = InetAddress.getByName("127.0.0.1")
            var lastError: Exception? = null
            repeat(10) {
                val u = DatagramSocket(InetSocketAddress(lo, 0))
                try {
                    val t = ServerSocket()
                    t.reuseAddress = true
                    t.bind(InetSocketAddress(lo, u.localPort), 8)
                    return DnsRelay(context, u, t).also { it.run() }
                } catch (e: IOException) {
                    lastError = e
                    u.close()           // TCP port taken: try another pair
                }
            }
            throw IOException("No free port pair for DNS relay: ${lastError?.message}")
        }
    }

    val port: Int get() = udp.localPort
    val queries = AtomicLong()
    val failures = AtomicLong()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastFailureLog = 0L

    private fun run() {
        scope.launch { udpLoop() }
        scope.launch { tcpLoop() }
        LogBuffer.log("DNS relay on 127.0.0.1:$port (follows the phone's network)")
    }

    fun stop() {
        scope.cancel()
        try { udp.close() } catch (_: Exception) {}
        try { tcp.close() } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- transports

    private suspend fun udpLoop() {
        val buf = ByteArray(4096)
        while (scope.isActive) {
            val pkt = DatagramPacket(buf, buf.size)
            try { udp.receive(pkt) } catch (_: IOException) { break }
            val query = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
            val from = pkt.socketAddress
            scope.launch {
                var answer = resolve(query)
                val limit = if (arCount(query) > 0) UDP_LIMIT_EDNS else UDP_LIMIT_PLAIN
                if (answer.size > limit) answer = truncated(query, answer)
                try { udp.send(DatagramPacket(answer, answer.size, from)) } catch (_: IOException) {}
            }
        }
    }

    private suspend fun tcpLoop() {
        while (scope.isActive) {
            val client = try { tcp.accept() } catch (_: IOException) { break }
            scope.launch { serveTcp(client) }
        }
    }

    /** DNS over TCP: 2-byte length prefix per message, possibly several per connection. */
    private suspend fun serveTcp(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 10_000
            val input = DataInputStream(s.getInputStream())
            val output = DataOutputStream(s.getOutputStream())
            while (true) {
                val len = try { input.readUnsignedShort() } catch (_: IOException) { break }
                val query = ByteArray(len)
                try { input.readFully(query) } catch (_: IOException) { break }
                val answer = resolve(query)
                try {
                    output.writeShort(answer.size)
                    output.write(answer)
                    output.flush()
                } catch (_: IOException) { break }
            }
        }
    }

    // ---------------------------------------------------------------- resolution

    /** Always returns a DNS message: the real answer, or SERVFAIL. */
    private suspend fun resolve(query: ByteArray): ByteArray {
        if (query.size < 12) return query
        queries.incrementAndGet()
        val answer = withTimeoutOrNull(QUERY_TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) resolveWithDnsResolver(query)
            else resolveDirect(query)
        }
        if (answer != null && answer.size >= 12) return answer
        failures.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - lastFailureLog > 30_000) {         // don't flood the log
            lastFailureLog = now
            LogBuffer.log("DNS relay: lookup failed (no network?) - ${failures.get()} failure(s) so far")
        }
        return servFail(query)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun resolveWithDnsResolver(query: ByteArray): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            DnsResolver.getInstance().rawQuery(
                null,                                   // null = the current default network
                query,
                DnsResolver.FLAG_EMPTY,
                Dispatchers.IO.asExecutor(),
                cancel,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (cont.isActive) cont.resume(answer)
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }

    /** Android 8/9 fallback: plain UDP to the current network's first IPv4 DNS server. */
    private fun resolveDirect(query: ByteArray): ByteArray? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val server = cm.getLinkProperties(net)?.dnsServers?.firstOrNull { it is Inet4Address } ?: return null
        DatagramSocket().use { s ->
            net.bindSocket(s)
            s.soTimeout = QUERY_TIMEOUT_MS.toInt()
            s.send(DatagramPacket(query, query.size, server, 53))
            val buf = ByteArray(4096)
            val pkt = DatagramPacket(buf, buf.size)
            s.receive(pkt)
            return buf.copyOf(pkt.length)
        }
    }

    // ---------------------------------------------------------------- DNS message helpers

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun arCount(q: ByteArray) = if (q.size >= 12) u16(q, 10) else 0

    /** End offset of the (first) question section, or -1 if malformed. */
    private fun questionEnd(q: ByteArray): Int {
        if (q.size < 12 || u16(q, 4) == 0) return 12
        var i = 12
        while (i < q.size) {
            val len = q[i].toInt() and 0xFF
            if (len == 0) { i += 1; break }
            if (len and 0xC0 != 0) return -1
            i += 1 + len
        }
        i += 4                                      // QTYPE + QCLASS
        return if (i <= q.size) i else -1
    }

    /** Header + question from the query, with the given flags/rcode and no records. */
    private fun headerOnly(query: ByteArray, flagsHi: Int, flagsLo: Int): ByteArray {
        val end = questionEnd(query)
        val out = if (end > 0) query.copyOf(end) else query.copyOf(12)
        out[2] = flagsHi.toByte()
        out[3] = flagsLo.toByte()
        out[4] = 0; out[5] = if (end > 12) 1 else 0  // QDCOUNT
        for (i in 6 until 12) out[i] = 0             // AN/NS/AR = 0
        return out
    }

    /** SERVFAIL reply: QR=1, keep opcode+RD from the query, RA=1, RCODE=2. */
    private fun servFail(query: ByteArray): ByteArray =
        headerOnly(query, 0x80 or (query[2].toInt() and 0x79), 0x82)

    /** Too big for UDP: same header with TC=1 so the client retries over TCP. */
    private fun truncated(query: ByteArray, answer: ByteArray): ByteArray =
        headerOnly(query, (answer[2].toInt() and 0xFF) or 0x02, answer[3].toInt() and 0xFF)
}
