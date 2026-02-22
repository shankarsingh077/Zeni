package com.zeni.voiceai.protocol

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * WebSocket message types for Zeni protocol.
 */
object MessageType {
    const val SESSION_START = "session_start"
    const val SESSION_END = "session_end"
    const val SESSION_ACK = "session_ack"
    const val LANGUAGE_CHANGE = "language_change"
    const val VOICE_CHANGE = "voice_change"
    const val TTS_PROVIDER_CHANGE = "tts_provider_change"
    const val TTS_SPEED_CHANGE = "tts_speed_change"
    const val PERSONALITY_CHANGE = "personality_change"
    const val AUDIO_FRAME = "audio_frame"
    const val IMAGE_FRAME = "image_frame"  // Camera frame for visual context
    const val REQUEST_IMAGE = "request_image"  // Server requests image capture
    const val SPEECH_FINISHED = "speech_finished"
    const val AUDIO_RESPONSE = "audio_response"
    const val TRANSCRIPT_PARTIAL = "transcript_partial"
    const val TRANSCRIPT_FINAL = "transcript_final"
    const val LLM_TOKEN = "llm_token"
    const val LLM_COMPLETE = "llm_complete"
    const val INTERRUPT = "interrupt"
    const val PLAYBACK_STOP = "playback_stop"
    const val ERROR = "error"
    const val HEARTBEAT = "heartbeat"
    const val HEARTBEAT_ACK = "heartbeat_ack"
    const val PING = "ping"
    const val PONG = "pong"
    const val STATE_CHANGE = "state_change"
    const val CAMPUS_TOUR = "campus_tour"  // For virtual campus tours
    const val FEE_STRUCTURE = "fee_structure"  // For detailed fee structure display
    const val SHOW_PLACEMENTS = "show_placements"  // For top placement students gallery
    const val ROBOT_COMMAND = "robot_command"  // Robot movement commands from LLM
    const val ROBOT_STATUS = "robot_status"  // Client reports robot connection status
}

/**
 * Session states.
 */
enum class SessionState {
    @SerializedName("idle") IDLE,
    @SerializedName("listening") LISTENING,
    @SerializedName("transcribing") TRANSCRIBING,
    @SerializedName("generating") GENERATING,
    @SerializedName("speaking") SPEAKING,
    @SerializedName("interrupted") INTERRUPTED,
    @SerializedName("error") ERROR,
    @SerializedName("closed") CLOSED
}

/**
 * Supported languages.
 */
enum class Language(val code: String) {
    @SerializedName("en") ENGLISH("en"),
    @SerializedName("hi") HINDI("hi"),
    @SerializedName("auto") AUTO("auto")
}

// ============== Outbound Messages (Client -> Server) ==============

/**
 * Session configuration.
 */
data class SessionConfig(
    @SerializedName("sample_rate") val sampleRate: Int = 16000,
    @SerializedName("language_preference") val languagePreference: String = "auto",
    @SerializedName("voice_preference") val voicePreference: String = "Kore",  // Female voice
    @SerializedName("tts_provider") val ttsProvider: String = "google",
    @SerializedName("speaking_rate") val speakingRate: Float = 1.0f,
    @SerializedName("push_to_talk") val pushToTalk: Boolean = false,
    @SerializedName("personality") val personality: String = "assistant"  // assistant or human
)

/**
 * Session start message.
 */
