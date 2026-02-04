package com.tatilacratita.lgcast.sampler

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class NewPipeDownloader private constructor(client: OkHttpClient) : Downloader() {

    private val client: OkHttpClient

    init {
        this.client = client
    }

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val httpRequestBuilder = okhttp3.Request.Builder()
            .url(url)

        // Adăugăm headerele
        headers.forEach { (name, values) ->
            values.forEach { value ->
                httpRequestBuilder.addHeader(name, value)
            }
        }

        // Creăm RequestBody
        var requestBody: RequestBody? = null
        if (dataToSend != null) {
            var contentTypeString: String? = null
            headers["Content-Type"]?.firstOrNull()?.let {
                contentTypeString = it
            }
            val mediaType = contentTypeString?.toMediaTypeOrNull()
            requestBody = dataToSend.toRequestBody(mediaType)
        } else if (httpMethod.equals("POST", ignoreCase = true) ||
            httpMethod.equals("PUT", ignoreCase = true) ||
            httpMethod.equals("PATCH", ignoreCase = true)) {
            requestBody = ByteArray(0).toRequestBody(null)
        }

        httpRequestBuilder.method(httpMethod, requestBody)

        val response = client.newCall(httpRequestBuilder.build()).execute()

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            response.body?.string(),
            response.request.url.toString()
        )
    }

    companion object {
        @JvmStatic
        var instance: NewPipeDownloader? = null
            private set

        @JvmStatic
        fun init(client: OkHttpClient) {
            if (instance == null) {
                instance = NewPipeDownloader(client)
            }
        }

        @JvmStatic
        fun init() {
            if (instance == null) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()
                instance = NewPipeDownloader(client)
            }
        }
    }
}