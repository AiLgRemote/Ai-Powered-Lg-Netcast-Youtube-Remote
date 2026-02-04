package com.tatilacratita.lgcast.sampler

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiAIHelper(private val context: Context) {

    private val tag = "GeminiAI"

    private val apiKey = "put your own key here"

    private val model = GenerativeModel(
        modelName = "gemini-2.0-flash",
        apiKey = apiKey
    )

    private val gson = Gson()

    /**
     * Interpretează comanda vocală naturală
     */
    suspend fun interpretVoiceCommand(userCommand: String): AICommandResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Processing command: $userCommand")

                val prompt = """
Ești un asistent pentru controlul unui Smart TV LG din 2012.
Analizează comanda utilizatorului în limba ROMÂNĂ și returnează JSON.

Comenzi disponibile:
- VOLUME_UP: crește volumul (parametru: steps = nr. trepte, default 1)
- VOLUME_DOWN: scade volumul (parametru: steps = nr. trepte)
- CHANNEL: schimbă canalul (parametru: number = "123")
- POWER: pornește/oprește TV-ul
- YOUTUBE_SEARCH: caută pe YouTube (parametru: query = "text căutare")
- YOUTUBE_URL: deschide URL YouTube (parametru: url = "link")
- YOUTUBE_PLAY: pornește video-ul curent din YouTube
- SLIDESHOW: pornește slideshow cu poze
- ALBUM: deschide album foto
- NAVIGATION: navigare (parametru: direction = UP/DOWN/LEFT/RIGHT/OK/BACK)
- MEDIA_CONTROL: control media (parametru: action = PLAY/PAUSE/STOP)
- STOP_VIDEO: oprește redarea video (necesită secvența BACK, LEFT, OK)
- MENU: deschide meniul TV
- UNKNOWN: nu înțelegi comanda

Comanda: "$userCommand"

RĂSPUNDE DOAR CU JSON ÎN ACEST FORMAT (fără text suplimentar):
{
  "action": "VOLUME_UP",
  "parameters": {
    "steps": 3
  },
  "explanation": "Cresc volumul cu 3 trepte"
}

Exemple:
- "mai tare puțin" → action: VOLUME_UP, steps: 4
- "mai încet puțin" → action: VOLUME_DOWN, steps: 4
- "oprește video-ul" → action: STOP_VIDEO, explanation: "Opresc redarea video."
- "închide player-ul" → action: STOP_VIDEO, explanation: "Închid player-ul video."
- "ieși din video" → action: STOP_VIDEO, explanation: "Ies din video."
- "baieram" → action: VOLUME_UP, steps: 15
- "mute" → action: VOLUME_DOWN ,steps: 15
- " dă pe 5" → action: CHANNEL, number: "cifra rostita"
- " dă pe (urmat de cifra )" → action: CHANNEL, number: "cifra rostita"
- " dă pe HBO " sau "HBO" → action: CHANNEL, number: "1"
- " dă pe HBO2" sau "HBO2" → action: CHANNEL, number: "2"
- " dă pe HBO3" sau "HBO3" → action: CHANNEL, number: "3"
- " dă pe DIGIFILM" sau "DIGIFILM" → action: CHANNEL, number: "4"
- " dă pe CINEMAX" sau "CINEMAX" → action: CHANNEL, number: "5"
- " dă pe TVR sau "TVR" → action: CHANNEL, number: "5"
- " dă pe DISCOVERY" sau "DISCOVERY" → action: CHANNEL, number: "25"
- " dă pe DIGI SPORT 1" sau "DIGI 1" → action: CHANNEL, number: "26"
- " dă pe DIGI SPORT 2" sau "DIGI 2" → action: CHANNEL, number: "27"
- " dă pe DIGI SPORT 3" sau "DIGI 3"→ action: CHANNEL, number: "28"
- " dă pe EUROSPORT" sau "EUROSPORT" → action: CHANNEL, number: "30"
- " dă pe PROTV" sau "PROTV" → action: CHANNEL, number: "33"
- " dă pe ANTENA 1" sau "ANTENA 1" → action: CHANNEL, number: "34"
- " dă pe ANTENA 3" sau "ANTENA 3" → action: CHANNEL, number: "36"
- " dă pe TVR 1" sau "TVR 1" → action: CHANNEL, number: "37"
- " dă pe PRIMA" → sau "PRIMA" → action: CHANNEL, number: "41"
- " dă pe PAPRIKA"sau "PAPRIKA" → action: CHANNEL, number: "44"
- " dă pe VIASAT" → sau "VIASAT" → action: CHANNEL, number: "49"
- " dă pe HISTORY" sau "HISTORY" → action: CHANNEL, number: "51"
- " dă pe FOOD TV" sau "FOOD TV" → action: CHANNEL, number: "53"
-  "PLAY" say "PLAY YOUTUBE" sau "YOUTUBE PLAY" → action: YOUTUBE_PLAY
- "caută melodii relaxante" → action: YOUTUBE_SEARCH, query: "melodii relaxante"
- "arată-mi poze" sau "poze" → action: SLIDESHOW
- "dă mai încet de 5 ori" → action: VOLUME_DOWN, steps: 5
""".trimIndent()

                val response = model.generateContent(prompt)
                val jsonText = response.text?.trim() ?: return@withContext AICommandResult.error("Răspuns gol")

                Log.d(tag, "AI Response: $jsonText")

                // Curăță răspunsul (elimină markdown dacă există)
                val cleanJson = jsonText
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()

                // Parse JSON
                val result = gson.fromJson(cleanJson, AICommandResult::class.java)
                result.copy(success = true)

            } catch (e: Exception) {
                Log.e(tag, "Error calling Gemini AI", e)
                AICommandResult.error("Eroare AI: ${e.message}")
            }
        }
    }

    /**
     * Optimizează căutarea YouTube
     */
    suspend fun optimizeYouTubeSearch(userQuery: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
Optimizează această căutare YouTube în limba ROMÂNĂ.
Adaugă termeni relevanți pentru rezultate mai bune.

Căutare originală: "$userQuery"

Reguli:
- Păstrează limba română
- Adaugă termeni care îmbunătățesc căutarea
- Fii concis (max 10 cuvinte)
- Returnează DOAR query-ul optimizat, fără explicații

Exemplu:
Input: "muzică"
Output: "muzică relaxantă ambient 2024"
""".trimIndent()

                val response = model.generateContent(prompt)
                response.text?.trim()?.replace("\"", "") ?: userQuery

            } catch (e: Exception) {
                Log.e(tag, "Error optimizing search", e)
                userQuery
            }
        }
    }

    /**
     * Generează recomandări personalizate
     */
    suspend fun getContentRecommendations(
        watchHistory: List<String>,
        currentContext: String = ""
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
Pe baza istoricului de vizionare, recomandă 3 video-uri YouTube în ROMÂNĂ.

Istoric recent: ${watchHistory.joinToString(", ")}
Context: $currentContext

Returnează DOAR 3 titluri de video-uri, câte unul pe linie.
Format:
1. Titlu video 1
2. Titlu video 2
3. Titlu video 3
""".trimIndent()

                val response = model.generateContent(prompt)
                response.text?.trim() ?: "Nu am putut genera recomandări"

            } catch (e: Exception) {
                Log.e(tag, "Error getting recommendations", e)
                "Eroare la generarea recomandărilor"
            }
        }
    }

    /**
     * Conversație naturală despre ce se urmărește
     */
    suspend fun chatAboutContent(userMessage: String, currentContent: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
Utilizatorul se uită la: "$currentContent"
Întrebarea lui: "$userMessage"

Răspunde în ROMÂNĂ, natural și prietenos, ca un asistent TV inteligent.
Fii concis (max 2-3 propoziții).
""".trimIndent()

                val response = model.generateContent(prompt)
                response.text?.trim() ?: "Nu am putut procesa întrebarea"

            } catch (e: Exception) {
                Log.e(tag, "Error in chat", e)
                "Îmi pare rău, nu pot răspunde acum"
            }
        }
    }
}

data class AICommandResult(
    @SerializedName("action") val action: String = "UNKNOWN",
    @SerializedName("parameters") val parameters: Map<String, Any> = emptyMap(),
    @SerializedName("explanation") val explanation: String = "",
    val success: Boolean = false,
    val videoId: String? = null,
    val query: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun error(message: String) = AICommandResult(
            action = "ERROR",
            parameters = emptyMap(),
            explanation = message,
            success = false,
            errorMessage = message
        )
    }

    // Helper functions pentru parametri
    fun getSteps(): Int = (parameters["steps"] as? Double)?.toInt() ?: 1
    fun getChannel(): String = parameters["number"] as? String ?: ""
    fun getQueryValue(): String = parameters["query"] as? String ?: ""
    fun getUrl(): String = parameters["url"] as? String ?: ""
    fun getDirection(): String = parameters["direction"] as? String ?: ""
    fun getMediaAction(): String = parameters["action"] as? String ?: ""

    // Validări pentru acțiuni
    fun isValidAction(): Boolean {
        return action in listOf(
            "VOLUME_UP", "VOLUME_DOWN", "CHANNEL", "POWER",
            "YOUTUBE_SEARCH", "YOUTUBE_URL", "SLIDESHOW", "ALBUM",
            "NAVIGATION", "MEDIA_CONTROL", "STOP_VIDEO", "MENU", "UNKNOWN", "YOUTUBE_PLAY" ,"ERROR"
        )
    }

    fun requiresParameters(): Boolean {
        return action in listOf(
            "VOLUME_UP", "VOLUME_DOWN", "CHANNEL", "YOUTUBE_SEARCH",
            "YOUTUBE_URL", "NAVIGATION", "MEDIA_CONTROL"
        )
    }

    override fun toString(): String {
        return "AICommandResult(action='$action', parameters=$parameters, " +
                "explanation='$explanation', success=$success, errorMessage=$errorMessage)"
    }
}