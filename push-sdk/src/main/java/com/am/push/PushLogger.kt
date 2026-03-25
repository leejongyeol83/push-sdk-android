package com.am.push

import android.util.Log

/**
 * Push SDK 내부 로거.
 *
 * 설정된 logLevel 이하인 경우에만 로그를 출력한다.
 */
internal object PushLogger {

    private const val TAG_PREFIX = "PushSDK"

    var logLevel: LogLevel = LogLevel.NONE

    fun debug(tag: String, message: String) {
        log(LogLevel.DEBUG, tag, message)
    }

    fun info(tag: String, message: String) {
        log(LogLevel.INFO, tag, message)
    }

    fun warn(tag: String, message: String) {
        log(LogLevel.WARN, tag, message)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (LogLevel.ERROR > logLevel) return
        Log.e("$TAG_PREFIX:$tag", message, throwable)
    }

    private fun log(level: LogLevel, tag: String, message: String) {
        if (level > logLevel) return
        when (level) {
            LogLevel.DEBUG -> Log.d("$TAG_PREFIX:$tag", message)
            LogLevel.INFO -> Log.i("$TAG_PREFIX:$tag", message)
            LogLevel.WARN -> Log.w("$TAG_PREFIX:$tag", message)
            else -> {}
        }
    }
}
