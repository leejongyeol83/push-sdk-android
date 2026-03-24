package com.am.push

/**
 * API 호출 결과 래퍼.
 *
 * Guard SDK와 동일한 패턴.
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val code: Int, val message: String) : ApiResult<Nothing>()
    data class NetworkError(val exception: Exception) : ApiResult<Nothing>()
}
