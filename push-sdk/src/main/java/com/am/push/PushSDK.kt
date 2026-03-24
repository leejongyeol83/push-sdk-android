package com.am.push

import android.content.Context
import android.os.Build
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
 * PushSDK.register("user123")
 * ```
 */
object PushSDK {

    private const val TAG = "Main"

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

    /**
     * SDK를 초기화한다.
     */
    fun configure(context: Context, config: PushConfig, enableLogging: Boolean = config.enableLogging) {
        this.context = context.applicationContext
        this.config = config
        this.prefs = PushPreferences(context.applicationContext)
        this.httpClient = PushHttpClient(config.serverUrl, config.apiKey)
        PushLogger.enabled = enableLogging

        // 설정 저장 (FCM 수신 시 복원용)
        prefs?.saveConfig(config.serverUrl, config.apiKey)

        isConfigured = true
        PushLogger.info(TAG, "SDK 초기화 완료 (apiKey: ${config.apiKey.take(10)}...)")
    }

    /**
     * SharedPreferences에서 설정을 복원한다 (FCM 수신 시 SDK 미초기화 상태 대비).
     */
    fun restoreFromPreferences(context: Context) {
        val tempPrefs = PushPreferences(context.applicationContext)
        val serverUrl = tempPrefs.getSavedServerUrl() ?: return
        val apiKey = tempPrefs.getSavedApiKey() ?: return

        configure(context, PushConfig(apiKey = apiKey, serverUrl = serverUrl))
        PushLogger.info(TAG, "SharedPreferences에서 설정 복원 완료")
    }

    // ── 토큰/멤버 관리 ──

    /**
     * FCM 토큰을 설정한다.
     */
    fun setDeviceToken(token: String) {
        prefs?.saveDeviceToken(token)
        PushLogger.debug(TAG, "토큰 설정: ${token.take(10)}...")
    }

    /**
     * 현재 memberNo를 반환한다 (없으면 deviceId).
     */
    val currentMemberNo: String
        get() = prefs?.getMemberNo() ?: prefs?.getOrCreateDeviceId() ?: ""

    /**
     * 저장된 memberNo를 반환한다.
     */
    fun getMemberNo(): String? = prefs?.getMemberNo()

    /**
     * 디바이스 UUID를 반환한다.
     */
    fun getDeviceId(): String = prefs?.getOrCreateDeviceId() ?: ""

    private fun resolveMemberNo(memberNo: String?): String {
        if (!memberNo.isNullOrBlank()) {
            prefs?.saveMemberNo(memberNo)
            return memberNo
        }
        return currentMemberNo
    }

    // ── 디바이스 등록 ──

    /**
     * 디바이스를 서버에 등록한다.
     * 이전 등록 정보와 동일하면 스킵한다 (스마트 비교).
     */
    suspend fun register(memberNo: String? = null): Boolean {
        return internalRegister(memberNo, compare = true)
    }

    private suspend fun internalRegister(memberNo: String? = null, compare: Boolean = true): Boolean {
        val client = httpClient ?: throw IllegalStateException("PushSDK.configure()를 먼저 호출하세요")
        val p = prefs ?: throw IllegalStateException("PushSDK.configure()를 먼저 호출하세요")

        val resolved = resolveMemberNo(memberNo)
        val token = p.getDeviceToken() ?: throw IllegalStateException("setDeviceToken()을 먼저 호출하세요")
        val ctx = context ?: throw IllegalStateException("PushSDK.configure()를 먼저 호출하세요")

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

        // 스마트 비교: 이전 등록과 동일하면 스킵
        if (compare) {
            val lastReg = p.getLastRegistration()
            if (lastReg != null && lastReg == regInfo) {
                PushLogger.debug(TAG, "등록 정보 변경 없음, 스킵")
                processUnconfirmedMessages()
                return true
            }
        }

        val result = client.post(
            EP_REGISTER,
            regInfo,
            object : TypeToken<Map<String, Any>>() {},
        )

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

    // ── 알림 설정 ──

    /**
     * 전체 푸시 수신 허용/거부.
     */
    suspend fun setAllowPush(allow: Boolean) {
        val client = httpClient ?: return
        val deviceId = getDeviceId()

        val result = client.post(
            EP_SETTINGS,
            mapOf("deviceId" to deviceId, "enabled" to allow),
            object : TypeToken<Map<String, Any>>() {},
        )

        when (result) {
            is ApiResult.Success -> PushLogger.info(TAG, "푸시 설정 변경: enabled=$allow")
            is ApiResult.Error -> PushLogger.error(TAG, "푸시 설정 실패: ${result.message}")
            is ApiResult.NetworkError -> PushLogger.error(TAG, "푸시 설정 네트워크 오류: ${result.exception.message}")
        }
    }

    /**
     * 방해금지 시간 설정. startTime/endTime 모두 null이면 해제.
     */
    suspend fun setDND(startTime: String? = null, endTime: String? = null) {
        val client = httpClient ?: return
        val deviceId = getDeviceId()

        val body = mutableMapOf<String, Any>("deviceId" to deviceId)
        if (startTime != null) body["startTime"] = startTime
        if (endTime != null) body["endTime"] = endTime

        val result = client.post(
            EP_SETTINGS_DND,
            body,
            object : TypeToken<Map<String, Any>>() {},
        )

        when (result) {
            is ApiResult.Success -> PushLogger.info(TAG, "DND 설정 변경: $startTime ~ $endTime")
            is ApiResult.Error -> PushLogger.error(TAG, "DND 설정 실패: ${result.message}")
            is ApiResult.NetworkError -> PushLogger.error(TAG, "DND 설정 네트워크 오류: ${result.exception.message}")
        }
    }

    /**
     * 메시지 유형별 수신 허용/거부.
     */
    suspend fun setAllowType(messageTypeCode: String, enabled: Boolean) {
        val client = httpClient ?: return
        val deviceId = getDeviceId()

        val result = client.post(
            EP_SETTINGS_TYPE,
            mapOf("deviceId" to deviceId, "messageTypeCode" to messageTypeCode, "enabled" to enabled),
            object : TypeToken<Map<String, Any>>() {},
        )

        when (result) {
            is ApiResult.Success -> PushLogger.info(TAG, "메시지 유형 설정: $messageTypeCode=$enabled")
            is ApiResult.Error -> PushLogger.error(TAG, "메시지 유형 설정 실패: ${result.message}")
            is ApiResult.NetworkError -> PushLogger.error(TAG, "메시지 유형 설정 네트워크 오류: ${result.exception.message}")
        }
    }

    // ── 수신/열람 확인 ──

    /**
     * 수신 확인을 전송한다.
     */
    suspend fun sendReceiveConfirm(data: Map<String, String>) {
        val client = httpClient ?: return
        val messageId = data["messageId"] ?: return
        val deviceId = getDeviceId()

        client.post(
            EP_RECEIVED,
            mapOf("deviceId" to deviceId, "messageId" to messageId),
            object : TypeToken<Map<String, Any>>() {},
        )
        PushLogger.debug(TAG, "수신 확인 전송: messageId=$messageId")
    }

    /**
     * 메시지 열람 확인.
     */
    suspend fun open(messageId: String) {
        val client = httpClient ?: return
        val deviceId = getDeviceId()

        client.post(
            EP_OPENED,
            mapOf("deviceId" to deviceId, "messageId" to messageId),
            object : TypeToken<Map<String, Any>>() {},
        )
        PushLogger.debug(TAG, "열람 확인 전송: messageId=$messageId")
    }

    /**
     * 전체 메시지 열람 처리.
     */
    suspend fun openAll() {
        val client = httpClient ?: return
        val deviceId = getDeviceId()

        val result = client.post(
            EP_OPENED_ALL,
            mapOf("deviceId" to deviceId),
            object : TypeToken<Map<String, Any>>() {},
        )

        when (result) {
            is ApiResult.Success -> PushLogger.info(TAG, "전체 열람 처리 완료")
            is ApiResult.Error -> PushLogger.error(TAG, "전체 열람 실패: ${result.message}")
            is ApiResult.NetworkError -> PushLogger.error(TAG, "전체 열람 네트워크 오류: ${result.exception.message}")
        }
    }

    // ── 조회 API ──

    /**
     * 메시지함을 조회한다.
     */
    data class InboxResponse(
        val messages: List<PushMessage>,
        val page: Int,
        val size: Int,
        val total: Int,
        val hasMore: Boolean,
    )

    suspend fun getInbox(page: Int = 1, size: Int = 20): InboxResponse {
        val client = httpClient ?: return InboxResponse(emptyList(), page, size, 0, false)
        val deviceId = getDeviceId()

        val result = client.get(
            EP_INBOX,
            mapOf("deviceId" to deviceId, "page" to page.toString(), "size" to size.toString()),
            object : TypeToken<Map<String, Any>>() {},
        )

        return when (result) {
            is ApiResult.Success -> {
                try {
                    val data = result.data
                    val messagesJson = gson.toJson(data["data"])
                    val messages: List<PushMessage> = gson.fromJson(messagesJson, object : TypeToken<List<PushMessage>>() {}.type)
                    val pagination = data["pagination"] as? Map<*, *>
                    val total = (pagination?.get("total") as? Number)?.toInt() ?: 0
                    InboxResponse(messages, page, size, total, page * size < total)
                } catch (e: Exception) {
                    PushLogger.error(TAG, "인박스 파싱 실패: ${e.message}", e)
                    InboxResponse(emptyList(), page, size, 0, false)
                }
            }
            is ApiResult.Error -> {
                PushLogger.error(TAG, "인박스 조회 실패: ${result.message}")
                InboxResponse(emptyList(), page, size, 0, false)
            }
            is ApiResult.NetworkError -> {
                PushLogger.error(TAG, "인박스 네트워크 오류: ${result.exception.message}")
                InboxResponse(emptyList(), page, size, 0, false)
            }
        }
    }

    /**
     * 메시지 상세를 조회한다.
     */
    suspend fun getMessageDetail(messageId: String): PushMessage? {
        val client = httpClient ?: return null
        val deviceId = getDeviceId()

        val result = client.get(
            "$EP_INBOX/$messageId",
            mapOf("deviceId" to deviceId),
            object : TypeToken<Map<String, Any>>() {},
        )

        return when (result) {
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
    }

    /**
     * 미열람 메시지 수를 조회한다.
     */
    suspend fun getBadgeCount(): Int {
        val client = httpClient ?: return 0
        val deviceId = getDeviceId()

        val result = client.get(
            EP_BADGE,
            mapOf("deviceId" to deviceId),
            object : TypeToken<Map<String, Any>>() {},
        )

        return when (result) {
            is ApiResult.Success -> {
                try {
                    val data = result.data["data"] as? Map<*, *>
                    (data?.get("count") as? Number)?.toInt() ?: 0
                } catch (_: Exception) { 0 }
            }
            else -> 0
        }
    }

    // ── 로그아웃 ──

    /**
     * 로그아웃. disablePush=true이면 푸시 수신 비활성화.
     */
    suspend fun logout(disablePush: Boolean = false) {
        if (disablePush) {
            setAllowPush(false)
        }

        // memberNo를 UUID로 복원 (게스트 상태)
        val deviceId = getDeviceId()
        prefs?.saveMemberNo(deviceId)

        // UUID로 재등록
        internalRegister(deviceId, compare = false)

        PushLogger.info(TAG, "로그아웃 완료 (disablePush=$disablePush)")
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
                    sendReceiveConfirm(mapOf("messageId" to messageId))
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
