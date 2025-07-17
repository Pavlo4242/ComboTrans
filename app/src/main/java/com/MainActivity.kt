package com.ComboTrans

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.ComboTrans.databinding.ActivityMainBinding
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.lang.StringBuilder

class MainActivity : AppCompatActivity(), SettingsDialog.DevSettingsListener, UserSettingsDialogFragment.UserSettingsListener {

    // --- COMPANION OBJECT ---
    companion object {
        private const val TAG = "MainActivity"
    }

    // --- VIEWS & BINDING ---
    private lateinit var binding: ActivityMainBinding

    // --- CORE COMPONENTS ---
    private lateinit var audioHandler: AudioHandler
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var translationAdapter: TranslationAdapter
    private var webSocketClient: WebSocketClient? = null
    private var restApiClient: RestApiClient? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val gson = Gson()

    // --- LAUNCHERS ---
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    // --- STATE & CONFIGURATION ---
    @Volatile private var isListening = false
    // WebSocket State
    @Volatile private var isSessionActive = false
    @Volatile private var isServerReady = false
    // REST State
    @Volatile private var isProcessing = false
    private var useStreaming = false
    
    private val audioBuffer = ByteArrayOutputStream()
    private val outputTranscriptBuffer = StringBuilder()
    private var sessionHandle: String? = null
    
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    // --- PREFERENCES & MODELS ---
    private lateinit var models: List<String>
    private var selectedModel: String = ""
    private var apiVersions: List<ApiVersion> = emptyList()
    private var apiKeys: List<ApiKeyInfo> = emptyList()
    private var selectedApiVersionObject: ApiVersion? = null
    private var selectedApiKeyInfo: ApiKeyInfo? = null

    // --- ACTIVITY LIFECYCLE ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate: Activity created.")

        models = resources.getStringArray(R.array.models).toList()
        loadApiVersionsFromResources()
        loadApiKeysFromResources()
        loadPreferences()

