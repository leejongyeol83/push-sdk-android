package com.am.push

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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
         */
        fun fromFcmData(data: Map<String, String>): PushMessage? {
            val messageId = data["messageId"] ?: return null
            val title = data["title"]
            val body = data["body"]
            val imageUrl = data["imageUrl"]
            val messageType = data["messageType"]

            val customData: Map<String, Any>? = data["data"]?.let {
                try {
                    gson.fromJson(it, object : TypeToken<Map<String, Any>>() {}.type)
                } catch (_: Exception) { null }
            }

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
