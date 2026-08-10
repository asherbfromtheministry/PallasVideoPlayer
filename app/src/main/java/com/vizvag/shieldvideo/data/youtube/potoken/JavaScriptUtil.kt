package com.vizvag.shieldvideo.data.youtube.potoken

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

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
