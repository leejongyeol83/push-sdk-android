package com.am.push

import android.util.Log

/**
 * Push SDK 내부 로거.
 */
internal object PushLogger {

    private const val TAG_PREFIX = "PushSDK"

    var enabled: Boolean = false

    fun debug(tag: String, message: String) {
        if (enabled) Log.d("$TAG_PREFIX:$tag", message)
    }

    fun info(tag: String, message: String) {
        if (enabled) Log.i("$TAG_PREFIX:$tag", message)
    }

    fun warn(tag: String, message: String) {
        if (enabled) Log.w("$TAG_PREFIX:$tag", message)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (enabled) Log.e("$TAG_PREFIX:$tag", message, throwable)
    }
}
