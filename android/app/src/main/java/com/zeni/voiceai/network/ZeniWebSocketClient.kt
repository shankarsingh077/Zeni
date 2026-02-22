package com.zeni.voiceai.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.zeni.voiceai.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket client for Zeni voice communication.
 * Handles connection management, message serialization, and heartbeat.
 */
class ZeniWebSocketClient {
    
    companion object {
        private const val TAG = "ZeniWebSocket"
        private const val HEARTBEAT_INTERVAL_MS = 10000L
        private const val RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val CONNECT_TIMEOUT_SECONDS = 10L
    }
    
    /**
     * Connection state.
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR
    }
    
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var serverUrl: String = ""
    private var sessionId: String = ""
    
    private val sequenceCounter = AtomicInteger(0)
    
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    
    // State flows
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    
    // Event channels
    private val _transcriptPartial = MutableSharedFlow<String>()
    val transcriptPartial: SharedFlow<String> = _transcriptPartial.asSharedFlow()
    
    private val _transcriptFinal = MutableSharedFlow<String>()
    val transcriptFinal: SharedFlow<String> = _transcriptFinal.asSharedFlow()
    
    private val _llmResponse = MutableSharedFlow<String>()
    val llmResponse: SharedFlow<String> = _llmResponse.asSharedFlow()
    
    private val _audioResponse = Channel<AudioResponseData>(Channel.UNLIMITED)
    val audioResponse: Flow<AudioResponseData> = _audioResponse.receiveAsFlow()
    
    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors.asSharedFlow()
    
    private val _playbackStop = MutableSharedFlow<Unit>()
    val playbackStop: SharedFlow<Unit> = _playbackStop.asSharedFlow()
    
    private val _campusTour = MutableSharedFlow<CampusTourData>()
    val campusTour: SharedFlow<CampusTourData> = _campusTour.asSharedFlow()
    
    /**
     * Audio response data class.
     */
    data class AudioResponseData(
        val data: ByteArray,
        val sampleRate: Int,
        val isFinal: Boolean
    )
    
    /**
     * Campus tour data class for Matterport integration.
     */
    data class CampusTourData(
        val tourId: String,
        val name: String,
        val url: String,
        val description: String
    )
    
    /**
     * Fee structure data class for displaying fee details.
     */
    data class FeeStructureData(
        val programId: String,
        val programName: String,
        val url: String
    )
    
    private val _feeStructure = MutableSharedFlow<FeeStructureData>()
    val feeStructure: SharedFlow<FeeStructureData> = _feeStructure.asSharedFlow()
    
    /**
     * Placement data class for displaying top placements.
     */
    data class PlacementData(
        val title: String
    )
    
    private val _placements = MutableSharedFlow<PlacementData>()
    val placements: SharedFlow<PlacementData> = _placements.asSharedFlow()

    // Image capture request - server requests image for vision analysis
    private val _imageCaptureRequest = MutableSharedFlow<Unit>()
    val imageCaptureRequest: SharedFlow<Unit> = _imageCaptureRequest.asSharedFlow()

    // Robot command from server (LLM decides to control robot)
    data class RobotCommandData(
        val action: String,
        val durationMs: Long,
        val speedPercent: Int
    )
    
    private val _robotCommand = MutableSharedFlow<RobotCommandData>()
    val robotCommand: SharedFlow<RobotCommandData> = _robotCommand.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var languagePreference: String = "en" // "en" or "hi"
    private var voicePreference: String = "Kore" // Default female voice
    private var personalityPreference: String = "assistant" // "assistant" or "human"
    
    /**
     * Connect to the Zeni server.
     */
    fun connect(url: String, coroutineScope: CoroutineScope, language: String = "en", voice: String = "Kore", personality: String = "assistant") {
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING) {
            Log.w(TAG, "Already connected or connecting")
            return
        }
        
        serverUrl = url
        scope = coroutineScope
        languagePreference = language
        voicePreference = voice
        personalityPreference = personality
        
        _connectionState.value = ConnectionState.CONNECTING
        
