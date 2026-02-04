package com.tatilacratita.lgcast.sampler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YouTubeSearchHelper {

    private val innerTubeSearchHelper = InnerTubeSearchHelper()

    data class SearchResult(
        val videoId: String,
        val title: String,
        val author: String,
        val thumbnail: String,
        val duration: String,
        val views: String,
        val uploadedDate: String
    )

    suspend fun searchVideos(query: String, maxResults: Int = 20): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // Deleagă căutarea către InnerTubeSearchHelper
        val innerTubeResults = innerTubeSearchHelper.searchVideos(query, maxResults)

        // Convertește rezultatele la tipul de date așteptat de UI
        return@withContext innerTubeResults.map { innerResult ->
            SearchResult(
                videoId = innerResult.videoId,
                title = innerResult.title,
                author = innerResult.author,
                thumbnail = innerResult.thumbnail,
                duration = innerResult.duration,
                views = innerResult.views,
                uploadedDate = innerResult.uploadedDate
            )
        }
    }
}
