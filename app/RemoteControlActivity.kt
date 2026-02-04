package com.tatilacratita.lgcast.sampler

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.connectsdk.device.ConnectableDevice
import com.connectsdk.device.ConnectableDeviceListener
import com.connectsdk.discovery.DiscoveryManager
import com.connectsdk.discovery.DiscoveryManagerListener
import com.connectsdk.service.DeviceService
import com.connectsdk.service.capability.KeyControl
import com.connectsdk.service.capability.MediaPlayer
import com.connectsdk.service.command.ServiceCommandError
import com.tatilacratita.lgcast.sampler.databinding.ActivityRemoteControlBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.BindException
import java.util.Locale
import java.util.concurrent.TimeUnit

class RemoteControlActivity : AppCompatActivity(), NetcastPairingHelper.SessionKeyListener, DiscoveryManagerListener {

    private val tag = "RemoteControlActivity"
    private lateinit var binding: ActivityRemoteControlBinding
    private lateinit var settingsManager: SettingsManager
    private var httpServer: SimpleHttpServer? = null
    private lateinit var prefs: SharedPreferences

    private var mediaDevice: ConnectableDevice? = null
    private var remoteDevice: ConnectableDevice? = null

    private var lastMediaDeviceIp: String? = null
    private var lastRemoteDeviceIp: String? = null
    private var netcastPairingHelper: NetcastPairingHelper? = null

    // private var youtubeSearchHelper: YouTubeSearchHelper? = null // Removed, no longer used directly
    private lateinit var geminiHelper: GeminiAIHelper

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var youtubeSpeechRecognizer: SpeechRecognizer // New recognizer for YouTube search

    private var isAISearchActive = false // State variable for AI search

    // DLNA Service related variables
    private var dlnaService: DLNAService? = null
    private var dlnaServiceBound = false
    private var isDLNAServiceRunning = false
    private var dlnaServiceDeferred: CompletableDeferred<DLNAService>? = null // Added

    private val dlnaServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DLNAService.LocalBinder
            dlnaService = binder.getService()
            dlnaServiceBound = true
            Log.d(tag, "DLNAService connected")

