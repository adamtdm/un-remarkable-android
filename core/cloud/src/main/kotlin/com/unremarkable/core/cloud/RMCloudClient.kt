package com.unremarkable.core.cloud

import com.unremarkable.core.model.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

@Serializable
data class RootInfo(
    val hash: String,
    val generation: Long,
    val schemaVersion: String
)

class RMCloudClient(
    val auth: AuthManager = AuthManager(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
    private val syncHost: String = DEFAULT_SYNC_HOST
) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun executeRequest(
        builder: Request.Builder,
        retryAuth: Boolean = true,
        maxRetries: Int = 3
    ): Response = withContext(Dispatchers.IO) {
        var attempt = 0
        var backoffMs = 1000L

        while (true) {
            attempt++
            val userToken = auth.getValidUserToken().getOrThrow()
            val request = builder
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $userToken")
                .build()

            val response = client.newCall(request).execute()

            if (response.code == 401 && retryAuth) {
                response.close()
                auth.refreshUserToken().getOrThrow()
                return@withContext executeRequest(builder, retryAuth = false, maxRetries = maxRetries)
            }

            // Retry on 429 Too Many Requests or 5xx server errors with exponential backoff
            if ((response.code == 429 || response.code in 500..504) && attempt <= maxRetries) {
                val retryAfterHeader = response.header("Retry-After")
                val retryAfterSec = retryAfterHeader?.toLongOrNull()
                val sleepMs = if (retryAfterSec != null && retryAfterSec > 0) {
                    (retryAfterSec * 1000L).coerceIn(1000L, 30000L)
                } else {
                    backoffMs
                }
                response.close()
                delay(sleepMs)
                backoffMs = (backoffMs * 2).coerceAtMost(16000L)
                continue
            }

            return@withContext response
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("Unreachable")
    }

    suspend fun getRoot(): RootInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$syncHost/sync/v4/root")
        executeRequest(req).use { resp ->
            if (!resp.isSuccessful) {
                throw CloudException.RequestFailed(resp.code, resp.body?.string().orEmpty())
            }
            val body = resp.body?.string().orEmpty()
            val parsed = json.parseToJsonElement(body).jsonObject
            val hash = parsed["hash"]?.jsonPrimitive?.content.orEmpty()
            val generation = parsed["generation"]?.jsonPrimitive?.longOrNull ?: 0L
            val schemaVersion = parsed["schemaVersion"]?.jsonPrimitive?.content ?: "4"
            RootInfo(hash, generation, schemaVersion)
        }
    }

    suspend fun getBlob(blobHash: String, filename: String = ""): ByteArray = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$syncHost/sync/v3/files/$blobHash")
        if (filename.isNotBlank()) {
            builder.header("rm-filename", filename)
        }

        executeRequest(builder).use { resp ->
            if (resp.code == 404) {
                throw CloudException.BlobNotFound(blobHash)
            }
            if (!resp.isSuccessful) {
                throw CloudException.RequestFailed(resp.code, "Failed to download blob $blobHash ($filename)")
            }
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun downloadBlobToFile(
        blobHash: String,
        destinationFile: File,
        filename: String = ""
    ) = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$syncHost/sync/v3/files/$blobHash")
        if (filename.isNotBlank()) {
            builder.header("rm-filename", filename)
        }

        executeRequest(builder).use { resp ->
            if (resp.code == 404) {
                throw CloudException.BlobNotFound(blobHash)
            }
            if (!resp.isSuccessful) {
                throw CloudException.RequestFailed(resp.code, "Failed to download blob $blobHash ($filename)")
            }
            val body = resp.body ?: throw CloudException.RequestFailed(resp.code, "Empty response body")
            val parent = destinationFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val tmp = File(parent ?: File("."), "${destinationFile.name}.tmp")
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (!tmp.renameTo(destinationFile)) {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    destinationFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
    }

    suspend fun getRootIndex(rootHash: String): Pair<List<Entry>, String> {
        val raw = getBlob(rootHash, "root.docSchema")
        return IndexParser.parseIndex(raw)
    }

    suspend fun getDocIndex(docHash: String, docId: String): Pair<List<Entry>, String> {
        val raw = getBlob(docHash, "$docId.docSchema")
        return IndexParser.parseIndex(raw)
    }

    suspend fun putBlob(
        blobHash: String,
        data: ByteArray,
        filename: String = "",
        contentType: String = "application/octet-stream"
    ) = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("$syncHost/sync/v3/files/$blobHash")
            .header("Content-Type", contentType)
            .header("x-goog-hash", "crc32c=${Crc32c.computeBase64(data)}")
            .put(data.toRequestBody(contentType.toMediaType()))

        if (filename.isNotBlank()) {
            builder.header("rm-filename", filename)
        }

        executeRequest(builder).use { resp ->
            if (resp.code !in 200..204) {
                throw CloudException.RequestFailed(
                    resp.code,
                    "Failed to upload blob $blobHash: ${resp.body?.string()}"
                )
            }
        }
    }

    suspend fun updateRoot(
        rootHash: String,
        generation: Long,
        broadcast: Boolean = true
    ): Long = withContext(Dispatchers.IO) {
        val payload = """
            {
                "broadcast": $broadcast,
                "hash": "$rootHash",
                "generation": $generation
            }
        """.trimIndent()

        val req = Request.Builder()
            .url("$syncHost/sync/v3/root")
            .header("rm-filename", "roothash")
            .header("Content-Type", "application/json")
            .put(payload.toRequestBody("application/json".toMediaType()))

        executeRequest(req).use { resp ->
            if (!resp.isSuccessful) {
                throw CloudException.RequestFailed(resp.code, "Failed to update root: ${resp.body?.string()}")
            }
            val body = resp.body?.string().orEmpty()
            val parsed = json.parseToJsonElement(body).jsonObject
            parsed["generation"]?.jsonPrimitive?.longOrNull ?: (generation + 1)
        }
    }

    suspend fun uploadDocument(
        fileBytes: ByteArray,
        filename: String,
        parentId: String = "",
        fileType: String = "pdf"
    ): String = withContext(Dispatchers.IO) {
        val metaJson = kotlinx.serialization.json.buildJsonObject {
            put("file_name", kotlinx.serialization.json.JsonPrimitive(filename))
            put("parent", kotlinx.serialization.json.JsonPrimitive(parentId))
        }.toString()
        val metaB64 = Base64.getEncoder().encodeToString(metaJson.toByteArray(Charsets.UTF_8))
        val contentType = if (fileType.equals("epub", ignoreCase = true)) {
            "application/epub+zip"
        } else {
            "application/pdf"
        }

        val req = Request.Builder()
            .url("$syncHost/doc/v2/files")
            .header("rm-source", "WebLibrary")
            .header("rm-meta", metaB64)
            .header("Content-Type", contentType)
            .post(fileBytes.toRequestBody(contentType.toMediaType()))

        executeRequest(req).use { resp ->
            if (resp.code !in 200..201) {
                throw CloudException.RequestFailed(
                    resp.code,
                    "Failed to upload document $filename: ${resp.body?.string()}"
                )
            }
            resp.body?.string().orEmpty()
        }
    }

    companion object {
        const val DEFAULT_SYNC_HOST = "https://internal.cloud.remarkable.com"
        const val USER_AGENT = "rmapi/0.0.32-unremarkable-android"
    }
}
