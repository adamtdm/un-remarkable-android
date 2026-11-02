package com.unremarkable.core.cloud

import com.unremarkable.core.model.AuthTokens

interface TokenStorage {
    fun loadTokens(): AuthTokens
    fun saveTokens(tokens: AuthTokens)
    fun clearTokens()
}

class InMemoryTokenStorage(initial: AuthTokens = AuthTokens()) : TokenStorage {
    private var current: AuthTokens = initial

    override fun loadTokens(): AuthTokens = current

    override fun saveTokens(tokens: AuthTokens) {
        current = tokens
    }

    override fun clearTokens() {
        current = AuthTokens(deviceId = current.deviceId)
    }
}
