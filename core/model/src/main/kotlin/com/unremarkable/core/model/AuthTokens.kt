package com.unremarkable.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AuthTokens(
    val deviceToken: String = "",
    val userToken: String = "",
    val deviceId: String = ""
) {
    val isAuthenticated: Boolean
        get() = deviceToken.isNotBlank()
}
