package org.ppplink

import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** The client's side of the link: a USB serial port, or the debug TCP bridge. */
interface SerialLine {
    val description: String
    /** USB device name for detach matching; null for the TCP bridge. */
    val usbDeviceName: String?
    /** True while the far end isn't accepting data (CTS held low). */
    val stalled: Boolean

    /**
     * Returns bytes read, 0 on timeout, or -1 if the peer hung up (TCP client left).
     * Throws IOException when the line itself is gone.
     */
    fun read(buf: ByteArray, timeoutMs: Int): Int

    /**
     * Writes up to [len] bytes and returns how many went out. Fewer than [len]
     * means the far end stalled and the rest was dropped. Throws when the line is gone.
     */
    fun write(data: ByteArray, len: Int = data.size): Int

    fun close()
}

class UsbLine(private val port: UsbSerialPort) : SerialLine {
    override val description: String = UsbAdapters.describe(port.device)
    override val usbDeviceName: String = port.device.deviceName

    @Volatile override var stalled = false
        private set

    override fun read(buf: ByteArray, timeoutMs: Int): Int = port.read(buf, timeoutMs)

    override fun write(data: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val chunk = if (off == 0) data else data.copyOfRange(off, len)
            val n = len - off
            // Once stalled, probe briefly instead of blocking 2 s per chunk.
            val timeout = if (stalled) 200 else 2000
            try {
                port.write(chunk, n, timeout)
                stalled = false
                return len
            } catch (e: SerialTimeoutException) {
                val done = e.bytesTransferred.coerceIn(0, n)
                off += done
                if (done == 0) {
                    stalled = true
                    return off            // drop the rest; PPP/TCP will resend
                }
                stalled = false           // partial progress: keep going
            }
        }
        return len
    }

    override fun close() {
        try { port.close() } catch (_: Exception) {}
    }
}

/**
 * Debug bridge: listens on 127.0.0.1:[tcpPort] and treats one TCP client as the
 * client. Test with `adb forward tcp:8000 tcp:8000` and a PC running pppd over socat.
 */
class TcpLine private constructor(private val server: ServerSocket, tcpPort: Int) : SerialLine {

    companion object {
        fun open(tcpPort: Int): TcpLine {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), tcpPort), 1)
            server.soTimeout = 200
            return TcpLine(server, tcpPort)
        }
    }

    override val description = "Debug TCP bridge 127.0.0.1:$tcpPort"
    override val usbDeviceName: String? = null
    override val stalled = false

    @Volatile private var client: Socket? = null

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val c = client ?: run {
            val s = try { server.accept() } catch (_: SocketTimeoutException) { return 0 }
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            client = s
            LogBuffer.log("Debug bridge: client connected from ${s.remoteSocketAddress}")
            s
        }
        return try {
            val n = c.getInputStream().read(buf)
            if (n < 0) { dropClient(); -1 } else n
        } catch (_: SocketTimeoutException) {
            0
        } catch (_: IOException) {
            dropClient()
            -1
        }
    }

    override fun write(data: ByteArray, len: Int): Int {
        val c = client ?: return len          // nobody connected: discard
        try {
            c.getOutputStream().write(data, 0, len)
        } catch (_: IOException) {
            dropClient()
        }
        return len
    }

    private fun dropClient() {
        val c = client ?: return
        client = null
        try { c.close() } catch (_: Exception) {}
        LogBuffer.log("Debug bridge: client disconnected")
    }

    override fun close() {
        dropClient()
        try { server.close() } catch (_: Exception) {}
    }
}
