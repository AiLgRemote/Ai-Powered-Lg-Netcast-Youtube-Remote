package com.tatilacratita.lgcast.sampler

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class SimpleHttpServer(private val context: Context, port: Int) : NanoHTTPD(port) {

    private val TAG = "SimpleHttpServer"
    private val servedFiles = ConcurrentHashMap<String, File>()

    init {
        start(SOCKET_READ_TIMEOUT, false) // Let the caller handle the exception
        Log.d(TAG, "HTTP Server started on port $listeningPort")
    }

    fun serveFile(file: File): String {
        val fileKey = URLEncoder.encode(file.name, StandardCharsets.UTF_8.toString())
        servedFiles[fileKey] = file
        val ipAddress = getLocalIpAddress()
        val url = "http://$ipAddress:$listeningPort/media/$fileKey"
        Log.d(TAG, "Serving file ${file.name} at URL: $url")
        return url
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Request received for $uri from ${session.remoteIpAddress}, Headers: ${session.headers}")

        return when {
            uri.startsWith("/media/") -> {
                val fileKey = uri.substringAfter("/media/")
                serveMediaFile(session, fileKey)
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun serveMediaFile(session: IHTTPSession, fileKey: String): Response {
        val file = servedFiles[fileKey]
        if (file == null || !file.exists()) {
            Log.e(TAG, "File not found for key: $fileKey")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }

        return try {
            val mimeType = getMimeType(file.name)
            val fileLen = file.length()
            val rangeHeader = session.headers["range"]

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val range = rangeHeader.substring("bytes=".length)
                val parts = range.split("-".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

                val start = try { parts[0].toLong() } catch (e: NumberFormatException) { 0L }
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    try { parts[1].toLong() } catch (e: NumberFormatException) { fileLen - 1 }
                } else {
                    fileLen - 1
                }

                if (start >= fileLen) {
                    return newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE,
                        MIME_PLAINTEXT,
                        "Requested range not satisfiable"
                    ).apply { addHeader("Content-Range", "bytes */$fileLen") }
                }

                val chunkLen = end - start + 1
                val fis = FileInputStream(file)
                fis.skip(start)

                val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, fis, chunkLen)
                response.addHeader("Content-Length", chunkLen.toString())
                response.addHeader("Content-Range", "bytes $start-$end/$fileLen")
                response.addHeader("Accept-Ranges", "bytes")
                Log.d(TAG, "Serving partial content for ${file.name}: bytes $start-$end/$fileLen")
                response

            } else {
                Log.d(TAG, "Serving full file: ${file.name}, size: $fileLen, mime: $mimeType")
                newFixedLengthResponse(Response.Status.OK, mimeType, FileInputStream(file), fileLen).apply {
                    addHeader("Content-Length", fileLen.toString())
                    addHeader("Accept-Ranges", "bytes")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error serving file", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".mp4", true) -> "video/mp4"
            fileName.endsWith(".mkv", true) -> "video/x-matroska"
            fileName.endsWith(".avi", true) -> "video/x-msvideo"
            fileName.endsWith(".mov", true) -> "video/quicktime"
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".gif", true) -> "image/gif"
            fileName.endsWith(".webp", true) -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun getLocalIpAddress(): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress

        return String.format(
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
    }

    override fun stop() {
        super.stop()
        Log.d(TAG, "HTTP Server stopped")
    }
}
