package com.example.syncaudio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-fidelity audio manager using AudioTrack for direct hardware control.
 * - Loads entire audio file into RAM to prevent disk I/O stuttering
 * - Uses 10x minimum buffer size for smooth playback
 * - Runs playback on URGENT_AUDIO priority thread
 */
class AudioManager {
    companion object {
        private const val TAG = "AudioManager"
        private const val BUFFER_MULTIPLIER = 10
        private const val DEFAULT_SAMPLE_RATE = 44100
        private const val DEFAULT_CHANNELS = 2
    }

    private var audioTrack: AudioTrack? = null
    private var audioData: ByteArray? = null
    private var playbackThread: Thread? = null
    
    @Volatile
    private var isPlaying = false
    
    @Volatile
    private var shouldStop = false

    // Audio format info (extracted from WAV header or assumed)
    var sampleRate: Int = DEFAULT_SAMPLE_RATE
        private set
    var channels: Int = DEFAULT_CHANNELS
        private set
    var bitsPerSample: Int = 16
        private set

    /**
     * Load audio file into RAM from URI.
     * Supports WAV format - reads header for format info.
     */
    /**
     * Load audio file into RAM from URI.
     * Decodes any format (MP3, AAC, WAV) into raw 16-bit PCM.
     */
    suspend fun loadToRam(uri: Uri, context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading and decoding audio from: $uri")
            
            val decoded = AudioDecoder.decodeToPcm(context, uri)
            
            if (decoded != null) {
                audioData = decoded.pcmData
                sampleRate = decoded.sampleRate
                channels = decoded.channels
                bitsPerSample = 16 // MediaCodec decodes to 16-bit PCM by default
                
                Log.d(TAG, "Audio loaded and decoded: ${sampleRate}Hz, $channels ch, ${audioData?.size} bytes")
                true
            } else {
                Log.e(TAG, "Failed to decode audio")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load audio: ${e.message}", e)
            false
        }
    }

    /**
     * Load audio data directly from byte array (used by client after download)
     */
    fun loadFromBytes(data: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE, channels: Int = DEFAULT_CHANNELS) {
        this.sampleRate = sampleRate
        this.channels = channels
        
        // Check for WAV header
        if (isWavFile(data)) {
            parseWavHeader(data)
            audioData = data.copyOfRange(44, data.size)
        } else {
            audioData = data
        }
        
        Log.d(TAG, "Loaded ${audioData?.size} bytes from byte array")
    }

    /**
     * Get the raw audio bytes (for network transfer)
     */
    fun getAudioBytes(): ByteArray? = audioData

    /**
     * Get audio bytes with WAV header (for network transfer with metadata)
     */
    fun getAudioBytesWithHeader(): ByteArray? {
        val pcmData = audioData ?: return null
        return createWavHeader(pcmData.size) + pcmData
    }

