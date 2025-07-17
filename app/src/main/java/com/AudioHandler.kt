package com.BWCTrans

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioHandler(
    private val context: Context,
    private val onAudioChunk: (ByteArray) -> Unit // Changed to pass ByteArray
) {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val audioScope = CoroutineScope(Dispatchers.IO)

    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "AudioHandler"
    }

    fun startRecording() {
        if (isRecording) return

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord parameters.")
            return
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted.")
            return
        }

        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        val sessionId = audioRecord?.audioSessionId ?: 0
        if (sessionId != 0) {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId).apply { enabled = true }
                Log.d(TAG, "NoiseSuppressor enabled.")
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId).apply { enabled = true }
                 Log.d(TAG, "AutomaticGainControl enabled.")
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId).apply { enabled = true }
                 Log.d(TAG, "AcousticEchoCanceler enabled.")
            }
        }

        audioRecord?.startRecording()
        isRecording = true
        Log.d(TAG, "Recording started.")

        audioScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val audioBuffer = ByteArray(bufferSize)
            while (isActive && isRecording) {
                val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readResult > 0) {
                    // Pass the raw byte array directly
                    onAudioChunk(audioBuffer.copyOf(readResult))
                }
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) return

        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        noiseSuppressor?.release()
        agc?.release()
        aec?.release()
        Log.d(TAG, "Recording stopped and resources released.")
    }
}
