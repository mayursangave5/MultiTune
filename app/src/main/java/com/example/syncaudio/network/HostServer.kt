package com.example.syncaudio.network

import android.util.Log
import com.example.syncaudio.audio.AudioManager
import com.example.syncaudio.data.DeviceInfo
import com.example.syncaudio.data.DeviceStatus
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Ktor-based HTTP server for hosting audio files and UDP broadcaster for sync commands.
 * - Serves audio directly from RAM (no disk I/O)
 * - Tracks connected devices
 * - Broadcasts synchronized play commands via UDP
 */
class HostServer(private val audioManager: AudioManager) {
    companion object {
        private const val TAG = "HostServer"
        const val DEFAULT_HTTP_PORT = 8080
        const val UDP_PORT = 9999
        const val BROADCAST_PORT = 9998
    }

    private var server: ApplicationEngine? = null
    private var udpListenerJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    
    private val _connectedDevices = ConcurrentHashMap<String, DeviceInfo>()
    val connectedDevices: List<DeviceInfo>
        get() = _connectedDevices.values.toList()

    var onDeviceConnected: ((DeviceInfo) -> Unit)? = null
    var onDeviceReady: ((DeviceInfo) -> Unit)? = null

    /**
     * Start the HTTP server and UDP listener
     */
    fun start(port: Int = DEFAULT_HTTP_PORT) {
        // Ensure previous instance is stopped
        stop()
        
        Log.d(TAG, "Starting server on port $port")

        // Small delay to ensure port release
        Thread.sleep(100)

        server = embeddedServer(CIO, port = port) {
            routing {
                // Serve audio file from RAM
                get("/audio") {
                    val audioBytes = audioManager.getAudioBytesWithHeader()
                    if (audioBytes != null) {
                        val clientIp = call.request.local.remoteAddress
                        Log.d(TAG, "Client downloading audio: $clientIp")
                        
                        // Track this device if not already tracked
                        if (!_connectedDevices.containsKey(clientIp)) {
                            val device = DeviceInfo(
                                ipAddress = clientIp,
                                deviceName = "Device-${_connectedDevices.size + 1}",
                                status = DeviceStatus.DOWNLOADING
                            )
                            _connectedDevices[clientIp] = device
                            onDeviceConnected?.invoke(device)
                        }
                        
                        call.respondBytes(
                            bytes = audioBytes,
                            contentType = ContentType.Audio.Any
                        )
                    } else {
                        call.respond(HttpStatusCode.NotFound, "No audio loaded")
                    }
                }

                // Return current server time for clock sync
                get("/time") {
                    call.respondText(System.currentTimeMillis().toString())
                }

                // Return audio metadata
                get("/info") {
                    val info = buildString {
                        append("sampleRate=${audioManager.sampleRate}")
                        append("&channels=${audioManager.channels}")
                        append("&duration=${audioManager.getDurationMs()}")
                    }
                    call.respondText(info)
                }
            }
        }

        server?.start(wait = false)
        startUdpListener()
        
        Log.d(TAG, "Server started. IP: ${getLocalIpAddress()}")
    }

    /**
     * Stop the server and cleanup
     */
    fun stop() {
        Log.d(TAG, "Stopping server")
        udpListenerJob?.cancel()
        udpSocket?.close()
        server?.stop(1000, 2000)
        _connectedDevices.clear()
    }

    /**
     * Broadcast the synchronized play command to all connected devices
     */
    fun broadcastPlayCommand(triggerTimeMs: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val message = "PLAY_AT|$triggerTimeMs"
                val messageBytes = message.toByteArray()
                
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    
                    // Broadcast to all connected devices
                    for (device in _connectedDevices.values) {
                        try {
                            val address = InetAddress.getByName(device.ipAddress)
                            val packet = DatagramPacket(
                                messageBytes,
                                messageBytes.size,
                                address,
                                BROADCAST_PORT
                            )
                            socket.send(packet)
                            Log.d(TAG, "Sent PLAY_AT to ${device.ipAddress}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send to ${device.ipAddress}: ${e.message}")
                        }
                    }
                    
                    // Also broadcast to network broadcast address for any missed devices
                    try {
                        val broadcastAddress = InetAddress.getByName("255.255.255.255")
                        val packet = DatagramPacket(
                            messageBytes,
                            messageBytes.size,
                            broadcastAddress,
                            BROADCAST_PORT
                        )
                        socket.send(packet)
                        Log.d(TAG, "Broadcast PLAY_AT to network")
                    } catch (e: Exception) {
                        Log.e(TAG, "Broadcast failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting: ${e.message}", e)
            }
        }
    }

    /**
     * Get local IP address for QR code generation
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress?.contains('.') == true) {
                        return address.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP: ${e.message}")
        }
        return "Unknown"
    }

    /**
     * Check if all connected devices are ready
     */
    fun areAllDevicesReady(): Boolean {
        if (_connectedDevices.isEmpty()) return false
        return _connectedDevices.values.all { it.status == DeviceStatus.READY }
    }

    /**
     * Get the number of connected devices
     */
    fun getConnectedDeviceCount(): Int = _connectedDevices.size

    /**
     * Get the number of ready devices
     */
    fun getReadyDeviceCount(): Int = _connectedDevices.values.count { it.status == DeviceStatus.READY }

    // --- Private methods ---

    private fun startUdpListener() {
        udpListenerJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                udpSocket = DatagramSocket(UDP_PORT)
                val buffer = ByteArray(1024)
                
                Log.d(TAG, "UDP listener started on port $UDP_PORT")
                
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    
                    val message = String(packet.data, 0, packet.length)
                    val senderIp = packet.address.hostAddress
                    
                    Log.d(TAG, "Received UDP from $senderIp: $message")
                    
                    handleUdpMessage(message, senderIp ?: "Unknown")
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "UDP listener error: ${e.message}", e)
                }
            }
        }
    }

    private fun handleUdpMessage(message: String, senderIp: String) {
        when {
            message.startsWith("READY|") -> {
                val deviceName = message.substringAfter("READY|")
                val device = _connectedDevices[senderIp]
                if (device != null) {
                    device.status = DeviceStatus.READY
                    _connectedDevices[senderIp] = device.copy(
                        deviceName = deviceName,
                        status = DeviceStatus.READY
                    )
                    onDeviceReady?.invoke(device)
                    Log.d(TAG, "Device ready: $deviceName ($senderIp)")
                } else {
                    // Device connected via broadcast, add it
                    val newDevice = DeviceInfo(
                        ipAddress = senderIp,
                        deviceName = deviceName,
                        status = DeviceStatus.READY
                    )
                    _connectedDevices[senderIp] = newDevice
                    onDeviceReady?.invoke(newDevice)
                }
            }
        }
    }
}
