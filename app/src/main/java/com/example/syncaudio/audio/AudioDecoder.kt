package com.example.syncaudio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Utility to decode audio files (MP3, AAC, etc.) into raw PCM data.
 */
object AudioDecoder {
    private const val TAG = "AudioDecoder"
    private const val TIMEOUT_US = 10000L

    data class DecodedAudio(
        val pcmData: ByteArray,
        val sampleRate: Int,
        val channels: Int
    )

    fun decodeToPcm(context: Context, uri: Uri): DecodedAudio? {
        val extractor = MediaExtractor()
        val codec: MediaCodec?
        var outputStream = ByteArrayOutputStream()
        
        try {
            extractor.setDataSource(context, uri, null)
            
            // Find audio track
            var trackIndex = -1
            var format: MediaFormat? = null
            var mime = ""
            
            for (i in 0 until extractor.trackCount) {
                format = extractor.getTrackFormat(i)
                mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    break
                }
            }
            
            if (trackIndex == -1 || format == null) {
                Log.e(TAG, "No audio track found")
                return null
            }
            
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            
            Log.d(TAG, "Decoding audio: $mime, SampleRate: ${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)}, Channels: ${format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}")

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            
            while (!outputEos) {
                if (!inputEos) {
                    val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                        
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer?.get(chunk)
                    outputBuffer?.clear()
                    
                    if (chunk.isNotEmpty()) {
                        outputStream.write(chunk)
                    }
                    
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true
                    }
                }
            }
            
            codec.stop()
            codec.release()
            extractor.release()
            
            val pcmData = outputStream.toByteArray()
            val finalSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val finalChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            
            Log.d(TAG, "Decoded ${pcmData.size} bytes. $finalSampleRate Hz, $finalChannels ch")
            
            return DecodedAudio(pcmData, finalSampleRate, finalChannels)
            
        } catch (e: Exception) {
            Log.e(TAG, "Decoding failed: ${e.message}", e)
            return null
        }
    }
}
