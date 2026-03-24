package com.am.push

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Push SDK 메인 진입점.
 *
 * 싱글톤 패턴. 앱 시작 시 [configure]로 초기화하고
 * [setDeviceToken] + [register]로 디바이스를 등록한다.
 *
 * ```kotlin
 * val config = PushConfig(apiKey = "pk_...", serverUrl = "https://...")
 * PushSDK.configure(context, config)
 * PushSDK.setDeviceToken(fcmToken)
 * PushSDK.register("user123") { success -> }
 * ```
 */
object PushSDK {

    private const val TAG = "Main"
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 상태 ──

    var isConfigured: Boolean = false
        private set

    private var context: Context? = null
    private var config: PushConfig? = null
    private var httpClient: PushHttpClient? = null
    private var prefs: PushPreferences? = null
    private val gson = Gson()
    private val sdkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── 엔드포인트 ──

    private const val EP_REGISTER = "/api/sdk/push/register"
    private const val EP_RECEIVED = "/api/sdk/push/received"
    private const val EP_OPENED = "/api/sdk/push/opened"
    private const val EP_OPENED_ALL = "/api/sdk/push/opened/all"
    private const val EP_SETTINGS = "/api/sdk/push/settings"
    private const val EP_SETTINGS_DND = "/api/sdk/push/settings/dnd"
    private const val EP_INBOX = "/api/sdk/push/inbox"
    private const val EP_BADGE = "/api/sdk/push/badge"
    private const val EP_SETTINGS_TYPE = "/api/sdk/push/settings/type"

    // ── 초기화 ──

    fun configure(context: Context, config: PushConfig, enableLogging: Boolean = config.enableLogging) {
        this.context = context.applicationContext
        this.config = config
        this.prefs = PushPreferences(context.applicationContext)
        this.httpClient = PushHttpClient(config.serverUrl, config.apiKey)
        PushLogger.enabled = enableLogging

        prefs?.saveConfig(config.serverUrl, config.apiKey)

        isConfigured = true
        PushLogger.info(TAG, "SDK 초기화 완료 (apiKey: ${config.apiKey.take(10)}...)")
    }

    fun restoreFromPreferences(context: Context) {
        val tempPrefs = PushPreferences(context.applicationContext)
        val serverUrl = tempPrefs.getSavedServerUrl() ?: return
        val apiKey = tempPrefs.getSavedApiKey() ?: return

        configure(context, PushConfig(apiKey = apiKey, serverUrl = serverUrl))
        PushLogger.info(TAG, "SharedPreferences에서 설정 복원 완료")
    }

    // ── 토큰/멤버 관리 ──

    fun setDeviceToken(token: String) {
        prefs?.saveDeviceToken(token)
        PushLogger.debug(TAG, "토큰 설정: ${token.take(10)}...")
    }

    val currentMemberNo: String
        get() = prefs?.getMemberNo() ?: prefs?.getOrCreateDeviceId() ?: ""

    fun getMemberNo(): String? = prefs?.getMemberNo()

    fun getDeviceId(): String = prefs?.getOrCreateDeviceId() ?: ""

    private fun resolveMemberNo(memberNo: String?): String {
        if (!memberNo.isNullOrBlank()) {
            prefs?.saveMemberNo(memberNo)
            return memberNo
        }
        return currentMemberNo
    }

    // ── 디바이스 등록 (콜백) ──

    /**
     * 디바이스를 서버에 등록한다. 변경 없으면 스킵 (스마트 비교).
     */
    fun register(memberNo: String? = null, callback: ((Boolean) -> Unit)? = null) {
        sdkScope.launch {
            val success = internalRegister(memberNo, compare = true)
            mainHandler.post { callback?.invoke(success) }
        }
    }

    private suspend fun internalRegister(memberNo: String? = null, compare: Boolean = true): Boolean {
        val client = httpClient ?: run {
            PushLogger.error(TAG, "configure()를 먼저 호출하세요")
            return false
        }
        val p = prefs ?: return false
        val token = p.getDeviceToken() ?: run {
            PushLogger.error(TAG, "setDeviceToken()을 먼저 호출하세요")
            return false
        }
        val ctx = context ?: return false

        val resolved = resolveMemberNo(memberNo)
        val appVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }

