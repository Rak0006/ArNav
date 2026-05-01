package com.project.arnav_app.core.navigation

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

class GeminiRepository(private val apiKey: String) {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
        
    private val json by lazy {
        Json { 
            ignoreUnknownKeys = true 
            isLenient = true
        }
    }
    
    private val url by lazy { "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey" }

    private fun shouldProcess(text: String): Boolean {
        return text.isNotBlank()
    }

    suspend fun parseIntent(text: String): IntentResult = withContext(Dispatchers.IO) {
        Log.d("Gemini", "parseIntent input: '$text'")
        if (!shouldProcess(text)) return@withContext IntentResult("UNKNOWN")

        val lowerText = text.lowercase().trim().replace(Regex("[^a-z0-9 ]"), "")
        
        val confirmWords = setOf("yes", "sure", "correct", "do it", "start", "yeah", "yep", "ok", "okay", "confirm", "yup")
        val cancelWords = setOf("no", "stop", "cancel", "nevermind", "nope", "dont", "quit")

        // 1. Local match for Confirm/Cancel
        if (confirmWords.any { lowerText == it || lowerText.startsWith("$it ") || lowerText.endsWith(" $it") }) {
            Log.d("Gemini", "Local match: CONFIRM")
            return@withContext IntentResult("CONFIRM")
        }
        if (cancelWords.any { lowerText == it || lowerText.startsWith("$it ") || lowerText.endsWith(" $it") }) {
            Log.d("Gemini", "Local match: CANCEL")
            return@withContext IntentResult("CANCEL")
        }

        // 2. Local match for simple Navigate commands (e.g., "go to Jayanagar")
        val navPatterns = listOf(
            Regex(".*(?:go to|take me to|navigate to|find|direction to|directions to) (.*)"),
            Regex("(.*) please")
        )
        
        for (pattern in navPatterns) {
            pattern.find(lowerText)?.let { match ->
                val dest = match.groupValues[1].trim()
                if (dest.isNotBlank() && dest.length > 2) {
                    Log.d("Gemini", "Local match: NAVIGATE to '$dest'")
                    return@withContext IntentResult("NAVIGATE", dest.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } })
                }
            }
        }

        val prompt = """
            You are a strict JSON generator for a navigation app.

            Task:
            Extract the user's navigation intent and optional destination from their speech.

            Intents:
            - "NAVIGATE": User wants to go somewhere (e.g., "take me to...", "go to...", "find a route to...")
            - "CONFIRM": User is confirming an action (e.g., "yes", "sure", "correct", "do it", "start")
            - "CANCEL": User wants to stop or cancel (e.g., "no", "stop", "cancel", "nevermind")
            - "UNKNOWN": Intent is unclear or unrelated to navigation.

            Rules:
            - Return ONLY valid JSON.
            - Do NOT include any explanations or markdown.
            - For "NAVIGATE", extract the "destination" (normalized).
            - For all other intents, "destination" should be null.

            Examples:
            Input: "take me to indiranagar"
            Output: {"intent":"NAVIGATE","destination":"Indiranagar"}

            Input: "yes"
            Output: {"intent":"CONFIRM","destination":null}
            
            Input: "sure"
            Output: {"intent":"CONFIRM","destination":null}

            Input: "i want to go to jayanagar"
            Output: {"intent":"NAVIGATE","destination":"Jayanagar"}

            Input: "$text"
        """.trimIndent()

        val response = callGemini(prompt)
        Log.d("Gemini", "Raw response: $response")
        
        try {
            val jsonStr = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL)
                .find(response)?.value
                ?: return@withContext IntentResult("UNKNOWN")

            json.decodeFromString<IntentResult>(jsonStr)
        } catch (e: Exception) {
            Log.e("Gemini", "Failed to parse JSON: $response", e)
            IntentResult("UNKNOWN")
        }
    }

    private suspend fun callGemini(prompt: String): String {
        var currentDelay = 1000L
        repeat(3) { attempt ->
            val res = executeRequest(prompt)
            // If it's not a retryable error, return the result
            if (!res.contains("503") && !res.contains("429")) return res
            
            Log.w("Gemini", "Request failed (attempt ${attempt + 1}): $res. Retrying in ${currentDelay}ms...")
            delay(currentDelay)
            currentDelay *= 2 // Exponential backoff
        }
        return """{"intent":"UNKNOWN","destination":null}"""
    }

    private fun executeRequest(prompt: String): String {
        try {
            val requestJson = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") {
                            addJsonObject {
                                put("text", prompt)
                            }
                        }
                    }
                }
            }

            val requestBody = requestJson.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return "Error: Empty"
                
                if (!response.isSuccessful) {
                    return "Error: ${response.code}"
                }

                val jsonResponse = json.parseToJsonElement(body).jsonObject
                return jsonResponse["candidates"]
                    ?.jsonArray?.getOrNull(0)
                    ?.jsonObject?.get("content")
                    ?.jsonObject?.get("parts")
                    ?.jsonArray?.getOrNull(0)
                    ?.jsonObject?.get("text")
                    ?.jsonPrimitive?.content ?: "Error: Format"
            }
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
    }

}
