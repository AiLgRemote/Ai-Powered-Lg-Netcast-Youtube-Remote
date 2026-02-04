package com.tatilacratita.lgcast.sampler

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * InnerTubeSearchHelper actualizat pentru a folosi NewPipeHelper
 * Păstrează interfața pentru compatibilitate cu YouTubeSearchDialog
 */
class InnerTubeSearchHelper {

    private val tag = "InnerTubeSearch"

    data class VideoResult(
        val videoId: String,
        val title: String,
        val author: String,
        val thumbnail: String,
        val duration: String,
        val views: String,
        val uploadedDate: String
    )

    /**
     * Caută videoclipuri folosind NewPipeExtractor
     */
    suspend fun searchVideos(query: String, maxResults: Int = 20): List<VideoResult> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Searching with NewPipe for: $query")

                val results = NewPipeHelper.searchVideos(query, maxResults)

                // Convertește rezultatele NewPipe la formatul așteptat
                results.map { result ->
                    VideoResult(
                        videoId = result.videoId,
                        title = result.title,
                        author = result.author,
                        thumbnail = result.thumbnail,
                        duration = result.duration,
                        views = result.views,
                        uploadedDate = result.uploadedDate
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Search error with NewPipe", e)
                emptyList()
            }
        }
}