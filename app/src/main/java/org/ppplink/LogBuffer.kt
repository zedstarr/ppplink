package org.ppplink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object LogBuffer {
    private const val MAX_LINES = 500
    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow: StateFlow<List<String>> = _logsFlow.asStateFlow()

    @Synchronized
    fun log(message: String) {
        val timestamp = timeFormat.format(Date())
        val formatted = "[$timestamp] $message"
        if (buffer.size >= MAX_LINES) {
            buffer.removeFirst()
        }
        buffer.addLast(formatted)
        _logsFlow.value = buffer.toList()
    }

    @Synchronized
    fun logHex(direction: String, data: ByteArray, length: Int) {
        val cappedLen = minOf(length, 64)
        val sb = StringBuilder()
        sb.append(direction).append(" [").append(length).append("B]: ")
        for (i in 0 until cappedLen) {
            sb.append(String.format(Locale.US, "%02X ", data[i].toInt() and 0xFF))
        }
        if (length > 64) {
            sb.append("...")
        }
        log(sb.toString())
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _logsFlow.value = emptyList()
    }

    @Synchronized
    fun getAllAsText(): String {
        return buffer.joinToString(separator = "\n")
    }
}
