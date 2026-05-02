package com.project.arnav_app.core.navigation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

const val USE_FAKE_LLM = true

@Serializable
data class GeminiRequest(val contents: List<Content>)

@Serializable
data class Content(val parts: List<Part>)

@Serializable
data class Part(val text: String)

@Serializable
data class GeminiResponse(val candidates: List<Candidate>? = null)

@Serializable
data class Candidate(val content: Content)

class GeminiRepository(private val apiKey: String) {
    private val client = OkHttpClient.Builder().build()
    private val json = Json { ignoreUnknownKeys = true }
    private val TAG = "GeminiRepository"

    private fun fakeLLM(text: String): IntentResult {
        return when {
            text.contains("go to", ignoreCase = true) -> {
                val dest = text.substringAfter("go to", "").trim().removeSuffix(".")
                IntentResult("NAVIGATE", if (dest.isNotEmpty()) dest else null)
            }
            text.contains("yes", ignoreCase = true) -> IntentResult("CONFIRM")
            text.contains("no", ignoreCase = true) -> IntentResult("CANCEL")
            text.contains("how far", ignoreCase = true) || text.contains("time", ignoreCase = true) -> IntentResult("QUERY", text)
            else -> IntentResult("UNKNOWN")
        }
    }

    suspend fun classifyIntent(text: String): IntentResult {
        if (USE_FAKE_LLM) {
            if ((1..10).random() <= 2) throw IOException("429")
            delay(500)
            return fakeLLM(text)
        }
        val prompt = """
        You are a strict JSON generator for a navigation assistant.

        Return ONLY valid JSON.

        Intents:
        NAVIGATE, CONFIRM, CANCEL, MODIFY, QUERY, UNKNOWN

        Schema:
        {
          "intent": "...",
          "destination": string | null,
          "query": string | null
        }

        Rules:
        - No explanation
        - If unclear → UNKNOWN
        - Normalize Indian place names

        Examples:
        "I want to go to Indiranagar"
        → {"intent":"NAVIGATE","destination":"Indiranagar","query":null}

        "yes"
        → {"intent":"CONFIRM","destination":null,"query":null}

        "how much time left"
        → {"intent":"QUERY","destination":null,"query":"eta"}

        Input: "$text"
        """.trimIndent()

        val response = callWithRetry(prompt)
        return if (response != null) parseIntentResponse(response) else IntentResult("UNKNOWN")
    }

    suspend fun answerQuery(query: String, eta: String, distance: String, nextInstruction: String): String? {
        if (USE_FAKE_LLM) {
            if ((1..10).random() <= 2) throw IOException("429")
            delay(500)
            return "Fake Response: $distance km away, $eta mins left. Next: $nextInstruction"
        }
        val prompt = """
        You are a navigation assistant.

        Answer briefly (1 sentence) using this context:

        ETA: $eta minutes
        Distance: $distance km
        Next step: $nextInstruction

        User question: "$query"

        Rules:
        - Be concise
        - Use simple Indian English
        - No extra explanation
        """.trimIndent()
        
        return callWithRetry(prompt)?.let { extractText(it) }
    }

    suspend fun summarizeRoute(distance: String, eta: String, steps: String): String? {
        if (USE_FAKE_LLM) {
            if ((1..10).random() <= 2) throw IOException("429")
            delay(500)
            return "Fake Summary: Destination is $distance km away, ETA $eta mins."
        }
        val prompt = """
        Summarize this route for a driver in India.

        Distance: $distance km
        ETA: $eta minutes
        Key steps:
        $steps

        Rules:
        - 1-2 sentences only
        - Mention main roads
        - Keep simple
        """.trimIndent()

        return callWithRetry(prompt)?.let { extractText(it) }
    }

    private suspend fun callWithRetry(prompt: String): String? {
        var retryCount = 0
        val maxRetries = 2
        val retryDelay = 300L

        while (retryCount <= maxRetries) {
            try {
                return withContext(Dispatchers.IO) {
                    callGeminiApi(prompt)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini API failure: ${e.message}", e)
                if (e.message?.contains("503") == true && retryCount < maxRetries) {
                    retryCount++
                    delay(retryDelay)
                    continue
                }
            }
            break
        }
        return null
    }

    private fun callGeminiApi(prompt: String): String? {
        val requestBody = json.encodeToString(
            GeminiRequest.serializer(),
            GeminiRequest(listOf(Content(listOf(Part(prompt)))))
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash:generateContent?key=$apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected code $response (503 if overloaded)")
            }
            return response.body?.string()
        }
    }

    private fun extractText(rawResponse: String): String {
        return try {
            val geminiResponse = json.decodeFromString(GeminiResponse.serializer(), rawResponse)
            geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseIntentResponse(rawResponse: String): IntentResult {
        return try {
            val textResponse = extractText(rawResponse)
            
            // Extract JSON from potential markdown blocks
            val jsonString = if (textResponse.contains("```json")) {
                textResponse.substringAfter("```json").substringBefore("```").trim()
            } else if (textResponse.contains("```")) {
                textResponse.substringAfter("```").substringBefore("```").trim()
            } else {
                textResponse.trim()
            }
            
            json.decodeFromString(IntentResult.serializer(), jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse intent: ${e.message}")
            IntentResult("UNKNOWN")
        }
    }
}
