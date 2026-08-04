package com.vizvag.shieldvideo.data.iptv

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class EpgAiProvider {
    GEMINI,
    OPENAI;

    companion object {
        fun fromStorage(raw: String?): EpgAiProvider =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: GEMINI
    }
}

/**
 * Real LLM matching of M3U channel names → XMLTV channel ids.
 * Gemini (free key from Google AI Studio) or OpenAI-compatible chat completions.
 */
class EpgAiMatcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()
) {
    data class Result(
        val assignments: Map<String, String>,
        /** channel.id → epg.id */
        val error: String? = null
    )

    /**
     * @return map of [IptvChannel.id] → XMLTV channel id
     * @param onProgress called as batches advance (done = channels processed so far)
     * @param onChannelResult called once per channel after its batch returns (epg null = no match)
     */
    fun match(
        channels: List<IptvChannel>,
        epgChannels: List<EpgChannelEntry>,
        apiKey: String,
        provider: EpgAiProvider = EpgAiProvider.GEMINI,
        openAiBaseUrl: String = DEFAULT_OPENAI_BASE,
        openAiModel: String = DEFAULT_OPENAI_MODEL,
        geminiModel: String = DEFAULT_GEMINI_MODEL,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        onChannelResult: ((channel: IptvChannel, epg: EpgChannelEntry?) -> Unit)? = null,
    ): Result {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            return Result(emptyMap(), "Add an AI API key under Settings → Live TV")
        }
        if (channels.isEmpty()) return Result(emptyMap())
        if (epgChannels.isEmpty()) {
            return Result(emptyMap(), "EPG channel list empty — refresh Live TV first")
        }

        val epgById = HashMap<String, EpgChannelEntry>(epgChannels.size)
        epgChannels.forEach { epgById[it.id.lowercase(Locale.US)] = it }
        // Build once — ranking each channel against a raw XMLTV list of tens of thousands
        // was freezing the UI at 0/N before the first Gemini call.
        val rankIndex = EpgChannelMatcher.EpgRankIndex(epgChannels)
        android.util.Log.i(TAG, "EPG index ready: ${epgChannels.size} channels, matching ${channels.size}")

        val out = LinkedHashMap<String, String>()
        val batches = channels.chunked(BATCH_SIZE)
        var processed = 0
        batches.forEachIndexed { batchIndex, batch ->
            onProgress?.invoke(processed, channels.size)
            android.util.Log.i(TAG, "AI batch ${batchIndex + 1}/${batches.size} size=${batch.size}")
            val jobs = batch.map { ch ->
                ch to rankIndex.rank(ch, limit = CANDIDATES).map { it.epg }
            }
            val json = when (provider) {
                EpgAiProvider.GEMINI -> callGemini(key, geminiModel, jobs)
                EpgAiProvider.OPENAI -> callOpenAi(key, openAiBaseUrl, openAiModel, jobs)
            }
            if (json.error != null) {
                android.util.Log.w(TAG, "AI batch error: ${json.error}")
            }
            if (json.error != null && out.isEmpty() && batchIndex == 0) {
                return Result(emptyMap(), json.error)
            }
            val batchAssigned = HashMap<String, String>()
            json.assignments.forEach { (channelId, epgId) ->
                val ch = batch.firstOrNull { it.id == channelId } ?: return@forEach
                val entry = epgById[epgId.lowercase(Locale.US)] ?: return@forEach
                if (!acceptAiAssignment(ch, entry, rankIndex)) {
                    android.util.Log.i(
                        TAG,
                        "Rejected AI match ${ch.name} → ${entry.id} (plus1/country/score gate)"
                    )
                    return@forEach
                }
                batchAssigned[channelId] = entry.id
                out[channelId] = entry.id
            }
            batch.forEach { ch ->
                val epgId = batchAssigned[ch.id]
                val epg = epgId?.let { epgById[it.lowercase(Locale.US)] }
                onChannelResult?.invoke(ch, epg)
            }
            processed += batch.size
            onProgress?.invoke(processed, channels.size)
        }
        return Result(out)
    }

    /**
     * Gate AI suggestions: leave unmatched rather than guess.
     * Enforces +1 ↔ +1, group/country preference, and a local score floor.
     */
    private fun acceptAiAssignment(
        channel: IptvChannel,
        epg: EpgChannelEntry,
        rankIndex: EpgChannelMatcher.EpgRankIndex,
    ): Boolean {
        val chPlus = EpgChannelMatcher.isPlus1(channel.name)
        val epgPlus = EpgChannelMatcher.isPlus1(epg.name) || EpgChannelMatcher.isPlus1(epg.id)
        if (chPlus != epgPlus) return false

        val preferred = EpgChannelMatcher.preferredCountries(channel.group, channel.name)
        val epgCountries = EpgChannelMatcher.countriesFromEpgId(epg.id)
        if (preferred.isNotEmpty() && epgCountries.isNotEmpty() &&
            preferred.none { it in epgCountries }
        ) {
            return false
        }

        val score = rankIndex.scoreFor(channel, epg.id)
        return score >= EpgChannelMatcher.AI_ACCEPT_MIN_SCORE
    }

    private data class AiJson(val assignments: Map<String, String>, val error: String? = null)

    private fun callGemini(
        apiKey: String,
        model: String,
        jobs: List<Pair<IptvChannel, List<EpgChannelEntry>>>
    ): AiJson {
        // Prefer the requested model, then fall back when Google has shut an alias down (404).
        val models = linkedSetOf(model.trim().ifBlank { DEFAULT_GEMINI_MODEL })
            .apply { addAll(GEMINI_MODEL_FALLBACKS) }
        var lastError: String? = null
        for (candidate in models) {
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$candidate:generateContent?key=$apiKey"
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", buildPrompt(jobs)))
                        )
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.1)
                        .put("responseMimeType", "application/json")
                )
            val result = postJson(url, body.toString(), authHeader = null)
            if (result.error == null) return result
            lastError = result.error
            // Retry next model only for missing/retired model ids.
            if (!isRetriedGeminiModelError(result.error)) {
                return result
            }
        }
        return AiJson(emptyMap(), lastError ?: "Gemini request failed")
    }

    private fun isRetriedGeminiModelError(error: String): Boolean {
        val e = error.lowercase(Locale.US)
        return e.contains("http 404") ||
            e.contains("not found") ||
            e.contains("is not supported") ||
            e.contains("no longer available") ||
            e.contains("has been shut down")
    }

    private fun callOpenAi(
        apiKey: String,
        baseUrl: String,
        model: String,
        jobs: List<Pair<IptvChannel, List<EpgChannelEntry>>>
    ): AiJson {
        val root = baseUrl.trim().trimEnd('/')
        val url = "$root/chat/completions"
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.1)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                "You map IPTV playlist channels to XMLTV EPG ids. " +
                                    "Be conservative: return null unless brand, country, and +1 clearly match. " +
                                    "Reply with JSON only."
                            )
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", buildPrompt(jobs))
                    )
            )
        return postJson(url, body.toString(), authHeader = "Bearer $apiKey")
    }

    private fun postJson(url: String, jsonBody: String, authHeader: String?): AiJson {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "PallasVideoPlayer/2.5")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (!authHeader.isNullOrBlank()) {
            reqBuilder.header("Authorization", authHeader)
        }
        return try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return AiJson(
                        emptyMap(),
                        "AI HTTP ${response.code}: ${friendlyHttpError(text, response.message)}"
                    )
                }
                // Gemini may return HTTP 200 with promptFeedback / empty candidates on block.
                val blocked = geminiBlockReason(text)
                if (blocked != null) {
                    return AiJson(emptyMap(), blocked)
                }
                parseModelJson(text)
            }
        } catch (e: Exception) {
            AiJson(emptyMap(), e.message ?: "AI request failed")
        }
    }

    private fun friendlyHttpError(body: String, fallback: String): String {
        val fromJson = try {
            JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        val raw = fromJson ?: body.ifBlank { fallback }
        return raw
            .replace(Regex("""\s+"""), " ")
            .take(220)
            .ifBlank { fallback }
    }

    private fun geminiBlockReason(raw: String): String? {
        return try {
            val root = JSONObject(raw)
            val feedback = root.optJSONObject("promptFeedback")
            val block = feedback?.optString("blockReason")?.takeIf { it.isNotBlank() }
            if (block != null) {
                return "AI blocked the request ($block) — try a smaller group or Assign EPG"
            }
            val finish = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optString("finishReason")
            if (finish.equals("SAFETY", ignoreCase = true)) {
                "AI refused for safety — try Assign EPG on a channel"
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseModelJson(raw: String): AiJson {
        // Gemini wraps in candidates[0].content.parts[0].text
        // OpenAI wraps in choices[0].message.content
        val content = extractContent(raw) ?: raw
        val cleaned = content
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            val obj = JSONObject(cleaned)
            val matches = obj.optJSONArray("matches") ?: obj.optJSONArray("assignments")
            val map = LinkedHashMap<String, String>()
            if (matches != null) {
                for (i in 0 until matches.length()) {
                    val row = matches.optJSONObject(i) ?: continue
                    val id = row.optString("channelId").ifBlank { row.optString("id") }
                    val epg = row.optString("epgId").ifBlank { row.optString("epg") }
                    if (id.isNotBlank() && epg.isNotBlank() &&
                        !epg.equals("null", true) && epg != "-"
                    ) {
                        map[id] = epg.trim()
                    }
                }
            } else {
                // Fallback: flat { "channelId": "epgId", ... }
                obj.keys().forEach { key ->
                    if (key == "matches" || key == "assignments") return@forEach
                    val epg = obj.optString(key)
                    if (epg.isNotBlank() && !epg.equals("null", true)) map[key] = epg.trim()
                }
            }
            AiJson(map)
        } catch (e: Exception) {
            AiJson(emptyMap(), "AI returned unreadable JSON: ${e.message}")
        }
    }

    private fun extractContent(raw: String): String? {
        return try {
            val root = JSONObject(raw)
            root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?: root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPrompt(jobs: List<Pair<IptvChannel, List<EpgChannelEntry>>>): String {
        val channelsJson = JSONArray()
        jobs.forEach { (ch, candidates) ->
            val preferred = EpgChannelMatcher.preferredCountries(ch.group, ch.name)
            val cands = JSONArray()
            candidates.forEach { e ->
                cands.put(
                    JSONObject()
                        .put("epgId", e.id)
                        .put("epgName", e.name)
                        .put("epgCountries", JSONArray(EpgChannelMatcher.countriesFromEpgId(e.id).toList()))
                        .put("epgPlus1", EpgChannelMatcher.isPlus1(e.id) || EpgChannelMatcher.isPlus1(e.name))
                )
            }
            channelsJson.put(
                JSONObject()
                    .put("channelId", ch.id)
                    .put("name", ch.name)
                    .put("group", ch.group)
                    .put("preferredCountries", JSONArray(preferred.toList()))
                    .put("channelPlus1", EpgChannelMatcher.isPlus1(ch.name))
                    .put("tvgId", ch.tvgId ?: "")
                    .put("candidates", cands)
            )
        }
        return """
You match IPTV M3U channels to XMLTV EPG channels. Be STRICT — leave unmatched when unsure.

Rules (must all hold):
1. +1 / timeshift: if channelPlus1 is true, ONLY pick a candidate with epgPlus1 true (ids like film4.uk.plus1). If channelPlus1 is false, NEVER pick a +1/plus1 EPG. Example: "UK: Film4 +1" → film4.uk.plus1, NOT film4.uk.
2. Country / group: preferredCountries come from the playlist group (e.g. "New Zealand" → nz, "UK Entertainment" → uk). Prefer candidates whose epgId country suffix matches (sky_sports.nz vs sky_sports.uk vs sky_sports.za). If preferredCountries is non-empty and NO candidate matches that country, return null — do not pick another country.
3. Brand must clearly match (Film4↔Film4, Sky Sports↔Sky Sports). Ignore quality noise (FHD/HD/4K) and cosmetic prefixes.
4. Do NOT invent epgIds. Only use candidates listed. Prefer null over a weak guess — it is fine to leave many channels unmatched.

Return JSON only:
{"matches":[{"channelId":"...","epgId":null_or_id}]}

Channels:
$channelsJson
""".trimIndent()
    }

    companion object {
        /** Gemini 2.0 Flash shut down June 2026. */
        const val DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite"
        const val DEFAULT_OPENAI_BASE = "https://api.openai.com/v1"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        private val GEMINI_MODEL_FALLBACKS = listOf(
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-2.5-flash",
            "gemini-flash-latest",
        )
        private const val BATCH_SIZE = 6
        private const val CANDIDATES = 10
        private const val TAG = "EpgAiMatcher"
    }
}
