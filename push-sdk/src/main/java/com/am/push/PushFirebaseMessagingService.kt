package com.am.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URL

/**
 * FCM 메시지 수신 서비스.
 *
 * 기존 APFirebaseMessagingService를 이식.
 * 호스트 앱에서 상속하여 알림 커스터마이징 가능.
 */
open class PushFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val EXTRA_MESSAGE_ID = "push_message_id"
        private const val CHANNEL_ID = "push_sdk_default"
        private const val CHANNEL_NAME = "푸시 알림"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        if (data.isEmpty()) return

        val message = PushMessage.fromFcmData(data) ?: return

        // SDK 미초기화 상태면 SharedPreferences에서 복원
        if (!PushSDK.isConfigured) {
            PushSDK.restoreFromPreferences(applicationContext)
        }

        // 미확인 메시지 마크
        PushSDK.markUnconfirmed(message.messageId)

        // 비동기로 수신 확인 전송 (파싱된 messageId 사용)
        serviceScope.launch {
            try {
                PushSDK.sendReceiveConfirmInternal(mapOf("messageId" to message.messageId))
                PushSDK.removeUnconfirmed(message.messageId)
            } catch (e: Exception) {
                PushLogger.error("FCM", "[수신] 수신 확인 전송 실패: ${e.message}", e)
            }
        }

        // 알림 표시 (Silent가 아닌 경우, 이미지 다운로드가 블로킹이므로 IO 스레드에서 실행)
        if (message.title != null || message.body != null) {
            serviceScope.launch {
                showNotification(message)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PushLogger.info("FCM", "[초기화] 새 토큰 수신: ${token.take(10)}...")

        PushSDK.setDeviceToken(token)

        if (PushSDK.isConfigured) {
            PushSDK.register()
        }
    }

    /**
     * 알림을 빌드한다. 호스트 앱에서 오버라이드하여 커스터마이징 가능.
     */
    protected open fun buildNotification(message: PushMessage): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(getAppIcon())
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(getContentIntent(message))

        // 이미지가 있으면 BigPictureStyle로 표시
        if (!message.imageUrl.isNullOrBlank()) {
            val bitmap = downloadImage(message.imageUrl)
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
                    .setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as Bitmap?)
                    )
            }
        }

        return builder
    }

    private fun downloadImage(url: String): Bitmap? {
        return try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.getInputStream().use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            PushLogger.error("FCM", "[알림] 이미지 다운로드 실패: ${e.message}")
            null
        }
    }

    /**
     * 알림 클릭 시 실행할 Intent. 호스트 앱에서 오버라이드 가능.
     */
    protected open fun getContentIntent(message: PushMessage): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent()
        intent.putExtra(EXTRA_MESSAGE_ID, message.messageId)
        // 기본 메시지 정보
        message.title?.let { intent.putExtra("push_title", it) }
        message.body?.let { intent.putExtra("push_body", it) }
        message.imageUrl?.let { intent.putExtra("push_image_url", it) }
        message.messageType?.let { intent.putExtra("push_message_type", it) }
        // data 필드 전부 전달
        message.data?.forEach { (key, value) ->
            intent.putExtra("push_$key", value.toString())
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, message.messageId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showNotification(message: PushMessage) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // 채널 생성 (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }

        val notification = buildNotification(message).build()
        manager.notify(message.messageId.hashCode(), notification)
    }

    private fun getAppIcon(): Int {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appInfo.icon
        } catch (_: Exception) {
            android.R.drawable.ic_dialog_info
        }
    }
}
