package com.zeni.voiceai.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeni.voiceai.network.ZeniWebSocketClient.ConnectionState
import com.zeni.voiceai.protocol.SessionState
import com.zeni.voiceai.ui.CampusTourActivity
import com.zeni.voiceai.ui.FeeStructureActivity
import com.zeni.voiceai.ui.PlacementGalleryActivity
import com.zeni.voiceai.ui.components.CharacterVideoView
import com.zeni.voiceai.ui.components.TalkButton
import com.zeni.voiceai.ui.theme.*
import com.zeni.voiceai.viewmodel.ZeniViewModel
import kotlinx.coroutines.launch

/**
 * Main screen for Zeni voice interaction.
 * Full-screen video avatar with minimal overlay UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ZeniViewModel = viewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val isAudioPlaying by viewModel.isAudioPlaying.collectAsState()
    val voicePreference by viewModel.voicePreference.collectAsState()
    val personalityPreference by viewModel.personalityPreference.collectAsState()
    
    // Robot state
    val robotConnected by viewModel.robotConnected.collectAsState()
    val availableRobots by viewModel.availableRobots.collectAsState()
    var showRobotDialog by remember { mutableStateOf(false) }
    
    var showSettings by remember { mutableStateOf(false) }
    var tempServerUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    
    // Female voices only
    val femaleVoices = listOf(
        "Achernar", "Aoede", "Autonoe", "Callirrhoe", "Despina",
        "Erinome", "Gacrux", "Kore", "Laomedeia", "Leda",
        "Pulcherrima", "Sulafat", "Vindemiatrix", "Zephyr"
    )
    
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    // Bluetooth permission launcher (for Android 12+)
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // Load paired devices and show dialog
            viewModel.loadPairedDevices()
            showRobotDialog = true
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Bluetooth permission required for robot control")
            }
        }
    }
    
    // Permission launcher for microphone (required)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Microphone permission required for voice chat")
            }
        }
    }
    
    // Initialize camera for visual context (requires lifecycle owner)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    
    // Camera permission launcher (optional - for visual context)
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Initialize camera after permission granted
            viewModel.initializeCamera(lifecycleOwner)
        }
        // Camera is optional, no action needed if denied
    }
    
    // Request camera permission on startup (camera is optional)
    LaunchedEffect(Unit) {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
    
    // Error handling
    LaunchedEffect(Unit) {
        viewModel.error.collect { error ->
            snackbarHostState.showSnackbar(error)
        }
    }
    
    // Sync personality from server on app startup
    LaunchedEffect(Unit) {
        viewModel.syncPersonalityFromServer()
    }
    
    // Get context for launching CampusTourActivity
    val context = LocalContext.current
    
    // Campus tour event - opens Matterport WebView
    LaunchedEffect(Unit) {
        viewModel.campusTour.collect { tourInfo ->
            context.startActivity(
                CampusTourActivity.createIntent(
                    context = context,
                    tourId = tourInfo.tourId,
                    tourName = tourInfo.name,
                    tourUrl = tourInfo.url,
                    tourDescription = tourInfo.description
                )
            )
        }
    }
    
    // Fee structure event - opens Fee WebView
    LaunchedEffect(Unit) {
        viewModel.feeStructure.collect { feeInfo ->
            context.startActivity(
                FeeStructureActivity.createIntent(
                    context = context,
                    programId = feeInfo.programId,
                    programName = feeInfo.programName,
                    feeUrl = feeInfo.url
                )
            )
        }
    }
    
    // Placement gallery event - opens top placements photo viewer
    LaunchedEffect(Unit) {
        viewModel.placements.collect { placementInfo ->
            context.startActivity(
                PlacementGalleryActivity.createIntent(
                    context = context,
                    title = placementInfo.title
                )
            )
        }
    }
    
    // Full-screen layout with video and overlays
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. FULL-SCREEN VIDEO CHARACTER (no margin, fit mode)
        CharacterVideoView(
            isAudioPlaying = isAudioPlaying,
            modifier = Modifier.fillMaxSize()
        )
        
        // 2. SETTINGS BUTTON (Top-right, small)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Small connection indicator
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (connectionState) {
                                ConnectionState.CONNECTED -> ZeniGreen
                                ConnectionState.CONNECTING -> ZeniOrange
                                else -> Color.Gray
                            }
                        )
                )
                
                // Small settings button
                IconButton(
                    onClick = { 
                        // Sync personality from server before opening settings
                        viewModel.syncPersonalityFromServer()
                        showSettings = true 
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                // Robot connection button
                IconButton(
                    onClick = { 
                        // Request Bluetooth permissions for Android 12+ before opening dialog
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            bluetoothPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_SCAN
                                )
                            )
                        } else {
                            // Load paired devices and show dialog
                            viewModel.loadPairedDevices()
                            showRobotDialog = true
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (robotConnected) ZeniGreen.copy(alpha = 0.6f) 
                            else Color.Black.copy(alpha = 0.4f)
                        )
                ) {
                    Icon(
                        Icons.Default.Bluetooth,
                        contentDescription = "Robot Connection",
                        tint = if (robotConnected) Color.White else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        
        // 3. HOLD TO SPEAK BUTTON (Bottom-center, faded)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            TalkButton(
                sessionState = sessionState,
                isRecording = isRecording,
                isAudioPlaying = isAudioPlaying,
                onPress = {
                    // Interrupt if speaking, then start recording
                    when {
                        connectionState != ConnectionState.CONNECTED -> {
                            viewModel.connect()
                        }
                        !viewModel.hasAudioPermission() -> {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        else -> {
                            // startRecording handles interrupt automatically
                            viewModel.startRecording()
                        }
                    }
                },
                onRelease = {
                    viewModel.stopRecording()
                },
                modifier = Modifier
                    .size(72.dp)
                    .alpha(0.7f) // Faded button
            )
        }
        
        // Snackbar host for messages
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
    
    // Settings Dialog (includes voice selection)
    if (showSettings) {
        SettingsDialog(
            serverUrl = tempServerUrl,
            connectionState = connectionState,
            voicePreference = voicePreference,
            personalityPreference = personalityPreference,
            femaleVoices = femaleVoices,
            onServerUrlChange = { tempServerUrl = it },
            onVoiceChange = { viewModel.setVoicePreference(it) },
            onPersonalityChange = { viewModel.setPersonalityPreference(it) },
            onConnect = { viewModel.connect() },
            onDisconnect = { viewModel.disconnect() },
            onDismiss = { showSettings = false },
            onSave = {
                viewModel.setServerUrl(tempServerUrl)
                showSettings = false
            }
        )
    }
    
    // Robot Connection Dialog
    if (showRobotDialog) {
        RobotConnectionDialog(
            robotConnected = robotConnected,
            availableDevices = availableRobots,
            onConnect = { address -> viewModel.connectRobot(address) },
            onDisconnect = { viewModel.disconnectRobot() },
            onDismiss = { showRobotDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    serverUrl: String,
    connectionState: ConnectionState,
    voicePreference: String,
    personalityPreference: String,
    femaleVoices: List<String>,
    onServerUrlChange: (String) -> Unit,
    onVoiceChange: (String) -> Unit,
    onPersonalityChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var voiceExpanded by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZeniSurface,
        titleContentColor = Color.White,
        textContentColor = Color.Gray,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Server URL
                Text("Server URL", style = MaterialTheme.typography.labelMedium, color = ZeniBlue)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = onServerUrlChange,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZeniBlue,
                        unfocusedBorderColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.Gray
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(Modifier.height(16.dp))
                
                // Connection status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Status: ${connectionState.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = when(connectionState) {
                            ConnectionState.CONNECTED -> ZeniGreen
                            ConnectionState.ERROR -> ZeniOrange
                            else -> Color.Gray
                        }
                    )
                    
                    if (connectionState == ConnectionState.CONNECTED) {
                        TextButton(onClick = onDisconnect) { Text("Disconnect", color = ZeniOrange) }
                    } else {
                        Button(
                            onClick = onConnect,
                            colors = ButtonDefaults.buttonColors(containerColor = ZeniBlue)
                        ) { Text("Connect") }
                    }
                }

                Spacer(Modifier.height(20.dp))
                
                // Voice Selection
                Text("Voice", style = MaterialTheme.typography.labelMedium, color = ZeniBlue)
                Spacer(Modifier.height(8.dp))
                
                ExposedDropdownMenuBox(
                    expanded = voiceExpanded,
                    onExpandedChange = { voiceExpanded = it }
                ) {
                    OutlinedTextField(
                        value = voicePreference,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ZeniBlue,
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = voiceExpanded,
                        onDismissRequest = { voiceExpanded = false },
                        modifier = Modifier
                            .background(ZeniSurface)
                            .heightIn(max = 250.dp)
                    ) {
                        femaleVoices.forEach { voice ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        voice,
                                        color = if (voice == voicePreference) ZeniBlue else Color.White
                                    )
                                },
                                onClick = {
                                    onVoiceChange(voice)
                                    voiceExpanded = false
                                },
                                leadingIcon = if (voice == voicePreference) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = ZeniBlue,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                
                // Personality Selection
                Text("AI Personality", style = MaterialTheme.typography.labelMedium, color = ZeniBlue)
                Spacer(Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Assistant Mode
                    Surface(
                        onClick = { onPersonalityChange("assistant") },
                        color = if (personalityPreference == "assistant") ZeniBlue.copy(alpha = 0.2f) else ZeniSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = if (personalityPreference == "assistant") 
                            androidx.compose.foundation.BorderStroke(2.dp, ZeniBlue) 
                            else null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("🤖", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                "Assistant",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (personalityPreference == "assistant") ZeniBlue else Color.White
                            )
                            Text(
                                "Professional",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                    
                    // Human Mode
                    Surface(
                        onClick = { onPersonalityChange("human") },
                        color = if (personalityPreference == "human") ZeniBlue.copy(alpha = 0.2f) else ZeniSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = if (personalityPreference == "human") 
                            androidx.compose.foundation.BorderStroke(2.dp, ZeniBlue) 
                            else null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("😊", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                "Human",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (personalityPreference == "human") ZeniBlue else Color.White
                            )
                            Text(
                                "Friendly",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                    
                    // General Mode (Free AI Chat)
                    Surface(
                        onClick = { onPersonalityChange("general") },
                        color = if (personalityPreference == "general") ZeniBlue.copy(alpha = 0.2f) else ZeniSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = if (personalityPreference == "general") 
                            androidx.compose.foundation.BorderStroke(2.dp, ZeniBlue) 
                            else null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("💬", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                "General",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (personalityPreference == "general") ZeniBlue else Color.White
                            )
                            Text(
                                "Free Chat",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                
                // TTS Engine info
                Text("TTS Engine", style = MaterialTheme.typography.labelMedium, color = ZeniBlue)
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = ZeniBlue.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = ZeniBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Google Cloud TTS", color = ZeniBlue, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("Save", color = ZeniBlue) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
        }
    )
}

/**
 * Dialog for connecting to robot via Bluetooth.
 * Shows paired devices directly - no scanning needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobotConnectionDialog(
    robotConnected: Boolean,
    availableDevices: List<com.zeni.voiceai.robot.RobotBluetoothService.BluetoothDeviceInfo>,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZeniSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = if (robotConnected) ZeniGreen else ZeniBlue
                )
                Text(
                    "Robot Connection",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                // Connection status
                Surface(
                    color = if (robotConnected) ZeniGreen.copy(alpha = 0.1f) else ZeniSurface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (robotConnected) ZeniGreen else Color.Gray)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (robotConnected) "Robot Connected" else "Not Connected",
                            color = if (robotConnected) ZeniGreen else Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        if (robotConnected) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onDisconnect) {
                                Text("Disconnect", color = Color.Red)
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // Paired devices section (only shown when not connected)
                if (!robotConnected) {
                    Text(
                        "Paired Devices",
                        style = MaterialTheme.typography.labelMedium,
                        color = ZeniBlue
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    // Device list
                    Surface(
                        color = Color.Black.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 250.dp)
                    ) {
                        if (availableDevices.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No paired devices found.\nPair your robot in Bluetooth settings first.",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp)
                            ) {
                                availableDevices.forEach { device ->
                                    Surface(
                                        onClick = { onConnect(device.address) },
                                        color = Color.Transparent,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Bluetooth,
                                                contentDescription = null,
                                                tint = ZeniBlue,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    device.name,
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    device.address,
                                                    color = Color.Gray,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                            Spacer(Modifier.weight(1f))
                                            Text(
                                                "Tap to connect",
                                                color = ZeniBlue,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    // Info text
                    Text(
                        "🤖 Tap a device to connect. Robot will be controlled by voice commands.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { 
                Text("Done", color = ZeniBlue) 
            }
        }
    )
}