        val regInfo = mapOf(
            "memberNo" to resolved,
            "token" to token,
            "platform" to "android",
            "osVersion" to Build.VERSION.RELEASE,
            "appVersion" to appVersion,
            "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
        )

        if (compare) {
            val lastReg = p.getLastRegistration()
            if (lastReg != null && lastReg == regInfo) {
                PushLogger.debug(TAG, "등록 정보 변경 없음, 스킵")
                processUnconfirmedMessages()
                return true
            }
        }

        val result = client.post(EP_REGISTER, regInfo, object : TypeToken<Map<String, Any>>() {})

        return when (result) {
            is ApiResult.Success -> {
                p.saveLastRegistration(regInfo)
                PushLogger.info(TAG, "디바이스 등록 성공")
                processUnconfirmedMessages()
                true
            }
            is ApiResult.Error -> {
                PushLogger.error(TAG, "디바이스 등록 실패: code=${result.code}, message=${result.message}")
                false
            }
            is ApiResult.NetworkError -> {
                PushLogger.error(TAG, "디바이스 등록 네트워크 오류: ${result.exception.message}", result.exception)
                false
            }
        }
    }

    // ── 알림 설정 (fire-and-forget) ──

    fun setAllowPush(allow: Boolean) {
        sdkScope.launch {
            val client = httpClient ?: return@launch
            client.post(EP_SETTINGS, mapOf("deviceId" to getDeviceId(), "enabled" to allow), object : TypeToken<Map<String, Any>>() {})
            PushLogger.info(TAG, "푸시 설정 변경: enabled=$allow")
        }
    }

    fun setDND(startTime: String? = null, endTime: String? = null) {
        sdkScope.launch {
            val client = httpClient ?: return@launch
            val body = mutableMapOf<String, Any>("deviceId" to getDeviceId())
            if (startTime != null) body["startTime"] = startTime
            if (endTime != null) body["endTime"] = endTime
            client.post(EP_SETTINGS_DND, body, object : TypeToken<Map<String, Any>>() {})
            PushLogger.info(TAG, "DND 설정 변경: $startTime ~ $endTime")
        }
    }

    fun setAllowType(messageTypeCode: String, enabled: Boolean) {
        sdkScope.launch {
            val client = httpClient ?: return@launch
            client.post(EP_SETTINGS_TYPE, mapOf("deviceId" to getDeviceId(), "messageTypeCode" to messageTypeCode, "enabled" to enabled), object : TypeToken<Map<String, Any>>() {})
            PushLogger.info(TAG, "메시지 유형 설정: $messageTypeCode=$enabled")
        }
    }

    // ── 수신/열람 확인 (fire-and-forget) ──

    fun sendReceiveConfirm(data: Map<String, String>) {
        sdkScope.launch {
            sendReceiveConfirmInternal(data)
        }
    }

    internal suspend fun sendReceiveConfirmInternal(data: Map<String, String>) {
        val client = httpClient ?: return
        val messageId = data["messageId"] ?: return
        client.post(EP_RECEIVED, mapOf("deviceId" to getDeviceId(), "messageId" to messageId), object : TypeToken<Map<String, Any>>() {})
        PushLogger.debug(TAG, "수신 확인 전송: messageId=$messageId")
    }

    fun open(messageId: String) {
        sdkScope.launch {
            val client = httpClient ?: return@launch
            client.post(EP_OPENED, mapOf("deviceId" to getDeviceId(), "messageId" to messageId), object : TypeToken<Map<String, Any>>() {})
            PushLogger.debug(TAG, "열람 확인 전송: messageId=$messageId")
        }
    }

    fun openAll() {
        sdkScope.launch {
            val client = httpClient ?: return@launch
            client.post(EP_OPENED_ALL, mapOf("deviceId" to getDeviceId()), object : TypeToken<Map<String, Any>>() {})
            PushLogger.info(TAG, "전체 열람 처리 완료")
        }
    }

    // ── 조회 API (콜백) ──

    data class InboxResponse(
        val messages: List<PushMessage>,
        val page: Int,
        val size: Int,
        val total: Int,
        val hasMore: Boolean,
    )

    fun getInbox(page: Int = 1, size: Int = 20, callback: (InboxResponse) -> Unit) {
        sdkScope.launch {
            val client = httpClient ?: run {
                mainHandler.post { callback(InboxResponse(emptyList(), page, size, 0, false)) }
                return@launch
            }
            val result = client.get(EP_INBOX, mapOf("deviceId" to getDeviceId(), "page" to page.toString(), "size" to size.toString()), object : TypeToken<Map<String, Any>>() {})

            val response = when (result) {
                is ApiResult.Success -> {
                    try {
                        val messagesJson = gson.toJson(result.data["data"])
                        val messages: List<PushMessage> = gson.fromJson(messagesJson, object : TypeToken<List<PushMessage>>() {}.type)
                        val pagination = result.data["pagination"] as? Map<*, *>
                        val total = (pagination?.get("total") as? Number)?.toInt() ?: 0
                        InboxResponse(messages, page, size, total, page * size < total)
                    } catch (e: Exception) {
                        PushLogger.error(TAG, "인박스 파싱 실패: ${e.message}", e)
                        InboxResponse(emptyList(), page, size, 0, false)
                    }
                }
                else -> InboxResponse(emptyList(), page, size, 0, false)
            }
            mainHandler.post { callback(response) }
        }
    }

    fun getMessageDetail(messageId: String, callback: (PushMessage?) -> Unit) {
        sdkScope.launch {
            val client = httpClient ?: run {
                mainHandler.post { callback(null) }
                return@launch
            }
            val result = client.get("$EP_INBOX/$messageId", mapOf("deviceId" to getDeviceId()), object : TypeToken<Map<String, Any>>() {})

            val message = when (result) {
                is ApiResult.Success -> {
                    try {
                        val dataJson = gson.toJson(result.data["data"])
                        gson.fromJson(dataJson, PushMessage::class.java)
                    } catch (e: Exception) {
                        PushLogger.error(TAG, "메시지 상세 파싱 실패: ${e.message}", e)
                        null
                    }
                }
                else -> null
            }
            mainHandler.post { callback(message) }
        }
    }

    fun getBadgeCount(callback: (Int) -> Unit) {
        sdkScope.launch {
            val client = httpClient ?: run {
                mainHandler.post { callback(0) }
                return@launch
            }
            val result = client.get(EP_BADGE, mapOf("deviceId" to getDeviceId()), object : TypeToken<Map<String, Any>>() {})

            val count = when (result) {
                is ApiResult.Success -> {
                    try {
                        val data = result.data["data"] as? Map<*, *>
                        (data?.get("count") as? Number)?.toInt() ?: 0
                    } catch (_: Exception) { 0 }
                }
                else -> 0
            }
            mainHandler.post { callback(count) }
        }
    }

    // ── 로그아웃 (fire-and-forget) ──

    fun logout(disablePush: Boolean = false) {
        sdkScope.launch {
            if (disablePush) {
                val client = httpClient ?: return@launch
                client.post(EP_SETTINGS, mapOf("deviceId" to getDeviceId(), "enabled" to false), object : TypeToken<Map<String, Any>>() {})
            }
            val deviceId = getDeviceId()
            prefs?.saveMemberNo(deviceId)
            internalRegister(deviceId, compare = false)
            PushLogger.info(TAG, "로그아웃 완료 (disablePush=$disablePush)")
        }
    }

    // ── 미확인 메시지 관리 ──

    fun markUnconfirmed(messageId: String) {
        prefs?.addUnconfirmedMessage(messageId)
    }

    fun removeUnconfirmed(messageId: String) {
        prefs?.removeUnconfirmedMessage(messageId)
    }

    private fun processUnconfirmedMessages() {
        val unconfirmed = prefs?.getUnconfirmedMessages() ?: return
        if (unconfirmed.isEmpty()) return

        PushLogger.debug(TAG, "미확인 메시지 ${unconfirmed.size}건 복구 전송")
        sdkScope.launch {
            for (messageId in unconfirmed) {
                try {
                    sendReceiveConfirmInternal(mapOf("messageId" to messageId))
                    removeUnconfirmed(messageId)
                } catch (e: Exception) {
                    PushLogger.error(TAG, "미확인 메시지 복구 실패: $messageId", e)
                }
            }
        }
    }

    // ── 리셋 (테스트용) ──

    internal fun reset() {
        httpClient?.shutdown()
        httpClient = null
        prefs = null
        config = null
        context = null
        isConfigured = false
    }
}
