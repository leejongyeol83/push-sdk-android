package com.am.push

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Push SDK HTTP 클라이언트.
 *
 * Guard SDK의 SdkApiClient와 동일한 패턴.
 * OkHttp + Gson + ApiResult 반환.
 */
internal class PushHttpClient(
    private val serverUrl: String,
    private val apiKey: String,
) {
    companion object {
        private const val CONNECT_TIMEOUT = 10L
        private const val READ_TIMEOUT = 30L
        private const val WRITE_TIMEOUT = 30L
        private const val HEADER_API_KEY = "X-API-Key"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()
    private val baseUrl = serverUrl.trimEnd('/')

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .build()

    /**
     * GET 요청.
     */
    suspend fun <T> get(
        path: String,
        params: Map<String, String>? = null,
        typeToken: TypeToken<T>,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = "$baseUrl$path".toHttpUrl().newBuilder()
            params?.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .header(HEADER_API_KEY, apiKey)
                .header("Accept", "application/json")
                .get()
                .build()

            executeRequest(request, typeToken)
        } catch (e: Exception) {
            ApiResult.NetworkError(e)
        }
    }

    /**
     * POST 요청.
     */
    suspend fun <T> post(
        path: String,
        body: Any,
        typeToken: TypeToken<T>,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("$baseUrl$path")
                .header(HEADER_API_KEY, apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(jsonBody)
                .build()

            executeRequest(request, typeToken)
        } catch (e: Exception) {
            ApiResult.NetworkError(e)
        }
    }

    private fun <T> executeRequest(request: Request, typeToken: TypeToken<T>): ApiResult<T> {
        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val errorMessage = tryParseError(responseBody) ?: "HTTP ${response.code}"
            return ApiResult.Error(response.code, errorMessage)
        }

        return try {
            val parsed: T = gson.fromJson(responseBody, typeToken.type)
            ApiResult.Success(parsed)
        } catch (e: Exception) {
            ApiResult.Error(response.code, "응답 파싱 실패: ${e.message}")
        }
    }

    private fun tryParseError(body: String): String? {
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
            json.getAsJsonObject("error")?.get("message")?.asString
        } catch (_: Exception) { null }
    }

    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
