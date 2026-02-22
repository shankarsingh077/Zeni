package com.zeni.voiceai.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.zeni.voiceai.audio.AudioCaptureEngine
import com.zeni.voiceai.audio.AudioPlaybackEngine
import com.zeni.voiceai.audio.VoiceActivityDetector
import com.zeni.voiceai.camera.CameraCapture
import com.zeni.voiceai.data.PreferencesManager
import com.zeni.voiceai.network.ZeniWebSocketClient
import com.zeni.voiceai.protocol.SessionState
import com.zeni.voiceai.robot.RobotBluetoothService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Main ViewModel for Zeni voice interaction.
 */
class ZeniViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "ZeniViewModel"
    }
    
    // Components
    private val webSocketClient = ZeniWebSocketClient()
    private val audioCaptureEngine = AudioCaptureEngine(application)
    private val audioPlaybackEngine = AudioPlaybackEngine()
    private val vad = VoiceActivityDetector()
    private val robotService = RobotBluetoothService(application)
    
    // Camera for visual context (optional - initializes when lifecycle available)
    private var cameraCapture: CameraCapture? = null
    private var cameraInitialized = false
    
    // Preferences - remembers settings between sessions
    private val preferencesManager = PreferencesManager(application)
    
    // State - initialized from saved preferences
    private val _serverUrl = MutableStateFlow(preferencesManager.getServerUrl())
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()
    
    private val _languagePreference = MutableStateFlow(preferencesManager.getLanguagePreference())
    val languagePreference: StateFlow<String> = _languagePreference.asStateFlow()
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    
    private val _voicePreference = MutableStateFlow(preferencesManager.getVoicePreference())
    val voicePreference: StateFlow<String> = _voicePreference.asStateFlow()
    
    private val _personalityPreference = MutableStateFlow(preferencesManager.getPersonality())
    val personalityPreference: StateFlow<String> = _personalityPreference.asStateFlow()
    
    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()
    
    private val _currentResponse = MutableStateFlow("")
    val currentResponse: StateFlow<String> = _currentResponse.asStateFlow()
    
    private val _conversationHistory = MutableStateFlow<List<ConversationItem>>(emptyList())
    val conversationHistory: StateFlow<List<ConversationItem>> = _conversationHistory.asStateFlow()
    
    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()
    
    // Robot connection state
    val robotConnected: StateFlow<Boolean> = robotService.isConnected
    val availableRobots: StateFlow<List<RobotBluetoothService.BluetoothDeviceInfo>> = robotService.availableDevices
    val isScanning: StateFlow<Boolean> = robotService.isScanning
    
    // Campus tour event for opening Matterport WebView
    private val _campusTour = MutableSharedFlow<CampusTourInfo>()
    val campusTour: SharedFlow<CampusTourInfo> = _campusTour.asSharedFlow()
    
    /**
     * Campus tour information.
     */
    data class CampusTourInfo(
        val tourId: String,
        val name: String,
        val url: String,
        val description: String
    )
    
    /**
     * Fee structure information.
     */
    data class FeeStructureInfo(
        val programId: String,
        val programName: String,
        val url: String
    )
    
    // Fee structure event for opening fee WebView
    private val _feeStructure = MutableSharedFlow<FeeStructureInfo>()
    val feeStructure: SharedFlow<FeeStructureInfo> = _feeStructure.asSharedFlow()
    
    /**
     * Placement gallery information.
     */
    data class PlacementInfo(
        val title: String
    )
    
    // Placement gallery event for showing top placements
    private val _placements = MutableSharedFlow<PlacementInfo>()
    val placements: SharedFlow<PlacementInfo> = _placements.asSharedFlow()
    
    // Expose WebSocket states
    val connectionState = webSocketClient.connectionState
    val sessionState = webSocketClient.sessionState
    
    // Expose audio amplitude for character animation
    val audioAmplitude = audioPlaybackEngine.audioAmplitude
    
    // TRUE only when audio is actually playing from speakers (for character video)
    val isAudioPlaying = audioPlaybackEngine.isActuallyPlaying
    
    private var audioProcessingJob: Job? = null
    private var responseBuilder = StringBuilder()
    
    init {
        setupObservers()
    }
    
    /**
     * Conversation item.
     */
    data class ConversationItem(
        val text: String,
        val isUser: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private fun setupObservers() {
        // Observe partial transcripts
        viewModelScope.launch {
            webSocketClient.transcriptPartial.collect { text ->
                _partialTranscript.value = text
            }
        }
        
        // Observe final transcripts
        viewModelScope.launch {
            webSocketClient.transcriptFinal.collect { text ->
                _partialTranscript.value = ""  // Clear immediately
                addConversationItem(text, isUser = true)
                
                // Also clear response from previous turn
                responseBuilder.clear()
                _currentResponse.value = ""
            }
        }
        
        // Observe LLM tokens
        viewModelScope.launch {
            webSocketClient.llmResponse.collect { token ->
                responseBuilder.append(token)
                _currentResponse.value = responseBuilder.toString()
            }
        }
        
        // Observe audio responses
        viewModelScope.launch {
            webSocketClient.audioResponse.collect { audioData ->
                audioPlaybackEngine.queueAudio(audioData.data, audioData.sampleRate)
                
                if (!audioPlaybackEngine.isPlaying()) {
                    audioPlaybackEngine.startPlayback(viewModelScope)
                }
                
                if (audioData.isFinal && responseBuilder.isNotEmpty()) {
                    addConversationItem(responseBuilder.toString(), isUser = false)
                    responseBuilder.clear()
                    _currentResponse.value = ""
                }
            }
        }
        
        // Observe playback stop
        viewModelScope.launch {
            webSocketClient.playbackStop.collect {
                audioPlaybackEngine.stopPlayback()
                
                // Save partial response if any
                if (responseBuilder.isNotEmpty()) {
                    addConversationItem(responseBuilder.toString() + "...", isUser = false)
                    responseBuilder.clear()
                    _currentResponse.value = ""
                }
            }
        }
        
        // Observe errors
        viewModelScope.launch {
            webSocketClient.errors.collect { errorMsg ->
                _error.emit(errorMsg)
            }
        }
        
        // Observe campus tour events
        viewModelScope.launch {
            webSocketClient.campusTour.collect { tourData ->
                Log.d(TAG, "Campus tour received: ${tourData.name}")
                _campusTour.emit(CampusTourInfo(
                    tourId = tourData.tourId,
                    name = tourData.name,
                    url = tourData.url,
                    description = tourData.description
                ))
            }
        }
        
        // Observe fee structure events
        viewModelScope.launch {
            webSocketClient.feeStructure.collect { feeData ->
                Log.d(TAG, "Fee structure received: ${feeData.programName}")
                _feeStructure.emit(FeeStructureInfo(
                    programId = feeData.programId,
                    programName = feeData.programName,
                    url = feeData.url
                ))
            }
        }
        
        // Observe placement gallery events
        viewModelScope.launch {
            webSocketClient.placements.collect { placementData ->
                Log.d(TAG, "Placements received: ${placementData.title}")
                _placements.emit(PlacementInfo(
                    title = placementData.title
                ))
            }
        }
        
        // Observe image capture requests from server (for vision analysis)
        viewModelScope.launch {
            webSocketClient.imageCaptureRequest.collect {
                Log.d(TAG, "Server requested image capture for vision")
                captureVisualContext()
            }
        }
        
        // Observe session state for playback control
        viewModelScope.launch {
            sessionState.collect { state ->
                when (state) {
                    SessionState.SPEAKING -> {
                        // Start playback if not already
                        if (!audioPlaybackEngine.isPlaying()) {
                            audioPlaybackEngine.initialize()
                            audioPlaybackEngine.startPlayback(viewModelScope)
                        }
                    }
                    SessionState.IDLE, SessionState.LISTENING -> {
                        // Response complete
                    }
                    else -> {}
                }
            }
        }
        
        // Observe robot commands from server
        viewModelScope.launch {
            webSocketClient.robotCommand.collect { robotCmd ->
                Log.d(TAG, "Robot command received: ${robotCmd.action}")
                if (robotService.isConnected.value) {
                    robotService.executeMovement(
                        direction = robotCmd.action,
                        durationMs = robotCmd.durationMs,
                        speedPercent = robotCmd.speedPercent
                    )
                } else {
                    Log.w(TAG, "Robot not connected, ignoring command")
                }
            }
        }
        
        // Observe robot connection state and notify server
        viewModelScope.launch {
            robotService.isConnected.collect { connected ->
                Log.i(TAG, ">>> ROBOT CONNECTION STATE OBSERVER: connected=$connected <<<")
                webSocketClient.sendRobotStatus(connected)
            }
        }
        
        // When WebSocket connects, send current robot status
        // This handles case where robot was connected before WebSocket, or WebSocket reconnects
        viewModelScope.launch {
            webSocketClient.connectionState.collect { state ->
                Log.i(TAG, ">>> WEBSOCKET STATE OBSERVER: state=$state <<<")
                if (state == ZeniWebSocketClient.ConnectionState.CONNECTED) {
                    val robotConnected = robotService.isConnected.value
                    Log.i(TAG, ">>> WebSocket connected, sending robot status: $robotConnected <<<")
                    webSocketClient.sendRobotStatus(robotConnected)
                }
            }
        }
    }
    
    /**
     * Set server URL and save to preferences.
     */
    fun setServerUrl(url: String) {
        _serverUrl.value = url
        preferencesManager.setServerUrl(url)
        Log.d(TAG, "Server URL saved: $url")
    }
    
    /**
     * Set language preference ("en" or "hi") and save to preferences.
     */
    fun setLanguagePreference(language: String) {
        _languagePreference.value = language
        preferencesManager.setLanguagePreference(language)
        
        // If already connected, send language change message
        if (connectionState.value == ZeniWebSocketClient.ConnectionState.CONNECTED) {
            webSocketClient.changeLanguage(language)
        }
    }

    /**
     * Set voice preference and save to preferences.
     */
    fun setVoicePreference(voice: String) {
        _voicePreference.value = voice
        preferencesManager.setVoicePreference(voice)
        Log.d(TAG, "Voice preference saved: $voice")
        
        // If already connected, send voice change message
        if (connectionState.value == ZeniWebSocketClient.ConnectionState.CONNECTED) {
            webSocketClient.changeVoice(voice)
        }
    }

    /**
     * Set personality preference and save to preferences.
     * Also syncs to server via HTTP API for dashboard sync.
     */
    fun setPersonalityPreference(personality: String) {
        _personalityPreference.value = personality
        preferencesManager.setPersonality(personality)
        Log.d(TAG, "Personality preference saved: $personality")
        
        // Sync to server API for dashboard sync
        viewModelScope.launch {
            try {
                syncPersonalityToServer(personality)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync personality to server", e)
            }
        }
        
        // If already connected via WebSocket, send personality change message
        if (connectionState.value == ZeniWebSocketClient.ConnectionState.CONNECTED) {
            webSocketClient.changePersonality(personality)
        }
    }
    
    /**
     * Sync personality setting to server via HTTP API.
     */
    private suspend fun syncPersonalityToServer(personality: String) {
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = preferencesManager.getHttpBaseUrl()
                val url = java.net.URL("$baseUrl/api/settings/personality")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                
                val jsonBody = """{"personality": "$personality"}"""
                connection.outputStream.bufferedWriter().use { it.write(jsonBody) }
                
                val responseCode = connection.responseCode
                Log.d(TAG, "Personality sync response: $responseCode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync personality", e)
            }
        }
    }
    
    /**
     * Fetch personality from server (for syncing from admin dashboard).
     */
    fun syncPersonalityFromServer() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val baseUrl = preferencesManager.getHttpBaseUrl()
                    val url = java.net.URL("$baseUrl/api/settings")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "GET"
                    
                    if (connection.responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().readText()
                        // Parse JSON manually (simple parsing)
                        val personalityMatch = """"personality":\s*"(\w+)"""".toRegex().find(response)
                        personalityMatch?.groupValues?.get(1)?.let { serverPersonality ->
                            if (serverPersonality != _personalityPreference.value) {
                                withContext(Dispatchers.Main) {
                                    _personalityPreference.value = serverPersonality
                                    preferencesManager.setPersonality(serverPersonality)
                                    Log.d(TAG, "Personality synced from server: $serverPersonality")
                                    
                                    // Update WebSocket if connected
                                    if (connectionState.value == ZeniWebSocketClient.ConnectionState.CONNECTED) {
                                        webSocketClient.changePersonality(serverPersonality)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch personality from server", e)
                }
            }
        }
    }

    private val _ttsProvider = MutableStateFlow("google")
    val ttsProvider: StateFlow<String> = _ttsProvider.asStateFlow()

    private val _speakingRate = MutableStateFlow(1.0f)
    val speakingRate: StateFlow<Float> = _speakingRate.asStateFlow()

    /**
     * Set TTS provider preference.
     */
    fun setTtsProvider(provider: String) {
        _ttsProvider.value = provider
        
        // If already connected, send change message
        if (connectionState.value == ZeniWebSocketClient.ConnectionState.CONNECTED) {
            webSocketClient.changeTtsProvider(provider)
        }
    }

    /**
     * Set TTS speaking rate.
     */
    fun setSpeakingRate(rate: Float) {
        _speakingRate.value = rate
        
        // If already connected, send change message
        if (connectionState.value == ZeniWebSocketClient.ConnectionState.CONNECTED) {
            webSocketClient.changeTtsSpeed(rate)
        }
    }
    
    /**
     * Connect to server.
     */
    fun connect() {
        if (connectionState.value == ZeniWebSocketClient.ConnectionState.CONNECTED) {
            return
        }
        
        // Sync personality from server first
        syncPersonalityFromServer()
        
        webSocketClient.connect(
            _serverUrl.value, 
            viewModelScope, 
            _languagePreference.value,
            _voicePreference.value,  // Pass voice preference
            _personalityPreference.value  // Pass personality preference
        )
    }
    
    /**
     * Disconnect from server.
     */
    fun disconnect() {
        stopRecording()
        webSocketClient.disconnect()
    }
    
    /**
     * Start recording and streaming.
     */
    fun startRecording() {
        if (!audioCaptureEngine.hasPermission()) {
            viewModelScope.launch {
                _error.emit("Microphone permission required")
            }
            return
        }
        
        if (!webSocketClient.isConnected()) {
            viewModelScope.launch {
                _error.emit("Not connected to server")
            }
            return
        }
        
        if (_isRecording.value) {
            return
        }
        
        // Initialize audio capture
        if (!audioCaptureEngine.initialize()) {
            viewModelScope.launch {
                _error.emit("Failed to initialize audio capture")
            }
            return
        }
        
        // Check if we need to interrupt - either by session state OR actual audio playback
        val currentSessionState = sessionState.value
        val isPlaying = audioPlaybackEngine.isActuallyPlaying.value
        
        if (currentSessionState == SessionState.GENERATING || 
            currentSessionState == SessionState.SPEAKING ||
            currentSessionState == SessionState.TRANSCRIBING ||
            isPlaying) {
            // INTERRUPT: Stop everything and prepare for new input
            Log.d(TAG, "Interrupting: state=$currentSessionState, isPlaying=$isPlaying")
            
            // 1. Send interrupt to server (stops TTS generation)
            webSocketClient.sendInterrupt()
            
            // 2. Stop local audio playback immediately
            audioPlaybackEngine.stopPlayback()
        }
        
        // Capture and send image IMMEDIATELY when mic is pressed
        // Server will have it ready if LLM needs vision (no round-trip delay!)
        captureVisualContext()
        
        // Start capture
        if (!audioCaptureEngine.startRecording(viewModelScope)) {
            viewModelScope.launch {
                _error.emit("Failed to start recording")
            }
            return
        }
        
        _isRecording.value = true
        vad.reset()
        
        // Start processing audio frames
        audioProcessingJob = viewModelScope.launch {
            audioCaptureEngine.audioFrames.collect { audioData ->
                // Send to server
                webSocketClient.sendAudioFrame(audioData)
            }
        }
        
        Log.d(TAG, "Recording started")
    }
    
    /**
     * Stop recording.
     */
    fun stopRecording() {
        if (!_isRecording.value) {
            return
        }
        
        _isRecording.value = false
        audioProcessingJob?.cancel()
        audioCaptureEngine.stopRecording()
        
        // Send explicit end of speech signal
        webSocketClient.sendSpeechFinished()
        
        Log.d(TAG, "Recording stopped")
    }
    
    /**
     * Toggle recording state.
     */
    fun toggleRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }
    
    /**
     * Send interrupt signal.
     */
    fun sendInterrupt() {
        webSocketClient.sendInterrupt()
        audioPlaybackEngine.stopPlayback()
    }
    
    /**
     * Clear conversation history.
     */
    fun clearHistory() {
        _conversationHistory.value = emptyList()
    }
    
    /**
     * Check if has audio permission.
     */
    fun hasAudioPermission(): Boolean = audioCaptureEngine.hasPermission()
    
    /**
     * Initialize camera for visual context capture.
     * Call this from Activity/Fragment with lifecycle owner.
     */
    fun initializeCamera(lifecycleOwner: LifecycleOwner) {
        if (cameraInitialized) return
        
        try {
            cameraCapture = CameraCapture(getApplication())
            cameraCapture?.initialize(lifecycleOwner) {
                cameraInitialized = true
                Log.i(TAG, "Camera initialized for visual context")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Camera initialization failed (visual context disabled)", e)
        }
    }
    
    /**
     * Capture image for visual context. Non-blocking.
     * Called automatically when recording starts.
     * Uses FRESH capture to ensure current view, not stale cache.
     */
    private fun captureVisualContext() {
        if (!cameraInitialized) {
            Log.w(TAG, "Camera not initialized - visual context skipped")
            return
        }
        if (cameraCapture == null) {
            Log.w(TAG, "CameraCapture is null - visual context skipped")
            return
        }
        
        Log.d(TAG, "Capturing FRESH visual context...")
        cameraCapture?.captureFreshFrame { base64Image ->
            if (base64Image != null) {
                webSocketClient.sendImageFrame(base64Image)
                Log.d(TAG, "Visual context sent (${base64Image.length} chars)")
            } else {
                Log.w(TAG, "Visual context capture returned null")
            }
        }
    }
    
    private fun addConversationItem(text: String, isUser: Boolean) {
        val current = _conversationHistory.value.toMutableList()
        current.add(ConversationItem(text, isUser))
        _conversationHistory.value = current
    }
    
    // ============ Robot Control Methods ============
    
    /**
     * Start scanning for Bluetooth robot devices.
     */
    fun startRobotScan() {
        robotService.startScan()
    }
    
    /**
     * Stop scanning for Bluetooth devices.
     */
    fun stopRobotScan() {
        robotService.stopScan()
    }
    
    /**
     * Connect to a robot device by address.
     */
    fun connectRobot(address: String) {
        viewModelScope.launch {
            try {
                robotService.connect(address)
                Log.i(TAG, "Robot connected: $address")
            } catch (e: Exception) {
                Log.e(TAG, "Robot connection failed: ${e.message}")
                _error.emit("Robot connection failed: ${e.message}")
            }
        }
    }
    
    /**
     * Disconnect from robot.
     */
    fun disconnectRobot() {
        robotService.disconnect()
        Log.i(TAG, "Robot disconnected")
    }
    
    /**
     * Load paired Bluetooth devices. Call before showing robot dialog.
     */
    fun loadPairedDevices() {
        robotService.refreshDevices()
        Log.d(TAG, "Loaded paired devices")
    }
    
    /**
     * Emergency stop - immediately stops robot movement.
     */
    fun emergencyStopRobot() {
        robotService.emergencyStop()
        Log.i(TAG, "Robot emergency stop")
    }
    
    override fun onCleared() {
        super.onCleared()
        disconnect()
        robotService.disconnect()
        audioCaptureEngine.release()
        audioPlaybackEngine.release()
        cameraCapture?.release()
    }
}
