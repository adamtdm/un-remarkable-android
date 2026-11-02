package com.unremarkable.core.cloud

import com.unremarkable.core.model.AuthTokens
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64

class AuthManagerTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `registerDevice exchanges one-time code and refreshes user token`() = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        val storage = InMemoryTokenStorage()
        val authManager = AuthManager(storage = storage, authHost = serverUrl)

        // Response 1: device/new returns device token
        server.enqueue(MockResponse().setResponseCode(200).setBody("test-device-token"))
        // Response 2: user/new returns user token
        server.enqueue(MockResponse().setResponseCode(200).setBody("test-user-token"))

        val result = authManager.registerDevice("abcdefgh")
        assertTrue(result.isSuccess)
        assertEquals("test-device-token", result.getOrNull())

        val tokens = authManager.currentTokens
        assertEquals("test-device-token", tokens.deviceToken)
        assertEquals("test-user-token", tokens.userToken)
        assertTrue(authManager.isAuthenticated)

        // Verify request 1
        val req1 = server.takeRequest()
        assertEquals("/token/json/2/device/new", req1.path)
        assertTrue(req1.body.readUtf8().contains(""""code": "abcdefgh""""))

        // Verify request 2
        val req2 = server.takeRequest()
        assertEquals("/token/json/2/user/new", req2.path)
        assertEquals("Bearer test-device-token", req2.getHeader("Authorization"))
    }

    @Test
    fun `isUserTokenValid checks JWT expiration correctly`() {
        val now = System.currentTimeMillis() / 1000
        val expValid = now + 3600 // 1 hour in future
        val expExpired = now - 3600 // 1 hour in past

        fun makeJwt(exp: Long): String {
            val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":$exp}""".toByteArray())
            return "$header.$payload."
        }

        val authValid = AuthManager(storage = InMemoryTokenStorage(AuthTokens(userToken = makeJwt(expValid))))
        assertTrue(authValid.isUserTokenValid())

        val authExpired = AuthManager(storage = InMemoryTokenStorage(AuthTokens(userToken = makeJwt(expExpired))))
        assertFalse(authExpired.isUserTokenValid())
    }
}
