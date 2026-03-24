package com.am.push

/**
 * Push SDK 설정.
 *
 * @property serverUrl 서버 URL (예: "https://api.example.com")
 * @property apiKey API Key (pk_ 접두사)
 * @property enableLogging 디버그 로그 활성화 여부
 */
data class PushConfig(
    val apiKey: String,
    val serverUrl: String,
    val enableLogging: Boolean = false,
)