    /**
     * Initialize the AudioTrack with current audio format settings.
     * Must be called before playback.
     */
    fun initializeTrack(): Boolean {
        val data = audioData ?: run {
            Log.e(TAG, "No audio data loaded")
            return false
        }

        try {
            val channelConfig = if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

            val audioFormat = when (bitsPerSample) {
                8 -> AudioFormat.ENCODING_PCM_8BIT
                16 -> AudioFormat.ENCODING_PCM_16BIT
                32 -> AudioFormat.ENCODING_PCM_FLOAT
                else -> AudioFormat.ENCODING_PCM_16BIT
            }

            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = minBufferSize * BUFFER_MULTIPLIER

            Log.d(TAG, "Creating AudioTrack: minBuffer=$minBufferSize, actualBuffer=$bufferSize")

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                // Removed PERFORMANCE_MODE_LOW_LATENCY to prioritize buffer stability as per "Engineering Prompt"
                .build()

            Log.d(TAG, "AudioTrack initialized successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
            return false
        }
    }

    /**
     * Play audio at a specific timestamp using optimized wait logic.
     */
    @Synchronized
    fun playAtTime(triggerTimeMs: Long) {
        if (isPlaying) {
            Log.w(TAG, "Already playing, ignoring duplicate play command")
            return
        }

        val track = audioTrack ?: run {
            Log.e(TAG, "AudioTrack not initialized")
            return
        }
        val data = audioData ?: run {
            Log.e(TAG, "No audio data loaded")
            return
        }

        shouldStop = false
        isPlaying = true

        playbackThread = Thread {
            try {
                // Priority Step: Set thread priority to URGENT_AUDIO
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                Log.d(TAG, "Thread Priority set to URGENT_AUDIO")

                // Prime the buffer (Pre-write) to prevent underrun at start
                val bufferSize = track.bufferSizeInFrames * channels * (bitsPerSample / 8)
                val primeSize = minOf(data.size, bufferSize) // Fill the buffer
                
                var written = 0
                if (primeSize > 0) {
                     written = track.write(data, 0, primeSize)
                }
                
                Log.d(TAG, "Primed Buffer: $written bytes")

                // Smart Wait: Sleep until close to target, then spin-wait for precision
                val waitTime = triggerTimeMs - System.currentTimeMillis()
                if (waitTime > 50) {
                    try {
                        Thread.sleep(waitTime - 50)
                    } catch (e: InterruptedException) {
                        // Ignore
                    }
                }

                Log.d(TAG, "Spin-waiting for trigger time: $triggerTimeMs")
                
                // Critical Spin-Wait Loop (Last ~50ms)
                while (System.currentTimeMillis() < triggerTimeMs && !shouldStop) {
                    // Busy wait for millisecond precision
                }

                if (shouldStop) {
                    Log.d(TAG, "Playback cancelled before start")
                    isPlaying = false
                    return@Thread
                }

                // Start Playback
                track.play()
                val actualStartTime = System.currentTimeMillis()
                Log.d(TAG, "Starting playback at: $actualStartTime (diff: ${actualStartTime - triggerTimeMs}ms)")

                // The "Zero-Latency" Blocking Write Loop
                val chunkSize = 4096 // Recommended chunk size
                var offset = written // Start from where priming left off

                while (offset < data.size && !shouldStop) {
                    val bytesToWrite = minOf(chunkSize, data.size - offset)
                    
                    // BLOCKING WRITE: This waits until hardware is ready
                    val result = track.write(data, offset, bytesToWrite)

                    if (result < 0) {
                        Log.e(TAG, "Error writing to AudioTrack: $result")
                        break
                    }
                    offset += result
                }

                // Cleanup: Wait for track to finish playing remaining data
                if (!shouldStop) {
                    track.stop()
                    track.flush()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Playback error: ${e.message}", e)
            } finally {
                isPlaying = false
                Log.d(TAG, "Playback finished")
            }
        }

        playbackThread?.start()
    }

    /**
     * Start immediate playback (for testing)
     */
    fun playNow() {
        playAtTime(System.currentTimeMillis())
    }

    /**
     * Stop playback
     */
    fun stop() {
        shouldStop = true
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping: ${e.message}")
        }
        playbackThread?.join(1000)
        isPlaying = false
    }

    /**
     * Release all resources
     */
    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        audioData = null
    }

    /**
     * Check if audio is currently playing
     */
    fun isCurrentlyPlaying(): Boolean = isPlaying

    /**
     * Check if audio data is loaded
     */
    fun isLoaded(): Boolean = audioData != null

    /**
     * Get duration in milliseconds
     */
    fun getDurationMs(): Long {
        val data = audioData ?: return 0
        val bytesPerSample = bitsPerSample / 8
        val totalSamples = data.size / (bytesPerSample * channels)
        return (totalSamples * 1000L) / sampleRate
    }

    // --- Private helper methods ---

    private fun isWavFile(data: ByteArray): Boolean {
        if (data.size < 44) return false
        return data[0] == 'R'.code.toByte() &&
               data[1] == 'I'.code.toByte() &&
               data[2] == 'F'.code.toByte() &&
               data[3] == 'F'.code.toByte()
    }

    private fun parseWavHeader(data: ByteArray) {
        if (data.size < 44) return
        
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        
        // Skip RIFF header (12 bytes)
        buffer.position(22)
        channels = buffer.short.toInt()
        sampleRate = buffer.int
        // Skip byte rate and block align
        buffer.position(34)
        bitsPerSample = buffer.short.toInt()
        
        Log.d(TAG, "Parsed WAV: $sampleRate Hz, $channels channels, $bitsPerSample bits")
    }

    private fun createWavHeader(dataSize: Int): ByteArray {
        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        
        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(dataSize + 36)
        buffer.put("WAVE".toByteArray())
        
        // fmt subchunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size
        buffer.putShort(1) // AudioFormat (PCM)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        
        // data subchunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        
        return header
    }
}
