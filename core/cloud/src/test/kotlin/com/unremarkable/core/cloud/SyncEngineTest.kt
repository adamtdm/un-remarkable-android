package com.unremarkable.core.cloud

import com.unremarkable.core.model.AuthTokens
import com.unremarkable.core.model.Entry
import com.unremarkable.core.model.InMemorySyncRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SyncEngineTest {

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
    fun `sync downloads manifests and populates repository`(@TempDir tempDir: File) = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        val storage = InMemoryTokenStorage(
            AuthTokens(deviceToken = "dev-token", userToken = "user-token")
        )
        val auth = AuthManager(storage = storage, authHost = serverUrl)
        val client = RMCloudClient(auth = auth, syncHost = serverUrl)
        val blobStore = FileBlobStore(tempDir)
        val repository = InMemorySyncRepository()

        val engine = SyncEngine(client, blobStore, repository)

        // 1. Root info response: generation 10
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"hash": "root-hash-1", "generation": 10, "schemaVersion": "4"}""")
        )

        // 2. Root manifest: 1 document
        val rootManifest = """
            4
            0:.:1:200
            doc1-hash:80000000:doc-uuid-1:1:200
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(rootManifest))

        // 3. Document manifest for doc-uuid-1: has .content
        val docManifest = """
            4
            0:.:1:100
            content-hash-1:0:doc-uuid-1.content:0:100
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(docManifest))

        // 4. .content JSON body
        val contentJson = """
            {
                "visibleName": "Meeting Notes",
                "type": "DocumentType",
                "parent": "",
                "lastModified": "1730500000000",
                "pinned": true,
                "deleted": false,
                "pageCount": 4,
                "tags": ["Meeting", "Action"]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(contentJson))

        val result = engine.sync()
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(1, success.syncedDocs)
        assertEquals(10L, success.generation)

        // Verify repository state
        assertEquals("root-hash-1", repository.getLocalRootHash())
        assertEquals(10L, repository.getLocalGeneration())

        val doc = repository.getDocument("doc-uuid-1")
        assertNotNull(doc)
        assertEquals("Meeting Notes", doc?.visibleName)
        assertTrue(doc?.pinned == true)
        assertEquals(4, doc?.pageCount)
        assertEquals(listOf("Meeting", "Action"), doc?.tags)

        // Verify second sync is UpToDate
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"hash": "root-hash-1", "generation": 10, "schemaVersion": "4"}""")
        )
        val secondResult = engine.sync()
        assertTrue(secondResult is SyncResult.UpToDate)
    }
}
