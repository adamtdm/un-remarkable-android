package com.unremarkable.core.cloud

import com.unremarkable.core.model.AuthTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.UUID

class AuthManager(
    private val storage: TokenStorage = InMemoryTokenStorage(),
    private val client: OkHttpClient = OkHttpClient(),
    private val authHost: String = DEFAULT_AUTH_HOST,
    private val deviceDesc: String = DEFAULT_DEVICE_DESC
) {
    private var tokens: AuthTokens = storage.loadTokens()
        get() {
            if (field.deviceId.isBlank()) {
                field = field.copy(deviceId = UUID.randomUUID().toString())
                storage.saveTokens(field)
            }
            return field
        }

    val currentTokens: AuthTokens
        get() = tokens

    val isAuthenticated: Boolean
        get() = tokens.isAuthenticated

    fun clearTokens() {
        storage.clearTokens()
        tokens = AuthTokens(deviceId = UUID.randomUUID().toString())
    }

    fun isUserTokenValid(): Boolean {
        val userToken = tokens.userToken
        if (userToken.isBlank()) return false

        return try {
            val parts = userToken.split(".")
            if (parts.size < 2) return true // Non-JWT string, consider valid

            val payload = parts[1]
            val padded = payload + "=".repeat((-payload.length % 4 + 4) % 4)
            val decodedBytes = Base64.getUrlDecoder().decode(padded)
            val jsonString = String(decodedBytes, Charsets.UTF_8)
            val json = Json.parseToJsonElement(jsonString).jsonObject
            val exp = json["exp"]?.jsonPrimitive?.longOrNull ?: return true

            val nowSeconds = System.currentTimeMillis() / 1000
            nowSeconds < (exp - EXPIRATION_BUFFER_SECONDS)
        } catch (e: Exception) {
            true // If decoding fails, fall back to treating token as valid string
        }
    }

    suspend fun registerDevice(oneTimeCode: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanCode = oneTimeCode.trim().lowercase()
        if (cleanCode.length != 8) {
            return@withContext Result.failure(
                IllegalArgumentException("One-time pairing code must be exactly 8 characters long")
            )
        }

        val deviceId = tokens.deviceId.ifBlank { UUID.randomUUID().toString() }
        val payload = """
            {
                "code": "$cleanCode",
                "deviceDesc": "$deviceDesc",
                "deviceID": "$deviceId"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("$authHost/token/json/2/device/new")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()?.trim().orEmpty()
                if (!response.isSuccessful || body.isBlank()) {
                    return@withContext Result.failure(
                        CloudException.AuthFailed("Device registration failed (${response.code}): $body")
                    )
                }

                tokens = tokens.copy(deviceToken = body, deviceId = deviceId)
                storage.saveTokens(tokens)

                // Immediately exchange device token for user token
                refreshUserToken()
                Result.success(body)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshUserToken(): Result<String> = withContext(Dispatchers.IO) {
        val deviceToken = tokens.deviceToken
        if (deviceToken.isBlank()) {
            return@withContext Result.failure(
                CloudException.AuthFailed("No device token available. Pair device first.")
            )
        }

        val request = Request.Builder()
            .url("$authHost/token/json/2/user/new")
            .header("Authorization", "Bearer $deviceToken")
            .post("".toRequestBody(null))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()?.trim().orEmpty()
                if (!response.isSuccessful || body.isBlank()) {
                    return@withContext Result.failure(
                        CloudException.AuthFailed("User token refresh failed (${response.code}): $body")
                    )
                }

                tokens = tokens.copy(userToken = body)
                storage.saveTokens(tokens)
                Result.success(body)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getValidUserToken(): Result<String> {
        if (!isUserTokenValid()) {
            val refreshResult = refreshUserToken()
            if (refreshResult.isFailure) {
                return refreshResult
            }
        }
        return Result.success(tokens.userToken)
    }

    companion object {
        const val DEFAULT_AUTH_HOST = "https://webapp-prod.cloud.remarkable.engineering"
        const val DEFAULT_DEVICE_DESC = "android"
        const val EXPIRATION_BUFFER_SECONDS = 120L
    }
}