data class SessionStartMessage(
    @SerializedName("type") val type: String = MessageType.SESSION_START,
    @SerializedName("session_id") val sessionId: String = UUID.randomUUID().toString(),
    @SerializedName("config") val config: SessionConfig = SessionConfig(),
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * Audio frame message.
 */
data class AudioFrameMessage(
    @SerializedName("type") val type: String = MessageType.AUDIO_FRAME,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("data") val data: String, // Base64 encoded PCM
    @SerializedName("sequence") val sequence: Int
)

/**
 * Interrupt message.
 */
data class InterruptMessage(
    @SerializedName("type") val type: String = MessageType.INTERRUPT,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * Speech finished message (explicit end of speech).
 */
data class SpeechFinishedMessage(
    @SerializedName("type") val type: String = MessageType.SPEECH_FINISHED,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * Heartbeat message.
 */
data class HeartbeatMessage(
    @SerializedName("type") val type: String = MessageType.HEARTBEAT,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * Language change message.
 */
data class LanguageChangeMessage(
    @SerializedName("type") val type: String = MessageType.LANGUAGE_CHANGE,
    @SerializedName("language") val language: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * Voice change message.
 */
data class VoiceChangeMessage(
    @SerializedName("type") val type: String = MessageType.VOICE_CHANGE,
    @SerializedName("voice") val voice: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * TTS Provider change message.
 */
data class TtsProviderChangeMessage(
    @SerializedName("type") val type: String = MessageType.TTS_PROVIDER_CHANGE,
    @SerializedName("provider") val provider: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * TTS Speed change message.
 */
data class TtsSpeedChangeMessage(
    @SerializedName("type") val type: String = MessageType.TTS_SPEED_CHANGE,
    @SerializedName("speed") val speed: Float,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * Personality change message.
 */
data class PersonalityChangeMessage(
    @SerializedName("type") val type: String = MessageType.PERSONALITY_CHANGE,
    @SerializedName("personality") val personality: String,  // "assistant" or "human"
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * Session end message.
 */
data class SessionEndMessage(
    @SerializedName("type") val type: String = MessageType.SESSION_END,
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

// ============== Inbound Messages (Server -> Client) ==============

/**
 * Generic message for initial parsing.
 */
data class GenericMessage(
    @SerializedName("type") val type: String
)

/**
 * Session acknowledgment.
 */
data class SessionAckMessage(
    @SerializedName("type") val type: String,
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("status") val status: String,
    @SerializedName("timestamp") val timestamp: Long
)

/**
 * Audio response chunk.
 */
data class AudioResponseMessage(
    @SerializedName("type") val type: String,
    @SerializedName("sequence") val sequence: Int,
    @SerializedName("data") val data: String, // Base64 encoded PCM
    @SerializedName("final") val isFinal: Boolean,
    @SerializedName("sample_rate") val sampleRate: Int
)

/**
 * Partial transcript.
 */
data class TranscriptPartialMessage(
    @SerializedName("type") val type: String,
    @SerializedName("text") val text: String,
    @SerializedName("language") val language: String,
    @SerializedName("timestamp") val timestamp: Long
)

/**
 * Final transcript.
 */
data class TranscriptFinalMessage(
    @SerializedName("type") val type: String,
    @SerializedName("text") val text: String,
    @SerializedName("confidence") val confidence: Float,
    @SerializedName("language") val language: String,
    @SerializedName("timestamp") val timestamp: Long
)

/**
 * LLM token.
 */
data class LLMTokenMessage(
    @SerializedName("type") val type: String,
    @SerializedName("token") val token: String,
    @SerializedName("sequence") val sequence: Int
)

/**
 * LLM complete response.
 */
data class LLMCompleteMessage(
    @SerializedName("type") val type: String,
    @SerializedName("full_text") val fullText: String,
    @SerializedName("timestamp") val timestamp: Long
)

/**
 * State change notification.
 */
data class StateChangeMessage(
    @SerializedName("type") val type: String,
    @SerializedName("state") val state: String,
    @SerializedName("previous_state") val previousState: String?,
    @SerializedName("timestamp") val timestamp: Long
)

/**
 * Playback stop signal.
 */
data class PlaybackStopMessage(
    @SerializedName("type") val type: String,
    @SerializedName("timestamp") val timestamp: Long
)

/**
 * Error message.
 */
data class ErrorMessage(
    @SerializedName("type") val type: String,
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("details") val details: Map<String, Any>?,
    @SerializedName("timestamp") val timestamp: Long
)

/**
 * Heartbeat acknowledgment.
 */
data class HeartbeatAckMessage(
    @SerializedName("type") val type: String,
    @SerializedName("timestamp") val timestamp: Long
)

/**
 * Campus tour message - triggers full-screen Matterport view.
 */
data class CampusTourMessage(
    @SerializedName("type") val type: String,
    @SerializedName("tour_id") val tourId: String,
    @SerializedName("name") val name: String,
    @SerializedName("url") val url: String,
    @SerializedName("description") val description: String,
    @SerializedName("timestamp") val timestamp: Long
)

/**
 * Fee structure message - triggers full-screen WebView with fee details.
 */
data class FeeStructureMessage(
    @SerializedName("type") val type: String,
    @SerializedName("program_id") val programId: String,
    @SerializedName("program_name") val programName: String,
    @SerializedName("url") val url: String,
    @SerializedName("timestamp") val timestamp: Long
)

/**
 * Placement gallery message - triggers full-screen placement photo viewer.
 */
data class PlacementMessage(
    @SerializedName("type") val type: String,
    @SerializedName("title") val title: String,
    @SerializedName("timestamp") val timestamp: Long
)

/**
 * Robot command message - movement commands from LLM for robot control.
 * 
 * Supported actions:
 * - forward: Move forward (1 sec for safety)
 * - backward: Move backward (1 sec for safety)
 * - left: Turn left
 * - right: Turn right
 * - spin_left: Spin in place to the left
 * - spin_right: Spin in place to the right
 * - stop: Stop all movement
 * - lights_on/lights_off: Toggle lights
 * - horn: Sound horn
 */
data class RobotCommandMessage(
    @SerializedName("type") val type: String,
    @SerializedName("action") val action: String,  // forward, backward, left, right, spin_left, spin_right, stop
    @SerializedName("duration_ms") val durationMs: Long = 1000,  // Duration in ms (capped at 1000 for safety)
    @SerializedName("speed_percent") val speedPercent: Int = 50,  // Speed 0-100 (default 50 for safety)
    @SerializedName("timestamp") val timestamp: Long
)
