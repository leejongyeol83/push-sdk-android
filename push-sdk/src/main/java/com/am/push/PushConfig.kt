package com.am.push

/**
 * Push SDK 설정.
 *
 * @property serverUrl 서버 URL (예: "https://api.example.com")
 * @property apiKey API Key (pk_ 접두사)
 * @property logLevel SDK 로그 레벨 (기본: NONE)
 */
data class PushConfig(
    val apiKey: String,
    val serverUrl: String,
    val logLevel: LogLevel = LogLevel.NONE,
)

/**
 * SDK 로그 레벨.
 *
 * [NONE]부터 [DEBUG]까지 점점 상세한 로그를 출력한다.
 * ordinal 값으로 비교하며, 값이 높을수록 상세한 로그를 출력한다.
 * 예: logLevel이 INFO이면 ERROR, WARN, INFO는 출력되고 DEBUG는 필터링된다.
 */
enum class LogLevel {
    /** 로그 출력 안 함 */
    NONE,
    /** 에러만 출력 */
    ERROR,
    /** 경고 이상 출력 */
    WARN,
    /** 정보 이상 출력 */
    INFO,
    /** 모든 디버그 로그 출력 */
    DEBUG
}
