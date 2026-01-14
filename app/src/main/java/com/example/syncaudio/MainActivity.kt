package com.example.syncaudio

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.syncaudio.audio.AudioManager
import com.example.syncaudio.network.ClientReceiver
import com.example.syncaudio.network.HostServer
import com.example.syncaudio.sync.SyncManager
import com.example.syncaudio.ui.ClientScreen
import com.example.syncaudio.ui.HostScreen
import com.example.syncaudio.ui.RoleSelectionScreen
import com.example.syncaudio.ui.theme.SyncAudioTheme

class MainActivity : ComponentActivity() {
    
    private val audioManager = AudioManager()
    private val hostServer by lazy { HostServer(audioManager) }
    private val clientReceiver by lazy { ClientReceiver(audioManager) }
    private val syncManager = SyncManager()

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions handled - UI will check individually
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request all needed permissions upfront
        requestPermissions()
        
        enableEdgeToEdge()
        setContent {
            SyncAudioTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SyncAudioApp(
                        audioManager = audioManager,
                        hostServer = hostServer,
                        clientReceiver = clientReceiver,
                        syncManager = syncManager,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
        permissionLauncher.launch(permissions)
    }

    override fun onDestroy() {
        super.onDestroy()
        hostServer.stop()
        clientReceiver.release()
        audioManager.release()
    }
}

sealed class Screen(val route: String) {
    object RoleSelection : Screen("role_selection")
    object Host : Screen("host")
    object Client : Screen("client")
}

@Composable
fun SyncAudioApp(
    audioManager: AudioManager,
    hostServer: HostServer,
    clientReceiver: ClientReceiver,
    syncManager: SyncManager,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.RoleSelection.route,
        modifier = modifier
    ) {
        composable(Screen.RoleSelection.route) {
            RoleSelectionScreen(
                onHostClick = { navController.navigate(Screen.Host.route) },
                onClientClick = { navController.navigate(Screen.Client.route) }
            )
        }

        composable(Screen.Host.route) {
            HostScreen(
                audioManager = audioManager,
                hostServer = hostServer,
                syncManager = syncManager,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Client.route) {
            // Create fresh instances for client mode
            val clientAudioManager = remember { AudioManager() }
            val clientReceiverInstance = remember { ClientReceiver(clientAudioManager) }
            
            DisposableEffect(Unit) {
                onDispose {
                    clientReceiverInstance.release()
                    clientAudioManager.release()
                }
            }
            
            ClientScreen(
                audioManager = clientAudioManager,
                clientReceiver = clientReceiverInstance,
                onBack = { navController.popBackStack() }
            )
        }
    }
}