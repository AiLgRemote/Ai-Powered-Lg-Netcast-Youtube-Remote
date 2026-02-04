package com.tatilacratita.lgcast.sampler

import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

object DLNAAutoplayHelper {

    private const val TAG = "DLNAAutoplayHelper"

    private var slideshowJob: Job? = null
    private var playlistJob: Job? = null

    private const val SLIDESHOW_DELAY_SECONDS = 5L
    private const val ALBUM_DELAY_SECONDS = 10L
    private const val VIDEO_LOAD_DELAY_MS = 5000L // Delay pentru încărcarea video-ului
    private const val BETWEEN_VIDEOS_DELAY_MS = 2000L // Delay între videoclipuri
    private const val VIDEO_CHECK_INTERVAL_MS = 2000L // Verifică starea video-ului la fiecare 2 secunde

    // StateFlow pentru a monitoriza starea video-ului
    val videoPlaybackState = MutableStateFlow<VideoPlaybackState>(VideoPlaybackState.Idle)

    sealed class VideoPlaybackState {
        object Idle : VideoPlaybackState()
        object Loading : VideoPlaybackState()
        object Playing : VideoPlaybackState()
        object Finished : VideoPlaybackState()
        data class Error(val message: String) : VideoPlaybackState()
    }

    // ======================== IMAGE FUNCTIONS ========================

    fun startSlideshow(dlnaService: DLNAService, imageUrls: List<String>, title: String) {
        playImageSequence(dlnaService, imageUrls, title, SLIDESHOW_DELAY_SECONDS)
    }

    fun playAlbum(dlnaService: DLNAService, imageUrls: List<String>, title: String) {
        playImageSequence(dlnaService, imageUrls, title, ALBUM_DELAY_SECONDS)
    }

    fun stopSlideshow() {
        slideshowJob?.cancel()
        slideshowJob = null
        Log.d(TAG, "Slideshow stopped")
    }

