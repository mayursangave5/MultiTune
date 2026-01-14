package com.example.syncaudio.sync

import android.util.Log
import com.example.syncaudio.audio.AudioManager

/**
 * Handles precise timing for synchronized playback.
 * Uses spin-wait loop for sub-millisecond accuracy.
 */
class SyncManager {
    companion object {
        private const val TAG = "SyncManager"
        const val SYNC_DELAY_MS = 3000L // 3 seconds for devices to prepare
    }

    /**
     * Calculate the trigger time (current time + delay)
     */
    fun calculateTriggerTime(): Long {
        val triggerTime = System.currentTimeMillis() + SYNC_DELAY_MS
        Log.d(TAG, "Trigger time calculated: $triggerTime (in ${SYNC_DELAY_MS}ms)")
        return triggerTime
    }

    /**
     * Execute synchronized playback with spin-wait for precise timing.
     * This should be called on a high-priority thread.
     */
    fun executePlayback(
        audioManager: AudioManager,
        triggerTimeMs: Long
    ) {
        val currentTime = System.currentTimeMillis()
        val waitTime = triggerTimeMs - currentTime
        
        Log.d(TAG, "Waiting $waitTime ms for synchronized playback")
        
        if (waitTime < 0) {
            Log.w(TAG, "Trigger time already passed! Playing immediately.")
            audioManager.playNow()
            return
        }

        // Use AudioManager's built-in spin-wait playback
        audioManager.playAtTime(triggerTimeMs)
    }
}
