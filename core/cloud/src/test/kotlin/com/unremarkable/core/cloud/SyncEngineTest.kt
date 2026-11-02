package com.unremarkable.core.cloud

import com.unremarkable.core.model.AuthTokens
import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.model.InMemorySyncRepository
import com.unremarkable.core.model.PageRef
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
    fun `sync downloads manifests populates cPages and skips unchanged docs incrementally`(@TempDir tempDir: File) = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        val storage = InMemoryTokenStorage(
            AuthTokens(deviceToken = "dev-token", userToken = "user-token")
        )
        val auth = AuthManager(storage = storage, authHost = serverUrl)
        val client = RMCloudClient(auth = auth, syncHost = serverUrl)
        val blobStore = FileBlobStore(tempDir)
        val repository = InMemorySyncRepository()

        // Seed an unpushed local-only note (no blob yet) to ensure sync never wipes local documents
        repository.upsertDocument(
            DocumentItem(
                id = "local-note-1",
                visibleName = "Offline Meeting Note",
                pageCount = 2,
                localOnly = true
            )
        )

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

        // 3. Document manifest for doc-uuid-1: has .content and .rm subfile (payload NOT eagerly downloaded)
        val docManifest = """
            4
            0:.:2:5100
            content-hash-1:0:doc-uuid-1.content:0:100
            rm-hash-1:0:doc-uuid-1/page-a.rm:0:5000
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(docManifest))

        // 4. .content JSON body with cPages
        val contentJson = """
            {
                "visibleName": "Meeting Notes",
                "type": "DocumentType",
                "parent": "",
                "lastModified": "1730500000000",
                "pinned": true,
                "deleted": false,
                "fileType": "notebook",
                "cPages": {
                    "pages": [
                        {"id": "page-a", "idx": {"value": "ba"}}
                    ]
                },
                "tags": ["Meeting", "Action"]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(contentJson))

        val result = engine.sync()
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(1, success.syncedDocs)
        assertEquals(10L, success.generation)

        val doc = repository.getDocument("doc-uuid-1")
        assertNotNull(doc)
        assertEquals("Meeting Notes", doc?.visibleName)
        assertTrue(doc?.pinned == true)
        assertEquals(1, doc?.pageCount)
        assertEquals(listOf(PageRef("page-a", -1)), doc?.pages)
        assertEquals(listOf("Meeting", "Action"), doc?.tags)

        // Local-only document must NOT be wiped by remote deletion pass
        assertNotNull(repository.getDocument("local-note-1"))

        // Verify incremental sync when root changes to generation 11 with a 2nd doc:
        // doc-uuid-1 has the SAME hash (doc1-hash) so zero subfile requests are made for doc-uuid-1!
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"hash": "root-hash-2", "generation": 11, "schemaVersion": "4"}""")
        )
        val rootManifest2 = """
            4
            0:.:2:300
            doc1-hash:80000000:doc-uuid-1:1:200
            folder-hash:80000000:folder-1:1:100
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(rootManifest2))
        val folderManifest = """
            4
            0:.:1:80
            folder-meta-hash:0:folder-1.metadata:0:80
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(folderManifest))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"visibleName":"Projects","type":"CollectionType","lastModified":"1730600000000"}""")
        )

        val incResult = engine.sync()
        assertTrue(incResult is SyncResult.Success)
        assertEquals(true, repository.getDocument("folder-1")?.isFolder)
        assertEquals("Meeting Notes", repository.getDocument("doc-uuid-1")?.visibleName)

        // Verify UpToDate when root hash & generation match
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"hash": "root-hash-2", "generation": 11, "schemaVersion": "4"}""")
        )
        assertTrue(engine.sync() is SyncResult.UpToDate)
    }

    @Test
    fun `sync pushes localOnly folder to cloud before pulling root`(@TempDir tempDir: File) = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        val storage = InMemoryTokenStorage(
            AuthTokens(deviceToken = "dev-token", userToken = "user-token")
        )
        val auth = AuthManager(storage = storage, authHost = serverUrl)
        val client = RMCloudClient(auth = auth, syncHost = serverUrl)
        val blobStore = FileBlobStore(tempDir)
        val repository = InMemorySyncRepository()

        repository.upsertDocument(
            DocumentItem(
                id = "folder-meeting-1",
                visibleName = "Meeting Notes",
                parent = "",
                isFolder = true,
                lastModified = 1730700000000L,
                localOnly = true
            )
        )

        val engine = SyncEngine(client, blobStore, repository)

        // createRemoteFolder sequence:
        // 1. putBlob(.metadata)
        server.enqueue(MockResponse().setResponseCode(200))
        // 2. putBlob(.docSchema)
        server.enqueue(MockResponse().setResponseCode(200))
        // 3. getRoot()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"hash": "initial-root", "generation": 1, "schemaVersion": "3"}""")
        )
        // 4. getRootIndex()
        server.enqueue(MockResponse().setResponseCode(200).setBody("3\n"))
        // 5. putBlob(new root.docSchema)
        server.enqueue(MockResponse().setResponseCode(200))
        // 6. updateRoot()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"hash": "updated-root", "generation": 2}""")
        )

        // Subsequent pull phase:
        // 7. getRoot()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"hash": "updated-root", "generation": 2, "schemaVersion": "3"}""")
        )
        // 8. getRootIndex()
        val folderRootManifest = """
            3
            folder-doc-hash:80000000:folder-meeting-1:1:120
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(folderRootManifest))
        // 9. getBlob(folder-doc-hash)
        val folderManifest = """
            3
            folder-meta-hash:0:folder-meeting-1.metadata:0:120
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(folderManifest))
        // 10. getBlob(folder-meta-hash)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"visibleName":"Meeting Notes","type":"CollectionType","parent":"","lastModified":"1730700000000"}""")
        )

        val res = engine.sync()
        assertTrue(res is SyncResult.Success)
        val syncedFolder = repository.getDocument("folder-meeting-1")
        assertNotNull(syncedFolder)
        assertEquals("Meeting Notes", syncedFolder?.visibleName)
        assertTrue(syncedFolder?.isFolder == true)
        assertFalse(syncedFolder?.localOnly ?: true)
    }
}
