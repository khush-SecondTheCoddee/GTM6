package com.nextgen.editor

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/** Direct JNI façade. Rust owns document state; this class only marshals its stable JSON protocol. */
object NativeBridge {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init { System.loadLibrary("nextgen_editor_core") }

    private external fun nativeCreateDocument(): String
    private external fun nativeApplyEdit(documentId: String, request: String): String
    private external fun nativeSnapshot(documentId: String): String
    private external fun nativeCloseDocument(documentId: String)

    fun createDocument(): String = nativeCreateDocument()
    fun applyEdit(documentId: String, request: NativeRequest): EngineResponse =
        json.decodeFromString(nativeApplyEdit(documentId, json.encodeToString(NativeRequest.serializer(), request)))
    fun snapshot(documentId: String): EngineResponse = json.decodeFromString(nativeSnapshot(documentId))
    fun close(documentId: String) = nativeCloseDocument(documentId)
}

@Serializable
data class EngineResponse(val ok: Boolean, val error: String? = null, val snapshot: DocumentSnapshot)

@Serializable
data class DocumentSnapshot(val text: String = "", val spans: List<StyledSpan> = emptyList())

@Serializable
data class StyledSpan(
    val start: Int, val end: Int, val bold: Boolean = false, val italic: Boolean = false,
    val heading: Int = 0, val alignment: Alignment = Alignment.START, val bullet: Boolean = false,
)

@Serializable
enum class Alignment {
    @SerialName("start") START, @SerialName("center") CENTER,
    @SerialName("end") END, @SerialName("justify") JUSTIFY,
}

@Serializable
sealed class NativeRequest {
    @Serializable @SerialName("replace")
    data class Replace(val start: Int, val end: Int, val text: String) : NativeRequest()
    @Serializable @SerialName("set_span")
    data class SetSpan(val span: StyledSpan) : NativeRequest()
    @Serializable @SerialName("load")
    data class Load(val snapshot: DocumentSnapshot) : NativeRequest()
}
