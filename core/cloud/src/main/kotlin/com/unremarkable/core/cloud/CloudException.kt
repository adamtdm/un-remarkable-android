package com.unremarkable.core.cloud

sealed class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthFailed(message: String) : CloudException(message)
    class RequestFailed(val statusCode: Int, message: String) : CloudException("HTTP $statusCode: $message")
    class BlobNotFound(val hash: String, message: String = "Blob $hash not found") : CloudException(message)
    class SyncError(message: String, cause: Throwable? = null) : CloudException(message, cause)
}
