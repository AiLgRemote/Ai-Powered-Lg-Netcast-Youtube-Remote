package com.tatilacratita.lgcast.sampler

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class DialHelper(private val deviceIp: String) {

    private val TAG = "DialHelper"

    // Porturi DIAL posibile pentru LG TVs
    private val dialPorts = listOf(
        56789,  // Port standard DIAL
        8080,   // Port alternativ pentru Netcast
        3000,   // Port alternativ
        3001    // Port alternativ
    )

    private var workingPort: Int? = null
    var isYouTubeRunning = false
        private set

    private var youtubeAppUrl: String? = null

    /**
     * Descoperă portul DIAL funcțional și verifică dacă YouTube este disponibil
     */
    fun discoverYouTubeApp(callback: (Boolean) -> Unit) {
        thread {
            var found = false

            // Încearcă toate porturile posibile
            for (port in dialPorts) {
                try {
                    Log.d(TAG, "Trying DIAL port: $port")
                    val url = URL("http://$deviceIp:$port/apps/YouTube")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 2000
                    connection.readTimeout = 2000

                    val responseCode = connection.responseCode
                    Log.d(TAG, "Port $port response code: $responseCode")

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        Log.d(TAG, "YouTube app info on port $port: $response")

                        workingPort = port
                        youtubeAppUrl = parseAppUrl(response)
                        val state = parseAppState(response)
                        isYouTubeRunning = state == "running"

                        found = true
                        connection.disconnect()
                        break
                    }

                    connection.disconnect()
                } catch (e: Exception) {
                    Log.d(TAG, "Port $port failed: ${e.message}")
                }
            }

            if (!found) {
                Log.w(TAG, "No working DIAL port found. Trying Netcast alternative...")
                // Încercăm metoda Netcast directă
                tryNetcastYouTubeLaunch(callback)
            } else {
                callback(true)
            }
        }
    }

    /**
     * Metodă alternativă pentru TV-uri Netcast vechi care nu au DIAL
     * Folosește API-ul Netcast pentru a lansa YouTube
     */
    private fun tryNetcastYouTubeLaunch(callback: (Boolean) -> Unit) {
        thread {
            try {
                val url = URL("http://$deviceIp:8080/roap/api/command")

                // Mai întâi încearcă să lanseze browserul web cu YouTube
                // Pentru că aplicația YouTube probabil nu mai există
                Log.d(TAG, "Trying to launch YouTube in web browser...")
                launchYouTubeInBrowser(null) { browserSuccess ->
                    if (browserSuccess) {
                        callback(true)
                        return@launchYouTubeInBrowser
                    }

                    // Dacă browserul nu funcționează, încearcă app-urile YouTube
                    val youtubeAppIds = listOf(
                        "youtube.leanback.v4",
                        "youtube",
                        "leanback.youtube"
                    )

                    for (appId in youtubeAppIds) {
                        val xmlBody = """<?xml version="1.0" encoding="utf-8"?>
                            <command>
                                <n>AppExecute</n>
                                <auid>$appId</auid>
                            </command>""".trimIndent()

                        Log.d(TAG, "Trying Netcast YouTube launch with appId: $appId")
                        val response = sendNetcastRequest(url, xmlBody)

                        if (response?.contains("200") == true) {
                            Log.d(TAG, "Successfully launched YouTube via Netcast!")
                            workingPort = 8080
                            callback(true)
                            return@launchYouTubeInBrowser
                        }
                    }

                    Log.e(TAG, "Failed to launch YouTube via Netcast")
                    callback(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error trying Netcast YouTube launch", e)
                callback(false)
            }
        }
    }
    
    /**
     * Lansează o aplicație generică pe TV
     */
    fun launchApp(appName: String, callback: (Boolean) -> Unit) {
        // Dacă e YouTube, folosim funcția specializată
        if (appName.equals("YouTube", ignoreCase = true)) {
            launchYouTube(callback = callback)
            return
        }

        thread {
            // 1. Încercăm protocolul DIAL mai întâi
            val port = workingPort
            var launched = false
            if (port != null) {
                try {
                    val dialAppName = appName.replace(" ", "")
                    Log.d(TAG, "Trying to launch '$dialAppName' via DIAL on port $port")
                    val url = URL("http://$deviceIp:$port/apps/$dialAppName")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000

                    val responseCode = connection.responseCode
                    Log.d(TAG, "Launch '$dialAppName' via DIAL response code: $responseCode")

                    if (responseCode == HttpURLConnection.HTTP_CREATED || responseCode == HttpURLConnection.HTTP_OK) {
                        launched = true
                    } else {
                        Log.w(TAG, "DIAL launch for '$dialAppName' failed. Trying Netcast...")
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching '$appName' via DIAL. Trying Netcast...", e)
                }
            }

            if (launched) {
                callback(true)
                return@thread
            }

            // 2. Fallback la protocolul Netcast
            try {
                val url = URL("http://$deviceIp:8080/roap/api/command")
                
                // Maparea numelor de aplicații la ID-urile posibile pentru Netcast
                val appId = when {
                    appName.equals("Netflix", ignoreCase = true) -> "netflix"
                    appName.equals("Prime Video", ignoreCase = true) -> "amazon" // ID comun pt Prime Video
                    else -> appName.lowercase().replace(" ", "")
                }

                val xmlBody = """<?xml version="1.0" encoding="utf-8"?>
                    <command>
                        <name>AppExecute</name>
                        <auid>$appId</auid>
                    </command>""".trimIndent()

                Log.d(TAG, "Trying Netcast launch for '$appName' with appId: '$appId'")
                val response = sendNetcastRequest(url, xmlBody)
                
                if (response?.contains("200") == true) {
                    Log.d(TAG, "Successfully launched '$appName' via Netcast!")
                    callback(true)
                } else {
                    Log.e(TAG, "Failed to launch '$appName' via Netcast.")
                    callback(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error trying Netcast launch for '$appName'", e)
                callback(false)
            }
        }
    }

    /**
     * Lansează aplicația YouTube pe TV
     */
    fun launchYouTube(videoId: String? = null, callback: (Boolean) -> Unit) {
        thread {
            val port = workingPort

            if (port == null) {
                // Dacă nu avem port, încearcă direct metoda Netcast
                tryNetcastYouTubeLaunch(callback)
                return@thread
            }

            try {
                val url = URL("http://$deviceIp:$port/apps/YouTube")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "text/plain")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true

                // Dacă avem un videoId, îl trimitem ca payload
                if (videoId != null) {
                    val payload = "v=$videoId"
                    connection.outputStream.use { os ->
                        os.write(payload.toByteArray(charset("utf-8")))
                    }
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "Launch YouTube response code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_CREATED ||
                    responseCode == HttpURLConnection.HTTP_OK) {

                    val location = connection.getHeaderField("Location")
                    if (location != null) {
                        youtubeAppUrl = location
                        Log.d(TAG, "YouTube instance URL: $youtubeAppUrl")
                    }

                    isYouTubeRunning = true
                    callback(true)
                } else {
                    Log.e(TAG, "Failed to launch YouTube via DIAL, trying Netcast...")
                    tryNetcastYouTubeLaunch(callback)
                }

                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error launching YouTube via DIAL, trying Netcast...", e)
                tryNetcastYouTubeLaunch(callback)
            }
        }
    }

    /**
     * Oprește aplicația YouTube de pe TV
     */
    fun stopYouTube(callback: (Boolean) -> Unit) {
        thread {
            try {
                val port = workingPort ?: 56789
                val appUrl = youtubeAppUrl ?: "http://$deviceIp:$port/apps/YouTube/run"

                val url = URL(appUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "DELETE"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                Log.d(TAG, "Stop YouTube response code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK ||
                    responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
                    isYouTubeRunning = false
                    youtubeAppUrl = null
                    callback(true)
                } else {
                    Log.e(TAG, "Failed to stop YouTube")
                    callback(false)
                }

                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping YouTube", e)
                // Chiar dacă nu putem opri prin DIAL, marcăm ca și deconectat
                isYouTubeRunning = false
                callback(true)
            }
        }
    }

    /**
     * Verifică starea curentă a aplicației YouTube
     */
    fun checkYouTubeStatus(callback: (Boolean, Boolean) -> Unit) {
        thread {
            try {
                val port = workingPort ?: 56789
                val url = URL("http://$deviceIp:$port/apps/YouTube")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val state = parseAppState(response)
                    val isRunning = state == "running"
                    isYouTubeRunning = isRunning

                    Log.d(TAG, "YouTube status: $state")
                    callback(true, isRunning)
                } else {
                    callback(false, false)
                }

                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking YouTube status", e)
                callback(false, false)
            }
        }
    }

    /**
     * Redă un video specific pe YouTube
     */
    fun playVideo(videoId: String, callback: (Boolean) -> Unit) {
        if (!isYouTubeRunning) {
            launchYouTube(videoId, callback)
        } else {
            thread {
                try {
                    val port = workingPort ?: 56789
                    val appUrl = youtubeAppUrl ?: "http://$deviceIp:$port/apps/YouTube/run"
                    val url = URL(appUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "text/plain")
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.doOutput = true

                    val payload = "v=$videoId"
                    connection.outputStream.use { os ->
                        os.write(payload.toByteArray(charset("utf-8")))
                    }

                    val responseCode = connection.responseCode
                    Log.d(TAG, "Play video response code: $responseCode")

                    callback(responseCode == HttpURLConnection.HTTP_OK ||
                            responseCode == HttpURLConnection.HTTP_CREATED)

                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error playing video", e)
                    callback(false)
                }
            }
        }
    }

    /**
     * Lansează browserul web și navighează la YouTube
     * Funcționează pe TV-uri Netcast fără aplicația YouTube
     */
    fun launchYouTubeInBrowser(videoId: String? = null, callback: (Boolean) -> Unit) {
        thread {
            try {
                val url = URL("http://$deviceIp:8080/roap/api/command")

                // Încearcă diferite formate de comandă pentru Netcast
                val youtubeUrl = if (videoId != null) {
                    "https://m.youtube.com/watch?v=$videoId"
                } else {
                    "https://m.youtube.com"
                }

                // Format 1: AppExecute cu auid și contentId (mai nou)
                val browserIds = listOf("browser", "netcast.browser", "lge.browser")

                for (browserId in browserIds) {
                    // Încearcă fără contentId mai întâi
                    val xmlBody1 = """<?xml version="1.0" encoding="utf-8"?>
                        <command>
                            <name>AppExecute</name>
                            <auid>$browserId</auid>
                        </command>""".trimIndent()

                    Log.d(TAG, "Trying browser launch format 1 with ID: $browserId")
                    val response1 = sendNetcastRequest(url, xmlBody1)

                    if (response1?.contains("200") == true) {
                        Log.d(TAG, "Successfully launched browser!")
                        callback(true)
                        return@thread
                    }

                    // Încearcă format cu <type> în loc de <name>
                    val xmlBody2 = """<?xml version="1.0" encoding="utf-8"?>
                        <command>
                            <type>AppExecute</type>
                            <auid>$browserId</auid>
                        </command>""".trimIndent()

                    Log.d(TAG, "Trying browser launch format 2 with ID: $browserId")
                    val response2 = sendNetcastRequest(url, xmlBody2)

                    if (response2?.contains("200") == true) {
                        Log.d(TAG, "Successfully launched browser!")
                        callback(true)
                        return@thread
                    }
                }

                Log.e(TAG, "Failed to launch browser with all formats")
                callback(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error launching YouTube in browser", e)
                callback(false)
            }
        }
    }

    /**
     * Obține lista aplicațiilor instalate pe TV
     */
    fun getInstalledApps(callback: (List<String>) -> Unit) {
        thread {
            try {
                val url = URL("http://$deviceIp:8080/roap/api/data?target=applist")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                Log.d(TAG, "Get app list response code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "App list response: $response")

                    // Parsează lista de aplicații
                    val apps = parseAppList(response)
                    callback(apps)
                } else {
                    Log.e(TAG, "Failed to get app list")
                    callback(emptyList())
                }

                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting installed apps", e)
                callback(emptyList())
            }
        }
    }

    /**
     * Testează lansarea aplicațiilor populare pentru a vedea ce funcționează
     */
    fun testLaunchApps(callback: (String) -> Unit) {
        thread {
            val testApps = listOf(
                "browser" to "Browser",
                "netflix" to "Netflix",
                "vudu" to "Vudu",
                "smartshare" to "SmartShare",
                "youtube.leanback.v4" to "YouTube"
            )

            val results = StringBuilder()

            for ((appId, appName) in testApps) {
                try {
                    val url = URL("http://$deviceIp:8080/roap/api/command")

                    // Încearcă format 1: cu <n>
                    val xmlBody1 = """<?xml version="1.0" encoding="utf-8"?>
                        <command>
                            <n>AppExecute</n>
                            <auid>$appId</auid>
                        </command>""".trimIndent()

                    val response1 = sendNetcastRequest(url, xmlBody1)
                    val success1 = response1?.contains("200") == true

                    if (success1) {
                        results.append("$appName ($appId): ✓ Format1\n")
                        Thread.sleep(500)
                        continue
                    }

                    // Încearcă format 2: cu <type>
                    val xmlBody2 = """<?xml version="1.0" encoding="utf-8"?>
                        <command>
                            <type>AppExecute</type>
                            <auid>$appId</auid>
                        </command>""".trimIndent()

                    val response2 = sendNetcastRequest(url, xmlBody2)
                    val success2 = response2?.contains("200") == true

                    if (success2) {
                        results.append("$appName ($appId): ✓ Format2\n")
                        Thread.sleep(500)
                    } else {
                        results.append("$appName ($appId): ✗\n")
                    }

                } catch (e: Exception) {
                    results.append("$appName: Error - ${e.message}\n")
                }
            }

            callback(results.toString())
        }
    }

    private fun parseAppList(xmlResponse: String): List<String> {
        val apps = mutableListOf<String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlResponse))

            var eventType = parser.eventType
            var inAuidTag = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "auid") {
                            inAuidTag = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inAuidTag && parser.text.isNotBlank()) {
                            apps.add(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "auid") {
                            inAuidTag = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing app list", e)
        }
        return apps
    }

    private fun sendNetcastRequest(url: URL, xmlBody: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/atom+xml")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true

            connection.outputStream.use { os ->
                os.write(xmlBody.toByteArray(charset("utf-8")))
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Netcast request to $url returned code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }.also {
                    Log.d(TAG, "Netcast response body: $it")
                }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }?.also {
                    Log.e(TAG, "Netcast error response body: $it")
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Netcast HTTP request to $url", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseAppUrl(xmlResponse: String): String? {
        return try {
            val pattern = """<link\s+rel="run"\s+href="([^"]+)"""".toRegex()
            val match = pattern.find(xmlResponse)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing app URL", e)
            null
        }
    }

    private fun parseAppState(xmlResponse: String): String? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlResponse))

            var eventType = parser.eventType
            var inStateTag = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "state") {
                            inStateTag = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inStateTag) {
                            return parser.text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "state") {
                            inStateTag = false
                        }
                    }
                }
                eventType = parser.next()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing app state", e)
            null
        }
    }

    companion object {
        fun extractVideoId(url: String): String? {
            val patterns = listOf(
                """(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/embed/)([^&\s]+)""".toRegex(),
                """^([a-zA-Z0-9_-]{11})$""".toRegex()
            )

            for (pattern in patterns) {
                val match = pattern.find(url)
                if (match != null) {
                    return match.groupValues[1]
                }
            }

            return null
        }
    }
}
