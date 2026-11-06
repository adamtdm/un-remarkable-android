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
        // 1. getRoot() (v4 root: the folder index must be v4 too)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"hash": "initial-root", "generation": 1, "schemaVersion": "4"}""")
        )
        // 2. getRootIndex()
        server.enqueue(MockResponse().setResponseCode(200).setBody("4\n0:.:0:0\n"))
        // 3. putBlob(.metadata)
        server.enqueue(MockResponse().setResponseCode(200))
        // 4. putBlob(.docSchema)
        server.enqueue(MockResponse().setResponseCode(200))
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

        // The folder's .docSchema must be named by the sha256 of its own bytes (schema v4)
        val docSchemaPut = (0 until server.requestCount).map { server.takeRequest() }
            .single { it.method == "PUT" && it.getHeader("rm-filename") == "folder-meeting-1.docSchema" }
        val body = docSchemaPut.body.readByteArray()
        assertEquals(IndexParser.computeRootHash(body), docSchemaPut.path!!.substringAfterLast('/'))
        assertTrue(String(body, Charsets.UTF_8).startsWith("4\n0:folder-meeting-1:1:"))
    }

    @Test
    fun `sync retrieves lastOpenedPage from cloud metadata`(@TempDir tempDir: File) = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        val storage = InMemoryTokenStorage(
            AuthTokens(deviceToken = "dev-token", userToken = "user-token")
        )
        val auth = AuthManager(storage = storage, authHost = serverUrl)
        val client = RMCloudClient(auth = auth, syncHost = serverUrl)
        val blobStore = FileBlobStore(tempDir)
        val repository = InMemorySyncRepository()
        val engine = SyncEngine(client, blobStore, repository)

        // 1. Root info
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"hash": "root-h1", "generation": 20, "schemaVersion": "4"}""")
        )
        // 2. Root docSchema
        val rootIndex = """
            4
            0:.:1:200
            doc-h1:80000000:doc-book-1:1:200
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(rootIndex))

        // 3. Doc docSchema
        val docIndex = """
            4
            0:.:1:150
            meta-h1:0:doc-book-1.metadata:0:150
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(docIndex))

        // 4. .metadata with lastOpenedPage = 14
        val metaJson = """
            {
                "visibleName": "Science Fiction Novel",
                "type": "DocumentType",
                "lastModified": "1730800000000",
                "lastOpened": "1730805000000",
                "lastOpenedPage": 14,
                "pinned": false
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(metaJson))

        val result = engine.sync()
        assertTrue(result is SyncResult.Success)

        val doc = repository.getDocument("doc-book-1")
        assertNotNull(doc)
        assertEquals("Science Fiction Novel", doc?.visibleName)
        assertEquals(14, doc?.lastOpenedPage)
    }

    @Test
    fun `updateRemotePosition updates metadata content and broadcasts root update`(@TempDir tempDir: File) = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        val storage = InMemoryTokenStorage(
            AuthTokens(deviceToken = "dev-token", userToken = "user-token")
        )
        val auth = AuthManager(storage = storage, authHost = serverUrl)
        val client = RMCloudClient(auth = auth, syncHost = serverUrl)
        val blobStore = FileBlobStore(tempDir)
        val repository = InMemorySyncRepository()
        val engine = SyncEngine(client, blobStore, repository)

        val docId = "doc-uuid-99"
        repository.saveRoot("root-h1", 10)
        repository.upsertDocument(
            DocumentItem(
                id = docId,
                visibleName = "Reading Book",
                lastOpenedPage = 0,
                pageCount = 10,
                hash = "doc-schema-h1",
                subfiles = mapOf("$docId.metadata" to "meta-h1", "$docId.content" to "content-h1")
            )
        )

        // 1. getRoot()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"hash": "root-h1", "generation": 10, "schemaVersion": "4"}""")
        )
        // 2. getRootIndex() (download root.docSchema)
        val rootIndex = """
            4
            0:.:1:200
            doc-schema-h1:80000000:$docId:1:200
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(rootIndex))

        // 3. getBlob(docEntry.hash, doc-uuid-99.docSchema)
        val docIndex = """
            4
            0:.:2:200
            meta-h1:0:$docId.metadata:0:100
            content-h1:0:$docId.content:0:100
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(docIndex))

        // 4. getBlob(metaEntry.hash, doc-uuid-99.metadata)
        val metaJson = """{"visibleName":"Reading Book","type":"DocumentType","lastOpenedPage":0}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(metaJson))

        // 5. putBlob(newMetaHash)
        server.enqueue(MockResponse().setResponseCode(200))

        // 6. getBlob(contentEntry.hash, doc-uuid-99.content)
        val contentJson = """
            {
                "cPages": {
                    "pages": [
                        {"id": "page-0", "idx": {"value": "aa"}},
                        {"id": "page-1", "idx": {"value": "ab"}},
                        {"id": "page-2", "idx": {"value": "ac"}}
                    ]
                }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(contentJson))

        // 7. putBlob(newContentHash)
        server.enqueue(MockResponse().setResponseCode(200))

        // 8. putBlob(newDocHash, doc-uuid-99.docSchema)
        server.enqueue(MockResponse().setResponseCode(200))

        // 9. putBlob(newRootHash, root.docSchema)
        server.enqueue(MockResponse().setResponseCode(200))

        // 10. updateRoot()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"hash": "new-root-h2", "generation": 11}""")
        )

        // User reads to page index 2 (Page 3)
        val success = engine.updateRemotePosition(docId, 2)
        assertTrue(success, engine.lastPositionSyncError)

        // The uploaded .docSchema must be named by the sha256 of its own bytes and carry the doc UUID header
        val requests = (0 until server.requestCount).map { server.takeRequest() }
        val docSchemaPut = requests.single { it.method == "PUT" && it.getHeader("rm-filename") == "$docId.docSchema" }
        val docSchemaBody = docSchemaPut.body.readByteArray()
        val uploadedHash = docSchemaPut.path!!.substringAfterLast('/')
        assertEquals(IndexParser.computeRootHash(docSchemaBody), uploadedHash)
        assertTrue(String(docSchemaBody, Charsets.UTF_8).lines()[1].startsWith("0:$docId:2:"))

        // Root entry for the doc must point at the new doc index with updated subfile count/size
        val rootPut = requests.single { it.method == "PUT" && it.getHeader("rm-filename") == "root.docSchema" }
        val rootLines = rootPut.body.readUtf8().lines()
        assertEquals("0:.:1:${rootLines[2].split(":")[4]}", rootLines[1])
        assertTrue(rootLines[2].startsWith("$uploadedHash:80000000:$docId:2:"))

        // Metadata keeps existing fields and gains the new page
        val metaPut = requests.single { it.method == "PUT" && it.getHeader("rm-filename") == "$docId.metadata" }
        val metaBody = metaPut.body.readUtf8()
        assertTrue(metaBody.contains("\"lastOpenedPage\":2"))
        assertTrue(metaBody.contains("\"visibleName\":\"Reading Book\""))

        // .content cPages.lastOpened points at the 3rd page id
        val contentPut = requests.single { it.method == "PUT" && it.getHeader("rm-filename") == "$docId.content" }
        assertTrue(contentPut.body.readUtf8().contains("\"lastOpened\":{\"value\":\"page-2\""))

        val updatedDoc = repository.getDocument(docId)
        assertNotNull(updatedDoc)
        assertEquals(2, updatedDoc?.lastOpenedPage)
        assertEquals(11L, repository.getLocalGeneration())
        assertFalse(repository.getPendingPageSyncDocIds().contains(docId))
    }

    @Test
    fun `moveToTrash sets metadata parent to trash and only advances local root when in sync`(@TempDir tempDir: File) = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        val auth = AuthManager(
            storage = InMemoryTokenStorage(AuthTokens(deviceToken = "dev-token", userToken = "user-token")),
            authHost = serverUrl
        )
        val repository = InMemorySyncRepository()
        repository.saveRoot("older-root", 5) // another device changed the cloud since our last sync
        val engine = SyncEngine(RMCloudClient(auth = auth, syncHost = serverUrl), FileBlobStore(tempDir), repository)
        val docId = "doc-1"

        server.enqueue(MockResponse().setBody("""{"hash": "root-h1", "generation": 10, "schemaVersion": "4"}"""))
        server.enqueue(MockResponse().setBody("4\n0:.:1:100\ndoc-h1:80000000:$docId:1:100\n"))
        server.enqueue(MockResponse().setBody("4\n0:$docId:1:100\nmeta-h1:0:$docId.metadata:0:100\n"))
        server.enqueue(MockResponse().setBody("""{"visibleName":"Old Book","parent":"","type":"DocumentType"}"""))
        server.enqueue(MockResponse()) // put .metadata
        server.enqueue(MockResponse()) // put .docSchema
        server.enqueue(MockResponse()) // put root.docSchema
        server.enqueue(MockResponse().setBody("""{"generation": 11}"""))

        assertTrue(engine.moveToTrash(docId).isSuccess)

        val metaPut = (0 until server.requestCount).map { server.takeRequest() }
            .single { it.method == "PUT" && it.getHeader("rm-filename") == "$docId.metadata" }
        val metaBody = metaPut.body.readUtf8()
        assertTrue(metaBody.contains("\"parent\":\"trash\""))
        assertTrue(metaBody.contains("\"visibleName\":\"Old Book\""))
        // We built on a root we had not pulled yet, so the next sync must still pull it
        assertEquals("older-root", repository.getLocalRootHash())
    }

    @Test
    fun `uploaded local doc is retired even if the rest of the sync fails`(@TempDir tempDir: File) = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        val auth = AuthManager(
            storage = InMemoryTokenStorage(AuthTokens(deviceToken = "dev-token", userToken = "user-token")),
            authHost = serverUrl
        )
        val blobStore = FileBlobStore(tempDir)
        val repository = InMemorySyncRepository()
        val pdf = "%PDF-1.4 test".toByteArray()
        val pdfHash = IndexParser.computeRootHash(pdf)
        blobStore.putBlob(pdfHash, pdf)
        repository.upsertDocument(
            DocumentItem(id = "local-1", visibleName = "Quick Note", fileType = "pdf", hash = pdfHash,
                subfiles = mapOf("local-1.pdf" to pdfHash), localOnly = true)
        )
        val engine = SyncEngine(RMCloudClient(auth = auth, syncHost = serverUrl), blobStore, repository)

        server.enqueue(MockResponse().setResponseCode(200)) // upload succeeds
        server.enqueue(MockResponse().setResponseCode(404)) // getRoot fails

        assertTrue(engine.sync() is SyncResult.Error)
        assertNull(repository.getDocument("local-1"))

        // A retry must not upload the document again
        server.enqueue(MockResponse().setResponseCode(404))
        engine.sync()
        val posts = (0 until server.requestCount).map { server.takeRequest() }.count { it.method == "POST" }
        assertEquals(1, posts)
    }
}