        audioPlayer = AudioPlayer()
        restApiClient = RestApiClient()

        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.i(TAG, "RECORD_AUDIO permission granted.")
                initializeAudioHandler()
                updateUI()
            } else {
                Log.e(TAG, "RECORD_AUDIO permission denied.")
                showError("Microphone permission is required for voice translation.")
                updateUI()
            }
        }

        setupUI()
        checkPermissions()
        determineClientType()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "onDestroy: Activity is being destroyed.")
        audioPlayer.release()
        teardownSession() // This will handle both WebSocket and general cleanup
        mainScope.cancel()
    }

    // --- INITIALIZATION & SETUP ---
    private fun setupUI() {
        setSupportActionBar(binding.topAppBar)
        binding.topAppBar.setNavigationOnClickListener {
             Toast.makeText(this, "Back action triggered", Toast.LENGTH_SHORT).show()
        }

        translationAdapter = TranslationAdapter()
        binding.transcriptLog.layoutManager = LinearLayoutManager(this).apply {
            reverseLayout = true // New messages appear at the bottom and scroll up
            stackFromEnd = true
        }
        binding.transcriptLog.adapter = translationAdapter

        binding.settingsBtn.setOnClickListener {
            val userSettingsDialog = UserSettingsDialogFragment()
            userSettingsDialog.show(supportFragmentManager, "UserSettingsDialog")
        }

        binding.debugSettingsBtn.setOnClickListener {
            val devSettingsDialog = SettingsDialog(this, this, getSharedPreferences("BWCTransPrefs", MODE_PRIVATE), models)
            devSettingsDialog.setOnDismissListener {
                Log.d(TAG, "Developer SettingsDialog dismissed.")
                val oldModel = selectedModel
                loadPreferences()
                // If the model type changed (e.g., streaming to REST), tear down the old connection
                if (isStreamingModel(oldModel) != isStreamingModel(selectedModel)) {
                     Toast.makeText(this, "Model type changed. Please reconnect.", Toast.LENGTH_LONG).show()
                     teardownSession()
                } else if (isSessionActive) {
                    Toast.makeText(this, "Dev settings saved. Reconnect to apply.", Toast.LENGTH_LONG).show()
                }
                determineClientType()
            }
            devSettingsDialog.show()
        }

        binding.micBtn.setOnClickListener {
            Log.d(TAG, "Mic button clicked.")
            handleMasterButton()
        }

        binding.historyBtn.setOnClickListener {
            Toast.makeText(this, "History view coming soon!", Toast.LENGTH_SHORT).show()
        }

        updateUI()
    }

    private fun initializeAudioHandler() {
        if (!::audioHandler.isInitialized) {
            audioHandler = AudioHandler(this) { audioData ->
                if (useStreaming) {
                    webSocketClient?.sendAudio(audioData)
                } else {
                    if (isListening) {
                        mainScope.launch(Dispatchers.IO) { audioBuffer.write(audioData) }
                    }
                }
            }
            Log.i(TAG, "AudioHandler initialized.")
        }
    }
    
    private fun determineClientType() {
        useStreaming = isStreamingModel(selectedModel)
        Log.i(TAG, "Model '$selectedModel' selected. Using streaming client: $useStreaming")
        if (useStreaming) {
            prepareNewWebSocketClient()
        }
        updateUI()
    }
    
    private fun isStreamingModel(modelName: String): Boolean {
        return modelName.contains("live", ignoreCase = true) || modelName.contains("dialog", ignoreCase = true)
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("BWCTransPrefs", MODE_PRIVATE)
        selectedModel = prefs.getString("selected_model", models.firstOrNull() ?: "") ?: ""
        sessionHandle = prefs.getString("session_handle", null)
        
        val defaultApiVersion = if (apiVersions.isNotEmpty()) apiVersions.first().value else ""
        val storedApiVersion = prefs.getString("api_version", defaultApiVersion)
        selectedApiVersionObject = apiVersions.firstOrNull { it.value == storedApiVersion } ?: apiVersions.firstOrNull()

        val defaultApiKey = if(apiKeys.isNotEmpty()) apiKeys.first().value else ""
        val storedApiKey = prefs.getString("api_key", defaultApiKey)
        selectedApiKeyInfo = apiKeys.firstOrNull { it.value == storedApiKey } ?: apiKeys.firstOrNull()

        Log.d(TAG, "loadPreferences: Loaded model '$selectedModel', API Version '${selectedApiVersionObject?.value}', Key '${selectedApiKeyInfo?.displayName}'")
    }

    private fun loadApiVersionsFromResources() {
        val rawApiVersions = resources.getStringArray(R.array.api_versions)
        val parsedList = mutableListOf<ApiVersion>()
        rawApiVersions.forEach { itemString ->
            val parts = itemString.split("|", limit = 2)
            parsedList.add(if (parts.size == 2) ApiVersion(parts[0].trim(), parts[1].trim()) else ApiVersion(itemString.trim(), itemString.trim()))
        }
        apiVersions = parsedList
    }

    private fun loadApiKeysFromResources() {
        val rawApiKeys = resources.getStringArray(R.array.api_keys)
        val parsedList = mutableListOf<ApiKeyInfo>()
        rawApiKeys.forEach { itemString ->
            val parts = itemString.split(":", limit = 2)
            if (parts.size == 2) parsedList.add(ApiKeyInfo(parts[0].trim(), parts[1].trim()))
        }
        apiKeys = parsedList
    }
    
    private fun prepareNewWebSocketClient() {
        webSocketClient?.disconnect()
        webSocketClient = WebSocketClient(
            context = applicationContext,
            modelName = selectedModel,
            vadSilenceMs = getVadSensitivity(),
            apiVersion = selectedApiVersionObject?.value ?: "v1beta",
            apiKey = selectedApiKeyInfo?.value ?: "",
            sessionHandle = sessionHandle,
            onOpen = { mainScope.launch {
                Log.i(TAG, "WebSocket onOpen callback.")
                isSessionActive = true
                reconnectAttempts = 0
                updateStatus("Connected, configuring server...")
                updateUI()
            } },
            onMessage = { text -> mainScope.launch { processWebSocketMessage(text) } },
            onClosing = { _, _ -> mainScope.launch { teardownSession(reconnect = true) } },
            onFailure = { t, response -> mainScope.launch {
                handleConnectionFailure(t, response)
                teardownSession(reconnect = true)
            } },
            onSetupComplete = { mainScope.launch {
                Log.i(TAG, "WebSocket onSetupComplete callback.")
                isServerReady = true
                updateStatus("Ready to listen")
                updateUI()
            } }
        )
        Log.i(TAG, "New WebSocketClient prepared.")
    }

    // --- PERMISSION HANDLING ---
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initializeAudioHandler()
        } else {
            Toast.makeText(this, "Microphone permission is needed for the translator.", Toast.LENGTH_LONG).show()
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onRequestPermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // --- UI & EVENT HANDLERS ---
    private fun handleMasterButton() {
        if (useStreaming) {
            handleWebSocketButton()
        } else {
            handleRestButton()
        }
    }
    
    private fun handleWebSocketButton() {
        if (!isSessionActive) {
            connect()
            return
        }
        if (!isServerReady) {
            Log.w(TAG, "WebSocket server not ready, ignoring.")
            return
        }
        isListening = !isListening
        if (isListening) startAudio() else stopAudio()
        updateUI()
    }

    private fun handleRestButton() {
        if (isProcessing) return
        isListening = !isListening
        if (isListening) {
            startAudio()
            updateStatus("Listening...")
        } else {
            stopAudio() // This will trigger the REST call
            updateStatus("Processing...")
        }
        updateUI()
    }

    // --- CORE LOGIC ---
    private fun connect() {
        if (!useStreaming) return
        if (isSessionActive) {
            Log.w(TAG, "connect: Already connected or connecting.")
            return
        }
        reconnectAttempts = 0
        updateStatus("Connecting...")
        updateUI()
        webSocketClient?.connect()
    }

    private fun teardownSession(reconnect: Boolean = false) {
        val wasActive = isSessionActive
        isListening = false
        isSessionActive = false
        isServerReady = false
        isProcessing = false
        if (::audioHandler.isInitialized) audioHandler.stopRecording()

        if (useStreaming && wasActive) {
            webSocketClient?.disconnect()
            mainScope.launch {
                updateUI()
                prepareNewWebSocketClient() // Prepare for the next connection
                if (reconnect && reconnectAttempts < maxReconnectAttempts) {
                    reconnectAttempts++
                    val delayMillis = (1000 * Math.pow(2.0, reconnectAttempts.toDouble())).toLong()
                    updateStatus("Connection lost. Reconnecting in ${delayMillis / 1000}s...")
                    delay(delayMillis)
                    connect()
                } else if (reconnect) {
                    showError("Could not establish a connection.")
                    reconnectAttempts = 0
                } else {
                     updateStatus("Disconnected")
                }
            }
        } else {
             updateStatus("Disconnected")
             updateUI()
        }
    }

    private fun startAudio() {
        if (!::audioHandler.isInitialized) initializeAudioHandler()
        audioBuffer.reset()
        audioHandler.startRecording()
        Log.i(TAG, "startAudio: Recording started.")
    }

    private fun stopAudio() {
        if (::audioHandler.isInitialized) audioHandler.stopRecording()
        Log.i(TAG, "stopAudio: Recording stopped.")
        
        if (useStreaming) {
            // For streaming, stopping audio might flush a final text segment
            if (outputTranscriptBuffer.isNotEmpty()) {
                translationAdapter.addOrUpdateTranslation(outputTranscriptBuffer.toString().trim(), false)
                outputTranscriptBuffer.clear()
            }
            updateStatus("Ready to listen")
        } else {
            // For REST, stopping audio triggers the API call
            val audioData = audioBuffer.toByteArray()
            if (audioData.isNotEmpty()) {
                isProcessing = true
                updateUI()
                translationAdapter.addOrUpdateTranslation("(Your voice input)", true, true)
                sendRestRequest(audioData)
            } else {
                updateStatus("Ready")
            }
        }
    }
    
    private fun sendRestRequest(audioData: ByteArray) {
        val apiKey = selectedApiKeyInfo?.value
        if (apiKey.isNullOrEmpty() || restApiClient == null) {
            showError("API Key or REST client not configured.")
            isProcessing = false
            updateUI()
            return
        }
        mainScope.launch {
            isProcessing = true
            updateUI()
            val result = restApiClient!!.generateContent(
                context = this@MainActivity,
                apiKey = apiKey,
                apiVersion = selectedApiVersionObject?.value ?: "v1beta",
                modelName = selectedModel,
                audioData = audioData
            )
            result.onSuccess { response ->
                val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (text != null) {
                    translationAdapter.addOrUpdateTranslation(text, false, false)
                } else {
                    translationAdapter.addOrUpdateTranslation("[No text in response]", false, false)
                }
                // REST API does not support streaming audio output in this configuration
            }.onFailure { error ->
                showError("API Error: ${error.message}")
                translationAdapter.addOrUpdateTranslation("Error: ${error.message}", false, false)
            }
            isProcessing = false
            isListening = false
            updateStatus("Ready")
            updateUI()
        }
    }

    private fun processWebSocketMessage(text: String) {
        Log.v(TAG, "processWebSocketMessage: Received raw message: ${text.take(200)}...")
        try {
            val response = gson.fromJson(text, ServerResponse::class.java)

            response.sessionResumptionUpdate?.let {
                if (it.resumable == true && it.newHandle != null) {
                    sessionHandle = it.newHandle
                    getSharedPreferences("BWCTransPrefs", MODE_PRIVATE).edit().putString("session_handle", sessionHandle).apply()
                    Log.i(TAG, "Session handle updated and saved: ${it.newHandle}")
                }
            }
            response.goAway?.let {
                showError("Server sent GO_AWAY. Will reconnect.")
            }

            // Handle incoming translation text
            val outputText = response.outputTranscription?.text ?: response.serverContent?.outputTranscription?.text
            if (outputText != null) {
                outputTranscriptBuffer.append(outputText)
            }

            // Handle user's transcribed speech
            val inputText = response.inputTranscription?.text ?: response.serverContent?.inputTranscription?.text
            if (inputText != null && inputText.isNotBlank()) {
                // An input transcript marks the end of a translated segment. Finalize the previous one.
                if (outputTranscriptBuffer.isNotEmpty()) {
                    translationAdapter.addOrUpdateTranslation(outputTranscriptBuffer.toString().trim(), false, false)
                    outputTranscriptBuffer.clear()
                }
                translationAdapter.addOrUpdateTranslation(inputText.trim(), true, false)
            }
            
            // Handle incoming audio stream
            response.serverContent?.modelTurn?.parts?.forEach { part ->
                part.inlineData?.data?.let {
                    audioPlayer.playAudio(it)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing WebSocket message: $text", e)
        }
    }
    
    private fun handleConnectionFailure(t: Throwable, response: Response?) {
        var errorMessage = "Connection error: ${t.message}"
        if (response != null) {
            errorMessage += " (Code: ${response.code})"
            if (response.code == 404) {
                errorMessage = "Error 404: Endpoint not found. Check API version/key."
            }
        }
        showError(errorMessage)
    }

    override fun onForceConnect() {
        Toast.makeText(this, "Forcing reconnection...", Toast.LENGTH_SHORT).show()
        teardownSession()
        mainScope.launch {
            delay(500)
            connect()
        }
    }

    // --- HELPER & UTILITY FUNCTIONS ---
    private fun getVadSensitivity(): Int {
        return getSharedPreferences("BWCTransPrefs", MODE_PRIVATE).getInt("vad_sensitivity_ms", 800)
    }

    private fun updateUI() {
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        
        binding.micBtn.isEnabled = hasPermission && !isProcessing
        binding.micBtn.setImageResource(if (isListening) R.drawable.ic_stop else R.drawable.ic_mic)

        val status = when {
            isProcessing -> "Processing..."
            useStreaming && !isSessionActive -> "Status: Disconnected"
            useStreaming && !isServerReady -> "Status: Connecting..."
            isListening -> "Listening..."
            else -> "Status: Ready"
        }
        binding.statusText.text = status

        binding.infoText.visibility = if (translationAdapter.itemCount == 0) View.VISIBLE else View.GONE
        binding.debugSettingsBtn.isEnabled = !isSessionActive && !isProcessing
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = message
        Log.i(TAG, "Status Updated: $message")
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        updateStatus("Alert: ${message.take(50)}")
        Log.e(TAG, "showError: $message")
    }
}
