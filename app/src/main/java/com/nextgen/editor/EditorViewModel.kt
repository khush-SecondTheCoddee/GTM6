package com.nextgen.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class EditorUiState(
    val text: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val spans: List<StyledSpan> = emptyList(),
    val reflowMode: Boolean = true,
    val error: String? = null,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val documentId = NativeBridge.createDocument()
    private val snapshotFile = File(application.filesDir, "documents/untitled.ngdoc.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init { restore() }

    fun updateText(value: String, selectionStart: Int, selectionEnd: Int) {
        val previous = _state.value.text
        if (value == previous) { _state.value = _state.value.copy(selectionStart = selectionStart, selectionEnd = selectionEnd); return }
        val prefix = previous.commonPrefixWith(value).length
        val oldSuffix = previous.substring(prefix).commonSuffixWith(value.substring(prefix)).length
        val response = NativeBridge.applyEdit(documentId, NativeRequest.Replace(
            previous.codePointOffset(prefix), previous.codePointOffset(previous.length - oldSuffix),
            value.substring(prefix, value.length - oldSuffix),
        ))
        publish(response, selectionStart, selectionEnd)
        saveAsync()
    }

    fun toggleBold() = applyStyle { copy(bold = !bold) }
    fun toggleItalic() = applyStyle { copy(italic = !italic) }
    fun setHeading(level: Int) = applyStyle { copy(heading = if (heading == level) 0 else level) }
    fun toggleBullet() = applyStyle { copy(bullet = !bullet) }
    fun setAlignment(alignment: Alignment) = applyStyle { copy(alignment = alignment) }
    fun toggleReflow() { _state.value = _state.value.copy(reflowMode = !_state.value.reflowMode) }

    private fun applyStyle(transform: StyledSpan.() -> StyledSpan) {
        val state = _state.value
        if (state.selectionStart == state.selectionEnd) return
        val start = state.text.codePointOffset(state.selectionStart)
        val end = state.text.codePointOffset(state.selectionEnd)
        val existing = state.spans.firstOrNull { it.start <= start && it.end >= end }
        val base = existing ?: StyledSpan(start, end)
        publish(NativeBridge.applyEdit(documentId, NativeRequest.SetSpan(base.transform())), state.selectionStart, state.selectionEnd)
        saveAsync()
    }

    private fun publish(response: EngineResponse, selectionStart: Int, selectionEnd: Int) {
        _state.value = if (response.ok) _state.value.copy(text = response.snapshot.text, spans = response.snapshot.spans, selectionStart = selectionStart, selectionEnd = selectionEnd, error = null)
        else _state.value.copy(error = response.error ?: "Native engine error")
    }

    private fun restore() = viewModelScope.launch {
        val snapshot = withContext(Dispatchers.IO) { snapshotFile.takeIf(File::exists)?.readText()?.let { runCatching { json.decodeFromString<DocumentSnapshot>(it) }.getOrNull() } }
        if (snapshot != null) publish(NativeBridge.applyEdit(documentId, NativeRequest.Load(snapshot)), 0, 0)
    }

    private fun saveAsync() = viewModelScope.launch(Dispatchers.IO) {
        val response = NativeBridge.snapshot(documentId)
        if (response.ok) { snapshotFile.parentFile?.mkdirs(); snapshotFile.writeText(json.encodeToString(DocumentSnapshot.serializer(), response.snapshot)) }
    }

    override fun onCleared() { NativeBridge.close(documentId); super.onCleared() }
}

/** Converts Compose's UTF-16 cursor offset to the code-point offsets used by Yrs. */
private fun String.codePointOffset(utf16Offset: Int): Int = codePointCount(0, utf16Offset.coerceIn(0, length))
