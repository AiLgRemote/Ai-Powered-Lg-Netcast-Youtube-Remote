package com.tatilacratita.lgcast.sampler

import android.util.Log
import kotlinx.coroutines.*
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object NewPipeHelper {

    private const val TAG = "NewPipeHelper"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Inițializează NewPipeDownloader
        NewPipeDownloader.init()
        // Inițializează NewPipe cu downloader-ul custom
        NewPipe.init(NewPipeDownloader.instance)
        Log.d(TAG, "NewPipeHelper initialized successfully")
    }

    data class VideoInfo(
        val title: String,
        val author: String,
        val duration: Long,
        val thumbnail: String,
        val videoUrl: String?,
        val audioUrl: String?,
        val description: String,
        val isMuxed: Boolean = false
    )

    data class SearchResult(
        val videoId: String,
        val title: String,
        val author: String,
        val thumbnail: String,
        val duration: String,
        val views: String,
        val uploadedDate: String
    )

    /**
     * Extrage video ID din URL YouTube
     */
    fun extractVideoId(youtubeUrl: String): String? {
        val patterns = listOf(
            Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/)([^&\\s]+)", RegexOption.IGNORE_CASE),
            Regex("youtube\\.com/embed/([^&\\s]+)", RegexOption.IGNORE_CASE),
            Regex("youtube\\.com/v/([^&\\s]+)", RegexOption.IGNORE_CASE),
            Regex("youtube\\.com/shorts/([^&\\s]+)", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(youtubeUrl)?.let { return it.groupValues[1] }
        }
        if (youtubeUrl.matches("[a-zA-Z0-9_-]{11}".toRegex())) {
            return youtubeUrl
        }
        return null
    }

    /**
     * Obține informații complete despre video cu retry logic
     */
    suspend fun getVideoInfo(videoId: String): VideoInfo? = withContext(Dispatchers.IO) {
        val maxRetries = 3
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                Log.d(TAG, "Fetching video info for: $videoId (attempt ${attempt + 1}/$maxRetries)")

                val url = "https://www.youtube.com/watch?v=$videoId"
                val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, url)

                // Extrage cel mai bun stream audio
                val bestAudio = streamInfo.audioStreams
                    .maxByOrNull { it.averageBitrate }
                    ?.url

                Log.d(TAG, "Found ${streamInfo.audioStreams.size} audio streams")

                // Extrage thumbnail de calitate înaltă
                val thumbnail = streamInfo.thumbnails
                    .maxByOrNull { it.width }
                    ?.url ?: ""

                // Verifică stream-uri disponibile
                val videoStreams = streamInfo.videoStreams
                val videoOnlyStreams = streamInfo.videoOnlyStreams

                var bestVideoUrl: String? = null
                var isMuxed = false

                // Prioritate 1: Stream muxed (video + audio împreună)
                if (videoStreams.isNotEmpty()) {
                    // Prioritizează calități în ordine: 1080p -> 720p -> 480p -> 360p
                    val preferredHeights = listOf(1080, 720, 480, 360)

                    for (height in preferredHeights) {
                        bestVideoUrl = videoStreams
                            .firstOrNull { it.height == height }
                            ?.url
                        if (bestVideoUrl != null) break
                    }

                    // Fallback la cea mai bună calitate disponibilă
                    if (bestVideoUrl == null) {
                        bestVideoUrl = videoStreams
                            .maxByOrNull { it.height }
                            ?.url
                    }

                    isMuxed = bestVideoUrl != null
                    Log.d(TAG, "Found muxed stream at ${videoStreams.firstOrNull { it.url == bestVideoUrl }?.height ?: "unknown"}p: $isMuxed")
                }

                // Prioritate 2: Stream video-only (necesită audio separat)
                if (bestVideoUrl == null && videoOnlyStreams.isNotEmpty()) {
                    val preferredHeights = listOf(1080, 720, 480, 360)

                    for (height in preferredHeights) {
                        bestVideoUrl = videoOnlyStreams
                            .firstOrNull { it.height == height }
                            ?.url
                        if (bestVideoUrl != null) break
                    }

                    if (bestVideoUrl == null) {
                        bestVideoUrl = videoOnlyStreams
                            .maxByOrNull { it.height }
                            ?.url
                    }
                    Log.d(TAG, "Using video-only stream at ${videoOnlyStreams.firstOrNull { it.url == bestVideoUrl }?.height ?: "unknown"}p")
                }

                if (bestVideoUrl == null) {
                    Log.e(TAG, "No video stream found for video: $videoId")
                    return@withContext null
                }

                if (bestAudio == null) {
                    Log.e(TAG, "No audio stream found for video: $videoId")
                    return@withContext null
                }

                Log.i(TAG, "✓ Successfully extracted video info for: ${streamInfo.name}")

                return@withContext VideoInfo(
                    title = streamInfo.name,
                    author = streamInfo.uploaderName ?: "Unknown",
                    duration = streamInfo.duration * 1000L, // Convertește la milisecunde
                    thumbnail = thumbnail,
                    videoUrl = bestVideoUrl,
                    audioUrl = if (isMuxed) null else bestAudio, // Dacă e muxed, nu mai trebuie audio separat
                    description = streamInfo.description?.content ?: "",
                    isMuxed = isMuxed
                )

            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1} failed for video $videoId: ${e.message}")
                if (attempt < maxRetries - 1) {
                    delay(1000L * (attempt + 1)) // Exponential backoff
                }
            }
        }

        Log.e(TAG, "All attempts failed for video: $videoId", lastException)
        return@withContext null
    }
    /**
     * Caută videoclipuri pe YouTube
     */
    suspend fun searchVideos(query: String, maxResults: Int = 20): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                if (query.isBlank()) return@withContext emptyList()

                Log.d(TAG, "Searching for: $query")

                val searchExtractor = ServiceList.YouTube.getSearchExtractor(query)
                searchExtractor.fetchPage()

                val results = mutableListOf<SearchResult>()
                val items = searchExtractor.initialPage.items

                for (item in items) {
                    if (results.size >= maxResults) break

                    if (item is StreamInfoItem) {
                        val videoId = extractVideoId(item.url) ?: continue

                        results.add(
                            SearchResult(
                                videoId = videoId,
                                title = item.name,
                                author = item.uploaderName ?: "Unknown",
                                thumbnail = item.thumbnails
                                    ?.sortedByDescending { it.width ?: 0 }
                                    ?.firstOrNull()
                                    ?.url ?: "",
                                duration = formatDuration(item.duration),
                                views = formatViews(item.viewCount),
                                uploadedDate = item.uploadDate?.date()?.toString() ?: ""
                            )
                        )
                    }
                }

                Log.i(TAG, "✓ Found ${results.size} search results")
                return@withContext results

            } catch (e: Exception) {
                Log.e(TAG, "Error searching videos", e)
                emptyList()
            }
        }

    /**
     * Formatează durata în format MM:SS sau HH:MM:SS
     */
    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0:00"

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, secs)
        }
    }

    /**
     * Formatează numărul de vizualizări
     */
    private fun formatViews(viewCount: Long): String {
        return when {
            viewCount < 1_000 -> viewCount.toString()
            viewCount < 1_000_000 -> String.format(java.util.Locale.US, "%.1fk", viewCount / 1_000.0)
            viewCount < 1_000_000_000 -> String.format(java.util.Locale.US, "%.1fM", viewCount / 1_000_000.0)
            else -> String.format(java.util.Locale.US, "%.1fB", viewCount / 1_000_000_000.0)
        }
    }

    /**
     * Obține statistici (pentru compatibilitate cu vechiul API)
     */
    fun getInstancesStats(): String {
        return buildString {
            appendLine("📊 NewPipe Extractor Statistics:")
            appendLine("Status: Direct YouTube extraction")
            appendLine("No proxy servers needed")
            appendLine("✓ Fast and reliable")
        }
    }

    /**
     * Force refresh (pentru compatibilitate cu vechiul API)
     */
    suspend fun forceRefresh() {
        Log.d(TAG, "Force refresh called - NewPipe doesn't need instance refresh")
    }
}