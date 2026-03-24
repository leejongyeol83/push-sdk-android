# Push SDK for Android

통합 플랫폼(App Manager)의 푸시 알림 SDK.
디바이스 등록, FCM 수신/열람 확인, 알림 설정, 메시지함 조회 등을 지원합니다.

## Installation

### 1. JitPack Repository 추가

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Dependency 추가

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.leejongyeol83:push-sdk-android:v1.0.0")
}
```

### 3. Firebase 설정

1. Firebase Console에서 `google-services.json` 다운로드 → `app/` 디렉토리에 배치
2. 루트 `build.gradle.kts`에 Google Services 플러그인 추가:

```kotlin
// 루트 build.gradle.kts
plugins {
    id("com.google.gms.google-services") version "4.4.0" apply false
}
```

3. 앱 `build.gradle.kts`에 플러그인 적용:

```kotlin
// app/build.gradle.kts
plugins {
    id("com.google.gms.google-services")
}
```

### 4. AndroidManifest.xml에 FCM 서비스 등록

```xml
<service
    android:name="com.am.push.PushFirebaseMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

커스텀 알림이 필요하면 `PushFirebaseMessagingService`를 상속하세요:

```kotlin
class MyPushService : PushFirebaseMessagingService() {
    override fun buildNotification(message: PushMessage): NotificationCompat.Builder {
        return super.buildNotification(message)
            .setSmallIcon(R.drawable.ic_notification)
    }
}
```

## Quick Start

```kotlin
import com.am.push.PushSDK
import com.am.push.PushConfig

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. SDK 초기화
        val config = PushConfig(
            apiKey = "pk_your_api_key",
            serverUrl = "https://your-platform.com",
            enableLogging = true  // 디버그용
        )
        PushSDK.configure(this, config)
    }
}
```

```kotlin
// 2. FCM 토큰 설정 + 디바이스 등록
FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    PushSDK.setDeviceToken(token)

    // memberNo: 회원 ID (비로그인이면 생략 → UUID 자동 생성)
    PushSDK.register("user123") { success ->
        Log.d("Push", "등록 결과: $success")
    }
}
```

```kotlin
// 3. 알림 클릭 처리
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    intent.getStringExtra(PushFirebaseMessagingService.EXTRA_MESSAGE_ID)?.let { messageId ->
        PushSDK.open(messageId)
    }
}
```

## API

### 초기화

| Method | Description |
|--------|-------------|
| `PushSDK.configure(context, config)` | SDK 초기화 |
| `PushSDK.setDeviceToken(token)` | FCM 토큰 설정 |
| `PushSDK.register(memberNo?)` | 디바이스 등록 (변경 없으면 스킵) |
| `PushSDK.restoreFromPreferences(context)` | FCM 수신 시 설정 복원 |

### 알림 설정

| Method | Description |
|--------|-------------|
| `PushSDK.setAllowPush(allow)` | 전체 푸시 수신 on/off |
| `PushSDK.setDND(startTime, endTime)` | 방해금지 설정 (null이면 해제) |
| `PushSDK.setAllowType(messageTypeCode, enabled)` | 메시지 유형별 수신 허용/거부 |

### 수신/열람 확인

| Method | Description |
|--------|-------------|
| `PushSDK.sendReceiveConfirm(data)` | 수신 확인 (FCM 서비스에서 자동 호출) |
| `PushSDK.open(messageId)` | 메시지 열람 확인 |
| `PushSDK.openAll()` | 전체 메시지 열람 처리 |

### 메시지함/뱃지

| Method | Description |
|--------|-------------|
| `PushSDK.getInbox(page, size)` | 메시지함 조회 (페이지네이션) |
| `PushSDK.getMessageDetail(messageId)` | 메시지 상세 조회 |
| `PushSDK.getBadgeCount()` | 미열람 메시지 수 조회 |

### 계정

| Method | Description |
|--------|-------------|
| `PushSDK.logout(disablePush?)` | 로그아웃 (UUID로 복원) |
| `PushSDK.getMemberNo()` | 저장된 memberNo 조회 |
| `PushSDK.getDeviceId()` | 디바이스 UUID 조회 |
| `PushSDK.isConfigured` | 초기화 완료 여부 |

## 스마트 등록

`register()` 호출 시 이전 등록 정보(memberNo, token, OS 버전, 앱 버전)와 비교하여 변경이 없으면 서버 호출을 스킵합니다.

## 미확인 메시지 자동 복구

FCM 수신 확인 전송이 실패하면 로컬에 저장했다가, 다음 `register()` 성공 시 자동으로 재전송합니다.

## Requirements

- Android API 24+ (Android 7.0)
- Firebase Cloud Messaging (FCM)
- `X-API-Key`: App Manager에서 발급받은 API Key

## License

Private - API Key 없이는 동작하지 않습니다.
