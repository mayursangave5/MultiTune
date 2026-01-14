package com.example.syncaudio.data

/**
 * Represents a connected client device
 */
data class DeviceInfo(
    val ipAddress: String,
    val deviceName: String,
    var status: DeviceStatus = DeviceStatus.DOWNLOADING
)

/**
 * Status of a connected device
 */
enum class DeviceStatus {
    DOWNLOADING,
    READY
}

/**
 * Status of the client
 */
enum class ClientStatus {
    IDLE,
    SCANNING,
    CONNECTING,
    DOWNLOADING,
    SYNCING,
    READY,
    PLAYING,
    ERROR
}

/**
 * Audio file metadata
 */
data class AudioInfo(
    val fileName: String,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val sizeBytes: Long
)
