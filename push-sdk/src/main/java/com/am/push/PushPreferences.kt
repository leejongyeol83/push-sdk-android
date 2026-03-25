package com.am.push

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Push SDK 로컬 저장소 (SharedPreferences).
 *
 * 디바이스 토큰, userId, 등록 정보 등을 영속 저장한다.
 */
internal class PushPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "com.am.push.preferences"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SERVER_DEVICE_ID = "server_device_id"
        private const val KEY_UNCONFIRMED = "unconfirmed_messages"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveDeviceToken(token: String) { prefs.edit().putString(KEY_DEVICE_TOKEN, token).apply() }
    fun getDeviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)

    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    fun saveUserId(userId: String) { prefs.edit().putString(KEY_USER_ID, userId).apply() }
    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun saveConfig(serverUrl: String, apiKey: String) {
        prefs.edit().putString(KEY_SERVER_URL, serverUrl).putString(KEY_API_KEY, apiKey).apply()
    }
    fun getSavedServerUrl(): String? = prefs.getString(KEY_SERVER_URL, null)
    fun getSavedApiKey(): String? = prefs.getString(KEY_API_KEY, null)



    fun saveServerDeviceId(id: String) { prefs.edit().putString(KEY_SERVER_DEVICE_ID, id).apply() }
    fun getServerDeviceId(): String? = prefs.getString(KEY_SERVER_DEVICE_ID, null)

    fun addUnconfirmedMessage(messageId: String) {
        val set = getUnconfirmedMessages().toMutableSet()
        set.add(messageId)
        prefs.edit().putStringSet(KEY_UNCONFIRMED, set).apply()
    }
    fun getUnconfirmedMessages(): Set<String> = prefs.getStringSet(KEY_UNCONFIRMED, emptySet()) ?: emptySet()
    fun removeUnconfirmedMessage(messageId: String) {
        val set = getUnconfirmedMessages().toMutableSet()
        set.remove(messageId)
        prefs.edit().putStringSet(KEY_UNCONFIRMED, set).apply()
    }
}
