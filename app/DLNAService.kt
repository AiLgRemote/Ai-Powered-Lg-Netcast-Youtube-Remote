package com.tatilacratita.lgcast.sampler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.connectsdk.core.MediaInfo
import com.connectsdk.device.ConnectableDevice
import com.connectsdk.service.capability.MediaControl
import com.connectsdk.service.capability.listeners.ResponseListener
import com.connectsdk.service.command.ServiceCommandError
import com.connectsdk.service.sessions.LaunchSession
import com.connectsdk.service.capability.MediaPlayer as ConnectSDKMediaPlayer
import kotlinx.coroutines.CompletableDeferred

class DLNAService : Service() {

    private val TAG = "DLNAService"

    private var connectedDevice: ConnectableDevice? = null
    private var connectSDKMediaPlayer: ConnectSDKMediaPlayer? = null
    private var mediaControl: MediaControl? = null
    private var mediaLaunchSession: LaunchSession? = null
    private lateinit var wifiLock: WifiManager.WifiLock

    // Monitoring stării playback-ului
    @Volatile
    private var currentPlaybackState: String = "IDLE"
    private var playStateSubscription: com.connectsdk.service.capability.listeners.ResponseListener<Any>? = null

    // Deferred pentru semnalare când service-ul este ready
    lateinit var servicePlayerReadyDeferred: CompletableDeferred<Unit>

