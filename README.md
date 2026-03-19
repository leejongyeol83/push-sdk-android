# Push SDK for Android

Push notification SDK for the unified platform.

## Installation

```kotlin
// settings.gradle.kts
maven { url = uri("https://jitpack.io") }

// build.gradle.kts
dependencies {
    implementation("com.github.leejongyeol83:push-sdk-android:1.0.0")
}
```

## Usage

```kotlin
val apiKey = "pk_your_api_key"
val serverURL = "https://your-platform.com"

PushSDK.configure(serverURL = serverURL, apiKey = apiKey)
```
