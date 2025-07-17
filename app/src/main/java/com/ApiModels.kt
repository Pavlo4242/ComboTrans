package com.ComboTrans

import com.google.gson.annotations.SerializedName

// --- SHARED DATA CLASSES ---
data class ApiVersion(
    val displayName: String, // The string to display in UI (e.g., "v1alpha (Preview)")
    val value: String        // The actual API version string (e.g., "v1alpha")
) {
    override fun toString(): String = displayName
}

data class ApiKeyInfo(
    val displayName: String, // The string to display in UI (e.g., "Language1a")
    val value: String        // The actual API key string
) {
    override fun toString(): String = displayName
}

// --- WEBSOCKET (BidiGenerateContent) RESPONSE DATA CLASSES ---
data class ServerResponse(
    @SerializedName("serverContent") val serverContent: ServerContent?,
    @SerializedName("inputTranscription") val inputTranscription: Transcription?,
    @SerializedName("outputTranscription") val outputTranscription: Transcription?,
    @SerializedName("setupComplete") val setupComplete: SetupComplete?,
    @SerializedName("sessionResumptionUpdate") val sessionResumptionUpdate: SessionResumptionUpdate?,
    @SerializedName("goAway") val goAway: GoAway?
)

data class ServerContent(
    @SerializedName("parts") val parts: List<Part>?,
    @SerializedName("modelTurn") val modelTurn: ModelTurn?,
    @SerializedName("inputTranscription") val inputTranscription: Transcription?,
    @SerializedName("outputTranscription") val outputTranscription: Transcription?
)

data class ModelTurn(@SerializedName("parts") val parts: List<Part>?)
data class Transcription(@SerializedName("text") val text: String?)
data class SetupComplete(val dummy: String? = null)
data class SessionResumptionUpdate(@SerializedName("newHandle") val newHandle: String?, @SerializedName("resumable") val resumable: Boolean?)
data class GoAway(@SerializedName("timeLeft") val timeLeft: String?)

// --- REST (generateContent) & SHARED DATA CLASSES ---
// Also used by WebSocket models
data class Part(
    @SerializedName("text") val text: String? = null,
    @SerializedName("inlineData") val inlineData: InlineData? = null
)

// Also used by WebSocket models
data class InlineData(
    @SerializedName("mime_type") val mimeType: String?,
    @SerializedName("data") val data: String?
)

// --- REST (generateContent) REQUEST DATA CLASSES ---
data class RestGenerateContentRequest(
    val contents: List<RestContent>,
    @SerializedName("system_instruction") val systemInstruction: RestContent? = null
)

data class RestContent(
    val parts: List<Part>,
    val role: String = "user"
)

// --- REST (generateContent) RESPONSE DATA CLASSES ---
data class RestGenerateContentResponse(
    val candidates: List<Candidate>?,
    val promptFeedback: PromptFeedback?
)

data class Candidate(
    val content: RestContent,
    val finishReason: String?,
    val index: Int
)

data class PromptFeedback(
    val safetyRatings: List<SafetyRating>?
)

data class SafetyRating(
    val category: String,
    val probability: String
)
