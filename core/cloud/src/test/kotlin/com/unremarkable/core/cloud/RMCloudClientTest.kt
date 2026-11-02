package com.unremarkable.core.cloud

import com.unremarkable.core.model.AuthTokens
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RMCloudClientTest {

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
    fun `getRoot parses root info correctly`() = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        val storage = InMemoryTokenStorage(
            AuthTokens(deviceToken = "dev-token", userToken = "valid-token")
        )
        val auth = AuthManager(storage = storage, authHost = serverUrl)
        val client = RMCloudClient(auth = auth, syncHost = serverUrl)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"hash": "root-sha256", "generation": 42, "schemaVersion": "4"}""")
        )

        val root = client.getRoot()
        assertEquals("root-sha256", root.hash)
        assertEquals(42L, root.generation)
        assertEquals("4", root.schemaVersion)

        val req = server.takeRequest()
        assertEquals("/sync/v4/root", req.path)
        assertEquals("Bearer valid-token", req.getHeader("Authorization"))
    }

    @Test
    fun `getBlob downloads blob data with rm-filename`() = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        val storage = InMemoryTokenStorage(
            AuthTokens(deviceToken = "dev-token", userToken = "valid-token")
        )
        val auth = AuthManager(storage = storage, authHost = serverUrl)
        val client = RMCloudClient(auth = auth, syncHost = serverUrl)

        val testBytes = "Hello reMarkable".toByteArray(Charsets.UTF_8)
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(testBytes)))

        val downloaded = client.getBlob("blob123", "sample.pdf")
        assertArrayEquals(testBytes, downloaded)

        val req = server.takeRequest()
        assertEquals("/sync/v3/files/blob123", req.path)
        assertEquals("sample.pdf", req.getHeader("rm-filename"))
    }

    @Test
    fun `putBlob sends x-goog-hash crc32c header`() = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        val storage = InMemoryTokenStorage(
            AuthTokens(deviceToken = "dev-token", userToken = "valid-token")
        )
        val auth = AuthManager(storage = storage, authHost = serverUrl)
        val client = RMCloudClient(auth = auth, syncHost = serverUrl)

        server.enqueue(MockResponse().setResponseCode(200))

        val testData = "123456789".toByteArray(Charsets.US_ASCII)
        client.putBlob("blob-hash-1", testData, filename = "test.txt")

        val req = server.takeRequest()
        assertEquals("/sync/v3/files/blob-hash-1", req.path)
        assertEquals("PUT", req.method)
        assertEquals("test.txt", req.getHeader("rm-filename"))
        assertEquals("crc32c=4waSgw==", req.getHeader("x-goog-hash"))
    }

    @Test
    fun `request refreshes token and retries on 401 unauthorized`() = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        val storage = InMemoryTokenStorage(
            AuthTokens(deviceToken = "dev-token", userToken = "expired-token")
        )
        val auth = AuthManager(storage = storage, authHost = serverUrl)
        val client = RMCloudClient(auth = auth, syncHost = serverUrl)

        // 1st request: GET /sync/v4/root -> 401
        server.enqueue(MockResponse().setResponseCode(401))
        // 2nd request: POST /token/json/2/user/new -> 200 with new token
        server.enqueue(MockResponse().setResponseCode(200).setBody("fresh-token"))
        // 3rd request: GET /sync/v4/root retried -> 200 with data
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"hash": "fresh-root", "generation": 100, "schemaVersion": "4"}""")
        )

        val root = client.getRoot()
        assertEquals("fresh-root", root.hash)
        assertEquals(100L, root.generation)

        // Verify sequence
        val req1 = server.takeRequest()
        assertEquals("Bearer expired-token", req1.getHeader("Authorization"))

        val req2 = server.takeRequest()
        assertEquals("/token/json/2/user/new", req2.path)

        val req3 = server.takeRequest()
        assertEquals("Bearer fresh-token", req3.getHeader("Authorization"))
    }
}
