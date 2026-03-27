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

3. 앱 `build.gradle.kts`에 플러그인 적용 + Firebase 라이브러리 추가:

```kotlin
// app/build.gradle.kts
plugins {
    id("com.google.gms.google-services")
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
```

### 4. AndroidManifest.xml 설정

알림 권한과 FCM 서비스를 등록합니다:

```xml
<manifest>
    <!-- 알림 권한 (Android 13+) -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application>
        <!-- FCM 서비스 -->
        <service
            android:name="com.am.push.PushFirebaseMessagingService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

### 5. 알림 권한 요청 (Android 13+)

Android 13(API 33) 이상에서는 알림 권한을 런타임에 요청해야 합니다:

```kotlin
// MainActivity.kt
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // 권한 허용됨
            } else {
                // 권한 거부됨 — 설정 안내 등 처리
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
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

> **주의**: 오버라이드한 경우 AndroidManifest.xml에 SDK의 서비스 대신 **내 서비스를 등록**해야 합니다:
> ```xml
> <service
>     android:name=".MyPushService"
>     android:exported="false">
>     <intent-filter>
>         <action android:name="com.google.firebase.MESSAGING_EVENT" />
>     </intent-filter>
> </service>
> ```

## Quick Start

```kotlin
import com.am.push.PushSDK
import com.am.push.PushConfig
import com.am.push.LogLevel

// ── 1. SDK 초기화 (Application.onCreate) ──

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = PushConfig(
            apiKey = "pk_your_api_key",
            serverUrl = "https://your-platform.com",
            logLevel = LogLevel.DEBUG // NONE, ERROR, WARN, INFO, DEBUG
        )
        PushSDK.configure(this, config)
    }
}

// ── 2. FCM 토큰 설정 + 디바이스 등록 ──

FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    PushSDK.setDeviceToken(token)
    PushSDK.register()  // 비회원(UUID)으로 등록
}

// ── 3. 로그인 후 회원으로 전환 ──

fun onLoginSuccess(userId: String) {
    PushSDK.register(userId)
}

// ── 4. 알림 클릭 처리 (Activity) ──

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handlePushAction(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handlePushAction(intent)
}

private fun handlePushAction(intent: Intent) {
    val messageId = intent.getStringExtra(PushFirebaseMessagingService.EXTRA_MESSAGE_ID)
        ?: return

    // 열람 확인
    PushSDK.open(messageId)

    // 동작 분기 (PushMessage.data에서 추출)
    val actionType = intent.getStringExtra("push_action_type") ?: "0"
    when (actionType) {
        "1" -> {  // 웹 URL 이동
            val webUrl = intent.getStringExtra("push_web_url")
            webUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
        }
        "2" -> {  // 앱 화면 이동
            val menuId = intent.getStringExtra("push_menu_id")
            // 앱 내 화면 이동 처리
        }
        "3" -> {  // 팝업
            val popupImgUrl = intent.getStringExtra("push_popup_img_url")
            val popupUrl = intent.getStringExtra("push_popup_url")
            // 팝업 표시 처리
        }
    }
}
```

> **참고**: 알림 클릭 시 동작 정보를 Intent extras에 전달하려면 `getContentIntent()`를 오버라이드하세요:
>
> ```kotlin
> class MyPushService : PushFirebaseMessagingService() {
>     override fun getContentIntent(message: PushMessage): PendingIntent {
>         val intent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
>         intent.putExtra(EXTRA_MESSAGE_ID, message.messageId)
>         message.data?.let { data ->
>             intent.putExtra("push_action_type", data["actionType"]?.toString() ?: "0")
>             intent.putExtra("push_web_url", data["webUrl"]?.toString())
>             intent.putExtra("push_menu_id", data["menuId"]?.toString())
>             intent.putExtra("push_popup_img_url", data["popupImgUrl"]?.toString())
>             intent.putExtra("push_popup_url", data["popupUrl"]?.toString())
>         }
>         intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
>         return PendingIntent.getActivity(
>             this, message.messageId.hashCode(), intent,
>             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
>         )
>     }
> }
> ```

### Intro/Splash 화면이 있는 경우

대부분의 앱은 런처 Activity가 `IntroActivity`(Splash)이고, 실제 푸시 처리는 `MainActivity`에서 합니다.
앱이 완전히 꺼진 상태(Cold Start)에서 알림을 클릭하면 `IntroActivity`가 열리므로, **Intent extras를 Main으로 전달**해야 합니다.

**방법 1: Intro에서 extras 전달 (권장)**

```kotlin
// IntroActivity.kt
class IntroActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 초기화 로직 (스플래시 등) ...

        val mainIntent = Intent(this, MainActivity::class.java)
        // 푸시 알림으로 진입한 경우 extras 전달
        intent.extras?.let { mainIntent.putExtras(it) }
        startActivity(mainIntent)
        finish()
    }
}
```

**방법 2: getContentIntent() 오버라이드로 MainActivity 직접 지정**

```kotlin
class MyPushService : PushFirebaseMessagingService() {
    override fun getContentIntent(message: PushMessage): PendingIntent {
        // Intro를 건너뛰고 MainActivity로 직접 이동
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra(EXTRA_MESSAGE_ID, message.messageId)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, message.messageId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
```

> **방법 1**은 Intro의 초기화 로직(로그인 체크 등)을 거치므로 안전합니다.
> **방법 2**는 Intro를 건너뛰므로 초기화가 보장되는 경우에만 사용하세요.

### MainActivity launchMode 설정 (필수)

Intro → Main 구조에서 푸시 클릭 시 **MainActivity가 중복 생성**되는 문제가 발생할 수 있습니다.
`AndroidManifest.xml`에서 `launchMode`를 설정하세요:

```xml
<activity
    android:name=".MainActivity"
    android:launchMode="singleTask" />
```

| launchMode | 동작 |
|------------|------|
| `singleTask` | 기존 인스턴스 재사용, `onNewIntent()`로 데이터 전달 (권장) |
| `singleTop` | 최상단에 있을 때만 재사용, 백스택에 있으면 새로 생성 |
| (미설정) | 항상 새 인스턴스 생성 → **중복 문제 발생** |

> `singleTask` 설정 시 반드시 `onNewIntent()`에서도 푸시 데이터를 처리해야 합니다 (위 Quick Start 예제 참고).

## API

### 초기화

| Method | Description |
|--------|-------------|
| `PushSDK.configure(context, config)` | SDK 초기화 |
| `PushSDK.setDeviceToken(token)` | FCM 토큰 설정 |
| `PushSDK.register(userId?)` | 디바이스 등록 (userId 없으면 UUID) |
| `PushSDK.restoreFromPreferences(context)` | FCM 수신 시 설정 복원 (자동 호출) |

### 알림 설정

| Method | Description |
|--------|-------------|
| `PushSDK.setAllowPush(allow)` | 전체 푸시 수신 on/off |
| `PushSDK.setDND(enabled, startTime?, endTime?)` | 방해금지 설정/해제 |
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
| `PushSDK.getInbox(page, size, callback)` | 메시지함 조회 (페이지네이션) |
| `PushSDK.getMessageDetail(messageId, callback)` | 메시지 상세 조회 |
| `PushSDK.getBadgeCount(callback)` | 미열람 메시지 수 조회 |

### 계정

| Method | Description |
|--------|-------------|
| `PushSDK.logout(disablePush?)` | 로그아웃 (UUID로 복원) |
| `PushSDK.getUserId()` | 저장된 userId 조회 |
| `PushSDK.getDeviceId()` | 디바이스 UUID 조회 |
| `PushSDK.isConfigured` | 초기화 완료 여부 |

## FCM Payload 구조

서버에서 전송하는 FCM data payload:

```json
{
  "appPush": "{\"messageId\":\"uuid\",\"title\":\"제목\",\"body\":\"내용\",\"imageUrl\":\"https://...\",\"actionType\":\"0\"}"
}
```

### actionType 값

| 값 | 동작 | 관련 필드 |
|----|------|-----------|
| `"0"` | 없음 (기본) | - |
| `"1"` | 웹 URL 이동 | `webUrl` |
| `"2"` | 앱 화면 이동 | `menuId` |
| `"3"` | 팝업 | `popupImgUrl`, `popupUrl` (선택) |

## 미확인 메시지 자동 복구

FCM 수신 확인 전송이 실패하면 로컬에 저장했다가, 다음 `register()` 성공 시 자동으로 재전송합니다.

## Requirements

- Android API 24+ (Android 7.0)
- Firebase Cloud Messaging (FCM)
- `X-API-Key`: App Manager에서 발급받은 API Key

## License

Private - API Key 없이는 동작하지 않습니다.
