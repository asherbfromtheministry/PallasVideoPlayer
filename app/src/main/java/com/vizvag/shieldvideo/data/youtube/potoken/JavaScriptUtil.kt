package com.vizvag.shieldvideo.data.youtube.potoken

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = JSONArray(rawChallengeData)
    val challengeData = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
        JSONArray(descramble(scrambled.getString(1)))
    } else {
        scrambled.optJSONArray(0)
            ?: throw PoTokenException("Missing challenge data in Create response")
    }

    val interpreterJs = JSONObject()
        .put(
            "privateDoNotAccessOrElseSafeScriptWrappedValue",
            challengeData.optJSONArray(1)?.findString(),
        )
        .put(
            "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue",
            challengeData.optJSONArray(2)?.findString(),
        )

    return JSONObject()
        .put("messageId", challengeData.getString(0))
        .put("interpreterJavascript", interpreterJs)
        .put("interpreterHash", challengeData.getString(3))
        .put("program", challengeData.getString(4))
        .put("globalName", challengeData.getString(5))
        .put("clientExperimentsStateBlob", challengeData.getString(7))
        .toString()
}

/**
 * SmartTube / BgUtils Aug 2026: homepage ytAtN challenges use a descrambled
 * `bgChallenge` object; interpreter JS is loaded from interpreterUrl.
 */
fun parseDescrambledChallengeData(rawChallengeData: String, http: OkHttpClient): String {
    val root = JSONObject(rawChallengeData)
    val bgChallenge = root.getJSONObject("bgChallenge")
    val interpreterUrl = bgChallenge
        .getJSONObject("interpreterUrl")
        .getString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
    val scriptUrl = if (interpreterUrl.startsWith("http")) interpreterUrl else "https:$interpreterUrl"
    val script = http.newCall(Request.Builder().url(scriptUrl).get().build()).execute().use { resp ->
        if (!resp.isSuccessful) throw PoTokenException("Interpreter fetch HTTP ${resp.code}")
        resp.body?.string().orEmpty()
    }
    if (script.isBlank()) throw PoTokenException("Empty interpreter script")

    val interpreterJs = JSONObject()
        .put("privateDoNotAccessOrElseSafeScriptWrappedValue", script)
        .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", interpreterUrl)

    return JSONObject()
        .put("interpreterJavascript", interpreterJs)
        .put("interpreterHash", bgChallenge.getString("interpreterHash"))
        .put("program", bgChallenge.getString("program"))
        .put("globalName", bgChallenge.getString("globalName"))
        .put("clientExperimentsStateBlob", bgChallenge.optString("clientExperimentsStateBlob"))
        .toString()
}

/**
 * Vendored from LuanRT/BgUtils — ytAtN payload is JS-object-literal-ish, not strict JSON.
 */
fun parseLooseJSON(looseJson: String): Map<String, String> {
    val hexPattern = Pattern.compile("""\\x([0-9A-Fa-f]{2})""")
    val hexMatcher = hexPattern.matcher(looseJson)
    val sanitizedString = buildString {
        var lastEnd = 0
        while (hexMatcher.find()) {
            append(looseJson, lastEnd, hexMatcher.start())
            append(hexMatcher.group(1)!!.toInt(16).toChar())
            lastEnd = hexMatcher.end()
        }
        append(looseJson, lastEnd, looseJson.length)
    }

    var jsonStr = Pattern.compile(""",\s*([\]}])""")
        .matcher(sanitizedString)
        .replaceAll("$1")

    val singleQuotePattern = Pattern.compile("""'((?:[^'\\]|\\[\s\S])*)'""")
    val singleQuoteMatcher = singleQuotePattern.matcher(jsonStr)
    jsonStr = buildString {
        var lastEnd = 0
        while (singleQuoteMatcher.find()) {
            append(jsonStr, lastEnd, singleQuoteMatcher.start())
            val innerStr = singleQuoteMatcher.group(1)!!
                .replace("""\'""", "'")
            append('"')
            for (char in innerStr) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
            lastEnd = singleQuoteMatcher.end()
        }
        append(jsonStr, lastEnd, jsonStr.length)
    }

    jsonStr = Pattern.compile("""([{,]\s*)([a-zA-Z0-9_$]+)\s*:""")
        .matcher(jsonStr)
        .replaceAll("""$1"$2":""")

    val parsed = JSONObject(jsonStr)
    val result = LinkedHashMap<String, String>()
    val keys = parsed.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result[key] = parsed.opt(key)?.toString() ?: "null"
    }
    return result
}

fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JSONArray(rawIntegrityTokenData)
    return base64ToU8(integrityTokenData.getString(0)) to integrityTokenData.getLong(1)
}

fun stringToU8(identifier: String): String = newUint8Array(identifier.toByteArray())

fun u8ToBase64(poToken: String): String =
    poToken.split(",")
        .map { it.toUByte().toByte() }
        .toByteArray()
        .let { bytes ->
            Base64.getEncoder().encodeToString(bytes)
                .replace("+", "-")
                .replace("/", "_")
        }

fun defaultPoTokenHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

private fun JSONArray.findString(): String? {
    for (i in 0 until length()) {
        val value = opt(i)
        if (value is String) return value
    }
    return null
}

private fun descramble(scrambledChallenge: String): String =
    base64ToByteString(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()

private fun base64ToU8(base64: String): String = newUint8Array(base64ToByteString(base64))

private fun newUint8Array(contents: ByteArray): String =
    "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"

private fun base64ToByteString(base64: String): ByteArray {
    val base64Mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')
    return Base64.getDecoder().decode(base64Mod)
}
