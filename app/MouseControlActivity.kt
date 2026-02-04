package com.tatilacratita.lgcast.sampler

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tatilacratita.lgcast.sampler.databinding.ActivityMouseControlBinding
import kotlin.math.abs

class MouseControlActivity : AppCompatActivity() {

    private val TAG = "MouseControlActivity"
    private lateinit var binding: ActivityMouseControlBinding
    private var netcastHelper: NetcastPairingHelper? = null

    // Touch state
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private val clickThreshold = 200 // ms
    private val touchSensitivity = 3.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMouseControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "=== ONCREATE STARTED ===")

        val deviceIp = intent.getStringExtra("DEVICE_IP")
        val sessionId = intent.getStringExtra("SESSION_ID")

        if (deviceIp == null || sessionId == null) {
            Log.e(TAG, "DATE CONEXIUNE LIPSA!")
            Toast.makeText(this, "Date de conexiune lipsă", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        netcastHelper = NetcastPairingHelper(deviceIp, sessionId)
        Log.d(TAG, "NetcastHelper creat cu succes")

        setupUI()
        setupTouchpad()

        netcastHelper?.setCursorVisible(true) { success ->
            runOnUiThread {
                val status = if (success) "Cursor activat" else "Eroare la activarea cursorului"
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Mouse Virtual"
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnClick.text = "Back"
        binding.btnClick.setOnClickListener {
            Log.d(TAG, "Back button pressed, sending BACK command")
            netcastHelper?.sendKeyCommand(NetcastPairingHelper.KEY_CODE_BACK, null)
            Toast.makeText(this, "Comandă 'Înapoi' trimisă", Toast.LENGTH_SHORT).show()
        }

        binding.btnShowMouse.visibility = View.GONE
        binding.btnCenter.visibility = View.GONE

        binding.btnKeyboard.setOnClickListener {
            val intent = Intent(this, KeyboardActivity::class.java)
            intent.putExtra("DEVICE_IP", netcastHelper?.deviceIp)
            intent.putExtra("SESSION_ID", netcastHelper?.sessionId)
            startActivity(intent)
        }
    }

    private fun setupTouchpad() {
        binding.touchpadArea.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.x - lastX) * touchSensitivity
                    val dy = (event.y - lastY) * touchSensitivity
                    if (abs(dx) > 0.1f || abs(dy) > 0.1f) {
                        netcastHelper?.moveMouse(dx, dy)
                    }
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val tapTime = System.currentTimeMillis() - downTime
                    if (tapTime < clickThreshold && abs(event.x - lastX) < 15 && abs(event.y - lastY) < 15) {
                        Log.d(TAG, "Tap detected on touchpad, sending mouse click command")
                        view.performClick()
                        performClick()
                    }
                }
            }
            true
        }

        binding.touchpadArea.setOnLongClickListener {
            Log.d(TAG, "Long press detected, sending BACK command")
            netcastHelper?.sendKeyCommand(NetcastPairingHelper.KEY_CODE_BACK, null)
            true
        }
    }

    private fun performClick() {
        netcastHelper?.sendMouseClick { success ->
            runOnUiThread {
                if (success) {
                    Log.d(TAG, "Mouse click command successful")
                } else {
                    Log.e(TAG, "Mouse click command failed")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== ACTIVITY DESTROYED ===")
        netcastHelper?.setCursorVisible(false)
    }
}
