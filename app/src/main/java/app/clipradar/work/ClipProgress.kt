package app.clipradar.work

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что сейчас происходит с нарезкой каждого клипа — чтобы показать это на экране. */
object ClipProgress {
    private val _state = MutableStateFlow<Map<Long, String>>(emptyMap())
    val state: StateFlow<Map<Long, String>> = _state.asStateFlow()

    fun set(momentId: Long, text: String) {
        _state.value = _state.value + (momentId to text)
    }

    fun clear(momentId: Long) {
        _state.value = _state.value - momentId
    }
}
