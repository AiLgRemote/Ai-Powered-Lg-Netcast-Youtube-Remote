package com.tatilacratita.lgcast.sampler

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.connectsdk.device.ConnectableDevice
import com.connectsdk.device.ConnectableDeviceListener
import com.connectsdk.discovery.DiscoveryManager
import com.connectsdk.discovery.DiscoveryManagerListener
import com.connectsdk.service.DeviceService
import com.connectsdk.service.command.ServiceCommandError
import com.tatilacratita.lgcast.sampler.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), DiscoveryManagerListener {

    private val tag = "LGCastApp_Main"
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var mediaDevice: ConnectableDevice? = null
    private var remoteDevice: ConnectableDevice? = null

    companion object {
        const val PREFS_NAME = "lgcast_prefs"
        const val KEY_SESSION_ID = "netcast_session_id_"
        const val KEY_LAST_MEDIA_DEVICE_IP = "last_media_device_ip"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        DiscoveryManager.init(applicationContext)
        DiscoveryManager.getInstance().addListener(this)

        setupUI()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaDevice?.disconnect()
        remoteDevice?.disconnect()
        DiscoveryManager.getInstance().removeListener(this)
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = "Legacy Device Discovery"

        binding.tvStatus.text = getString(R.string.searching_for_tv)
        binding.tvStatusRemote.text = getString(R.string.searching_for_remote)
        binding.tvStatusYouTube.text = "This is the hidden MainActivity."

        // Hide most buttons, leaving a minimal interface
        binding.btnSelectMedia.visibility = View.GONE
        binding.btnDisconnect.visibility = View.VISIBLE
        binding.btnPlayYouTube.visibility = View.GONE
        binding.photoButtonsLayout.visibility = View.GONE
        binding.newRemoteControls.visibility = View.GONE
        binding.btnLaunchRemote.visibility = View.GONE
        binding.touchpad.visibility = View.GONE
        binding.btnVoice.visibility = View.GONE
        // binding.aiButtonsLayout.visibility = View.GONE // Removed this line as it caused an error


        binding.btnDisconnect.setOnClickListener {
            Log.d(tag, "Manual disconnect initiated.")
            mediaDevice?.disconnect()
            prefs.edit { remove(KEY_LAST_MEDIA_DEVICE_IP) }
            Toast.makeText(this, getString(R.string.disconnected_from_media_tv), Toast.LENGTH_SHORT).show()
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
                binding.tvStatus.text = "Media Device: ${device.friendlyName} (Connected)"
                Toast.makeText(this@MainActivity, "Media device connected: ${device.friendlyName}", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onDeviceDisconnected(device: ConnectableDevice) {
            mediaDevice = null
            runOnUiThread {
                binding.tvStatus.text = getString(R.string.searching_for_tv)
            }
        }

        override fun onConnectionFailed(device: ConnectableDevice, error: ServiceCommandError?) {
            mediaDevice = null
            runOnUiThread {
                binding.tvStatus.text = getString(R.string.media_connection_failed)
            }
        }
        override fun onCapabilityUpdated(device: ConnectableDevice, added: MutableList<String>, removed: MutableList<String>) {}
        override fun onPairingRequired(device: ConnectableDevice, service: DeviceService, pairingType: DeviceService.PairingType) {}
    }

    private val remoteDeviceListener = object : ConnectableDeviceListener {
        override fun onDeviceReady(device: ConnectableDevice) {
            runOnUiThread {
                binding.tvStatusRemote.text = "Remote Device: ${device.friendlyName} (Connected)"
                 Toast.makeText(this@MainActivity, "Remote device connected: ${device.friendlyName}", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onDeviceDisconnected(device: ConnectableDevice) {
            remoteDevice = null
            runOnUiThread {
                binding.tvStatusRemote.text = getString(R.string.searching_for_remote)
            }
        }

        override fun onConnectionFailed(device: ConnectableDevice, error: ServiceCommandError?) {
            runOnUiThread { binding.tvStatusRemote.text = getString(R.string.remote_connection_failed) }
        }

        override fun onPairingRequired(device: ConnectableDevice, service: DeviceService, pairingType: DeviceService.PairingType) {}
        override fun onCapabilityUpdated(device: ConnectableDevice, added: MutableList<String>, removed: MutableList<String>) {}
    }


    override fun onDeviceAdded(manager: DiscoveryManager?, device: ConnectableDevice?) {
        if (device == null) return

        val hasDLNA = device.getServiceByName("DLNA") != null
        val hasNetcast = device.getServiceByName("Netcast TV") != null

        if (mediaDevice == null && hasDLNA) {
            handleDeviceSelection(device, isRemote = false)
        }

        if (remoteDevice == null && hasNetcast) {
            handleDeviceSelection(device, isRemote = true)
        }
    }

    override fun onDeviceUpdated(manager: DiscoveryManager?, device: ConnectableDevice?) {}

    override fun onDeviceRemoved(manager: DiscoveryManager?, device: ConnectableDevice?) {
        if (device == mediaDevice) mediaDevice?.disconnect()
        if (device == remoteDevice) remoteDevice?.disconnect()
    }

    override fun onDiscoveryFailed(manager: DiscoveryManager?, error: ServiceCommandError?) {
        Log.e(tag, "Discovery failed: ${error?.message}")
    }
}
