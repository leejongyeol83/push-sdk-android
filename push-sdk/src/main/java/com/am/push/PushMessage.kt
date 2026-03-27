package com.am.push

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 푸시 메시지 데이터 모델.
 *
 * 서버 messages 테이블 구조에 맞춰 설계.
 * FCM 페이로드에서 파싱하거나, 인박스 API 응답에서 생성한다.
 */
data class PushMessage(
    val messageId: String,
    val title: String? = null,
    val body: String? = null,
    val imageUrl: String? = null,
    val data: Map<String, Any>? = null,
    val messageType: String? = null,
    val status: String? = null,
    val sentAt: String? = null,
    val openedAt: String? = null,
) {
    companion object {
        private val gson = Gson()

        /**
         * FCM data 맵에서 PushMessage를 생성한다.
         * 서버는 `appPush` 키에 JSON 문자열로 페이로드를 전달한다.
         */
        fun fromFcmData(data: Map<String, String>): PushMessage? {
            val appPushJson = data["appPush"] ?: return null
            val parsed: Map<String, Any> = try {
                gson.fromJson(appPushJson, object : TypeToken<Map<String, Any>>() {}.type)
            } catch (_: Exception) { return null }

            val messageId = parsed["messageId"] as? String ?: return null
            val title = parsed["title"] as? String
            val body = parsed["body"] as? String
            val imageUrl = parsed["imageUrl"] as? String
            val messageType = parsed["messageType"] as? String

            // appPush 내 커스텀 데이터 (표준 필드 제외)
            val standardKeys = setOf("messageId", "title", "body", "imageUrl", "messageType")
            val filtered = parsed.filterKeys { it !in standardKeys }
            val customData = if (filtered.isEmpty()) null else filtered

            return PushMessage(
                messageId = messageId,
                title = title,
                body = body,
                imageUrl = imageUrl,
                data = customData,
                messageType = messageType,
            )
        }
    }
}