    inner class LocalBinder : Binder() {
        fun getService(): DLNAService = this@DLNAService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return LocalBinder()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL_ID = "DLNA_SERVICE_CHANNEL"

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_CLOSE = "ACTION_CLOSE"
        const val ACTION_PLAY_YOUTUBE = "ACTION_PLAY_YOUTUBE"
        const val ACTION_PLAY_LOCAL_MEDIA = "ACTION_PLAY_LOCAL_MEDIA"
        const val ACTION_PLAY_IMAGE = "ACTION_PLAY_IMAGE"

        const val EXTRA_YOUTUBE_URL = "EXTRA_YOUTUBE_URL"
        const val EXTRA_LOCAL_MEDIA_PATH = "EXTRA_LOCAL_MEDIA_PATH"
        const val EXTRA_LOCAL_MEDIA_MIMETYPE = "EXTRA_LOCAL_MEDIA_MIMETYPE"
        const val EXTRA_IMAGE_URL = "EXTRA_IMAGE_URL"
        const val EXTRA_IMAGE_TITLE = "EXTRA_IMAGE_TITLE"
        const val EXTRA_IMAGE_DESCRIPTION = "EXTRA_IMAGE_DESCRIPTION"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DLNAService onCreate")
        createNotificationChannelAndStartForeground()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(3, "DLNAServiceWifiLock")
        wifiLock.acquire()

        servicePlayerReadyDeferred = CompletableDeferred()
        currentPlaybackState = "IDLE"
        Log.d(TAG, "WifiLock acquired (HIGH_PERF)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "DLNAService onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Service started with ACTION_START")
                createNotificationChannelAndStartForeground()
            }
            ACTION_CLOSE -> {
                Log.d(TAG, "Service received ACTION_CLOSE. Stopping self.")
                stopMediaPlayback()
                stopSelf()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Service received ACTION_STOP. Stopping playback.")
                stopMediaPlayback()
            }
            ACTION_PLAY_YOUTUBE -> {
                val youtubeUrl = intent.getStringExtra(EXTRA_YOUTUBE_URL)
                if (youtubeUrl != null) {
                    playYoutube(youtubeUrl)
                } else {
                    Log.e(TAG, "ACTION_PLAY_YOUTUBE received without EXTRA_YOUTUBE_URL")
                }
            }
            ACTION_PLAY_LOCAL_MEDIA -> {
                val localMediaPath = intent.getStringExtra(EXTRA_LOCAL_MEDIA_PATH)
                val mimeType = intent.getStringExtra(EXTRA_LOCAL_MEDIA_MIMETYPE)
                if (localMediaPath != null && mimeType != null) {
                    playLocalMedia(localMediaPath, mimeType)
                } else {
                    Log.e(TAG, "ACTION_PLAY_LOCAL_MEDIA received without path or mimetype")
                }
            }
            ACTION_PLAY_IMAGE -> {
                val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL)
                val imageTitle = intent.getStringExtra(EXTRA_IMAGE_TITLE)
                val imageDescription = intent.getStringExtra(EXTRA_IMAGE_DESCRIPTION)
                if (imageUrl != null) {
                    playImage(imageUrl, imageTitle, imageDescription)
                } else {
                    Log.e(TAG, "ACTION_PLAY_IMAGE received without EXTRA_IMAGE_URL")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DLNAService onDestroy")

        // Cleanup monitoring
        cleanupPlayStateMonitoring()

        stopMediaPlayback()
        if (wifiLock.isHeld) {
            wifiLock.release()
            Log.d(TAG, "WifiLock released")
        }
        stopForeground(Service.STOP_FOREGROUND_DETACH)

        connectSDKMediaPlayer = null
        mediaControl = null
        connectedDevice = null
        mediaLaunchSession = null
        currentPlaybackState = "IDLE"

        servicePlayerReadyDeferred.cancel()
    }

    fun setDeviceAndPlayer(device: ConnectableDevice, player: ConnectSDKMediaPlayer) {
        if (servicePlayerReadyDeferred.isCompleted) {
            servicePlayerReadyDeferred = CompletableDeferred()
        }
        connectedDevice = device
        connectSDKMediaPlayer = player
        mediaControl = device.getCapability(MediaControl::class.java)
        Log.d(TAG, "Connected device (${device.friendlyName}) and media player set in service.")
        servicePlayerReadyDeferred.complete(Unit)
    }

    /**
     * Funcție publică pentru a obține starea curentă a playback-ului
     * Folosită de DLNAAutoplayHelper pentru monitoring
     */
    fun getCurrentPlaybackState(): String {
        return currentPlaybackState
    }

    private fun stopMediaPlayback() {
        cleanupPlayStateMonitoring()

        mediaControl?.stop(object : ResponseListener<Any> {
            override fun onSuccess(response: Any?) {
                Log.d(TAG, "Media stopped successfully")
                currentPlaybackState = "STOPPED"
            }

            override fun onError(error: ServiceCommandError?) {
                Log.e(TAG, "Failed to stop media: ${error?.message}")
                currentPlaybackState = "ERROR"
            }
        })
    }

    private fun createNotificationChannelAndStartForeground() {
        val notificationChannelName = getString(R.string.dlna_notification_channel_name)
        val notificationTitle = getString(R.string.dlna_notification_title)
        val notificationText = getString(R.string.dlna_notification_text)
        val closeActionText = getString(R.string.dlna_notification_close_action)

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            notificationChannelName,
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notificationIntent = Intent(this, RemoteControlActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val closeIntent = Intent(this, DLNAService::class.java)
        closeIntent.action = ACTION_CLOSE
        val closePendingIntent = PendingIntent.getService(
            this,
            0,
            closeIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val closeAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_close),
            closeActionText,
            closePendingIntent
        ).build()

        val notification: Notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentIntent(pendingIntent)
            .addAction(closeAction)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun playYoutube(youtubeUrl: String) {
        Log.d(TAG, "Attempting to play YouTube URL: $youtubeUrl")
        currentPlaybackState = "LOADING"

        val mediaInfo = MediaInfo.Builder(youtubeUrl, "video/mp4")
            .setTitle(getString(R.string.youtube_video_title))
            .setDescription(getString(R.string.streaming_from_app_description))
            .build()

        connectSDKMediaPlayer?.playMedia(
            mediaInfo,
            false,
            object : ConnectSDKMediaPlayer.LaunchListener {
                override fun onSuccess(mediaLaunchObject: ConnectSDKMediaPlayer.MediaLaunchObject?) {
                    Log.i(TAG, "YouTube Launch Success")
                    mediaLaunchSession = mediaLaunchObject?.launchSession
                    mediaControl = mediaLaunchObject?.mediaControl

                    currentPlaybackState = "PLAYING"

                    // Start monitoring playback state
                    startPlayStateMonitoring()

                    // Auto-play
                    mediaControl?.play(object : ResponseListener<Any> {
                        override fun onSuccess(response: Any?) {
                            Log.i(TAG, "YouTube Play Success")
                        }

                        override fun onError(err: ServiceCommandError?) {
                            Log.e(TAG, "YouTube Play Error: ${err?.message}")
                            currentPlaybackState = "ERROR"
                        }
                    })
                }

                override fun onError(err: ServiceCommandError?) {
                    Log.e(TAG, "YouTube Launch Error: ${err?.message}")
                    currentPlaybackState = "ERROR"
                }
            }
        )
    }

    private fun playLocalMedia(localMediaPath: String, mimeType: String) {
        Log.d(TAG, "Attempting to play local media: $localMediaPath")
        currentPlaybackState = "LOADING"

        val mediaInfo = MediaInfo.Builder(localMediaPath, mimeType)
            .setTitle(getString(R.string.local_media_title))
            .build()

        connectSDKMediaPlayer?.playMedia(
            mediaInfo,
            false,
            object : ConnectSDKMediaPlayer.LaunchListener {
                override fun onSuccess(mediaLaunchObject: ConnectSDKMediaPlayer.MediaLaunchObject?) {
                    Log.i(TAG, "Local Media Launch Success")
                    mediaLaunchSession = mediaLaunchObject?.launchSession
                    mediaControl = mediaLaunchObject?.mediaControl

                    currentPlaybackState = "PLAYING"

                    // Start monitoring playback state
                    startPlayStateMonitoring()

                    mediaControl?.play(object : ResponseListener<Any> {
                        override fun onSuccess(response: Any?) {
                            Log.i(TAG, "Local Media Play Success")
                        }

                        override fun onError(err: ServiceCommandError?) {
                            Log.e(TAG, "Local Media Play Error: ${err?.message}")
                            currentPlaybackState = "ERROR"
                        }
                    })
                }

                override fun onError(err: ServiceCommandError?) {
                    Log.e(TAG, "Local Media Launch Error: ${err?.message}")
                    currentPlaybackState = "ERROR"
                }
            }
        )
    }

    private fun playImage(imageUrl: String, title: String?, description: String?) {
        Log.d(TAG, "Attempting to display image: $imageUrl")
        currentPlaybackState = "LOADING"

        val mediaInfo = MediaInfo.Builder(imageUrl, "image/jpeg")
            .setTitle(title ?: "")
            .setDescription(description ?: "")
            .build()

        connectSDKMediaPlayer?.playMedia(
            mediaInfo,
            false,
            object : ConnectSDKMediaPlayer.LaunchListener {
                override fun onSuccess(mediaLaunchObject: ConnectSDKMediaPlayer.MediaLaunchObject?) {
                    Log.d(TAG, "Image displayed successfully")
                    mediaLaunchSession = mediaLaunchObject?.launchSession
                    mediaControl = mediaLaunchObject?.mediaControl
                    currentPlaybackState = "PLAYING" // Pentru imagini considerăm ca "playing"
                }

                override fun onError(err: ServiceCommandError?) {
                    Log.e(TAG, "Image display error: ${err?.message}")
                    currentPlaybackState = "ERROR"
                }
            }
        )
    }

    /**
     * Începe monitorizarea stării playback-ului
     * Folosește polling pentru a detecta când video-ul se termină
     */
    private fun startPlayStateMonitoring() {
        cleanupPlayStateMonitoring()

        Log.d(TAG, "Starting playback state monitoring")

        // Folosim un thread separat pentru polling
        Thread {
            try {
                while (currentPlaybackState == "PLAYING" || currentPlaybackState == "LOADING") {
                    Thread.sleep(2000) // Check la fiecare 2 secunde

                    // Verifică starea prin getPlayState
                    mediaControl?.getPlayState(object : MediaControl.PlayStateListener {
                        override fun onSuccess(playState: MediaControl.PlayStateStatus?) {
                            val newState = when (playState) {
                                MediaControl.PlayStateStatus.Playing -> "PLAYING"
                                MediaControl.PlayStateStatus.Paused -> "PAUSED"
                                MediaControl.PlayStateStatus.Idle -> "IDLE"
                                MediaControl.PlayStateStatus.Finished -> "FINISHED"
                                MediaControl.PlayStateStatus.Buffering -> "PLAYING" // Considerăm buffering ca playing
                                else -> "UNKNOWN"
                            }

                            if (newState != currentPlaybackState) {
                                Log.d(TAG, "Playback state changed: $currentPlaybackState -> $newState")
                                currentPlaybackState = newState
                            }
                        }

                        override fun onError(error: ServiceCommandError?) {
                            Log.w(TAG, "Error getting play state: ${error?.message}")
                            // Nu schimbăm starea pe eroare temporară
                        }
                    })

                    // Dacă starea s-a schimbat la FINISHED, IDLE sau STOPPED, ieșim din loop
                    if (currentPlaybackState in listOf("FINISHED", "IDLE", "STOPPED", "ERROR")) {
                        Log.d(TAG, "Playback monitoring stopped - state: $currentPlaybackState")
                        break
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Playback monitoring interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "Error in playback monitoring", e)
            }
        }.start()
    }

    /**
     * Curăță resursele de monitoring
     */
    private fun cleanupPlayStateMonitoring() {
        playStateSubscription = null
        // Thread-ul se va opri singur când starea se schimbă
    }
}