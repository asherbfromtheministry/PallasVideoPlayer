package com.vizvag.shieldvideo.data.youtube.potoken

class PoTokenException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception =
    if (error.contains("SyntaxError")) {
        BadWebViewException(error)
    } else {
        PoTokenException(error)
    }
