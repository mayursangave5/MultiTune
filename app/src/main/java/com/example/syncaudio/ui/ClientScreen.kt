package com.example.syncaudio.ui

import android.Manifest
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.syncaudio.audio.AudioManager
import com.example.syncaudio.data.ClientStatus
import com.example.syncaudio.network.ClientReceiver
import kotlinx.coroutines.launch

/**
 * Client screen for scanning QR, downloading audio, and displaying sync status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(
    audioManager: AudioManager,
    clientReceiver: ClientReceiver,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hostIp by remember { mutableStateOf("") }
    var manualIpInput by remember { mutableStateOf("") }
    var showManualInput by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(ClientStatus.IDLE) }
    var progress by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasCameraPermission by remember { mutableStateOf(false) }

    // Check camera permission
    LaunchedEffect(Unit) {
        val permission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        )
        hasCameraPermission = permission == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // Setup callbacks
    LaunchedEffect(Unit) {
        clientReceiver.onProgressUpdate = { progress = it }
        clientReceiver.onStatusChange = { status = it }
        clientReceiver.onPlayCommand = { triggerTime ->
            audioManager.playAtTime(triggerTime)
        }
    }

    // Connect and download when IP is set
    LaunchedEffect(hostIp) {
        if (hostIp.isNotEmpty()) {
            scope.launch {
                try {
                    status = ClientStatus.CONNECTING
                    
                    // Download the audio
                    val downloadSuccess = clientReceiver.downloadAudio(hostIp)
                    
                    if (downloadSuccess) {
                        // Sync clock
                        val syncSuccess = clientReceiver.syncClock(hostIp)
                        
                        if (syncSuccess) {
                            // Start listening for play commands
                            clientReceiver.startListeningForPlayCommands()
                            
                            // Send ready signal
                            clientReceiver.sendReadySignal(hostIp)
                            status = ClientStatus.READY
                        } else {
                            errorMessage = "Failed to sync clock with host"
                            status = ClientStatus.ERROR
                        }
                    } else {
                        errorMessage = "Failed to download audio"
                        status = ClientStatus.ERROR
                    }
                } catch (e: Exception) {
                    errorMessage = e.message
                    status = ClientStatus.ERROR
                }
            }
        }
    }

    // Cleanup on exit
    DisposableEffect(Unit) {
        onDispose {
            clientReceiver.release()
            audioManager.release()
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
                    clientReceiver.release()
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
                    text = "Client Mode",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Main content based on status
            when {
                status == ClientStatus.IDLE && hostIp.isEmpty() -> {
                    // Show QR scanner or manual input
                    ScannerSection(
                        showManualInput = showManualInput,
                        manualIpInput = manualIpInput,
                        hasCameraPermission = hasCameraPermission,
                        onManualIpChange = { manualIpInput = it },
                        onToggleManualInput = { showManualInput = !showManualInput },
                        onConnect = { ip -> hostIp = ip },
                        onQrScanned = { hostIp = it }
                    )
                }
                
                status == ClientStatus.CONNECTING || status == ClientStatus.DOWNLOADING -> {
                    DownloadingSection(progress = progress, hostIp = hostIp)
                }
                
                status == ClientStatus.SYNCING -> {
                    SyncingSection()
                }
                
                status == ClientStatus.READY -> {
                    ReadySection()
                }
                
                status == ClientStatus.PLAYING -> {
                    PlayingSection()
                }
                
                status == ClientStatus.ERROR -> {
                    ErrorSection(
                        errorMessage = errorMessage,
                        onRetry = {
                            hostIp = ""
                            errorMessage = null
                            status = ClientStatus.IDLE
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerSection(
    showManualInput: Boolean,
    manualIpInput: String,
    hasCameraPermission: Boolean,
    onManualIpChange: (String) -> Unit,
    onToggleManualInput: () -> Unit,
    onConnect: (String) -> Unit,
    onQrScanned: (String) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Scan Host QR Code",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (!showManualInput && hasCameraPermission) {
            QrScannerView(
                onQrCodeScanned = onQrScanned,
                modifier = Modifier.padding(16.dp)
            )
        } else if (!hasCameraPermission) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2d3a5a))
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "Camera permission required\nfor QR scanning",
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Toggle manual input
        TextButton(onClick = onToggleManualInput) {
            Text(
                text = if (showManualInput) "Use QR Scanner" else "Enter IP Manually",
                color = Color(0xFF5c6bc0)
            )
        }

        if (showManualInput) {
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = manualIpInput,
                onValueChange = onManualIpChange,
                label = { Text("Host IP Address") },
                placeholder = { Text("192.168.1.100") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { 
                        if (manualIpInput.isNotEmpty()) {
                            onConnect(manualIpInput)
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF5c6bc0),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    focusedLabelColor = Color(0xFF5c6bc0),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onConnect(manualIpInput) },
                enabled = manualIpInput.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5c6bc0))
            ) {
                Text("Connect", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun DownloadingSection(progress: Float, hostIp: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "📥 Downloading Audio",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "From: $hostIp",
            color = Color.White.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Progress bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .padding(horizontal = 32.dp),
            color = Color(0xFF4CAF50),
            trackColor = Color.White.copy(alpha = 0.2f),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "${(progress * 100).toInt()}%",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun SyncingSection() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = Color(0xFF5c6bc0),
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "⏱️ Synchronizing Clock",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Calculating time offset with host...",
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ReadySection() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "✓",
            fontSize = 80.sp,
            color = Color(0xFF4CAF50),
            modifier = Modifier.scale(scale)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Ready & Synchronized!",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Waiting for host to start playback...",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp
        )
    }
}

@Composable
private fun PlayingSection() {
    val infiniteTransition = rememberInfiniteTransition(label = "playing")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🎵",
            fontSize = 100.sp,
            modifier = Modifier.scale(scale)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Playing in Sync!",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4CAF50)
        )
    }
}

@Composable
private fun ErrorSection(
    errorMessage: String?,
    onRetry: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "❌",
            fontSize = 80.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Connection Error",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE91E63)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = errorMessage ?: "Unknown error occurred",
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5c6bc0)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Try Again", fontSize = 16.sp)
        }
    }
}