    private fun playImageSequence(
        dlnaService: DLNAService,
        imageUrls: List<String>,
        title: String,
        delaySeconds: Long
    ) {
        slideshowJob?.cancel()
        if (imageUrls.isEmpty()) return

        slideshowJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                for ((index, imageUrl) in imageUrls.withIndex()) {
                    val imageTitle = "$title (${index + 1}/${imageUrls.size})"
                    val imageDescription = "Imagine ${index + 1} din ${imageUrls.size}"

                    val playImageIntent = Intent(dlnaService, DLNAService::class.java).apply {
                        action = DLNAService.ACTION_PLAY_IMAGE
                        putExtra(DLNAService.EXTRA_IMAGE_URL, imageUrl)
                        putExtra(DLNAService.EXTRA_IMAGE_TITLE, imageTitle)
                        putExtra(DLNAService.EXTRA_IMAGE_DESCRIPTION, imageDescription)
                    }
                    dlnaService.onStartCommand(playImageIntent, 0, 0)

                    delay(delaySeconds * 1000)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Image sequence cancelled")
            } finally {
                slideshowJob = null
                dlnaService.onStartCommand(
                    Intent(dlnaService, DLNAService::class.java).apply {
                        action = DLNAService.ACTION_STOP
                    }, 0, 0
                )
            }
        }
    }

    // ======================== VIDEO PLAYLIST FUNCTIONS ========================

    /**
     * Redă un playlist de videoclipuri YouTube automat, unul după altul
     */
    fun playVideoPlaylist(
        dlnaService: DLNAService,
        videoUrls: List<String>,
        playlistTitle: String,
        videoTitles: List<String>? = null
    ) {
        playlistJob?.cancel()
        if (videoUrls.isEmpty()) {
            Log.w(TAG, "Cannot play empty playlist")
            return
        }

        Log.d(TAG, "Starting video playlist: $playlistTitle with ${videoUrls.size} videos")

        playlistJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                for ((index, videoUrl) in videoUrls.withIndex()) {
                    if (!isActive) {
                        Log.d(TAG, "Playlist job cancelled")
                        break
                    }

                    val videoTitle = videoTitles?.getOrNull(index)
                        ?: "Video ${index + 1}/${videoUrls.size}"

                    Log.d(TAG, "Playing video ${index + 1}/${videoUrls.size}: $videoTitle")

                    // Resetează starea video-ului
                    videoPlaybackState.value = VideoPlaybackState.Loading

                    // Redă video-ul
                    val playIntent = Intent(dlnaService, DLNAService::class.java).apply {
                        action = DLNAService.ACTION_PLAY_YOUTUBE
                        putExtra(DLNAService.EXTRA_YOUTUBE_URL, videoUrl)
                    }
                    dlnaService.onStartCommand(playIntent, 0, 0)

                    // Așteaptă ca video-ul să se încarce
                    Log.d(TAG, "Waiting for video to load...")
                    delay(VIDEO_LOAD_DELAY_MS)

                    // Marchează video-ul ca "Playing"
                    videoPlaybackState.value = VideoPlaybackState.Playing

                    // Așteaptă ca video-ul să se termine prin monitorizare activă
                    Log.d(TAG, "Monitoring video playback...")
                    val finished = waitForVideoToFinish(dlnaService, index, videoUrls.size)

                    if (!finished) {
                        Log.w(TAG, "Video monitoring timed out or was cancelled")
                        // Continuăm oricum la următorul video
                    }

                    Log.d(TAG, "Video ${index + 1} finished/timed out")

                    // Pauză între videoclipuri
                    if (index < videoUrls.size - 1) {
                        delay(BETWEEN_VIDEOS_DELAY_MS)
                    }
                }

                Log.d(TAG, "Playlist completed successfully")
                videoPlaybackState.value = VideoPlaybackState.Idle

            } catch (e: CancellationException) {
                Log.d(TAG, "Playlist cancelled")
                videoPlaybackState.value = VideoPlaybackState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Error in playlist playback", e)
                videoPlaybackState.value = VideoPlaybackState.Error(e.message ?: "Unknown error")
            } finally {
                playlistJob = null
            }
        }
    }

    /**
     * Așteaptă finalizarea video-ului prin monitorizare activă
     * Returnează true dacă video-ul s-a terminat normal, false dacă a fost timeout
     */
    private suspend fun waitForVideoToFinish(
        dlnaService: DLNAService,
        videoIndex: Int,
        totalVideos: Int
    ): Boolean {
        // Timeout maxim per video: 30 minute (pentru videoclipuri lungi)
        val maxChecks = (30 * 60 * 1000) / VIDEO_CHECK_INTERVAL_MS.toInt()
        var checksPerformed = 0

        while (checksPerformed < maxChecks && playlistJob?.isActive == true) {
            delay(VIDEO_CHECK_INTERVAL_MS)
            checksPerformed++

            // Verifică starea video-ului din DLNAService
            val currentState = dlnaService.getCurrentPlaybackState()

            when (currentState) {
                "STOPPED", "IDLE", "FINISHED" -> {
                    Log.d(TAG, "Video ${videoIndex + 1}/$totalVideos finished (state: $currentState)")
                    videoPlaybackState.value = VideoPlaybackState.Finished
                    return true
                }
                "PLAYING" -> {
                    // Video-ul încă se redă - continuă monitorizarea
                    if (checksPerformed % 15 == 0) { // Log la fiecare 30 secunde
                        Log.d(TAG, "Video ${videoIndex + 1}/$totalVideos still playing... (${checksPerformed * VIDEO_CHECK_INTERVAL_MS / 1000}s elapsed)")
                    }
                }
                "PAUSED" -> {
                    Log.d(TAG, "Video ${videoIndex + 1}/$totalVideos is paused")
                    // Continuă monitorizarea - utilizatorul ar putea relua
                }
                "ERROR" -> {
                    Log.e(TAG, "Video ${videoIndex + 1}/$totalVideos encountered an error")
                    videoPlaybackState.value = VideoPlaybackState.Error("Playback error")
                    return false
                }
                else -> {
                    Log.w(TAG, "Unknown playback state: $currentState")
                }
            }
        }

        if (checksPerformed >= maxChecks) {
            Log.w(TAG, "Video ${videoIndex + 1}/$totalVideos monitoring timeout reached (30 minutes)")
            return false
        }

        return false
    }

    /**
     * Oprește playlist-ul de video curent
     */
    fun stopPlaylist() {
        playlistJob?.cancel()
        playlistJob = null
        videoPlaybackState.value = VideoPlaybackState.Idle
        Log.d(TAG, "Video playlist stopped")
    }

    /**
     * Oprește orice redare activă (slideshow sau playlist)
     */
    fun stopAll() {
        stopSlideshow()
        stopPlaylist()
        Log.d(TAG, "All playback stopped")
    }

    /**
     * Verifică dacă un playlist este activ în acest moment
     */
    fun isPlaylistActive(): Boolean {
        return playlistJob?.isActive == true
    }

    /**
     * Verifică dacă un slideshow este activ în acest moment
     */
    fun isSlideshowActive(): Boolean {
        return slideshowJob?.isActive == true
    }

    /**
     * Placeholder pentru playlist audio
     */
    fun playAudioPlaylist(
        dlnaService: DLNAService,
        audioUrls: List<String>,
        playlistTitle: String,
        audioTitles: List<String>? = null
    ) {
        Log.d(TAG, "Audio playlist not yet implemented")
    }
}