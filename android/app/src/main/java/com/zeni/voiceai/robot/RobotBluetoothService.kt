package com.zeni.voiceai.robot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth service for robot control.
 * Uses SPP (Serial Port Profile) to send single-byte ASCII commands.
 * 
 * COMMAND PROTOCOL:
 * Movement: F=Forward, B=Back, L=Left, R=Right, S=Stop
 * Diagonal: G=Forward-Left, I=Forward-Right, H=Back-Left, J=Back-Right
 * Speed: 0-9, q=100%
 * Accessories: W/w=Front lights, U/u=Back lights, V/v=Horn, X/x=Extra
 */
class RobotBluetoothService(private val context: Context) {

    companion object {
        private const val TAG = "RobotBluetooth"
        // Standard SPP UUID for Bluetooth serial communication
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        
        // Movement commands
        const val CMD_FORWARD = "F"
        const val CMD_BACKWARD = "B"
        const val CMD_LEFT = "L"
        const val CMD_RIGHT = "R"
        const val CMD_STOP = "S"
        const val CMD_STOP_ALL = "D"
        
        // Diagonal movement
        const val CMD_FORWARD_LEFT = "G"
        const val CMD_FORWARD_RIGHT = "I"
        const val CMD_BACK_LEFT = "H"
        const val CMD_BACK_RIGHT = "J"
        
        // Speed levels (0=stop, 1-9=10%-90%, q=100%)
        val SPEED_COMMANDS = arrayOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "q")
        
        // Accessories
        const val CMD_FRONT_LIGHTS_ON = "W"
        const val CMD_FRONT_LIGHTS_OFF = "w"
        const val CMD_BACK_LIGHTS_ON = "U"
        const val CMD_BACK_LIGHTS_OFF = "u"
        const val CMD_HORN_ON = "V"
        const val CMD_HORN_OFF = "v"
        const val CMD_EXTRA_ON = "X"
        const val CMD_EXTRA_OFF = "x"
        
