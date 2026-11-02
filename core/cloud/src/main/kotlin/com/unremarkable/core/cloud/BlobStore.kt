package com.unremarkable.core.cloud

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

interface BlobStore {
    fun hasBlob(hash: String): Boolean
    fun getBlob(hash: String): ByteArray?
    fun putBlob(hash: String, data: ByteArray): File
    fun getBlobFile(hash: String): File
    fun totalSizeBytes(): Long
    fun blobCount(): Int
    fun clear()
}

class FileBlobStore(private val baseDir: File) : BlobStore {

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    override fun hasBlob(hash: String): Boolean {
        if (hash.isBlank()) return false
        val file = getBlobFile(hash)
        return file.exists() && file.isFile && file.length() > 0
    }

    override fun getBlob(hash: String): ByteArray? {
        val file = getBlobFile(hash)
        if (!file.exists() || !file.isFile) return null
        return file.readBytes()
    }

    override fun putBlob(hash: String, data: ByteArray): File {
        val file = getBlobFile(hash)
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(parent, "$hash.tmp")
        tmpFile.writeBytes(data)
        try {
            Files.move(
                tmpFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            // Fallback for filesystems that do not support ATOMIC_MOVE
            Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return file
    }

    override fun getBlobFile(hash: String): File {
        // Shard blobs by first 2 chars of hash: blobs/a1/a1b2c3...
        val prefix = if (hash.length >= 2) hash.substring(0, 2) else "xx"
        val dir = File(baseDir, prefix)
        return File(dir, hash)
    }

    override fun totalSizeBytes(): Long {
        var total = 0L
        baseDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach {
            total += it.length()
        }
        return total
    }

    override fun blobCount(): Int {
        return baseDir.walkTopDown().count { it.isFile && !it.name.endsWith(".tmp") }
    }

    override fun clear() {
        baseDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        fun sha256(data: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(data)
            val sb = StringBuilder()
            for (b in digest) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        }
    }
}
