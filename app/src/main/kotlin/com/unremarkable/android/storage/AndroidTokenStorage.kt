package com.unremarkable.android.storage

import android.content.Context
import com.unremarkable.core.cloud.TokenStorage
import com.unremarkable.core.model.AuthTokens

class AndroidTokenStorage(context: Context) : TokenStorage {
    private val prefs = context.getSharedPreferences("rm_auth_prefs", Context.MODE_PRIVATE)

    override fun loadTokens(): AuthTokens {
        val devToken = prefs.getString("device_token", "").orEmpty()
        val userToken = prefs.getString("user_token", "").orEmpty()
        val devId = prefs.getString("device_id", "").orEmpty()
        return AuthTokens(
            deviceToken = devToken,
            userToken = userToken,
            deviceId = devId
        )
    }

    override fun saveTokens(tokens: AuthTokens) {
        prefs.edit()
            .putString("device_token", tokens.deviceToken)
            .putString("user_token", tokens.userToken)
            .putString("device_id", tokens.deviceId)
            .apply()
    }

    override fun clearTokens() {
        prefs.edit().clear().apply()
    }
}
