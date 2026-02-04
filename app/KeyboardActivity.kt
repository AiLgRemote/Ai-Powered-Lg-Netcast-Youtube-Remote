package com.tatilacratita.lgcast.sampler

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.connectsdk.service.capability.TextInputControl
import java.util.Locale
import kotlin.math.abs

class KeyboardActivity : AppCompatActivity() {
    private var textInputControl: TextInputControl? = null
    private lateinit var text: EditText
    private var netcastHelper: NetcastPairingHelper? = null
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var btnSpeak: ImageButton

    // Touch state
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private val clickThreshold = 200
    private val touchSensitivity = 3.0f

    private val requestRecordAudioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            startVoiceRecognition()
        } else {
            Toast.makeText(this, "Permisiunea pentru microfon este necesară", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard)

        val mTV = SingletonTV.getInstance().getTV()
        if (mTV == null) {
            Toast.makeText(applicationContext, "Device not connected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        textInputControl = mTV.getCapability(TextInputControl::class.java)
        textInputControl?.subscribeTextInputStatus(null)

        val deviceIp = intent.getStringExtra("DEVICE_IP")
        val sessionId = intent.getStringExtra("SESSION_ID")
        if (deviceIp != null && sessionId != null) {
            netcastHelper = NetcastPairingHelper(deviceIp, sessionId)
        }

        setupKeyboard()
        setupTouchpad()
        setupSpeechRecognizer()
    }

    override fun onBackPressed() {
        val resultIntent = Intent()
        // Changed MainActivity to RemoteControlActivity
        resultIntent.putExtra(RemoteControlActivity.KEY_STOP_SLIDESHOW, true)
        setResult(RESULT_OK, resultIntent)
        super.onBackPressed()
    }

    private fun setupKeyboard() {
        text = findViewById(R.id.editInput)
        btnSpeak = findViewById(R.id.btnSpeak)
        btnSpeak.setOnClickListener { checkRecordAudioPermission() }

        text.addTextChangedListener(object : TextWatcher {
            var lastString = ""
            override fun afterTextChanged(editable: Editable) {
                if (editable.isEmpty()) editable.append("\u200B")
                lastString = editable.toString().replace("\u200B", "")
            }
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, beforeLength: Int, charsChanged: Int) {
                val control = textInputControl ?: return
                val newString = s.toString().replace("\u200B", "")
                val matching = getMatchingCharacterLength(lastString, newString)

                if (s.isEmpty()) {
                    control.sendDelete()
                } else {
                    if (matching < lastString.length) {
                        for (i in 0 until lastString.length - matching) control.sendDelete()
                    }
                    if (matching < newString.length) {
                        control.sendText(newString.substring(matching))
                    }
                }
            }
        })

        text.setOnEditorActionListener { _, _, _ ->
            textInputControl?.sendEnter()
            true
        }

        text.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DEL) {
                textInputControl?.sendDelete()
            }
            false
        }
    }

    private fun setupTouchpad() {
        val touchpad = findViewById<View>(R.id.touchpadAreaKeyboard)
        touchpad.setOnTouchListener { _, event ->
            val helper = netcastHelper ?: return@setOnTouchListener true
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.x - lastX) * touchSensitivity
                    val dy = (event.y - lastY) * touchSensitivity
                    if (abs(dx) > 0.1f || abs(dy) > 0.1f) helper.moveMouse(dx, dy)
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (System.currentTimeMillis() - downTime < clickThreshold) {
                        helper.sendMouseClick(null)
                    }
                }
            }
            true
        }
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
                 if (error != SpeechRecognizer.ERROR_NO_MATCH) Log.e("Speech", "Error: $error")
            }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)?.let {
                    text.setText(it)
                }
            }
            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
    }

    private fun checkRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
        } else {
            requestRecordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Recunoașterea vocală nu este disponibilă", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Vorbiți acum...")
        }
        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e("Speech", "Could not start voice recognition", e)
        }
    }

    fun closeClick(view: View) {
        val resultIntent = Intent()
        // Changed MainActivity to RemoteControlActivity
        resultIntent.putExtra(RemoteControlActivity.KEY_STOP_SLIDESHOW, true)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    fun sendClick(view: View) {
        textInputControl?.sendEnter()
    }

    private fun getMatchingCharacterLength(oldString: String, newString: String): Int {
        val oldChars = oldString.toCharArray()
        val newChars = newString.toCharArray()
        val length = oldChars.size.coerceAtMost(newChars.size)
        for (i in 0 until length) {
            if (oldChars[i] != newChars[i]) return i
        }
        return length
    }

    override fun onResume() {
        super.onResume()
        netcastHelper?.setCursorVisible(true, null)
    }

    override fun onPause() {
        super.onPause()
        netcastHelper?.setCursorVisible(false, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
    }
}
