package com.tatilacratita.lgcast.sampler

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class NetcastPairingHelper(val deviceIp: String, initialSessionId: String? = null) {

    private val tag = "NetcastPairingHelper"
    
    // Am eliminat 'private set' pentru a permite modificarea din exterior
    var sessionId: String? = initialSessionId

    var sessionKeyListener: SessionKeyListener? = null

    // === AUTH / PAIRING ===
    fun displayPairingKey(callback: (Boolean) -> Unit) {
        thread {
            val url = URL("http://$deviceIp:8080/roap/api/auth")
            val xmlBody = """<?xml version="1.0" encoding="utf-8"?><auth><type>AuthKeyReq</type></auth>"""
            sendRequest(url, xmlBody) { response, _ -> callback(response != null) }
        }
    }

    fun finishPairing(pin: String, callback: (Boolean) -> Unit) {
        thread {
            val url = URL("http://$deviceIp:8080/roap/api/auth")
            val xmlBody = """<?xml version="1.0" encoding="utf-8"?><auth><type>AuthReq</type><value>$pin</value></auth>"""
            sendRequest(url, xmlBody) { response, _ ->
                val newSessionId = if (response != null) parseSessionId(response) else null
                if (newSessionId != null) {
                    this.sessionId = newSessionId
                    sessionKeyListener?.onSessionKeyAcquired(newSessionId)
                    callback(true)
                } else {
                    callback(false)
                }
            }
        }
    }

    // === KEY COMMANDS ===
    fun sendKeyCommand(keyCode: Int, callback: ((Boolean) -> Unit)? = null) {
        thread {
            if (sessionId == null) {
                callback?.invoke(false)
                return@thread
            }
            val url = URL("http://$deviceIp:8080/roap/api/command")
            val xmlBody =
                """<?xml version="1.0" encoding="utf-8"?><command><session>$sessionId</session><type>HandleKeyInput</type><value>$keyCode</value></command>"""
            sendRequest(url, xmlBody) { response, responseCode ->
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    sessionKeyListener?.onSessionInvalid()
                }
                callback?.invoke(responseCode == HttpURLConnection.HTTP_OK)
            }
        }
    }

    // === MOUSE VISIBILITY (CONNECT/DISCONNECT) ===
    fun setCursorVisible(visible: Boolean, callback: ((Boolean) -> Unit)? = null) {
        thread {
            val url = URL("http://$deviceIp:8080/roap/api/event")
            val value = if (visible) "true" else "false"
            val xmlBody = """<?xml version="1.0" encoding="utf-8"?><event><name>CursorVisible</name><value>$value</value><mode>auto</mode></event>"""

            Log.d(tag, "Setting cursor visibility to: $visible")
            sendRequest(url, xmlBody) { response, code ->
                val success = code == HttpURLConnection.HTTP_OK
                Log.d(tag, "Set cursor visibility result: $success (code=$code)")
                callback?.invoke(success)
            }
        }
    }

    // === MOUSE MOVEMENT (Displacement with Floats) ===
    fun moveMouse(dx: Float, dy: Float) {
        if (sessionId == null || (dx == 0f && dy == 0f)) return

        Log.d(tag, "Request move: dx=$dx dy=$dy")

        thread {
            sendMouseMovementDisplacement(dx, dy)
        }
    }

    private fun sendMouseMovementDisplacement(dx: Float, dy: Float) {
        val session = sessionId ?: return
        val url = URL("http://$deviceIp:8080/roap/api/command")
        val xmlBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <command>
                <session>$session</session>
                <type>HandleTouchMove</type>
                <x>${dx.toInt()}</x>
                <y>${dy.toInt()}</y>
            </command>
        """.trimIndent()

        Log.d(tag, "Sending HandleTouchMove DISP -> dx=$dx, dy=$dy")
        sendRequestKeepAlive(url, xmlBody) { response, code ->
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) sessionKeyListener?.onSessionInvalid()
            Log.d(tag, "HandleTouchMove DISP -> code=$code, responseLen=${response?.length ?: 0}")
        }
    }

    fun sendMouseClick(callback: ((Boolean) -> Unit)? = null) {
        thread {
            val session = sessionId ?: run {
                callback?.invoke(false)
                return@thread
            }
            val url = URL("http://$deviceIp:8080/roap/api/command")
            val xmlBody = """
                <?xml version="1.0" encoding="utf-8"?>
                <command>
                    <session>$session</session>
                    <type>HandleTouchClick</type>
                </command>
            """.trimIndent()

            Log.d(tag, "Sending HandleTouchClick")
            sendRequest(url, xmlBody) { response, code ->
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED) sessionKeyListener?.onSessionInvalid()
                val ok = (code == HttpURLConnection.HTTP_OK)
                Log.d(tag, "HandleTouchClick -> code=$code, ok=$ok")
                callback?.invoke(ok)
            }
        }
    }

    // === WHEEL ===
    fun sendWheel(direction: String, callback: ((Boolean) -> Unit)? = null) {
        thread {
            if (sessionId == null) {
                callback?.invoke(false)
                return@thread
            }
            val url = URL("http://$deviceIp:8080/roap/api/command")
            val xmlBody =
                """<?xml version="1.0" encoding="utf-8"?><command><session>$sessionId</session><type>HandleTouchWheel</type><value>$direction</value></command>"""
            sendRequest(url, xmlBody) { response, responseCode ->
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    sessionKeyListener?.onSessionInvalid()
                }
                callback?.invoke(responseCode == HttpURLConnection.HTTP_OK)
            }
        }
    }

    // === REQUEST HANDLERS ===
    private fun sendRequest(url: URL, xmlBody: String, callback: (String?, Int) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/atom+xml")
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.doOutput = true

            connection.outputStream.use { os -> os.write(xmlBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                callback(response, responseCode)
            } else {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(tag, "Error for $url - Code: $responseCode, Response: $errorResponse")
                callback(null, responseCode)
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception for $url", e)
            callback(null, -1)
        } finally {
            connection?.disconnect()
        }
    }

    private fun sendRequestKeepAlive(url: URL, xmlBody: String, callback: (String?, Int) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/atom+xml")
            connection.setRequestProperty("Connection", "Keep-Alive")
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.doOutput = true

            connection.outputStream.use { os -> os.write(xmlBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                callback(response, responseCode)
            } else {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(tag, "Error for $url - Code: $responseCode, Response: $errorResponse")
                callback(null, responseCode)
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception for $url", e)
            callback(null, -1)
        } finally {
            connection?.disconnect()
        }
    }

    // === PARSE SESSION ===
    private fun parseSessionId(xmlResponse: String): String? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlResponse))
            var eventType = parser.eventType
            var inSessionTag = false
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "session") {
                    inSessionTag = true
                } else if (eventType == XmlPullParser.TEXT && inSessionTag) {
                    return parser.text
                } else if (eventType == XmlPullParser.END_TAG && parser.name == "session") {
                    inSessionTag = false
                }
                eventType = parser.next()
            }
            null
        } catch (e: Exception) {
            Log.e(tag, "Error parsing session ID", e)
            null
        }
    }

    // === INTERFACE ===
    interface SessionKeyListener {
        fun onSessionKeyAcquired(sessionId: String)
        fun onSessionInvalid()
    }

    companion object {
        const val KEY_CODE_POWER = 1
        const val KEY_CODE_0 = 2
        const val KEY_CODE_1 = 3
        const val KEY_CODE_2 = 4
        const val KEY_CODE_3 = 5
        const val KEY_CODE_4 = 6
        const val KEY_CODE_5 = 7
        const val KEY_CODE_6 = 8
        const val KEY_CODE_7 = 9
        const val KEY_CODE_8 = 10
        const val KEY_CODE_9 = 11
        const val KEY_CODE_UP = 12
        const val KEY_CODE_DOWN = 13
        const val KEY_CODE_LEFT = 14
        const val KEY_CODE_RIGHT = 15
        const val KEY_CODE_OK = 20
        const val KEY_CODE_HOME = 21
        const val KEY_CODE_MENU = 22
        const val KEY_CODE_BACK = 23
        const val KEY_CODE_VOL_UP = 24
        const val KEY_CODE_VOL_DOWN = 25
        const val KEY_CODE_MUTE = 26
        const val KEY_CODE_CH_UP = 27
        const val KEY_CODE_CH_DOWN = 28
        const val KEY_CODE_BLUE = 29
        const val KEY_CODE_GREEN = 30
        const val KEY_CODE_RED = 31
        const val KEY_CODE_YELLOW = 32
        const val KEY_CODE_PLAY = 33
        const val KEY_CODE_PAUSE = 34
        const val KEY_CODE_STOP = 35
        const val KEY_CODE_FF = 36
        const val KEY_CODE_REW = 37
        const val KEY_CODE_SKIP_FORWARD = 38
        const val KEY_CODE_SKIP_BACKWARD = 39
        const val KEY_CODE_RECORD = 40
        const val KEY_CODE_RECORDING_LIST = 41
        const val KEY_CODE_REPEAT = 42
        const val KEY_CODE_LIVE_TV = 43
        const val KEY_CODE_GUIDE = 44 // EPG
        const val KEY_CODE_INFO = 45
        const val KEY_CODE_RATIO = 46
        const val KEY_CODE_INPUT = 47
        const val KEY_CODE_PIP_SECONDARY_VIDEO = 48
        const val KEY_CODE_SUBTITLE = 49
        const val KEY_CODE_LIST = 50
        const val KEY_CODE_TELE_TEXT = 51
        const val KEY_CODE_MARK = 52
        const val KEY_CODE_VIDEO_3D = 400
        const val KEY_CODE_AUDIO_3D_L_R = 401
        const val KEY_CODE_DASH = 402
        const val KEY_CODE_Q_VIEW = 403
        const val KEY_CODE_FAVORITE_CHANNEL = 404
        const val KEY_CODE_Q_MENU = 405
        const val KEY_CODE_TEXT_OPTION = 406
        const val KEY_CODE_AUDIO_DESCRIPTION = 407
        const val KEY_CODE_NETCAST_KEY = 408
        const val KEY_CODE_ENERGY_SAVING = 409
        const val KEY_CODE_AV_MODE = 410
        const val KEY_CODE_SIMPLINK = 411
        const val KEY_CODE_EXIT = 412
        const val KEY_CODE_RESERVATION_PROGRAM_LIST = 413
        const val KEY_CODE_PIP_CHANNEL_UP = 414
        const val KEY_CODE_PIP_CHANNEL_DOWN = 415
        const val KEY_CODE_SWITCHING_PRIMARY_SECONDARY_VIDEO = 416
        const val KEY_CODE_MY_APPS = 417

        const val WHEEL_UP = "up"
        const val WHEEL_DOWN = "down"
    }
}