        try {
            client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // No timeout for WebSocket
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            webSocket = client!!.newWebSocket(request, createWebSocketListener())
            
            Log.d(TAG, "Connecting to $url")
            
        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
            _connectionState.value = ConnectionState.ERROR
            handleConnectionError()
        }
    }
    
    /**
     * Disconnect from the server.
     */
    fun disconnect() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        
        try {
            // Send session end if connected
            if (_connectionState.value == ConnectionState.CONNECTED && sessionId.isNotEmpty()) {
                sendSessionEnd()
            }
            
            webSocket?.close(1000, "Client disconnect")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
        
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        
        _connectionState.value = ConnectionState.DISCONNECTED
        _sessionState.value = SessionState.IDLE
        
        Log.d(TAG, "Disconnected")
    }
    
    /**
     * Send audio frame to server - OPTIMIZED with binary WebSocket.
     * 
     * Uses raw binary frames instead of JSON+Base64 for:
     * - 33% less data (no Base64 overhead)
     * - No JSON serialization overhead
     * - Faster processing on server
     */
    fun sendAudioFrame(audioData: ByteArray) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return
        }
        
        // OPTIMIZED: Send raw binary instead of JSON+Base64
        try {
            val sent = webSocket?.send(okio.ByteString.of(*audioData)) ?: false
            if (!sent) {
                Log.w(TAG, "Failed to send binary audio frame")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending binary audio", e)
        }
    }
    
    /**
     * Legacy JSON audio frame sender (kept for compatibility).
     */
    fun sendAudioFrameJson(audioData: ByteArray) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return
        }
        
        val base64Data = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val message = AudioFrameMessage(
            timestamp = System.currentTimeMillis(),
            data = base64Data,
            sequence = sequenceCounter.incrementAndGet()
        )
        
        sendMessage(message)
    }
    
    /**
     * Send interrupt signal.
     */
    fun sendInterrupt() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return
        }
        
        Log.d(TAG, "Sending interrupt")
        sendMessage(InterruptMessage())
    }
    
    /**
     * Send speech finished signal.
     */
    fun sendSpeechFinished() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return
        }
        
        Log.d(TAG, "Sending speech finished")
        sendMessage(SpeechFinishedMessage())
    }
    
    /**
     * Send image frame for visual context.
     * Called when mic button pressed - runs parallel to audio capture.
     */
    fun sendImageFrame(imageBase64: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return
        }
        
        Log.d(TAG, "Sending image frame for visual context")
        val message = mapOf(
            "type" to MessageType.IMAGE_FRAME,
            "data" to imageBase64,
            "timestamp" to System.currentTimeMillis()
        )
        sendMessage(message)
    }
    
    /**
     * Send robot connection status to server.
     * Called when robot connects/disconnects via Bluetooth.
     */
    fun sendRobotStatus(connected: Boolean) {
        Log.i(TAG, "sendRobotStatus called: connected=$connected, wsState=${_connectionState.value}")
        
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send robot status: WebSocket not connected")
            return
        }
        
        Log.i(TAG, ">>> SENDING ROBOT STATUS: connected=$connected <<<")
        val message = mapOf(
            "type" to MessageType.ROBOT_STATUS,
            "connected" to connected
        )
        val sent = sendMessage(message)
        Log.i(TAG, "Robot status message sent: $sent")
    }
    
    /**
     * Check if connected.
     */
    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
    
    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0
                
                // Start session
                startSession()
                
                // Start heartbeat
                startHeartbeat()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                
                if (code != 1000) {
                    handleConnectionError()
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                _connectionState.value = ConnectionState.ERROR
                handleConnectionError()
            }
        }
    }
    
    private fun startSession() {
        sessionId = java.util.UUID.randomUUID().toString()
        sequenceCounter.set(0)
        
        val message = SessionStartMessage(
            sessionId = sessionId,
            config = SessionConfig(
                languagePreference = languagePreference,
                voicePreference = voicePreference,
                personality = personalityPreference
            )
        )
        
        sendMessage(message)
        Log.d(TAG, "Session started: $sessionId with language: $languagePreference, voice: $voicePreference, personality: $personalityPreference")
    }
    
    private fun sendSessionEnd() {
        val message = SessionEndMessage(sessionId = sessionId)
        sendMessage(message)
    }
    
    /**
     * Change language during active session.
     */
    fun changeLanguage(language: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot change language - not connected")
            return
        }
        
        languagePreference = language
        val message = LanguageChangeMessage(language = language)
        sendMessage(message)
        Log.d(TAG, "Language changed to: $language")
    }

    /**
     * Change voice during active session.
     */
    fun changeVoice(voice: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot change voice - not connected")
            return
        }
        
        val message = VoiceChangeMessage(voice = voice)
        sendMessage(message)
        Log.d(TAG, "Voice changed to: $voice")
    }

    /**
     * Change TTS provider during active session.
     */
    fun changeTtsProvider(provider: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot change TTS provider - not connected")
            return
        }
        
        val message = TtsProviderChangeMessage(provider = provider)
        sendMessage(message)
        Log.d(TAG, "TTS provider changed to: $provider")
    }

    /**
     * Change TTS speed during active session.
     */
    fun changeTtsSpeed(speed: Float) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot change TTS speed - not connected")
            return
        }
        
        val message = TtsSpeedChangeMessage(speed = speed)
        sendMessage(message)
        Log.d(TAG, "TTS speed changed to: $speed")
    }

    /**
     * Change personality mode during active session.
     * @param personality "assistant" (professional) or "human" (expressive)
     */
    fun changePersonality(personality: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot change personality - not connected")
            return
        }
        
        personalityPreference = personality
        val message = PersonalityChangeMessage(personality = personality)
        sendMessage(message)
        Log.d(TAG, "Personality changed to: $personality")
    }
    
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope?.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendMessage(HeartbeatMessage())
            }
        }
    }
    
    private fun handleConnectionError() {
        heartbeatJob?.cancel()
        
        // Attempt reconnection
        if (serverUrl.isNotEmpty() && scope != null) {
            reconnectJob?.cancel()
            reconnectJob = scope?.launch {
                reconnectAttempts++
                val delay = minOf(
                    RECONNECT_DELAY_MS * (1 shl minOf(reconnectAttempts, 5)),
                    MAX_RECONNECT_DELAY_MS
                )
                
                Log.d(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
                _connectionState.value = ConnectionState.RECONNECTING
                
                delay(delay)
                
                if (isActive) {
                    connect(serverUrl, scope!!)
                }
            }
        }
    }
    
    private fun handleMessage(text: String) {
        try {
            val genericMsg = gson.fromJson(text, GenericMessage::class.java)
            
            when (genericMsg.type) {
                MessageType.SESSION_ACK -> {
                    val msg = gson.fromJson(text, SessionAckMessage::class.java)
                    Log.d(TAG, "Session acknowledged: ${msg.sessionId}")
                }
                
                MessageType.STATE_CHANGE -> {
                    val msg = gson.fromJson(text, StateChangeMessage::class.java)
                    val newState = try {
                        SessionState.valueOf(msg.state.uppercase())
                    } catch (e: Exception) {
                        SessionState.IDLE
                    }
                    _sessionState.value = newState
                    Log.d(TAG, "State changed: ${msg.previousState} -> ${msg.state}")
                }
                
                MessageType.TRANSCRIPT_PARTIAL -> {
                    val msg = gson.fromJson(text, TranscriptPartialMessage::class.java)
                    scope?.launch {
                        _transcriptPartial.emit(msg.text)
                    }
                }
                
                MessageType.TRANSCRIPT_FINAL -> {
                    val msg = gson.fromJson(text, TranscriptFinalMessage::class.java)
                    scope?.launch {
                        _transcriptFinal.emit(msg.text)
                    }
                }
                
                MessageType.LLM_TOKEN -> {
                    val msg = gson.fromJson(text, LLMTokenMessage::class.java)
                    scope?.launch {
                        _llmResponse.emit(msg.token)
                    }
                }
                
                MessageType.LLM_COMPLETE -> {
                    val msg = gson.fromJson(text, LLMCompleteMessage::class.java)
                    Log.d(TAG, "LLM complete: ${msg.fullText.take(50)}...")
                }
                
                MessageType.AUDIO_RESPONSE -> {
                    val msg = gson.fromJson(text, AudioResponseMessage::class.java)
                    val audioData = Base64.decode(msg.data, Base64.DEFAULT)
                    _audioResponse.trySend(AudioResponseData(
                        data = audioData,
                        sampleRate = msg.sampleRate,
                        isFinal = msg.isFinal
                    ))
                }
                
                MessageType.PLAYBACK_STOP -> {
                    scope?.launch {
                        _playbackStop.emit(Unit)
                    }
                }
                
                MessageType.ERROR -> {
                    val msg = gson.fromJson(text, ErrorMessage::class.java)
                    Log.e(TAG, "Server error: ${msg.code} - ${msg.message}")
                    scope?.launch {
                        _errors.emit(msg.message)
                    }
                }
                
                MessageType.HEARTBEAT_ACK -> {
                    // Heartbeat acknowledged
                }
                
                MessageType.PING -> {
                    // Server sent ping - respond with pong to keep connection alive
                    val pongMsg = """{"type":"pong","timestamp":${System.currentTimeMillis()}}"""
                    webSocket?.send(pongMsg)
                }
                
                MessageType.PONG -> {
                    // Server responded to our ping - connection is alive
                }
                
                MessageType.CAMPUS_TOUR -> {
                    val msg = gson.fromJson(text, CampusTourMessage::class.java)
                    Log.d(TAG, "Campus tour received: ${msg.name}")
                    scope?.launch {
                        _campusTour.emit(CampusTourData(
                            tourId = msg.tourId,
                            name = msg.name,
                            url = msg.url,
                            description = msg.description
                        ))
                    }
                }
                
                MessageType.FEE_STRUCTURE -> {
                    val msg = gson.fromJson(text, FeeStructureMessage::class.java)
                    Log.d(TAG, "Fee structure received: ${msg.programName}")
                    scope?.launch {
                        _feeStructure.emit(FeeStructureData(
                            programId = msg.programId,
                            programName = msg.programName,
                            url = msg.url
                        ))
                    }
                }
                
                MessageType.SHOW_PLACEMENTS -> {
                    val msg = gson.fromJson(text, PlacementMessage::class.java)
                    Log.d(TAG, "Placements received: ${msg.title}")
                    scope?.launch {
                        _placements.emit(PlacementData(
                            title = msg.title
                        ))
                    }
                }
                
                MessageType.REQUEST_IMAGE -> {
                    Log.d(TAG, "Server requesting image capture for vision analysis")
                    scope?.launch {
                        _imageCaptureRequest.emit(Unit)
                    }
                }
                
                MessageType.ROBOT_COMMAND -> {
                    val msg = gson.fromJson(text, RobotCommandMessage::class.java)
                    Log.d(TAG, "Robot command received: ${msg.action} for ${msg.durationMs}ms at ${msg.speedPercent}%")
                    scope?.launch {
                        _robotCommand.emit(RobotCommandData(
                            action = msg.action,
                            durationMs = msg.durationMs,
                            speedPercent = msg.speedPercent
                        ))
                    }
                }
                
                else -> {
                    Log.w(TAG, "Unknown message type: ${genericMsg.type}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message", e)
        }
    }
    
    private fun sendMessage(message: Any): Boolean {
        try {
            val json = gson.toJson(message)
            val ws = webSocket
            if (ws == null) {
                Log.e(TAG, "WebSocket is null, cannot send message")
                return false
            }
            val sent = ws.send(json)
            if (!sent) {
                Log.e(TAG, "WebSocket.send() returned false")
            }
            return sent
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            return false
        }
    }
}