        // Safe movement duration for small room (milliseconds)
        // Movements executed in 1-second bursts for safety
        const val SAFE_BURST_DURATION_MS = 1000L
        const val MAX_TOTAL_DURATION_MS = 5000L  // Max 5 seconds total
    }
    
    /**
     * Simple device info for UI display.
     */
    data class BluetoothDeviceInfo(
        val name: String,
        val address: String
    )
    
    // Connection state
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
    
    // Coroutine scope - must be declared before isConnected
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var movementJob: Job? = null
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // Simple boolean for connection status - DIRECT update, no derived flow
    // This is more reliable than using map/stateIn which can have timing issues
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    
    private val _availableDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<BluetoothDeviceInfo>> = _availableDevices.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    
    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
    }
    
    /**
     * Check if Bluetooth is available and enabled.
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * Get list of paired Bluetooth devices as BluetoothDeviceInfo.
     */
    @SuppressLint("MissingPermission")
    private fun getPairedDevices(): List<BluetoothDeviceInfo> {
        return try {
            bluetoothAdapter?.bondedDevices?.map { device ->
                BluetoothDeviceInfo(
                    name = device.name ?: "Unknown Device",
                    address = device.address
                )
            } ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied", e)
            emptyList()
        }
    }
    
    /**
     * Start scanning for Bluetooth devices (shows paired devices).
     */
    fun startScan() {
        _isScanning.value = true
        _availableDevices.value = getPairedDevices()
        // For paired devices, scanning is instant
        scope.launch {
            delay(1000) // Simulate scan time for UI feedback
            _isScanning.value = false
        }
    }
    
    /**
     * Stop scanning for Bluetooth devices.
     */
    fun stopScan() {
        _isScanning.value = false
    }
    
    /**
     * Refresh available paired devices.
     */
    @SuppressLint("MissingPermission")
    fun refreshDevices() {
        try {
            _availableDevices.value = getPairedDevices()
            Log.d(TAG, "Refreshed devices: ${_availableDevices.value.size} found")
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied when refreshing devices", e)
            _availableDevices.value = emptyList()
        }
    }
    
    /**
     * Connect to a Bluetooth device by address.
     */
    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            Log.e(TAG, "Device not found: $address")
            _connectionState.value = ConnectionState.ERROR
            return
        }
        
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                Log.d(TAG, "Connecting to ${device.name ?: device.address}")
                
                // Cancel discovery to speed up connection
                bluetoothAdapter?.cancelDiscovery()
                
                // Create RFCOMM socket
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                
                // Connect (blocking)
                bluetoothSocket?.connect()
                
                // Get output stream for sending commands
                outputStream = bluetoothSocket?.outputStream
                
                _connectionState.value = ConnectionState.CONNECTED
                _isConnected.value = true  // DIRECT update - critical for server notification
                _connectedDeviceName.value = device.name ?: device.address
                
                Log.i(TAG, ">>> ROBOT CONNECTED! _connectionState=CONNECTED, _isConnected=true <<<")
                Log.i(TAG, ">>> isConnected.value now = ${isConnected.value} <<<")
                
                // Send initial stop command
                sendCommand(CMD_STOP)
                
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = ConnectionState.ERROR
                disconnect()
            } catch (e: SecurityException) {
                Log.e(TAG, "Bluetooth permission denied", e)
                _connectionState.value = ConnectionState.ERROR
                _isConnected.value = false
            }
        }
    }
    
    /**
     * Disconnect from the robot.
     */
    fun disconnect() {
        scope.launch {
            try {
                // Stop any ongoing movement
                movementJob?.cancel()
                sendCommand(CMD_STOP)
                
                outputStream?.close()
                bluetoothSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing connection", e)
            } finally {
                bluetoothSocket = null
                outputStream = null
                _connectionState.value = ConnectionState.DISCONNECTED
                _isConnected.value = false  // DIRECT update for reliable server notification
                _connectedDeviceName.value = null
                Log.i(TAG, ">>> ROBOT DISCONNECTED! _isConnected=false <<<")
            }
        }
    }
    
    /**
     * Send a single command byte to the robot.
     */
    private fun sendCommand(command: String) {
        try {
            if (_connectionState.value == ConnectionState.CONNECTED) {
                outputStream?.write(command.toByteArray())
                outputStream?.flush()
                Log.d(TAG, "Sent command: $command")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send command", e)
            _connectionState.value = ConnectionState.ERROR
            _isConnected.value = false
        }
    }
    
    /**
     * Execute a safe movement command with auto-stop.
     * For safety, movements are executed in 1-second bursts.
     * Longer durations are broken into multiple bursts.
     * 
     * @param direction Movement direction (forward, backward, left, right)
     * @param durationMs Total duration in milliseconds (max 5000ms)
     * @param speedPercent Speed 0-100 (default 50 for safety)
     */
    fun executeMovement(direction: String, durationMs: Long = SAFE_BURST_DURATION_MS, speedPercent: Int = 50) {
        // Cancel any previous movement
        movementJob?.cancel()
        
        movementJob = scope.launch {
            try {
                // Cap total duration for safety (max 5 seconds)
                val totalDuration = minOf(durationMs, MAX_TOTAL_DURATION_MS)
                
                // Set speed (convert 0-100 to command index)
                val speedIndex = (speedPercent / 10).coerceIn(0, 10)
                val speedCmd = SPEED_COMMANDS[speedIndex]
                sendCommand(speedCmd)
                
                // Small delay for speed to take effect
                delay(50)
                
                // Get the movement command for this direction
                val moveCmd = when (direction.lowercase()) {
                    "forward" -> CMD_FORWARD
                    "backward", "back" -> CMD_BACKWARD
                    "left" -> CMD_LEFT
                    "right" -> CMD_RIGHT
                    "stop" -> {
                        sendCommand(CMD_STOP)
                        return@launch
                    }
                    else -> {
                        Log.w(TAG, "Unknown direction: $direction")
                        sendCommand(CMD_STOP)
                        return@launch
                    }
                }
                
                // Execute movement in safe 1-second bursts
                var remainingTime = totalDuration
                var burstCount = 0
                
                while (remainingTime > 0 && isActive) {
                    val burstDuration = minOf(remainingTime, SAFE_BURST_DURATION_MS)
                    burstCount++
                    
                    Log.d(TAG, "Executing burst $burstCount: $direction for ${burstDuration}ms")
                    
                    // Start movement
                    sendCommand(moveCmd)
                    
                    // Wait for burst duration
                    delay(burstDuration)
                    
                    // Stop between bursts (safety check)
                    sendCommand(CMD_STOP)
                    
                    remainingTime -= burstDuration
                    
                    // Small pause between bursts for safety
                    if (remainingTime > 0) {
                        delay(50)
                    }
                }
                
                Log.i(TAG, "Movement complete: $direction for ${totalDuration}ms ($burstCount bursts) at $speedPercent% speed")
                
            } catch (e: CancellationException) {
                // Movement cancelled, stop robot
                sendCommand(CMD_STOP)
                Log.d(TAG, "Movement cancelled")
            }
        }
    }
    
    /**
     * Emergency stop - immediately stops all movement.
     */
    fun emergencyStop() {
        movementJob?.cancel()
        sendCommand(CMD_STOP_ALL)
        sendCommand(CMD_STOP)
        Log.w(TAG, "EMERGENCY STOP")
    }
    
    /**
     * Toggle front lights.
     */
    fun setFrontLights(on: Boolean) {
        sendCommand(if (on) CMD_FRONT_LIGHTS_ON else CMD_FRONT_LIGHTS_OFF)
    }
    
    /**
     * Toggle back lights.
     */
    fun setBackLights(on: Boolean) {
        sendCommand(if (on) CMD_BACK_LIGHTS_ON else CMD_BACK_LIGHTS_OFF)
    }
    
    /**
     * Toggle horn.
     */
    fun setHorn(on: Boolean) {
        sendCommand(if (on) CMD_HORN_ON else CMD_HORN_OFF)
    }
    
    /**
     * Cleanup resources.
     */
    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
