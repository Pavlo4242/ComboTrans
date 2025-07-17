//AudioPlayer.kt
package com.BWCTrans

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AudioPlayer {

    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // --- NEW: Add a lock object for synchronization ---
    private val audioLock = Any()

    // --- NEW: Add a volatile flag to track the released state ---
    @Volatile private var isReleased = false

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 24000
    }

    init {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // The initialization is thread-safe as it's in the constructor
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2) // Increase buffer size for stability
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            Log.d(TAG, "AudioTrack initialized and playing.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    fun playAudio(base64Audio: String) {
        // --- MODIFIED: Check the isReleased flag first ---
        if (isReleased) {
            Log.w(TAG, "AudioPlayer is released, skipping audio chunk.")
            return
        }

        scope.launch {
            try {
                val decodedData = Base64.decode(base64Audio, Base64.DEFAULT)
                
                // --- MODIFIED: Use the synchronized block ---
                // This ensures that `release()` cannot run at the same time as `write()`.
                synchronized(audioLock) {
                    // Double-check the released status inside the lock before writing
                    if (!isReleased && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.write(decodedData, 0, decodedData.size)
                    }
                }
            } catch (e: Exception) {
                // This will catch IllegalArgumentException from Base64 as well
                Log.e(TAG, "Failed to decode or play audio chunk", e)
            }
        }
    }

    fun release() {
        // --- MODIFIED: Use the synchronized block ---
        // This ensures no other thread can access the audioTrack while we're releasing it.
        synchronized(audioLock) {
            if (isReleased) return
            isReleased = true // Set the flag to true immediately inside the lock

            Log.d(TAG, "Releasing AudioTrack...")
            try {
                // Check if audioTrack is not null and is playing before stopping
                if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.flush()
                    audioTrack?.stop()
                }
                audioTrack?.release()
                audioTrack = null
                Log.d(TAG, "AudioTrack released successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Exception while releasing AudioTrack", e)
            }
        }
    }
}
