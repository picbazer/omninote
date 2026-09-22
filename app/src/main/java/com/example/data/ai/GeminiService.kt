package com.example.data.ai

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiService(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("omninote_settings", Context.MODE_PRIVATE)

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun getApiKey(): String {
        val userKey = prefs.getString("custom_gemini_api_key", "")?.trim().orEmpty()
        if (userKey.isNotEmpty()) return userKey

        val buildKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (_: Exception) {
            ""
        }
        return if (buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY") buildKey else ""
    }

    fun saveCustomApiKey(key: String) {
        prefs.edit().putString("custom_gemini_api_key", key.trim()).apply()
    }

    suspend fun generateGroundedResponse(
        documentTitle: String,
        documentContent: String,
        history: List<ChatMessage>,
        latestQuery: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()

        if (apiKey.isBlank()) {
            // Intelligent local grounded analysis fallback
            val localAnswer = generateLocalGroundedAnswer(documentTitle, documentContent, latestQuery)
            return@withContext Result.success(localAnswer)
        }

        val systemPrompt = """
            You are OmniNote AI, an expert research assistant inspired by NotebookLM.
            Your primary mandate is STRICT GROUNDING:
            1. Answer the user's questions strictly and solely based on the provided document/notebook text.
            2. DO NOT hallucinate, extrapolate, or bring in external knowledge not present in the document.
            3. If the answer cannot be found in the document, state clearly and politely:
               "I couldn't find information about that in this note. Please refer to the document text or ask a question directly related to its contents."
            4. Quote or reference specific sections or key points from the document when answering.
            5. Provide clean, well-formatted responses with bullet points or paragraphs as appropriate.
        """.trimIndent()

        // Build contents array for conversation
        val contentsArray = JSONArray()

        // First turn contains grounded context
        val contextHeader = """
            [NOTEBOOK DOCUMENT CONTEXT]
            Title: $documentTitle
            
            Content:
            \"\"\"
            $documentContent
            \"\"\"
            
            [USER QUESTION]
        """.trimIndent()

        // Include last 6 messages from history for conversational continuity
        val recentHistory = history.takeLast(6)
        if (recentHistory.isEmpty()) {
            val userContent = JSONObject().apply {
                put("role", "user")
                val parts = JSONArray().apply {
                    put(JSONObject().put("text", "$contextHeader\n$latestQuery"))
                }
                put("parts", parts)
            }
            contentsArray.put(userContent)
        } else {
            // First user message in recent history carries context
            var isFirstUserMsg = true
            for (msg in recentHistory) {
                val roleStr = if (msg.role == ChatMessage.Role.USER) "user" else "model"
                val textContent = if (isFirstUserMsg && msg.role == ChatMessage.Role.USER) {
                    isFirstUserMsg = false
                    "$contextHeader\n${msg.content}"
                } else {
                    msg.content
                }

                val turn = JSONObject().apply {
                    put("role", roleStr)
                    val parts = JSONArray().apply {
                        put(JSONObject().put("text", textContent))
                    }
                    put("parts", parts)
                }
                contentsArray.put(turn)
            }

            // Append current query
            val currentTurn = JSONObject().apply {
                put("role", "user")
                val parts = JSONArray().apply {
                    put(JSONObject().put("text", latestQuery))
                }
                put("parts", parts)
            }
            contentsArray.put(currentTurn)
        }

        val requestBodyJson = JSONObject().apply {
            val sysInstructionObj = JSONObject().apply {
                val parts = JSONArray().apply {
                    put(JSONObject().put("text", systemPrompt))
                }
                put("parts", parts)
            }
            put("systemInstruction", sysInstructionObj)
            put("contents", contentsArray)

            val genConfig = JSONObject().apply {
                put("temperature", 0.2)
                put("topP", 0.95)
            }
            put("generationConfig", genConfig)
        }

        // Try primary model gemini-2.5-flash, fallback to gemini-flash-latest or gemini-3.5-flash
        val models = listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-1.5-flash")
        var lastException: Exception? = null

        for (model in models) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(url)
                .post(requestBodyJson.toString().toRequestBody(mediaType))
                .build()

            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val jsonResp = JSONObject(responseBody)
                        val candidates = jsonResp.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val candidate = candidates.getJSONObject(0)
                            val content = candidate.optJSONObject("content")
                            val parts = content?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val text = parts.getJSONObject(0).optString("text", "")
                                if (text.isNotBlank()) {
                                    return@withContext Result.success(text.trim())
                                }
                            }
                        }
                    } else {
                        val errorJson = try { JSONObject(responseBody).optJSONObject("error") } catch (_: Exception) { null }
                        val errorMsg = errorJson?.optString("message") ?: "API Error (${response.code})"
                        lastException = Exception("Model $model error: $errorMsg")
                        // If 404 (model not found), try next model in list
                        if (response.code != 404) {
                            return@withContext Result.failure(Exception(errorMsg))
                        }
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        // If network failed and we have no response, provide local grounded fallback with notice
        val localFallback = generateLocalGroundedAnswer(documentTitle, documentContent, latestQuery)
        val notice = "[Offline / Local Grounded Mode]\n${lastException?.message?.let { "(Notice: $it)\n\n" } ?: ""}$localFallback"
        Result.success(notice)
    }

    /**
     * Local semantic text-grounded fallback when offline or no API key is set.
     * Ensures the app remains 100% responsive and strictly grounded in the document context.
     */
    private fun generateLocalGroundedAnswer(
        title: String,
        content: String,
        query: String
    ): String {
        val cleanContent = content.trim()
        val queryLower = query.lowercase().trim()

        if (cleanContent.isBlank()) {
            return "This note is currently empty. Please write some text or import a PDF document to begin chatting with OmniNote AI."
        }

        // Summary request
        if (queryLower.contains("summar") || queryLower.contains("overview") || queryLower.contains("brief")) {
            val lines = cleanContent.lines().filter { it.isNotBlank() }
            val preview = lines.take(6).joinToString("\n• ", prefix = "• ")
            return """
                ### Summary of "$title"
                Based strictly on the notebook content:
                
                $preview
                
                *(Total characters: ${cleanContent.length}, Total lines: ${lines.size})*
            """.trimIndent()
        }

        // Key points / takeaways
        if (queryLower.contains("key") || queryLower.contains("takeaway") || queryLower.contains("point") || queryLower.contains("main")) {
            val sentences = cleanContent.split(Regex("""(?<=[.!?])\s+""")).filter { it.length > 20 }
            val takeaways = sentences.take(4).mapIndexed { i, s -> "${i + 1}. ${s.trim()}" }.joinToString("\n\n")
            return """
                ### Key Takeaways from "$title"
                
                $takeaways
            """.trimIndent()
        }

        // Action items / tasks
        if (queryLower.contains("action") || queryLower.contains("task") || queryLower.contains("todo") || queryLower.contains("next step")) {
            val actionLines = cleanContent.lines()
                .filter { it.contains("to", true) || it.contains("will", true) || it.contains("should", true) || it.contains("must", true) || it.contains("-") }
                .take(4)

            return if (actionLines.isNotEmpty()) {
                """
                    ### Extracted Action Items
                    
                    ${actionLines.joinToString("\n") { "- [ ] ${it.trim().removePrefix("-").trim()}" }}
                """.trimIndent()
            } else {
                """
                    ### Action Items
                    No explicit action verbs or tasks found in this document. Consider adding bullet points or task assignments to "$title".
                """.trimIndent()
            }
        }

        // Specific keyword search within context
        val words = queryLower.split(Regex("""\W+""")).filter { it.length > 3 && it !in setOf("what", "when", "where", "which", "about", "does", "have", "from", "this", "that") }
        val matchingLines = mutableListOf<String>()

        for (line in cleanContent.lines()) {
            val lineLower = line.lowercase()
            if (words.any { lineLower.contains(it) }) {
                matchingLines.add(line.trim())
                if (matchingLines.size >= 4) break
            }
        }

        return if (matchingLines.isNotEmpty()) {
            """
                ### Grounded Findings for "$query"
                According to the text in "$title":
                
                ${matchingLines.joinToString("\n\n") { "> \"$it\"" }}
            """.trimIndent()
        } else {
            "I couldn't find information about \"$query\" in this note. Please refer to the document text or ask a question directly related to its contents."
        }
    }
}
