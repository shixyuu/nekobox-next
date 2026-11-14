package io.nekobox.next.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class Phase { Idle, Connecting, Connected }

data class SessionUi(
    val phase: Phase = Phase.Idle,
    val uploadBps: Long = 0,
    val downloadBps: Long = 0,
    val nativeCore: Boolean = false,
    val detail: String = "",
)

@Singleton
class SessionStore @Inject constructor() {
    private val _state = MutableStateFlow(SessionUi())
    val state: StateFlow<SessionUi> = _state.asStateFlow()
    fun update(block: (SessionUi) -> SessionUi) = _state.update(block)
}

@Singleton
class LogBuffer @Inject constructor() {
    private val lines = ArrayDeque<String>()
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow.asStateFlow()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(message: String) {
        val line = "${stamp.format(Date())}  $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > 400) lines.removeFirst()
            _flow.value = lines.toList()
        }
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            _flow.value = emptyList()
        }
    }
}