            dlnaServiceDeferred?.complete(dlnaService!!) // Complete the deferred object
            dlnaServiceDeferred = null // Reset
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            dlnaServiceBound = false
            dlnaService = null
            Log.d(tag, "DLNAService disconnected")
            dlnaServiceDeferred?.completeExceptionally(IllegalStateException("DLNA Service disconnected prematurely."))
            dlnaServiceDeferred = null // Reset
        }
    }

    override fun attachBaseContext(newBase: Context) {
        settingsManager = SettingsManager(newBase)
        val updatedContext = settingsManager.applyLanguage(newBase, settingsManager.getLanguage())
        super.attachBaseContext(updatedContext)
    }

    companion object {
        const val PREFS_NAME = "lgcast_prefs"
        const val KEY_SESSION_ID = "netcast_session_id_"
        const val KEY_LAST_MEDIA_DEVICE_IP = "last_media_device_ip"
        const val KEY_LAST_REMOTE_DEVICE_IP = "last_remote_device_ip"
        const val KEY_YOUTUBE_HISTORY = "youtube_search_history"
        const val KEY_STOP_SLIDESHOW = "stop_slideshow"
        const val REQUEST_CODE_SETTINGS = 1001
        const val RESULT_CODE_LANGUAGE_CHANGED = 101
    }

    private val mediaPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                it.data?.data?.let { uri -> playMedia(uri) }
            }
        }

    private val slideshowPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val clipData = result.data?.clipData
            val imageUrls = mutableListOf<String>()

            lifecycleScope.launch(Dispatchers.IO) {
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        val uri = clipData.getItemAt(i).uri
                        val fileName = getFileName(uri)
                        val tempFile = copyToTempFile(uri, fileName)
                        val url = httpServer?.serveFile(tempFile)
                        url?.let { imageUrls.add(it) }
                    }
                } else {
                    result.data?.data?.let { uri ->
                        val fileName = getFileName(uri)
                        val tempFile = copyToTempFile(uri, fileName)
                        val url = httpServer?.serveFile(tempFile)
                        url?.let { imageUrls.add(it) }
                    }
                }

                withContext(Dispatchers.Main) {
                    val service = getOrCreateDlnaService() // Get or create service
                    if (imageUrls.isNotEmpty() && service != null) {
                        DLNAAutoplayHelper.startSlideshow(
                            service,
                            imageUrls,
                            "Slideshow (${imageUrls.size} imagini)"
                        )

                        Toast.makeText(
                            this@RemoteControlActivity,
                            getString(R.string.slideshow_started, imageUrls.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@RemoteControlActivity,
                            getString(R.string.could_not_load_images),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private val albumPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val clipData = result.data?.clipData
            val imageUrls = mutableListOf<String>()

            lifecycleScope.launch(Dispatchers.IO) {
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        val uri = clipData.getItemAt(i).uri
                        val fileName = getFileName(uri)
                        val tempFile = copyToTempFile(uri, fileName)
                        val url = httpServer?.serveFile(tempFile)
                        url?.let { imageUrls.add(it) }
                    }
                } else {
                    result.data?.data?.let { uri ->
                        val fileName = getFileName(uri)
                        val tempFile = copyToTempFile(uri, fileName)
                        val url = httpServer?.serveFile(tempFile)
                        url?.let { imageUrls.add(it) }
                    }
                }

                withContext(Dispatchers.Main) {
                    val service = getOrCreateDlnaService() // Get or create service
                    if (imageUrls.isNotEmpty() && service != null) {
                        DLNAAutoplayHelper.playAlbum(
                            service,
                            imageUrls,
                            "Album foto (${imageUrls.size} imagini)"
                        )

                        Toast.makeText(
                            this@RemoteControlActivity,
                            getString(R.string.album_started, imageUrls.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions: Map<String, Boolean> ->
            if (permissions.values.all { it }) {
                launchMediaPicker()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.permissions_not_granted),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val requestRecordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startVoiceRecognition()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.microphone_permission_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val requestYouTubeVoicePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startYouTubeVoiceRecognition()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.microphone_permission_required_for_youtube_search),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_CODE_LANGUAGE_CHANGED) {
                recreate()
            }
        }

    // New launcher for KeyboardActivity
    private val keyboardActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                if (result.data?.getBooleanExtra(KEY_STOP_SLIDESHOW, false) == true) {
                    // Stop slideshow and media via service
                    DLNAAutoplayHelper.stopSlideshow()
                    stopDLNAService() // This will also stop media if handled in service's onDestroy/onStop
                    Toast.makeText(this, getString(R.string.slideshow_stopped), Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager.applyTheme() // Apply theme early
        binding = ActivityRemoteControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lastMediaDeviceIp = prefs.getString(KEY_LAST_MEDIA_DEVICE_IP, null)
        lastRemoteDeviceIp = prefs.getString(KEY_LAST_REMOTE_DEVICE_IP, null)

        startHttpServerWithRetry()
        if (httpServer == null) {
            Toast.makeText(this, "Failed to start HTTP server. Exiting.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        DiscoveryManager.init(applicationContext)
        DiscoveryManager.getInstance().addListener(this)
        DiscoveryManager.getInstance().start() // Start DiscoveryManager here

        // youtubeSearchHelper = YouTubeSearchHelper() // Removed, no longer used directly
        geminiHelper = GeminiAIHelper(this)

        setupUI()
        setupSpeechRecognizer()
        setupYouTubeSpeechRecognizer()
    }

    override fun onResume() {
        super.onResume()
        Log.d(tag, "Resuming.")
        // DiscoveryManager.getInstance().start() // Removed to keep it running from onCreate
    }

    override fun onPause() {
        super.onPause()
        Log.d(tag, "Pausing.")
        // DiscoveryManager.getInstance().stop() // Removed to keep it running until onDestroy
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "RemoteControlActivity onDestroy")
        if (isDLNAServiceRunning) {
            stopDLNAService()
        }
        mediaDevice?.disconnect()
        remoteDevice?.disconnect()
        DiscoveryManager.getInstance().removeListener(this)
        DiscoveryManager.getInstance().stop() // Stop DiscoveryManager here
        httpServer?.stop()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::youtubeSpeechRecognizer.isInitialized) youtubeSpeechRecognizer.destroy()
    }

    private fun startHttpServerWithRetry() {
        val portsToTry = listOf(8080, 8888, 9000)
        for (port in portsToTry) {
            try {
                httpServer = SimpleHttpServer(this, port)
                return
            } catch (_: BindException) {
                Log.w(tag, "Port $port is in use, trying next one.")
            }
        }

        Log.e(tag, "Failed to start HTTP server on any of the specified ports.")
        Toast.makeText(this, getString(R.string.server_ports_in_use), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Set the hidden MainActivity button listener on the logo
        binding.logoImage.setOnLongClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            true // Consume the long click
        }

        updateConnectionStatusUI()

        binding.btnPower.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_POWER) }
        binding.btnInput.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_INPUT) }

        binding.btnNum0.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_0) }
        binding.btnNum1.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_1) }
        binding.btnNum2.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_2) }
        binding.btnNum3.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_3) }
        binding.btnNum4.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_4) }
        binding.btnNum5.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_5) }
        binding.btnNum6.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_6) }
        binding.btnNum7.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_7) }
        binding.btnNum8.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_8) }
        binding.btnNum9.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_9) }

        binding.btnList.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_LIST) }
        binding.btnQView.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_Q_VIEW) }

        binding.btnVolUp.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_VOL_UP) }
        binding.btnVolDown.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_VOL_DOWN) }
        binding.btnMute.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_MUTE) }

        binding.btnChUp.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_CH_UP) }
        binding.btnChDown.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_CH_DOWN) }

        binding.btnUp.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_UP) }
        binding.btnDown.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_DOWN) }
        binding.btnLeft.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_LEFT) }
        binding.btnRight.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_RIGHT) }
        binding.btnOk.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_OK) }

        binding.btnMenu.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_MENU) }
        binding.btnSettings.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_MENU) } // This maps to "SETTINGS" in some TVs
        binding.btnGuide.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_GUIDE) }
        binding.btnQMenu.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_Q_MENU) }
        binding.btnBack.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_BACK) }
        binding.btnInfo.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_INFO) }
        binding.btnExit.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_EXIT) }
        binding.btnHome.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_HOME) }

        binding.btnPlay.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_PLAY) }
        binding.btnPause.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_PAUSE) }
        binding.btnStop.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_STOP) }
        binding.btnRew.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_REW) }
        binding.btnFf.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_FF) }

        binding.btnRed.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_RED) }
        binding.btnGreen.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_GREEN) }
        binding.btnYellow.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_YELLOW) }
        binding.btnBlue.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_BLUE) }

        binding.btn3d.setOnClickListener { sendKey(NetcastPairingHelper.KEY_CODE_VIDEO_3D) }
        binding.btnVoice.setOnClickListener { checkRecordAudioPermission() }

        binding.btnYoutube.setOnClickListener { showYouTubeSearchDialog() }
        binding.btnYoutube.setOnLongClickListener {
            showYouTubeUrlDialog()
            true
        }

        binding.btnNetflix.setOnClickListener { launchApp("Netflix") }
        binding.btnPrime.setOnClickListener { launchApp("Prime Video") }

        binding.btnUploadMedia.setOnClickListener { openMediaPicker() }
        binding.btnSlideshow.setOnClickListener { selectAndPlaySlideshow() } // Added slideshow click listener

        binding.btnMouseControl.setOnClickListener {
            if (netcastPairingHelper?.sessionId == null) {
                Toast.makeText(
                    this,
                    getString(R.string.netcast_remote_not_connected),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            try {
                val intent = Intent(this, KeyboardActivity::class.java)
                intent.putExtra("DEVICE_IP", remoteDevice?.ipAddress) // Use remoteDevice's IP
                intent.putExtra("SESSION_ID", netcastPairingHelper?.sessionId)
                keyboardActivityLauncher.launch(intent) // Use the new launcher
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.cannot_open_keyboard), Toast.LENGTH_SHORT)
                    .show()
                Log.e(tag, "Could not launch KeyboardActivity: ${e.message}")
            }
        }

        binding.btnYouTubeSearch.setOnClickListener { showYouTubeSearchDialog() }
        binding.btnAISearch.setOnClickListener { showYouTubeSearchWithAI() }
        binding.btnAIRecommend.setOnClickListener { showAIRecommendations() }
    }

    private fun sendKey(keyCode: Int) {
        if (netcastPairingHelper?.sessionId != null) {
            netcastPairingHelper?.sendKeyCommand(keyCode)
        } else {
            Log.w(tag, "Attempted to send standard keycode $keyCode without Netcast pairing.")
            Toast.makeText(
                this,
                getString(R.string.netcast_remote_not_connected),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun launchApp(appName: String) {
        Toast.makeText(
            this,
            "Launching $appName is not yet implemented for this device type.",
            Toast.LENGTH_SHORT
        ).show()
        // Here you would typically have logic to send a command to the TV to launch the app
        // This is highly dependent on the TV's API (e.g., ConnectSDK capability for launching apps)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.remote_control_menu, menu)
        menu?.add(0, 1, 101, getString(R.string.clear_saved_sessions))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                true
            }

            1 -> {
                clearAllSessionIds()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun clearAllSessionIds() {
        prefs.edit {
            val keysToRemove = prefs.all.keys.filter { it.startsWith(KEY_SESSION_ID) }
            for (key in keysToRemove) {
                remove(key)
                Log.d(tag, "Removed session key: $key")
            }
        }
        Toast.makeText(this, getString(R.string.all_saved_sessions_cleared), Toast.LENGTH_SHORT)
            .show()
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p0: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                    Log.e(tag, "Speech error: $error")
                }
            }

            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)?.let {
                    handleVoiceCommand(it)
                }
            }

            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
    }

    private fun checkRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceRecognition()
        } else {
            requestRecordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                getString(R.string.voice_recognition_not_available),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO")
            }
            speechRecognizer.startListening(intent)
            Toast.makeText(this, getString(R.string.listening), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(tag, "Could not start voice recognition", e)
        }
    }

    private fun handleVoiceCommand(command: String) {
        Log.d(tag, "Voice command received: $command")
        runOnUiThread {
            Toast.makeText(this, getString(R.string.processing_with_ai), Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            try {
                val aiResult = geminiHelper.interpretVoiceCommand(command)

                if (!aiResult.success) {
                    runOnUiThread {
                        Toast.makeText(
                            this@RemoteControlActivity,
                            aiResult.errorMessage,
                            Toast.LENGTH_LONG
                        ).show()
                        handleVoiceCommandFallback(command)
                    }
                    return@launch
                }

                runOnUiThread {
                    Toast.makeText(
                        this@RemoteControlActivity,
                        getString(R.string.ai_explanation, aiResult.explanation),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                withContext(Dispatchers.Main) {
                    executeAICommand(aiResult)
                }

            } catch (e: Exception) {
                Log.e(tag, "Error processing AI command", e)
                runOnUiThread {
                    Toast.makeText(
                        this@RemoteControlActivity,
                        getString(R.string.using_standard_system),
                        Toast.LENGTH_SHORT
                    ).show()
                    handleVoiceCommandFallback(command)
                }
            }
        }
    }

    private fun handleVolumeCommands(command: String): Boolean {
        val isVolumeUp = command.contains("mai tare") || command.contains("dă mai tare")
        val isVolumeDown = command.contains("mai încet") || command.contains("dă mai încet")

        if (!isVolumeUp && !isVolumeDown) return false

        val words = command.split(" ")
        var repetitions = 1
        for (word in words) {
            val num = word.toIntOrNull() ?: wordToNumber(word)
            if (num > 0) {
                repetitions = num.coerceAtMost(20)
                break
            }
        }

        val keyCode =
            if (isVolumeUp) NetcastPairingHelper.KEY_CODE_VOL_UP else NetcastPairingHelper.KEY_CODE_VOL_DOWN

        lifecycleScope.launch(Dispatchers.IO) {
            repeat(repetitions) {
                sendKey(keyCode)
                delay(100)
            }
        }

        runOnUiThread {
            val direction = if (isVolumeUp) "sus" else "jos"
            Toast.makeText(
                this,
                getString(R.string.volume_direction, direction, repetitions),
                Toast.LENGTH_SHORT
            ).show()
        }

        return true
    }

    private fun handleChannelCommands(command: String): Boolean {
        val commandWords = command.replace("programul", "").replace("canalul", "").trim()
        val channelNumberStr = commandWords.split(" ").mapNotNull {
            it.toIntOrNull()?.toString() ?: wordToNumber(it).takeIf { n -> n != -1 }?.toString()
        }.joinToString("")

        if (channelNumberStr.isNotEmpty()) {
            Log.d(tag, "Channel number detected: $channelNumberStr")

            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(R.string.channel, channelNumberStr),
                    Toast.LENGTH_SHORT
                ).show()
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val delayTime = if (channelNumberStr.length <= 2) 50L else 80L

                channelNumberStr.forEach { digitChar ->
                    digitToKeyCode(digitChar)?.let { sendKey(it) }
                    delay(delayTime)
                }

                delay(100)
            }
            return true
        }
        return false
    }

    private fun handleSimpleCommands(command: String): Boolean {
        when {
            command.contains("închide tv") || command.contains("opreste tv") -> {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.shutting_down_tv),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                sendKey(NetcastPairingHelper.KEY_CODE_POWER)
                return true
            }

            command.contains("porneste tv") || command.contains("deschide tv") -> {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.turning_on_tv),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                sendKey(NetcastPairingHelper.KEY_CODE_POWER)
                return true
            }

            command.contains("meniu") || command.contains("home") -> {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.opening_menu),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                sendKey(NetcastPairingHelper.KEY_CODE_HOME)
                return true
            }

            command.contains("înapoi") || command.contains("back") -> {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.back), Toast.LENGTH_SHORT).show()
                }
                sendKey(NetcastPairingHelper.KEY_CODE_BACK)
                return true
            }

            command.contains("sus") -> {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.up), Toast.LENGTH_SHORT).show()
                }
                sendKey(NetcastPairingHelper.KEY_CODE_UP)
                return true
            }

            command.contains("jos") -> {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.down), Toast.LENGTH_SHORT).show()
                }
                sendKey(NetcastPairingHelper.KEY_CODE_DOWN)
                return true
            }

            command.contains("stânga") || command.contains("stanga") -> {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.left), Toast.LENGTH_SHORT).show()
                }
                sendKey(NetcastPairingHelper.KEY_CODE_LEFT)
                return true
            }

            command.contains("dreapta") -> {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.right), Toast.LENGTH_SHORT).show()
                }
                sendKey(NetcastPairingHelper.KEY_CODE_RIGHT)
                return true
            }

            command.contains("ok") || command.contains("enter") || command.contains("selectează") || command.contains(
                "selecteaza"
            ) -> {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.ok), Toast.LENGTH_SHORT).show()
                }
                sendKey(NetcastPairingHelper.KEY_CODE_OK)
                return true
            }

            command.contains("pauză") || command.contains("pauza") -> {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.pause), Toast.LENGTH_SHORT).show()
                }
                sendKey(NetcastPairingHelper.KEY_CODE_PAUSE)
                return true
            }

            command.contains("play") || command.contains("redare") -> {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.play), Toast.LENGTH_SHORT).show()
                }
                sendKey(NetcastPairingHelper.KEY_CODE_PLAY)
                return true
            }

            command.contains("stop") || command.contains("oprește") || command.contains("opreste") -> {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.stop), Toast.LENGTH_SHORT).show()
                }
                sendKey(NetcastPairingHelper.KEY_CODE_STOP)
                return true
            }
        }

        return false
    }

    private fun wordToNumber(word: String): Int {
        return when (word) {
            "unu" -> 1
            "doi" -> 2
            "trei" -> 3
            "patru" -> 4
            "cinci" -> 5
            "șase", "sase" -> 6
            "șapte", "sapte" -> 7
            "opt" -> 8
            "nouă", "noua" -> 9
            "zece" -> 10
            "zero" -> 0
            else -> -1
        }
    }

    private fun digitToKeyCode(digit: Char): Int? {
        return when (digit) {
            '1' -> NetcastPairingHelper.KEY_CODE_1
            '2' -> NetcastPairingHelper.KEY_CODE_2
            '3' -> NetcastPairingHelper.KEY_CODE_3
            '4' -> NetcastPairingHelper.KEY_CODE_4
            '5' -> NetcastPairingHelper.KEY_CODE_5
            '6' -> NetcastPairingHelper.KEY_CODE_6
            '7' -> NetcastPairingHelper.KEY_CODE_7
            '8' -> NetcastPairingHelper.KEY_CODE_8
            '9' -> NetcastPairingHelper.KEY_CODE_9
            '0' -> NetcastPairingHelper.KEY_CODE_0
            else -> null
        }
    }

    private fun initiatePairing() {
        netcastPairingHelper?.displayPairingKey { success ->
            runOnUiThread {
                if (success) {
                    remoteDevice?.let { showPinPairingDialog(it, isNetcast = true) }
                }
            }
        }
    }

    private fun showPinPairingDialog(device: ConnectableDevice, isNetcast: Boolean = false) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).apply {
            setTitle(getString(R.string.enter_pin_from_tv))
            val input =
                EditText(this@RemoteControlActivity).apply { hint = getString(R.string.pin_hint) }
            setView(input)
            setPositiveButton(getString(R.string.confirm)) { _, _ ->
                if (isNetcast) {
                    netcastPairingHelper?.finishPairing(input.text.toString()) { success ->
                        // Pairing complet - fără feedback vizual
                        // Succesul sau eșecul sunt gestionate de session key listener
                    }
                } else {
                    device.sendPairingKey(input.text.toString())
                }
            }
            setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
        }.show()
    }

    override fun onSessionKeyAcquired(sessionId: String) {
        remoteDevice?.ipAddress?.let { ip ->
            prefs.edit { putString(KEY_SESSION_ID + ip, sessionId) }
            netcastPairingHelper?.sessionId = sessionId // Update the helper's session ID
            updateConnectionStatusUI() // Update UI when session is acquired
            // Toast.makeText(
            //     this,
            //     getString(R.string.pairing_successful_and_key_saved),
            //     Toast.LENGTH_SHORT
            // ).show()
        }
    }

    private fun getSessionIdForDevice(ipAddress: String): String? {
        return prefs.getString(KEY_SESSION_ID + ipAddress, null)
    }

    override fun onSessionInvalid() {
        runOnUiThread {
            // Toast.makeText(
            //     this,
            //     getString(R.string.invalid_session_re_pairing_needed),
            //     Toast.LENGTH_SHORT
            // ).show()
            remoteDevice?.let { ip ->
                prefs.edit { remove(KEY_SESSION_ID + ip.ipAddress).apply() }
            }
            initiatePairing()
        }
    }

    private fun setupYouTubeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(tag, "Speech recognition is not available on this device.")
            return
        }
        youtubeSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        youtubeSpeechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runOnUiThread {
                    Toast.makeText(
                        this@RemoteControlActivity,
                        if (isAISearchActive) getString(R.string.listening_for_youtube_search_ai) else getString(R.string.listening_for_youtube_search),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onResults(results: Bundle?) {
                val query =
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)
                if (!query.isNullOrEmpty()) {
                    Log.d(tag, "YouTube search query recognized: $query")
                    saveSearchQuery(query)
                    if (isAISearchActive) {
                        lifecycleScope.launch {
                            val optimizedQuery = geminiHelper.optimizeYouTubeSearch(query)
                            Log.d(tag, "AI optimized voice query: $query -> $optimizedQuery")
                            withContext(Dispatchers.Main) {
                                isAISearchActive = false // Reset state
                                showYouTubeSearchDialog(optimizedQuery)
                            }
                        }
                    } else {
                        showYouTubeSearchDialog(query)
                    }
                }
            }

            override fun onError(error: Int) {
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    Log.e(tag, "YouTube speech recognizer error: $error")
                    runOnUiThread {
                        Toast.makeText(
                            this@RemoteControlActivity,
                            getString(R.string.voice_recognition_error, error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                isAISearchActive = false // Reset state on error
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                runOnUiThread {
                    Toast.makeText(
                        this@RemoteControlActivity,
                        getString(R.string.processing),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun requestYouTubeVoiceSearchPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startYouTubeVoiceRecognition()
        } else {
            requestYouTubeVoicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startYouTubeVoiceRecognition() {
        if (!::youtubeSpeechRecognizer.isInitialized) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        youtubeSpeechRecognizer.startListening(intent)
    }

    private fun stopYouTubeVoiceRecognition() {
        if (::youtubeSpeechRecognizer.isInitialized) youtubeSpeechRecognizer.stopListening()
    }

    private fun handleVoiceCommandFallback(command: String): Boolean {
        val lowerCaseCommand = command.lowercase(Locale.ROOT)

        if (handleVolumeCommands(lowerCaseCommand)) return true
        if (handleChannelCommands(lowerCaseCommand)) return true
        if (handleSimpleCommands(lowerCaseCommand)) return true

        Toast.makeText(this, getString(R.string.unknown_command, command), Toast.LENGTH_SHORT)
            .show()
        return false
    }

    private fun showYouTubeSearchWithAI() {
        isAISearchActive = true // Set state for AI search
        showYouTubeSearchDialog(null) // Open YouTubeSearchDialog directly
        Toast.makeText(this, getString(R.string.ai_will_optimize_voice_search), Toast.LENGTH_LONG).show()
    }

    private fun showAIRecommendations() {
        lifecycleScope.launch {
            try {
                // Update the connection status UI temporarily for user feedback
                binding.tvConnectionStatus.text = getString(R.string.generating_ai_recommendations)

                val history = getSearchHistory()
                if (history.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        updateConnectionStatusUI() // Revert UI
                        Toast.makeText(
                            this@RemoteControlActivity,
                            getString(R.string.no_search_history_for_recommendations),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                val recommendations = geminiHelper.getContentRecommendations(
                    history,
                    "Sunt acasă, vreau ceva relaxant"
                )

                withContext(Dispatchers.Main) {
                    updateConnectionStatusUI() // Revert UI
                    val recommendationLines = recommendations.lines()
                        .filter { it.matches(Regex("^\\d+\\..*")) }
                        .map { it.substringAfter(". ").trim() }

                    if (recommendationLines.isEmpty()) {
                        Toast.makeText(
                            this@RemoteControlActivity,
                            getString(R.string.could_not_extract_recommendations),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@withContext
                    }

                    AlertDialog.Builder(this@RemoteControlActivity).apply {
                        setTitle(getString(R.string.ai_recommendations_for_you))
                        setItems(recommendationLines.toTypedArray()) { _, which ->
                            val selectedQuery = recommendationLines[which]
                            showYouTubeSearchDialog(selectedQuery)
                        }
                        setNegativeButton(getString(R.string.close), null)
                    }.show()
                }

            } catch (e: Exception) {
                Log.e(tag, "Error showing recommendations", e)
                runOnUiThread {
                    updateConnectionStatusUI() // Revert UI
                    Toast.makeText(
                        this@RemoteControlActivity,
                        getString(R.string.recommendations_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun saveSearchQuery(query: String) {
        if (query.isBlank()) return
        val history =
            prefs.getStringSet(KEY_YOUTUBE_HISTORY, emptySet())?.toMutableSet() ?: mutableSetOf()
        history.add(query.trim())
        prefs.edit {
            putStringSet(KEY_YOUTUBE_HISTORY, history)
        }
    }

    private fun getSearchHistory(): List<String> {
        return prefs.getStringSet(KEY_YOUTUBE_HISTORY, emptySet())?.toList()?.takeLast(10)
            ?: emptyList()
    }

    private suspend fun getOrCreateDlnaService(): DLNAService? {
        if (dlnaService != null && dlnaServiceBound) {
            // If service is already bound, ensure setDeviceAndPlayer has been called
            if (!dlnaService!!.servicePlayerReadyDeferred.isCompleted) {
                Log.d(tag, "DLNAService is bound but player not ready. Awaiting readiness signal.")
                try {
                    withTimeout(5000) { // Wait for a maximum of 5 seconds for setDeviceAndPlayer to complete
                        dlnaService?.servicePlayerReadyDeferred?.await()
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(tag, "DLNAService player readiness timed out.", e)
                    return null
                } catch (e: Exception) {
                    Log.e(tag, "Error waiting for DLNAService player readiness.", e)
                    return null
                }
            }
            return dlnaService
        }

        if (dlnaServiceDeferred == null) {
            dlnaServiceDeferred = CompletableDeferred()
            isDLNAServiceRunning = true // Set this state early to indicate service start attempt
            val serviceIntent = Intent(this, DLNAService::class.java).apply {
                action = DLNAService.ACTION_START
            }
            startForegroundService(serviceIntent)
            bindService(serviceIntent, dlnaServiceConnection, Context.BIND_AUTO_CREATE)
            Log.d(tag, "DLNAService started and binding initiated. Waiting for connection...")
        } else {
            Log.d(tag, "DLNAService binding already in progress. Waiting for existing deferred.")
        }

        return try {
            val service = withTimeout(5000) { // Wait for a maximum of 5 seconds for the service to bind
                dlnaServiceDeferred?.await()
            }

            if (service != null && !service.servicePlayerReadyDeferred.isCompleted) {
                // Service is bound, now ensure setDeviceAndPlayer is called and completes
                mediaDevice?.let { device ->
                    device.getCapability(MediaPlayer::class.java)?.let { player ->
                        service.setDeviceAndPlayer(device, player)
                        Log.d(tag, "ConnectableDevice and MediaPlayer passed to DLNAService from getOrCreateDlnaService.")
                    }
                }

                withTimeout(5000) { // Wait for setDeviceAndPlayer to complete
                    service.servicePlayerReadyDeferred.await()
                }
            }
            service

        } catch (e: TimeoutCancellationException) {
            Log.e(tag, "DLNAService connection/player readiness timed out.", e)
            dlnaServiceDeferred = null // Reset deferred on timeout
            stopDLNAService() // Stop service if it timed out during connection/initialization
            null
        } catch (e: Exception) {
            Log.e(tag, "Error waiting for DLNAService.", e)
            dlnaServiceDeferred = null // Reset deferred on error
            stopDLNAService() // Stop service on other errors during connection/initialization
            null
        }
    }

    private fun stopDLNAService() {
        if (isDLNAServiceRunning) {
            unbindService(dlnaServiceConnection)
            val serviceIntent = Intent(this, DLNAService::class.java).apply {
                action = DLNAService.ACTION_STOP
            }
            stopService(serviceIntent)
            isDLNAServiceRunning = false
            dlnaServiceBound = false
            dlnaService = null
            dlnaServiceDeferred?.cancel() // Cancel any pending deferred
            dlnaServiceDeferred = null
            Log.d(tag, "DLNAService stopped and unbound.")
        }
    }

    override fun onDeviceAdded(manager: DiscoveryManager?, device: ConnectableDevice?) {
        if (device == null) return

        val hasDLNA = device.getServiceByName("DLNA") != null
        val deviceIp = device.ipAddress

        if (mediaDevice == null && hasDLNA) {
            if (lastMediaDeviceIp != null && deviceIp == lastMediaDeviceIp) {
                Log.d(tag, "Found last used media device ($lastMediaDeviceIp). Auto-connecting.")
                handleDeviceSelection(device, isRemote = false)
            } else if (lastMediaDeviceIp == null) {
                Log.d(
                    tag,
                    "No last used media device. Connecting to first discovered: ${device.friendlyName}"
                )
                handleDeviceSelection(device, isRemote = false)
            }
        }

        if (remoteDevice == null && (device.hasCapability(KeyControl.Any) || device.getServiceByName(
                "Netcast TV"
            ) != null)
        ) {
            // Prioritize connecting to the last known remote device
            if (lastRemoteDeviceIp != null && deviceIp == lastRemoteDeviceIp) {
                Log.d(tag, "Found last used remote device ($lastRemoteDeviceIp). Auto-connecting.")
                handleDeviceSelection(device, isRemote = true)
            } else if (lastRemoteDeviceIp == null) {
                // If no last remote device, connect to the first discovered
                Log.d(
                    tag,
                    "No last used remote device. Connecting to first discovered: ${device.friendlyName}"
                )
                handleDeviceSelection(device, isRemote = true)
            }
        }
    }

    override fun onDeviceUpdated(manager: DiscoveryManager?, device: ConnectableDevice?) {}


    override fun onDeviceRemoved(manager: DiscoveryManager?, device: ConnectableDevice?) {
        Log.d(tag, "Device removed from network: ${device?.friendlyName}")
        if (device == mediaDevice) {
            Log.w(
                tag,
                "Connected media device ${device?.friendlyName} was removed. State will be cleared by disconnect listener."
            )
            mediaDevice?.disconnect()
            // Removed stopDLNAService() here to prevent premature service termination
        }
        if (device == remoteDevice) {
            Log.w(tag, "Connected remote device ${device?.friendlyName} was removed.")
            remoteDevice?.disconnect()
        }
    }

    override fun onDiscoveryFailed(manager: DiscoveryManager?, error: ServiceCommandError?) {
        Log.e(tag, "Discovery failed: ${error?.message}")
        runOnUiThread {
            binding.tvConnectionStatus.text = getString(R.string.discovery_failed_check_network)
        }
    }

    private fun handleDeviceSelection(device: ConnectableDevice, isRemote: Boolean) {
        if (isRemote) {
            if (remoteDevice != null && remoteDevice != device) remoteDevice?.disconnect()
            remoteDevice = device
            device.addListener(remoteDeviceListener)
        } else {
            if (mediaDevice != null && mediaDevice != device) mediaDevice?.disconnect()
            mediaDevice = device
            device.addListener(mediaDeviceListener)
        }
        device.connect()
    }

    private val mediaDeviceListener = object : ConnectableDeviceListener {
        override fun onDeviceReady(device: ConnectableDevice) {
            runOnUiThread {
                updateConnectionStatusUI()
                binding.btnYoutube.isEnabled = true
                binding.btnYouTubeSearch.isEnabled = true
                binding.btnAISearch.isEnabled = true
                binding.btnAIRecommend.isEnabled = true
                binding.btnUploadMedia.isEnabled = true
                binding.btnSlideshow.isEnabled = true // Enabled slideshow button
                // Toast.makeText(
                //     this@RemoteControlActivity,
                //     getString(R.string.media_device_connected, device.friendlyName),
                //     Toast.LENGTH_SHORT
                // ).show()

                lastMediaDeviceIp = device.ipAddress
                prefs.edit { putString(KEY_LAST_MEDIA_DEVICE_IP, lastMediaDeviceIp) }
                Log.d(
                    tag,
                    "Media device ready: ${device.friendlyName}. IP saved: $lastMediaDeviceIp"
                )

                // The call to setDeviceAndPlayer is now handled by getOrCreateDlnaService.
                // Remove the immediate call here.
            }
        }

        override fun onCapabilityUpdated(
            device: ConnectableDevice,
            added: MutableList<String>,
            removed: MutableList<String>
        ) {
        }

        override fun onDeviceDisconnected(device: ConnectableDevice) {
            Log.d(tag, "Media device disconnected: ${device.friendlyName}")
            mediaDevice = null
            runOnUiThread {
                updateConnectionStatusUI()
                binding.btnYoutube.isEnabled = false
                binding.btnYouTubeSearch.isEnabled = false
                binding.btnAISearch.isEnabled = false
                binding.btnAIRecommend.isEnabled = false
                binding.btnUploadMedia.isEnabled = false
                binding.btnSlideshow.isEnabled = false
            }
        }

        override fun onConnectionFailed(device: ConnectableDevice, error: ServiceCommandError?) {
            Log.e(tag, "Media device connection failed: ${error?.message}")
            mediaDevice = null
            runOnUiThread {
                updateConnectionStatusUI()
                // Toast.makeText(
                //     this@RemoteControlActivity,
                //     getString(R.string.media_connection_failed),
                //     Toast.LENGTH_SHORT
                // ).show()
            }
        }

        override fun onPairingRequired(
            device: ConnectableDevice,
            service: DeviceService,
            pairingType: DeviceService.PairingType
        ) {
        }
    }

    private val remoteDeviceListener = object : ConnectableDeviceListener {
        override fun onDeviceReady(device: ConnectableDevice) {
            SingletonTV.getInstance().setTV(device)
            runOnUiThread {
                updateConnectionStatusUI()
                binding.btnMouseControl.isEnabled = true
                // Toast.makeText(
                //     this@RemoteControlActivity,
                //     getString(R.string.remote_device_connected, device.friendlyName),
                //     Toast.LENGTH_SHORT
                // ).show()

                // Save the IP of the newly connected remote device
                lastRemoteDeviceIp = device.ipAddress
                prefs.edit { putString(KEY_LAST_REMOTE_DEVICE_IP, lastRemoteDeviceIp) }
                Log.d(tag, "Remote device ready: ${device.friendlyName}. IP saved: $lastRemoteDeviceIp")

                device.getServiceByName("Netcast TV")?.let {
                    device.ipAddress?.let { ip ->
                        val savedSessionId = getSessionIdForDevice(ip)
                        netcastPairingHelper = NetcastPairingHelper(ip, savedSessionId).apply {
                            sessionKeyListener = this@RemoteControlActivity
                        }
                        if (savedSessionId == null) {
                            initiatePairing()
                        } else {
                            // Proactively check if the saved session is still valid
                            netcastPairingHelper?.sendKeyCommand(NetcastPairingHelper.KEY_CODE_INFO)
                        }
                    }
                }
            }
        }

        override fun onDeviceDisconnected(device: ConnectableDevice) {
            Log.d(tag, "Remote device disconnected: ${device.friendlyName}")
            remoteDevice = null // Clear the remote device reference
            netcastPairingHelper = null // Clear the pairing helper
            // Do NOT clear lastRemoteDeviceIp here. We want to remember it for reconnection.
            runOnUiThread {
                updateConnectionStatusUI()
                binding.btnMouseControl.isEnabled = false
                // Toast.makeText(
                //     this@RemoteControlActivity,
                //     getString(R.string.remote_device_disconnected),
                //     Toast.LENGTH_SHORT
                // ).show()
            }
        }

        override fun onConnectionFailed(device: ConnectableDevice, error: ServiceCommandError?) {
            Log.e(tag, "Remote device connection failed: ${error?.message}")
            remoteDevice = null // Clear the remote device reference
            netcastPairingHelper = null // Clear the pairing helper
            // Do NOT clear lastRemoteDeviceIp here. We want to remember it for reconnection.
            runOnUiThread {
                updateConnectionStatusUI()
                // Toast.makeText(
                //     this@RemoteControlActivity,
                //     getString(R.string.remote_connection_failed),
                //     Toast.LENGTH_SHORT
                // ).show()
            }
        }

        override fun onPairingRequired(
            device: ConnectableDevice,
            service: DeviceService,
            pairingType: DeviceService.PairingType
        ) {
            runOnUiThread {
                if (service.serviceName == "Netcast TV") {
                    showPinPairingDialog(device, isNetcast = true)
                }
            }
        }

        override fun onCapabilityUpdated(
            device: ConnectableDevice,
            added: MutableList<String>,
            removed: MutableList<String>
        ) {
        }
    }

    private fun updateConnectionStatusUI() {
        val mediaStatus = if (mediaDevice != null) getString(
            R.string.connected_to,
            mediaDevice?.friendlyName
        ) else getString(R.string.searching_for_media_tv)
        val remoteStatus = if (remoteDevice != null) getString(
            R.string.connected_to,
            remoteDevice?.friendlyName
        ) else getString(R.string.searching_for_remote_tv)




        binding.tvConnectionStatus.text = "Media: $mediaStatus\nRemote: $remoteStatus"
    }

    private fun openMediaPicker() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissions.all {
                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) == PackageManager.PERMISSION_GRANTED
            }) {
            launchMediaPicker()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun launchMediaPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
        }
        mediaPicker.launch(intent)
    }

    private fun playMedia(uri: Uri) {
        lifecycleScope.launch {
            val service = getOrCreateDlnaService()
            if (httpServer == null || service == null) {
                runOnUiThread {
                    Toast.makeText(
                        this@RemoteControlActivity,
                        getString(R.string.http_or_dlna_service_not_ready),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            val fileName = getFileName(uri)
            val mimeType = contentResolver.getType(uri) ?: getMimeTypeFromFileName(fileName)
            val mediaUrl = httpServer!!.serveFile(copyToTempFile(uri, fileName))

            if (mimeType.startsWith("image/")) {
                val playImageIntent =
                    Intent(this@RemoteControlActivity, DLNAService::class.java).apply {
                        action = DLNAService.ACTION_PLAY_IMAGE
                        putExtra(DLNAService.EXTRA_IMAGE_URL, mediaUrl)
                        putExtra(DLNAService.EXTRA_IMAGE_TITLE, fileName)
                        putExtra(
                            DLNAService.EXTRA_IMAGE_DESCRIPTION,
                            getString(R.string.streaming_from_app_description)
                        )
                    }
                service.onStartCommand(playImageIntent, 0, 0)
            } else {
                val playMediaIntent =
                    Intent(this@RemoteControlActivity, DLNAService::class.java).apply {
                        action = DLNAService.ACTION_PLAY_LOCAL_MEDIA
                        putExtra(DLNAService.EXTRA_LOCAL_MEDIA_PATH, mediaUrl)
                        putExtra(DLNAService.EXTRA_LOCAL_MEDIA_MIMETYPE, mimeType)
                    }
                service.onStartCommand(playMediaIntent, 0, 0)
            }

            runOnUiThread {
                Toast.makeText(
                    this@RemoteControlActivity,
                    getString(R.string.sending_local_media_to_tv),
                    Toast.LENGTH_SHORT
                ).show()
                binding.tvConnectionStatus.text =
                    getString(R.string.media_on_tv, fileName, mediaDevice?.friendlyName)
            }
        }
    }

    private suspend fun copyToTempFile(uri: Uri, fileName: String): File =
        withContext(Dispatchers.IO) {
            File(cacheDir, "streaming_" + fileName).also {
                contentResolver.openInputStream(uri)
                    ?.use { input -> it.outputStream().use { output -> input.copyTo(output) } }
            }
        }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }
        return "media_" + System.currentTimeMillis()
    }

    private fun getMimeTypeFromFileName(fileName: String): String {
        return when {
            fileName.endsWith(".mp4", true) -> "video/mp4"
            fileName.endsWith(".mkv", true) -> "video/x-matrosroska"
            fileName.endsWith(".avi", true) -> "video/x-msvideo"
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".webp", true) -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun downloadToCache(url: String, filename: String): File? {
        return try {
            val cacheDir = File(cacheDir, "youtube_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val outFile = File(cacheDir, filename)
            val client = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.MINUTES)
                .build()
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(tag, "Download failed: HTTP ${resp.code}")
                    return null
                }
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            outFile
        } catch (e: Exception) {
            Log.e(tag, "downloadToCache error", e)
            null
        }
    }

    private fun mergeVideoAndAudio(videoFile: File, audioFile: File, outputFile: File): Boolean {
        outputFile.parentFile?.mkdirs()

        return try {
            Log.d(tag, "Starting FFmpeg merge...")
            val command = "-y -i \"${videoFile.absolutePath}\" -i \"${audioFile.absolutePath}\" -c:v copy -c:a aac -strict experimental \"${outputFile.absolutePath}\"";
            val session = FFmpegKit.execute(command)
            Log.d(tag, "FFmpeg session complete. Return code: ${session.returnCode}")
            Log.d(tag, "FFmpeg logs: ${session.logsAsString}")

            if (session.returnCode.isValueSuccess) {
                Log.d(tag, "FFmpeg merge successful.")
                true
            } else {
                Log.e(tag, "FFmpeg merge failed with code ${session.returnCode}. See logs.")
                false
            }
        } catch (e: Exception) {
            Log.e(tag, "FFmpeg merge error", e)
            false
        }
    }

    private fun showYouTubeSearchDialog(initialQuery: String? = null) {
        if (isFinishing || isDestroyed) return

        val dialog = YouTubeSearchDialog(
            context = this,
            lifecycleScope = lifecycleScope,
            initialQuery = initialQuery,
            onVideoSelected = { videoId, title ->
                Toast.makeText(this, getString(R.string.preparing_video, title), Toast.LENGTH_SHORT).show()
                playYouTubeVideoById(videoId, title)
            },
            onAutoplayRequested = { playlist ->
                // NOU: Handler pentru autoplay
                playYouTubePlaylist(playlist)
            },
            onVoiceSearchRequested = {
                requestYouTubeVoiceSearchPermission()
            }
        )

        dialog.show()
    }

    // 2. Adaugă funcția pentru redarea playlist-ului:
    private fun playYouTubePlaylist(playlist: List<Pair<String, String>>) {
        if (playlist.isEmpty()) return

        lifecycleScope.launch {
            val service = getOrCreateDlnaService()
            if (service == null) {
                runOnUiThread {
                    Toast.makeText(
                        this@RemoteControlActivity,
                        getString(R.string.dlna_service_not_ready_for_playback),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            Toast.makeText(
                this@RemoteControlActivity,
                "Pregătesc playlist cu ${playlist.size} videoclipuri...",
                Toast.LENGTH_LONG
            ).show()

            // Redă loading video
            playLoadingVideo()

            val videoUrls = mutableListOf<String>()

            for ((index, pair) in playlist.withIndex()) {
                val (videoId, title) = pair

                try {
                    binding.tvConnectionStatus.text =
                        "Procesare video ${index + 1}/${playlist.size}: $title"

                    val videoInfo = NewPipeHelper.getVideoInfo(videoId)
                    if (videoInfo == null || videoInfo.videoUrl == null) {
                        Log.w(tag, "Could not fetch video: $title")
                        continue
                    }

                    // Procesează video-ul (downloadează și merge dacă e nevoie)
                    val videoUrl = processYouTubeVideo(videoId, videoInfo)
                    if (videoUrl != null) {
                        videoUrls.add(videoUrl)
                    }

                } catch (e: Exception) {
                    Log.e(tag, "Error processing video $title", e)
                }
            }

            if (videoUrls.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(
                        this@RemoteControlActivity,
                        "Nu s-a putut procesa niciun videoclip",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            // Pregătește titlurile videoclipurilor
            val videoTitles = playlist.take(videoUrls.size).map { it.second }

            // Folosește DLNAAutoplayHelper pentru a reda playlist-ul
            DLNAAutoplayHelper.playVideoPlaylist(
                service,
                videoUrls,
                "YouTube Playlist (${videoUrls.size} videoclipuri)",
                videoTitles
            )

            runOnUiThread {
                binding.tvConnectionStatus.text =
                    "Playlist YouTube pe TV: ${videoUrls.size} videoclipuri"
                Toast.makeText(
                    this@RemoteControlActivity,
                    "Playlist trimis la TV! Se va reda automat.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // 3. Adaugă funcția helper pentru procesarea unui video:
    private suspend fun processYouTubeVideo(videoId: String, videoInfo: NewPipeHelper.VideoInfo): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Verifică dacă videoUrl este valid
                val videoUrl = videoInfo.videoUrl
                if (videoUrl == null) {
                    Log.e(tag, "Video URL is null for video $videoId")
                    return@withContext null
                }

                if (videoInfo.isMuxed) {
                    // Video simplu - downloadează direct
                    val temp = downloadToCache(videoUrl, "youtube_${videoId}_muxed.mp4")
                    if (temp != null && httpServer != null) {
                        httpServer!!.serveFile(temp)
                    } else null
                } else {
                    // Video + audio separat - merge
                    val audioUrl = videoInfo.audioUrl
                    if (audioUrl == null) {
                        Log.e(tag, "Audio URL is null for video $videoId")
                        return@withContext null
                    }

                    val videoFile = downloadToCache(videoUrl, "youtube_${videoId}_video.mp4")
                    val audioFile = downloadToCache(audioUrl, "youtube_${videoId}_audio.m4a")

                    if (videoFile != null && audioFile != null && httpServer != null) {
                        val outputFile = File(cacheDir, "youtube_cache/youtube_${videoId}_final.mp4")
                        val success = mergeVideoAndAudio(videoFile, audioFile, outputFile)

                        videoFile.delete()
                        audioFile.delete()

                        if (success) {
                            httpServer!!.serveFile(outputFile)
                        } else {
                            Log.e(tag, "Failed to merge video and audio for $videoId")
                            null
                        }
                    } else {
                        Log.e(tag, "Failed to download video or audio for $videoId")
                        videoFile?.delete()
                        audioFile?.delete()
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error processing video $videoId", e)
                null
            }
        }
    }

    private fun playYouTubeVideoById(videoId: String, title: String) {
        lifecycleScope.launch {
            val service = getOrCreateDlnaService()
            if (service == null) {
                runOnUiThread {
                    Toast.makeText(
                        this@RemoteControlActivity,
                        getString(R.string.dlna_service_not_ready_for_playback),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            try {
                // ADĂUGAT: Redă video-ul de loading și oprește video-ul curent
                playLoadingVideo()

                binding.tvConnectionStatus.text = getString(R.string.fetching_video, title)

                val videoInfo = NewPipeHelper.getVideoInfo(videoId)
                if (videoInfo == null || videoInfo.videoUrl == null) {
                    runOnUiThread {
                        binding.tvConnectionStatus.text = getString(R.string.error_fetching_video)
                        Toast.makeText(
                            this@RemoteControlActivity,
                            getString(R.string.could_not_fetch_video),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                if (videoInfo.isMuxed) {
                    Log.d(tag, getString(R.string.using_muxed_stream))

                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            withContext(Dispatchers.Main) {
                                binding.tvConnectionStatus.text =
                                    getString(R.string.downloading_video)
                            }

                            val temp =
                                downloadToCache(videoInfo.videoUrl, "youtube_" + videoId + "_muxed.mp4")

                            if (temp != null && httpServer != null) {
                                val localUrl = httpServer!!.serveFile(temp)

                                // MODIFICAT: Oprește video-ul curent înainte de a reda noul video
                                val stopIntent = Intent(
                                    this@RemoteControlActivity,
                                    DLNAService::class.java
                                ).apply {
                                    action = DLNAService.ACTION_STOP
                                }
                                service.onStartCommand(stopIntent, 0, 0)
                                delay(300)

                                val playIntent = Intent(
                                    this@RemoteControlActivity,
                                    DLNAService::class.java
                                ).apply {
                                    action = DLNAService.ACTION_PLAY_YOUTUBE
                                    putExtra(DLNAService.EXTRA_YOUTUBE_URL, localUrl)
                                }
                                service.onStartCommand(playIntent, 0, 0)

                                withContext(Dispatchers.Main) {
                                    binding.tvConnectionStatus.text = getString(
                                        R.string.youtube_video_on_tv,
                                        title,
                                        mediaDevice?.friendlyName
                                    )
                                    Toast.makeText(
                                        this@RemoteControlActivity,
                                        getString(R.string.video_sent_to_tv_via_service),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    binding.tvConnectionStatus.text =
                                        getString(R.string.download_error)
                                    Toast.makeText(
                                        this@RemoteControlActivity,
                                        getString(R.string.could_not_download_video),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } catch (ex: Exception) {
                            Log.e(tag, "Error with muxed stream in RemoteControlActivity", ex)
                            withContext(Dispatchers.Main) {
                                binding.tvConnectionStatus.text =
                                    getString(R.string.processing_error)
                                Toast.makeText(
                                    this@RemoteControlActivity,
                                    getString(R.string.error_processing_video, ex.message),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } else if (videoInfo.audioUrl != null) {
                    Log.d(tag, getString(R.string.separate_video_audio_merge_needed))

                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            withContext(Dispatchers.Main) {
                                binding.tvConnectionStatus.text =
                                    getString(R.string.downloading_video)
                                Toast.makeText(
                                    this@RemoteControlActivity,
                                    getString(R.string.video_requires_processing),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            val videoFile =
                                downloadToCache(videoInfo.videoUrl, "youtube_" + videoId + "_video.mp4")

                            withContext(Dispatchers.Main) {
                                binding.tvConnectionStatus.text =
                                    getString(R.string.downloading_audio)
                            }

                            val audioFile =
                                downloadToCache(videoInfo.audioUrl, "youtube_" + videoId + "_audio.m4a")

                            if (videoFile != null && audioFile != null && httpServer != null) {
                                withContext(Dispatchers.Main) {
                                    binding.tvConnectionStatus.text =
                                        getString(R.string.processing_video)
                                    Toast.makeText(
                                        this@RemoteControlActivity,
                                        getString(R.string.merging_audio_video),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }

                                val outputFile =
                                    File(cacheDir, "youtube_cache/youtube_" + videoId + "_final.mp4")
                                val success = mergeVideoAndAudio(videoFile, audioFile, outputFile)

                                if (success) {
                                    val localUrl = httpServer!!.serveFile(outputFile)

                                    // MODIFICAT: Oprește video-ul curent înainte de a reda noul video
                                    val stopIntent = Intent(
                                        this@RemoteControlActivity,
                                        DLNAService::class.java
                                    ).apply {
                                        action = DLNAService.ACTION_STOP
                                    }
                                    service.onStartCommand(stopIntent, 0, 0)
                                    delay(300)

                                    val playIntent = Intent(
                                        this@RemoteControlActivity,
                                        DLNAService::class.java
                                    ).apply {
                                        action = DLNAService.ACTION_PLAY_YOUTUBE
                                        putExtra(DLNAService.EXTRA_YOUTUBE_URL, localUrl)
                                    }
                                    service.onStartCommand(playIntent, 0, 0)

                                    withContext(Dispatchers.Main) {
                                        binding.tvConnectionStatus.text = getString(
                                            R.string.youtube_video_on_tv,
                                            videoInfo.title,
                                            mediaDevice?.friendlyName
                                        )
                                        Toast.makeText(
                                            this@RemoteControlActivity,
                                            getString(R.string.video_sent_to_tv_with_sound),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        binding.tvConnectionStatus.text =
                                            getString(R.string.processing_error)
                                        Toast.makeText(
                                            this@RemoteControlActivity,
                                            getString(R.string.could_not_merge_audio_video),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }

                                videoFile.delete()
                                audioFile.delete()
                            } else {
                                withContext(Dispatchers.Main) {
                                    binding.tvConnectionStatus.text =
                                        getString(R.string.download_error)
                                    Toast.makeText(
                                        this@RemoteControlActivity,
                                        getString(R.string.could_not_download_video_or_audio),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } catch (ex: Exception) {
                            Log.e(tag, "Error processing video in RemoteControlActivity", ex)
                            withContext(Dispatchers.Main) {
                                binding.tvConnectionStatus.text =
                                    getString(R.string.processing_error)
                                Toast.makeText(
                                    this@RemoteControlActivity,
                                    getString(R.string.error_processing_video, ex.message),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } else {
                    runOnUiThread {
                        binding.tvConnectionStatus.text =
                            getString(R.string.incompatible_video_format)
                        Toast.makeText(
                            this@RemoteControlActivity,
                            getString(R.string.video_not_compatible),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(tag, "Error playing YouTube video in RemoteControlActivity", e)
                runOnUiThread {
                    binding.tvConnectionStatus.text = getString(R.string.playback_error)
                    Toast.makeText(
                        this@RemoteControlActivity,
                        getString(R.string.error_processing_video, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    private fun showYouTubeUrlDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).apply {
            setTitle(getString(R.string.enter_youtube_url))
            setMessage(getString(R.string.paste_youtube_link))

            val input = EditText(this@RemoteControlActivity).apply {
                hint = getString(R.string.youtube_url_hint)
            }
            setView(input)

            setPositiveButton(getString(R.string.send_to_tv)) { _, _ ->
                val youtubeUrl = input.text.toString().trim()
                if (youtubeUrl.isNotEmpty()) {
                    playYouTubeVideo(youtubeUrl)
                }
                else {
                    Toast.makeText(this@RemoteControlActivity, getString(R.string.enter_valid_youtube_url), Toast.LENGTH_SHORT).show()
                }
            }
            setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
        }.show()
    }

    private fun playYouTubeVideo(youtubeUrl: String) {
        lifecycleScope.launch {
            val service = getOrCreateDlnaService()
            if (service == null) {
                runOnUiThread {
                    Toast.makeText(
                        this@RemoteControlActivity,
                        getString(R.string.dlna_service_not_ready_for_playback),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            try {
                // ADĂUGAT: Redă video-ul de loading și oprește video-ul curent
                playLoadingVideo()

                binding.tvConnectionStatus.text = getString(R.string.fetching_youtube_video)

                val videoId = NewPipeHelper.extractVideoId(youtubeUrl)
                if (videoId == null) {
                    runOnUiThread {
                        binding.tvConnectionStatus.text = getString(R.string.invalid_youtube_url)
                        Toast.makeText(
                            this@RemoteControlActivity,
                            getString(R.string.invalid_youtube_url),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val videoInfo = NewPipeHelper.getVideoInfo(videoId)
                if (videoInfo == null || videoInfo.videoUrl == null) {
                    runOnUiThread {
                        binding.tvConnectionStatus.text = getString(R.string.error_fetching_video)
                        Toast.makeText(
                            this@RemoteControlActivity,
                            getString(R.string.could_not_fetch_youtube_video),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                if (videoInfo.isMuxed) {
                    Log.d(tag, getString(R.string.using_muxed_stream))

                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            withContext(Dispatchers.Main) {
                                binding.tvConnectionStatus.text =
                                    getString(R.string.downloading_video)
                                Toast.makeText(
                                    this@RemoteControlActivity,
                                    getString(R.string.fast_stream_detected),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            val temp =
                                downloadToCache(videoInfo.videoUrl, "youtube_" + videoId + "_muxed.mp4")

                            if (temp != null && httpServer != null) {
                                val localUrl = httpServer!!.serveFile(temp)

                                // MODIFICAT: Oprește video-ul curent înainte de a reda noul video
                                val stopIntent = Intent(
                                    this@RemoteControlActivity,
                                    DLNAService::class.java
                                ).apply {
                                    action = DLNAService.ACTION_STOP
                                }
                                service.onStartCommand(stopIntent, 0, 0)
                                delay(300)

                                val playIntent = Intent(
                                    this@RemoteControlActivity,
                                    DLNAService::class.java
                                ).apply {
                                    action = DLNAService.ACTION_PLAY_YOUTUBE
                                    putExtra(DLNAService.EXTRA_YOUTUBE_URL, localUrl)
                                }
                                service.onStartCommand(playIntent, 0, 0)

                                withContext(Dispatchers.Main) {
                                    binding.tvConnectionStatus.text = getString(
                                        R.string.youtube_video_on_tv,
                                        videoInfo.title,
                                        mediaDevice?.friendlyName
                                    )
                                    Toast.makeText(
                                        this@RemoteControlActivity,
                                        getString(R.string.video_sent_to_tv_with_sound),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    binding.tvConnectionStatus.text =
                                        getString(R.string.download_error)
                                    Toast.makeText(
                                        this@RemoteControlActivity,
                                        getString(R.string.could_not_download_video),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } catch (ex: Exception) {
                            Log.e(tag, "Error with muxed stream in RemoteControlActivity", ex)
                            withContext(Dispatchers.Main) {
                                binding.tvConnectionStatus.text =
                                    getString(R.string.processing_error)
                                Toast.makeText(
                                    this@RemoteControlActivity,
                                    getString(R.string.error_processing_video, ex.message),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } else if (videoInfo.audioUrl != null) {
                    Log.d(tag, getString(R.string.separate_video_audio_merge_needed))

                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            withContext(Dispatchers.Main) {
                                binding.tvConnectionStatus.text =
                                    getString(R.string.downloading_video)
                                Toast.makeText(
                                    this@RemoteControlActivity,
                                    getString(R.string.video_requires_processing),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            val videoFile =
                                downloadToCache(videoInfo.videoUrl, "youtube_" + videoId + "_video.mp4")

                            withContext(Dispatchers.Main) {
                                binding.tvConnectionStatus.text =
                                    getString(R.string.downloading_audio)
                            }

                            val audioFile =
                                downloadToCache(videoInfo.audioUrl, "youtube_" + videoId + "_audio.m4a")

                            if (videoFile != null && audioFile != null && httpServer != null) {
                                withContext(Dispatchers.Main) {
                                    binding.tvConnectionStatus.text =
                                        getString(R.string.processing_video)
                                    Toast.makeText(
                                        this@RemoteControlActivity,
                                        getString(R.string.merging_audio_video),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }

                                val outputFile =
                                    File(cacheDir, "youtube_cache/youtube_" + videoId + "_final.mp4")
                                val success = mergeVideoAndAudio(videoFile, audioFile, outputFile)

                                if (success) {
                                    val localUrl = httpServer!!.serveFile(outputFile)

                                    // MODIFICAT: Oprește video-ul curent înainte de a reda noul video
                                    val stopIntent = Intent(
                                        this@RemoteControlActivity,
                                        DLNAService::class.java
                                    ).apply {
                                        action = DLNAService.ACTION_STOP
                                    }
                                    service.onStartCommand(stopIntent, 0, 0)
                                    delay(300)

                                    val playIntent = Intent(
                                        this@RemoteControlActivity,
                                        DLNAService::class.java
                                    ).apply {
                                        action = DLNAService.ACTION_PLAY_YOUTUBE
                                        putExtra(DLNAService.EXTRA_YOUTUBE_URL, localUrl)
                                    }
                                    service.onStartCommand(playIntent, 0, 0)

                                    withContext(Dispatchers.Main) {
                                        binding.tvConnectionStatus.text = getString(
                                            R.string.youtube_video_on_tv,
                                            videoInfo.title,
                                            mediaDevice?.friendlyName
                                        )
                                        Toast.makeText(
                                            this@RemoteControlActivity,
                                            getString(R.string.video_sent_to_tv_with_sound),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        binding.tvConnectionStatus.text =
                                            getString(R.string.processing_error)
                                        Toast.makeText(
                                            this@RemoteControlActivity,
                                            getString(R.string.could_not_merge_audio_video),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }

                                videoFile.delete()
                                audioFile.delete()
                            } else {
                                withContext(Dispatchers.Main) {
                                    binding.tvConnectionStatus.text =
                                        getString(R.string.download_error)
                                    Toast.makeText(
                                        this@RemoteControlActivity,
                                        getString(R.string.could_not_download_video_or_audio),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } catch (ex: Exception) {
                            Log.e(tag, "Error processing video in RemoteControlActivity", ex)
                            withContext(Dispatchers.Main) {
                                binding.tvConnectionStatus.text =
                                    getString(R.string.processing_error)
                                Toast.makeText(
                                    this@RemoteControlActivity,
                                    getString(R.string.error_processing_video, ex.message),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } else {
                    runOnUiThread {
                        binding.tvConnectionStatus.text =
                            getString(R.string.incompatible_video_format)
                        Toast.makeText(
                            this@RemoteControlActivity,
                            getString(R.string.video_not_compatible),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(tag, "Error playing YouTube video in RemoteControlActivity", e)
                runOnUiThread {
                    binding.tvConnectionStatus.text = getString(R.string.playback_error)
                    Toast.makeText(
                        this@RemoteControlActivity,
                        getString(R.string.error_processing_video, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    // Adaugă această funcție după playYouTubeVideo() pentru a copia asset-ul în cache
    private suspend fun copyAssetToCache(assetFileName: String): File? = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(cacheDir, assetFileName)
            assets.open(assetFileName).use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(tag, "Asset $assetFileName copied to cache: ${cacheFile.absolutePath}")
            cacheFile
        } catch (e: Exception) {
            Log.e(tag, "Error copying asset $assetFileName to cache", e)
            null
        }
    }
    // Funcție helper pentru a opri video-ul curent și a reda loading video
    private suspend fun playLoadingVideo() {
        val service = getOrCreateDlnaService() ?: return

        try {
            // Oprește orice redare curentă
            val stopIntent = Intent(this, DLNAService::class.java).apply {
                action = DLNAService.ACTION_STOP
            }
            service.onStartCommand(stopIntent, 0, 0)
            delay(500) // Mică pauză pentru a se asigura că s-a oprit

            // Copiază și redă video-ul de loading
            val loadingVideo = copyAssetToCache("youtube3.mp4")
            if (loadingVideo != null && httpServer != null) {
                val loadingUrl = httpServer!!.serveFile(loadingVideo)

                val playLoadingIntent = Intent(this, DLNAService::class.java).apply {
                    action = DLNAService.ACTION_PLAY_LOCAL_MEDIA
                    putExtra(DLNAService.EXTRA_LOCAL_MEDIA_PATH, loadingUrl)
                    putExtra(DLNAService.EXTRA_LOCAL_MEDIA_MIMETYPE, "video/mp4")
                }
                service.onStartCommand(playLoadingIntent, 0, 0)

                Log.d(tag, "Loading video started")
                // Așteaptă 2 secunde pentru ca loading video să fie vizibil
                  delay(2000)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error playing loading video", e)
        }
    }
    private fun executeAICommand(result: AICommandResult) {
        Log.d(tag, "Executing AI command: ${result.action}")

        when (result.action) {
            "VOLUME_UP" -> {
                val steps = result.getSteps()
                lifecycleScope.launch(Dispatchers.IO) {
                    repeat(steps) {
                        sendKey(NetcastPairingHelper.KEY_CODE_VOL_UP)
                        delay(100)
                    }
                }
            }

            "VOLUME_DOWN" -> {
                val steps = result.getSteps()
                lifecycleScope.launch(Dispatchers.IO) {
                    repeat(steps) {
                        sendKey(NetcastPairingHelper.KEY_CODE_VOL_DOWN)
                        delay(100)
                    }
                }
            }

            "CHANNEL" -> {
                val channel = result.getChannel()
                lifecycleScope.launch(Dispatchers.IO) {
                    val delayTime = if (channel.length <= 2) 50L else 80L
                    channel.forEach { digitChar ->
                        digitToKeyCode(digitChar)?.let { sendKey(it) }
                        delay(delayTime)
                    }
                }
            }

            "POWER" -> {
                sendKey(NetcastPairingHelper.KEY_CODE_POWER)
            }

            "YOUTUBE_SEARCH" -> {
                val query = result.getQueryValue()
                if (query.isNotEmpty()) {
                    saveSearchQuery(query)
                    showYouTubeSearchDialog(query)
                }
            }

            "YOUTUBE_URL" -> {
                val url = result.getUrl()
                if (url.isNotEmpty()) {
                    playYouTubeVideo(url)
                }
            }
            "YOUTUBE_PLAY" -> {
                if (result.videoId != null) {
                    Toast.makeText(this, "Pornește video-ul cerut…", Toast.LENGTH_SHORT).show()
                    playYouTubeVideoById(result.videoId, result.query ?: "Video YouTube")
                } else {
                    Toast.makeText(this, "AI nu a găsit video-ul exact.", Toast.LENGTH_LONG).show()
                }
            }

            "SLIDESHOW" -> {
                // Assuming you have an `openSlideshowPicker()` method or similar
                // For now, let's just show a toast
                Toast.makeText(this, "AI: Initiating slideshow", Toast.LENGTH_SHORT).show()
                selectAndPlaySlideshow()
            }

            "ALBUM" -> {
                // Assuming you have an `openAlbumPicker()` method or similar
                // For now, let's just show a toast
                Toast.makeText(this, "AI: Initiating album playback", Toast.LENGTH_SHORT).show()
                selectAndPlayAlbum()
            }

            "NAVIGATION" -> {
                val direction = result.getDirection()
                when (direction.uppercase()) {
                    "UP" -> sendKey(NetcastPairingHelper.KEY_CODE_UP)
                    "DOWN" -> sendKey(NetcastPairingHelper.KEY_CODE_DOWN)
                    "LEFT" -> sendKey(NetcastPairingHelper.KEY_CODE_LEFT)
                    "RIGHT" -> sendKey(NetcastPairingHelper.KEY_CODE_RIGHT)
                    "OK" -> sendKey(NetcastPairingHelper.KEY_CODE_OK)
                    "BACK" -> sendKey(NetcastPairingHelper.KEY_CODE_BACK)
                }
            }

            "MEDIA_CONTROL" -> {
                val action = result.getMediaAction()
                when (action.uppercase()) {
                    "PLAY" -> sendKey(NetcastPairingHelper.KEY_CODE_PLAY)
                    "PAUSE" -> sendKey(NetcastPairingHelper.KEY_CODE_PAUSE)
                    "STOP" -> sendKey(NetcastPairingHelper.KEY_CODE_STOP)
                }
            }

            "STOP_VIDEO" -> {
                DLNAAutoplayHelper.stopSlideshow()
                stopDLNAService()
                lifecycleScope.launch {
                    sendKey(NetcastPairingHelper.KEY_CODE_BACK)
                    delay(500)
                    sendKey(NetcastPairingHelper.KEY_CODE_LEFT) // Often needed to exit some media apps
                    delay(500)
                    sendKey(NetcastPairingHelper.KEY_CODE_OK) // Confirm exit
                }
                Toast.makeText(this, "AI: Stopping video playback", Toast.LENGTH_SHORT).show()
            }

            "MENU" -> {
                sendKey(NetcastPairingHelper.KEY_CODE_HOME)
            }

            "UNKNOWN" -> {
                Toast.makeText(this, getString(R.string.command_not_understood), Toast.LENGTH_SHORT)
                    .show()
            }

            else -> {
                Toast.makeText(
                    this,
                    getString(R.string.unknown_action, result.action),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun selectAndPlaySlideshow() {
        // No longer check dlnaService == null here, getOrCreateDlnaService handles it
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        slideshowPicker.launch(intent)
    }

    private fun selectAndPlayAlbum() {
        // No longer check dlnaService == null here, getOrCreateDlnaService handles it
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        albumPicker.launch(intent)
    }
}