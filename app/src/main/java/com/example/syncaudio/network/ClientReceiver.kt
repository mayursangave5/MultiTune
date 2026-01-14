package com.example.syncaudio.network

import android.os.Build
import android.util.Log
import com.example.syncaudio.audio.AudioManager
import com.example.syncaudio.data.ClientStatus
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Client-side networking for downloading audio and receiving sync commands.
 * - Downloads audio with progress tracking
 * - Syncs clock with host
 * - Listens for play commands
 */
class ClientReceiver(private val audioManager: AudioManager) {
    companion object {
        private const val TAG = "ClientReceiver"
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 120L
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private var udpListenerJob: Job? = null
    private var udpSocket: DatagramSocket? = null

    var downloadProgress: Float = 0f
        private set
    var status: ClientStatus = ClientStatus.IDLE
        private set
    var clockOffset: Long = 0L
        private set

    var onProgressUpdate: ((Float) -> Unit)? = null
    var onStatusChange: ((ClientStatus) -> Unit)? = null
    var onPlayCommand: ((Long) -> Unit)? = null

    private fun updateStatus(newStatus: ClientStatus) {
        status = newStatus
        onStatusChange?.invoke(newStatus)
    }

    private fun updateProgress(progress: Float) {
        downloadProgress = progress
        onProgressUpdate?.invoke(progress)
    }

    /**
     * Download audio file from host with progress tracking
     */
    suspend fun downloadAudio(hostIp: String, port: Int = HostServer.DEFAULT_HTTP_PORT): Boolean = 
        withContext(Dispatchers.IO) {
            try {
                updateStatus(ClientStatus.DOWNLOADING)
                updateProgress(0f)

                val url = "http://$hostIp:$port/audio"
                Log.d(TAG, "Downloading audio from: $url")

                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: ${response.code}")
                    updateStatus(ClientStatus.ERROR)
                    return@withContext false
                }

                val body = response.body ?: run {
                    Log.e(TAG, "Empty response body")
                    updateStatus(ClientStatus.ERROR)
                    return@withContext false
                }

                val contentLength = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = ByteArrayOutputStream()
                
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    
                    if (contentLength > 0) {
                        val progress = totalBytesRead.toFloat() / contentLength.toFloat()
                        updateProgress(progress)
                    }
                }

                val audioBytes = outputStream.toByteArray()
                Log.d(TAG, "Downloaded ${audioBytes.size} bytes")

                // Load into audio manager
                audioManager.loadFromBytes(audioBytes)
                
                // Initialize the AudioTrack
                if (!audioManager.initializeTrack()) {
                    Log.e(TAG, "Failed to initialize AudioTrack")
                    updateStatus(ClientStatus.ERROR)
                    return@withContext false
                }

                updateProgress(1f)
                Log.d(TAG, "Audio ready for playback")
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}", e)
                updateStatus(ClientStatus.ERROR)
                false
            }
        }

    /**
     * Sync clock with host to calculate time offset
     */
    suspend fun syncClock(hostIp: String, port: Int = HostServer.DEFAULT_HTTP_PORT): Boolean = 
        withContext(Dispatchers.IO) {
            try {
                updateStatus(ClientStatus.SYNCING)
                
                // Multiple samples for accuracy
                val offsets = mutableListOf<Long>()
                
                repeat(5) {
                    val url = "http://$hostIp:$port/time"
                    val request = Request.Builder().url(url).build()
                    
                    val t1 = System.currentTimeMillis()
                    val response = okHttpClient.newCall(request).execute()
                    val t2 = System.currentTimeMillis()
                    
                    if (response.isSuccessful) {
                        val hostTime = response.body?.string()?.toLongOrNull()
                        if (hostTime != null) {
                            // Estimate server time at request midpoint
                            val rtt = t2 - t1
                            val estimatedHostTime = hostTime + (rtt / 2)
                            val offset = estimatedHostTime - t2
                            offsets.add(offset)
                            Log.d(TAG, "Clock sample: hostTime=$hostTime, rtt=$rtt, offset=$offset")
                        }
                    }
                    
                    delay(100) // Small delay between samples
                }

                if (offsets.isEmpty()) {
                    Log.e(TAG, "Failed to get any clock samples")
                    updateStatus(ClientStatus.ERROR)
                    return@withContext false
                }

                // Use median offset for robustness
                offsets.sort()
                clockOffset = offsets[offsets.size / 2]
                Log.d(TAG, "Clock synchronized. Offset: $clockOffset ms")
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "Clock sync error: ${e.message}", e)
                updateStatus(ClientStatus.ERROR)
                false
            }
        }

    /**
     * Send ready signal to host
     */
    suspend fun sendReadySignal(hostIp: String, deviceName: String = Build.MODEL) = 
        withContext(Dispatchers.IO) {
            try {
                val message = "READY|$deviceName"
                val messageBytes = message.toByteArray()
                
                DatagramSocket().use { socket ->
                    val address = InetAddress.getByName(hostIp)
                    val packet = DatagramPacket(
                        messageBytes,
                        messageBytes.size,
                        address,
                        HostServer.UDP_PORT
                    )
                    socket.send(packet)
                    Log.d(TAG, "Sent ready signal to $hostIp")
                }
                
                updateStatus(ClientStatus.READY)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ready signal: ${e.message}", e)
            }
        }

    /**
     * Start listening for play commands from host
     */
    fun startListeningForPlayCommands() {
        udpListenerJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                udpSocket = DatagramSocket(HostServer.BROADCAST_PORT)
                val buffer = ByteArray(1024)
                
                Log.d(TAG, "Listening for play commands on port ${HostServer.BROADCAST_PORT}")
                
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    
                    val message = String(packet.data, 0, packet.length)
                    Log.d(TAG, "Received: $message")
                    
                    if (message.startsWith("PLAY_AT|")) {
                        val triggerTime = message.substringAfter("PLAY_AT|").toLongOrNull()
                        if (triggerTime != null) {
                            Log.d(TAG, "Play command received. Trigger time: $triggerTime")
                            
                            // Adjust for clock offset
                            val adjustedTriggerTime = triggerTime - clockOffset
                            Log.d(TAG, "Adjusted trigger time: $adjustedTriggerTime (offset: $clockOffset)")
                            
                            updateStatus(ClientStatus.PLAYING)
                            onPlayCommand?.invoke(adjustedTriggerTime)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "UDP listener error: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Stop listening for play commands
     */
    fun stopListening() {
        udpListenerJob?.cancel()
        udpSocket?.close()
    }

    /**
     * Full cleanup
     */
    fun release() {
        stopListening()
        updateStatus(ClientStatus.IDLE)
    }
}
