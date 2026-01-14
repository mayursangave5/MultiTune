package com.example.syncaudio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.syncaudio.audio.AudioManager
import com.example.syncaudio.data.DeviceInfo
import com.example.syncaudio.data.DeviceStatus
import com.example.syncaudio.network.HostServer
import com.example.syncaudio.sync.SyncManager
import kotlinx.coroutines.launch

/**
 * Host screen for selecting audio, viewing connected devices, and starting sync playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    audioManager: AudioManager,
    hostServer: HostServer,
    syncManager: SyncManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var isAudioLoaded by remember { mutableStateOf(false) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var serverIp by remember { mutableStateOf<String?>(null) }
    var isServerRunning by remember { mutableStateOf(false) }
    var connectedDevices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }

    // Update connected devices when they change
    LaunchedEffect(isServerRunning) {
        if (isServerRunning) {
            hostServer.onDeviceConnected = { 
                connectedDevices = hostServer.connectedDevices.toList()
            }
            hostServer.onDeviceReady = {
                connectedDevices = hostServer.connectedDevices.toList()
            }
        }
    }

    // File picker
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            isLoading = true
            selectedFileName = uri.lastPathSegment ?: "Unknown"
            
            scope.launch {
                val success = audioManager.loadToRam(uri, context)
                isLoading = false
                
                if (success) {
                    isAudioLoaded = true
                    audioManager.initializeTrack()
                    
                    // Start the server
                    hostServer.start()
                    serverIp = hostServer.getLocalIpAddress()
                    isServerRunning = true
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1a1a2e),
                        Color(0xFF16213e),
                        Color(0xFF0f3460)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Top Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = {
                    hostServer.stop()
                    audioManager.release()
                    onBack()
                }) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Text(
                    text = "Host Mode",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Server IP Display
            if (serverIp != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF2d3a5a)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Server Running",
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.SemiBold
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // QR Code
                        val qrBitmap = remember(serverIp) {
                            com.example.syncaudio.utils.QrCodeGenerator.generateQrCode(serverIp ?: "")
                        }
                        
                        qrBitmap?.let { bitmap ->
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Host IP QR Code",
                                modifier = Modifier
                                    .size(180.dp)
                                    .background(Color.White)
                                    .padding(8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = serverIp ?: "",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Scan with Client App",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Select Song Button
            Button(
                onClick = {
                    filePicker.launch(arrayOf("audio/*"))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAudioLoaded) Color(0xFF4CAF50) else Color(0xFF5c6bc0)
                ),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text(
                        text = if (isAudioLoaded) "✓ $selectedFileName" else "🎵 Select Song",
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Connected Devices Section
            Text(
                text = "Connected Devices",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (connectedDevices.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF2d3a5a)
                    )
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = if (isServerRunning) 
                                "Waiting for devices to connect...\n\nClients should scan QR or enter IP:\n${serverIp ?: ""}"
                            else 
                                "Select a song to start the server",
                            color = Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(connectedDevices) { device ->
                        DeviceCard(device = device)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Start Synced Playback Button
            val allReady = connectedDevices.isNotEmpty() && 
                           connectedDevices.all { it.status == DeviceStatus.READY }
            
            Button(
                onClick = {
                    if (!isPlaying) {
                        val triggerTime = syncManager.calculateTriggerTime()
                        hostServer.broadcastPlayCommand(triggerTime)
                        syncManager.executePlayback(audioManager, triggerTime)
                        isPlaying = true
                    } else {
                        audioManager.stop()
                        isPlaying = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPlaying) Color(0xFFE91E63) else Color(0xFF4CAF50)
                ),
                enabled = isAudioLoaded
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Check else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isPlaying) "Stop Playback" else "Start Synced Playback",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!allReady && connectedDevices.isNotEmpty()) {
                Text(
                    text = "⏳ Waiting for all devices to be ready...",
                    color = Color(0xFFFFC107),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2d3a5a)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = when (device.status) {
                            DeviceStatus.DOWNLOADING -> Color(0xFFFFC107)
                            DeviceStatus.READY -> Color(0xFF4CAF50)
                        },
                        shape = CircleShape
                    )
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.deviceName,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = device.ipAddress,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }

            Text(
                text = when (device.status) {
                    DeviceStatus.DOWNLOADING -> "Downloading..."
                    DeviceStatus.READY -> "Ready ✓"
                },
                color = when (device.status) {
                    DeviceStatus.DOWNLOADING -> Color(0xFFFFC107)
                    DeviceStatus.READY -> Color(0xFF4CAF50)
                },
                fontSize = 14.sp
            )
        }
    }
}
